import {
  ACTIONS,
  GESTURES,
  createDefault,
  normalize,
} from "../profile/model.mjs";
import { Failure } from "../server/failure.mjs";
import { NOT_READY, READY } from "../server/readiness.mjs";
import { Authority, EffectBoundary } from "./effects.mjs";
import { Execution } from "./execution.mjs";
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
  #execution;
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
    actionDelaysMs,
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
      actionDelaysMs,
    });
    this.#execution = new Execution({
      desktop: this.#desktop,
      state: this.#state,
      refresh: this.#refresh,
      profile: () => this.#profile,
      activeLayerId: () => this.#activeLayerId,
      selectLayer: (layerId) => this.#selectLayer(layerId),
      replaceProfile: (nextProfile, message, options) => this.#replaceProfile(nextProfile, message, options),
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
      const result = await this.#execution.perform(
        (effects) => this.#desktop.bindThread(threadId, effects),
        "Attached the current Codex desktop task.",
        authority,
      );
      this.#refresh.invalidate();
      await this.#refresh.now({ publishIfChanged: true });
      const confirmedTarget = this.#state.target;
      if (!sameTarget(confirmedTarget, result?.target)
        && !sameTaskTarget(confirmedTarget, result?.target)) {
        throw new Failure(
          409,
          "desktop_binding_not_confirmed",
          "The Codex task changed before Vibe Pocket could confirm its target.",
        );
      }
      const binding = this.#state.snapshot({
        profile: this.#profile,
        gestures: GESTURES,
        actions: ACTIONS,
        activeLayerId: this.#activeLayerId,
        taskId: TASK_ID,
      }).controller.binding;
      const attached = binding.state === "confirmed"
        && binding.contextId === this.#state.target.agentId;
      return { attached, target: confirmedTarget, revision: this.#state.revision };
    });
  }

  async configureMicro() {
    this.#requireReady();
    return this.#queue.run(async () => {
      try {
        const result = await this.#desktop.configureMicro();
        this.#refresh.invalidate();
        await this.#refresh.now({ publishIfChanged: true });
        return {
          configured: true,
          message: result?.message ?? "Configured the Codex Micro knob for reasoning.",
        };
      } catch (error) {
        throw new Failure(
          409,
          "micro_configuration_failed",
          error.message || "Codex did not accept the Micro configuration.",
        );
      }
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
    this.#execution.resolve(command);

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
      const effects = new EffectBoundary(() => principal.valid());
      const authority = new Authority((operation) => this.#dispatch(operation), effects);
      try {
        await this.#execution.execute(command, authority);
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

  #selectLayer(layerId) {
    const layer = this.#profile.layers.find(({ id }) => id === layerId);
    if (!layer) throw new Failure(409, "layer_unavailable", "That controller layer is unavailable.");
    this.#refresh.invalidate();
    this.#activeLayerId = layer.id;
    this.#state.record(`Selected ${layer.name}.`);
  }

  #dispatch(operation) {
    this.#refresh.invalidate();
    return operation();
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

function sameTarget(left, right) {
  if (!left || !right) return false;
  return [
    "threadId",
    "agentId",
    "bindingEpoch",
    "bridgeInstanceId",
    "appServerGeneration",
    "canonicalWorkspaceId",
  ].every((key) => left[key] === right[key]);
}

function sameTaskTarget(left, right) {
  if (!left || !right) return false;
  return [
    "threadId",
    "agentId",
    "bridgeInstanceId",
    "canonicalWorkspaceId",
  ].every((key) => left[key] === right[key]);
}

function indeterminateFailure() {
  return new Failure(
    409,
    "command_outcome_indeterminate",
    "The controller action may have completed; check this operation's status before taking any further action.",
  );
}
