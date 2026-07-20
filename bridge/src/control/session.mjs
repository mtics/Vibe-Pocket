import {
  ACTIONS,
  GESTURES,
  ValidationError,
  createDefault,
  normalize,
  validateAction,
  workflowPrompt,
} from "../profile/model.mjs";
import { Failure } from "../server/failure.mjs";
import { NOT_READY, READY } from "../server/readiness.mjs";
import { resolve } from "./command.mjs";
import { Operations } from "./operations.mjs";
import { Queue } from "./queue.mjs";
import { Refresh } from "./refresh.mjs";
import { State } from "./state.mjs";

const TASK_ID = "vibe-pocket-codex";
const DEFAULT_POLL_INTERVAL_MS = 2_000;
const HOOK_EVENTS = new Set(["UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse", "Stop"]);

export class Session {
  #desktop;
  #profile;
  #profileStore;
  #activeLayerId;
  #state;
  #queue;
  #operations;
  #executions = new Map();
  #revokedPrincipals = new Set();
  #refresh;
  #ready = null;
  #readiness = NOT_READY;
  #stopping = null;
  #disposing = null;

  constructor({
    workspaces,
    events,
    desktop,
    profile,
    profileStore = null,
    operationPath = null,
    operations = null,
    pollIntervalMs = DEFAULT_POLL_INTERVAL_MS,
    maxPendingCommands,
    maxPendingCommandsPerPrincipal,
  }) {
    if (!desktop) {
      throw new TypeError("The control session requires a visible Codex desktop.");
    }
    this.#desktop = desktop;
    this.#profile = profile ? normalize(profile) : createDefault();
    this.#profileStore = profileStore;
    this.#queue = new Queue({
      ...(maxPendingCommands == null ? {} : { maxPending: maxPendingCommands }),
      ...(maxPendingCommandsPerPrincipal == null ? {} : {
        maxPendingPerPrincipal: maxPendingCommandsPerPrincipal,
      }),
    });
    this.#operations = operations ?? new Operations({ path: operationPath });
    this.#activeLayerId = this.#profile.layers[0].id;
    this.#state = new State({ events, workspaces, taskId: TASK_ID });
    this.#refresh = new Refresh({
      desktop,
      state: this.#state,
      blocked: () => this.#queue.busy,
      intervalMs: pollIntervalMs,
    });
  }

  start() {
    if (!this.#ready) {
      this.#ready = this.#start().then(
        () => {
          if (!this.#stopping) this.#readiness = READY;
        },
        (error) => {
          this.#readiness = NOT_READY;
          throw error;
        },
      );
    }
    return this.#ready;
  }

  async #start() {
    if (this.#profileStore) {
      this.#profile = await this.#profileStore.load();
      this.#activeLayerId = this.#profile.layers[0].id;
    }
    if (await this.#refresh.start()) this.#state.publish("snapshot_changed");
  }

  stop() {
    this.#readiness = NOT_READY;
    if (!this.#stopping) {
      this.#stopping = (async () => {
        await this.#queue.stop();
        await this.#refresh.stop();
      })();
    }
    return this.#stopping;
  }

  dispose() {
    if (!this.#disposing) {
      this.#disposing = (async () => {
        try {
          await this.stop();
        } finally {
          await this.#desktop.dispose?.();
        }
      })();
    }
    return this.#disposing;
  }

  async snapshot() {
    this.#requireReady();
    return this.#state.snapshot({
      profile: this.#profile,
      gestures: GESTURES,
      actions: ACTIONS,
      activeLayerId: this.#activeLayerId,
      taskId: TASK_ID,
    });
  }

  async bindDesktopThread(threadId) {
    this.#requireReady();
    return this.#queue.run(async () => {
      const authority = new Authority(
        (operation) => this.#dispatch(operation),
        new EffectBoundary(),
      );
      await this.#perform(
        () => this.#desktop.bindThread(threadId),
        "Attached the current Codex desktop task.",
        authority,
      );
      return { attached: true, revision: this.#state.revision };
    });
  }

  async codexHook(event, payload) {
    this.#requireReady();
    if (!HOOK_EVENTS.has(event)) {
      throw new Failure(400, "invalid_hook_event", "Unsupported Codex lifecycle event.");
    }
    if (!payload || typeof payload !== "object" || Array.isArray(payload)) {
      throw new Failure(400, "invalid_hook_payload", "Codex lifecycle payload must be a JSON object.");
    }

    this.#refresh.invalidate();
    const lifecycle = await this.#desktop.applyLifecycleHook(event, payload);
    await this.#refresh.now({ publishIfChanged: true });
    const response = await lifecycle.response;
    await this.#refresh.now({ publishIfChanged: true });
    return response;
  }

  async command(command, idempotencyKey, principal = null) {
    this.#requireReady();
    principal = normalizePrincipal(principal);
    const existing = this.#operations.match(idempotencyKey, command, principal.id);
    if (existing) return this.#replay(existing, principal.id);
    this.#resolveCommand(command);

    const admission = this.#queue.reserve({ principal });
    let claim;
    try {
      claim = this.#operations.create(idempotencyKey, command, principal.id);
    } catch (error) {
      admission.release();
      throw error;
    }
    if (!claim.created) {
      admission.release();
      return this.#replay(claim.operation, principal.id);
    }

    const execution = this.#runCommand(command, claim.operation.operationId, principal, admission);
    const tracked = { principalId: principal.id, execution };
    this.#executions.set(claim.operation.operationId, tracked);
    const clear = () => {
      if (this.#executions.get(claim.operation.operationId) === tracked) {
        this.#executions.delete(claim.operation.operationId);
      }
      this.#cleanupRevokedPrincipal(principal.id);
    };
    execution.then(clear, clear);
    return execution;
  }

  async commandResult(operationId, principal = null) {
    this.#requireReady();
    principal = normalizePrincipal(principal);
    return this.#operations.get(operationId, principal.id);
  }

  revokePrincipal(principalId) {
    this.#revokedPrincipals.add(principalId);
    return this.#cleanupRevokedPrincipal(principalId);
  }

  #requireReady() {
    if (this.#readiness !== READY) {
      throw new Failure(503, "bridge_not_ready", "The Vibe Pocket bridge is not ready.");
    }
  }

  #replay(operation, principalId) {
    const tracked = this.#executions.get(operation.operationId);
    if (tracked?.principalId === principalId) return tracked.execution;
    return operation;
  }

  #cleanupRevokedPrincipal(principalId) {
    if (!this.#revokedPrincipals.has(principalId)) return false;
    if ([...this.#executions.values()].some((execution) => execution.principalId === principalId)) return false;
    try {
      const removed = this.#operations.removePrincipal(principalId);
      this.#revokedPrincipals.delete(principalId);
      return removed;
    } catch {
      return false;
    }
  }

  async #runCommand(command, operationId, principal, admission) {
    const execution = this.#queue.run(async () => {
      this.#operations.markRunning(operationId, principal.id);
      const effects = new EffectBoundary();
      const authority = new Authority((operation) => this.#dispatch(operation), effects);
      try {
        await this.#execute(command, authority);
      } catch (error) {
        if (effects.crossed) {
          this.#operations.markUnknown(operationId, principal.id);
          throw indeterminateFailure();
        }
        try {
          this.#operations.markFailed(operationId, principal.id, error);
        } catch {
          this.#operations.markUnknown(operationId, principal.id);
          throw indeterminateFailure();
        }
        throw error;
      }

      try {
        return this.#operations.markSucceeded(operationId, principal.id, {
          accepted: true,
          revision: this.#state.revision,
        });
      } catch {
        this.#operations.markUnknown(operationId, principal.id);
        throw indeterminateFailure();
      }
    }, { principal, admission });

    try {
      return await execution;
    } catch (error) {
      const current = this.#operations.get(operationId, principal.id);
      if (current.status === "accepted") {
        this.#operations.markFailed(operationId, principal.id, error);
      }
      throw error;
    }
  }

  async #execute(command, authority) {
    try {
      const intent = this.#resolveCommand(command);
      if (intent.kind === "voice") {
        await this.#setVoice(intent.active, authority);
        return;
      }
      if (intent.kind === "agent") {
        await this.#focusAgent(intent.id, authority);
        return;
      }
      if (intent.kind === "model") {
        await this.#selectModel(intent.id, authority);
        return;
      }
      if (intent.kind === "action") {
        await this.#executeAction(intent.value, authority);
        return;
      }
      if (intent.kind === "layer") {
        const layer = this.#profile.layers.find(({ id }) => id === intent.id);
        this.#refresh.invalidate();
        this.#activeLayerId = layer.id;
        this.#state.record(`Selected ${layer.name}.`);
        return;
      }
      if (intent.kind === "profile") {
        await this.#replaceProfile(intent.value, intent.message, {
          resetLayer: intent.resetLayer,
          authority,
        });
      }
    } catch (error) {
      if (error instanceof Failure) throw error;
      if (error instanceof ValidationError) {
        throw new Failure(400, "invalid_controller_configuration", error.message);
      }
      const message = error.message || "Codex did not accept this controller action.";
      this.#state.reject(message);
      this.#refresh.afterAction();
      throw new Failure(409, "desktop_action_failed", message);
    }
  }

  #resolveCommand(command) {
    try {
      const intent = resolve(command, { profile: this.#profile, layerId: this.#activeLayerId });
      return intent.kind === "action"
        ? { ...intent, value: validateAction(intent.value) }
        : intent;
    } catch (error) {
      if (error instanceof Failure) throw error;
      if (error instanceof ValidationError) {
        throw new Failure(400, "invalid_controller_configuration", error.message);
      }
      throw error;
    }
  }

  async #executeAction(action, authority) {
    action = validateAction(action);
    switch (action.type) {
      case "attach":
        await this.#perform(() => this.#desktop.attach(), "Resumed the focused Vibe Pocket Codex task.", authority);
        return;
      case "voice":
        await this.#setVoice(!this.#state.voice.active, authority);
        return;
      case "stop":
        await this.#press("stop", "Stopped the focused Codex turn.", authority);
        return;
      case "approve":
        await this.#press("approve", "Approved the focused Codex request.", authority);
        return;
      case "reject":
        await this.#press("reject", "Rejected the focused Codex request.", authority);
        return;
      case "new_task":
        await this.#press("new-task", "Created a new Vibe Pocket Codex task.", authority);
        return;
      case "navigate":
        if (!["up", "down", "left", "right"].includes(action.direction)) {
          throw new Failure(400, "invalid_direction", "Navigation direction must be up, down, left, or right.");
        }
        await this.#perform(() => this.#desktop.navigate(action.direction), `Moved ${action.direction} in Codex.`, authority);
        return;
      case "mode_cycle":
        await this.#perform(() => this.#desktop.cycleMode(), "Selected the next Codex mode.", authority);
        return;
      case "model_picker":
        await this.#perform(() => this.#desktop.openModel(), "Opened the Codex model picker.", authority);
        return;
      case "access_cycle":
        await this.#perform(() => this.#desktop.cycleAccess(), "Selected the next Codex access level.", authority);
        return;
      case "delete_backward":
        await this.#perform(() => this.#desktop.deleteBackward(), "Deleted one character from the visible Codex input.", authority);
        return;
      case "clear_input":
        await this.#perform(() => this.#desktop.clearInput(), "Cleared the visible Codex input.", authority);
        return;
      case "focus_next": {
        const agents = this.#state.agents;
        if (agents.length === 0) {
          await this.#perform(() => this.#desktop.attach(), "Resumed the focused Vibe Pocket Codex task.", authority);
          return;
        }
        const nextIndex = (this.#state.focusedAgentIndex + 1) % agents.length;
        await this.#perform(() => this.#desktop.focusAgent(agents[nextIndex].id), `Focused ${agents[nextIndex].label}.`, authority);
        this.#state.focus(agents[nextIndex].id);
        return;
      }
      case "focus_agent": {
        const agent = this.#state.agents[action.index];
        if (!agent) {
          throw new Failure(409, "agent_slot_unavailable", "That Codex agent slot is not currently available.");
        }
        await this.#perform(() => this.#desktop.focusAgent(agent.id), `Focused ${agent.label}.`, authority);
        this.#state.focus(agent.id);
        return;
      }
      case "select_layer": {
        const layer = this.#profile.layers.find(({ id }) => id === action.layerId);
        if (!layer) throw new Failure(409, "layer_unavailable", "That controller layer is unavailable.");
        this.#refresh.invalidate();
        this.#activeLayerId = layer.id;
        this.#state.record(`Selected ${layer.name}.`);
        return;
      }
      case "reasoning_depth":
        if (action.delta !== 1 && action.delta !== -1) {
          throw new Failure(400, "invalid_reasoning_delta", "Reasoning adjustment must be one step clockwise or counter-clockwise.");
        }
        await this.#performDeferred(
          (effects) => this.#desktop.adjustReasoning(action.delta, effects),
          "Adjusted Codex reasoning depth.",
          authority,
        );
        return;
      case "workflow":
        await this.#perform(
          () => this.#desktop.workflow(workflowPrompt(this.#profile, action.workflowId)),
          "Started the selected workflow in a new Codex task.",
          authority,
        );
        return;
      default:
        throw new Failure(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
    }
  }

  async #replaceProfile(nextProfile, message, { resetLayer = false, authority } = {}) {
    let persisted = normalize(nextProfile);
    if (this.#profileStore) {
      try {
        persisted = await authority.immediate(() => this.#profileStore.save(persisted));
      } catch {
        throw new Failure(500, "profile_persistence_failed", "The controller profile could not be saved.");
      }
    } else {
      this.#refresh.invalidate();
    }
    this.#profile = persisted;
    if (resetLayer || !this.#profile.layers.some(({ id }) => id === this.#activeLayerId)) {
      this.#activeLayerId = this.#profile.layers[0].id;
    }
    this.#state.record(message);
  }

  async #perform(operation, fallbackMessage, authority) {
    const result = await authority.immediate(operation);
    this.#completeAction(result, fallbackMessage);
  }

  async #performDeferred(operation, fallbackMessage, authority) {
    const result = await authority.deferred(operation);
    this.#completeAction(result, fallbackMessage);
  }

  #completeAction(result, fallbackMessage) {
    // The scheduled capability scan verifies desktop actions. Avoid publishing
    // a transient success message before that scan changes the controller UI.
    this.#state.record(result?.message ?? fallbackMessage, { publish: false });
    this.#refresh.afterAction();
  }

  async #press(control, fallbackMessage, authority) {
    await this.#perform(() => this.#desktop.press(control), fallbackMessage, authority);
  }

  async #setVoice(active, authority) {
    if (active && !this.#state.voice.available) {
      throw new Failure(409, "voice_unavailable", "The visible ChatGPT Codex dictation control is unavailable.");
    }
    const result = await authority.immediate(() => this.#desktop.setVoice(active));
    this.#state.setVoice(active);
    // A press-to-talk release can enqueue the matching stop immediately after
    // start. Let the common delayed scan publish the stable final state once.
    this.#state.record(
      result?.message ?? (active ? "Started ChatGPT Codex dictation." : "Stopped ChatGPT Codex dictation."),
      { publish: false },
    );
    this.#refresh.afterAction();
  }

  async #focusAgent(agentId, authority) {
    const index = this.#state.agents.findIndex((agent) => agent.id === agentId);
    if (index < 0) {
      throw new Failure(409, "agent_unavailable", "That Codex agent is no longer available.");
    }
    const agent = this.#state.agents[index];
    await this.#perform(() => this.#desktop.focusAgent(agent.id), `Focused ${agent.label}.`, authority);
    this.#state.focus(agent.id);
  }

  async #selectModel(modelId, authority) {
    await this.#performDeferred(
      (effects) => this.#desktop.selectModel(modelId, effects),
      "Selected the Codex model.",
      authority,
    );
  }

  #dispatch(operation) {
    this.#refresh.invalidate();
    return operation();
  }
}

class EffectBoundary {
  #crossed = false;

  get crossed() {
    return this.#crossed;
  }

  async commit(operation) {
    if (this.#crossed) throw new Error("A controller command may cross its effect boundary only once.");
    this.#crossed = true;
    return operation();
  }
}

class Authority {
  #dispatch;
  #effects;

  constructor(dispatch, effects) {
    this.#dispatch = dispatch;
    this.#effects = effects;
  }

  immediate(operation) {
    return this.#dispatch(() => this.#effects.commit(operation));
  }

  async deferred(operation) {
    const result = await this.#dispatch(() => operation(this.#effects));
    if (!this.#effects.crossed) {
      throw new Error("The controller mutation completed without crossing its effect boundary.");
    }
    return result;
  }
}

function normalizePrincipal(principal) {
  if (principal == null) {
    return { id: "principal:local-root", role: "root", revocable: false, valid: () => true };
  }
  return {
    ...principal,
    role: principal.role ?? (principal.revocable ? "device" : "root"),
  };
}

function indeterminateFailure() {
  return new Failure(
    409,
    "command_outcome_indeterminate",
    "The controller action may have completed; check this operation's status before taking any further action.",
  );
}
