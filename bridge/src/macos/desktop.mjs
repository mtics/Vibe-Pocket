import { createConnection } from "node:net";

import { open } from "../task/open.mjs";
import { agentIdForThread } from "../task/catalog.mjs";

const MUTATION_BINDING = Symbol("desktopMutationBinding");
const MAXIMUM_CONTROL_REQUEST_LIFETIME_MS = 10_000;
const VISIBLE_UI_CONTROLS = [
  "voice",
  "stop",
  "new-task",
  "approve",
  "reject",
  "clear-input",
  "model-picker",
  "access-cycle",
  "navigate",
  "workflow",
];

export class Desktop {
  #socketPath;
  #run;
  #openThread;
  #threadCatalog;
  #wait;
  #focusPollAttempts;
  #focusPollIntervalMs;
  #voiceActive = false;
  #lastAgents = [];
  #operationQueue = Promise.resolve();
  #visibleFocusLease = Promise.resolve();
  #expectedVisibleAgentId = null;
  #confirmedVisibleAgentId = null;

  constructor({
    socketPath = process.env.VIBE_POCKET_HOST_SOCKET,
    run = runSocketRequest,
    openThread = open,
    threadCatalog = null,
    wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
    focusPollAttempts = 20,
    focusPollIntervalMs = 150,
  } = {}) {
    this.#socketPath = socketPath;
    this.#run = run;
    this.#openThread = openThread;
    this.#threadCatalog = threadCatalog;
    this.#wait = wait;
    this.#focusPollAttempts = focusPollAttempts;
    this.#focusPollIntervalMs = focusPollIntervalMs;
  }

  async status() {
    return this.#enqueue(() => this.#statusNow());
  }

  async #statusNow() {
    let result;
    let agents;
    let tasks;
    let desktopFresh = true;
    try {
      ({ result, agents, tasks } = await this.#observeAgentsNow());
    } catch (error) {
      desktopFresh = false;
      result = unavailableDesktop(error);
      if (this.#threadCatalog) {
        agents = await this.#threadCatalog.resolveVisibleAgents([]);
        tasks = taskObservation(this.#threadCatalog.freshness);
      } else {
        agents = [];
        tasks = { availability: "unavailable", message: error.message ?? null };
      }
    }
    if (!this.#threadCatalog) return { ...result, agents, tasks };
    let settings = null;
    try {
      settings = await this.#threadCatalog.settings({
        ...result.reasoning,
        modeLabel: result.mode?.label,
      });
    } catch {
      settings = null;
    }
    const target = this.#threadCatalog.target;
    this.#acceptObservedFocus(agents);
    const observedBinding = bindingObservation(result, agents);
    this.#confirmedVisibleAgentId = observedBinding.state === "confirmed"
      ? observedBinding.contextId
      : null;
    const visibleTargetConfirmed = !target || (
      observedBinding.state === "confirmed"
      && observedBinding.contextId === target.agentId
    );
    const binding = target && !visibleTargetConfirmed
      ? { state: "reconciling", contextId: observedBinding.contextId }
      : observedBinding;
    const appServerFresh = settings !== null && settings.fresh !== false;
    const settingsMutable = Boolean(target?.threadId && appServerFresh);
    const modelAvailable = Boolean(settingsMutable && settings?.model.available === true);
    const reasoningAvailable = Boolean(settingsMutable && settings?.reasoning.available === true);
    const modeAvailable = Boolean(
      settingsMutable
        && settings?.mode?.available === true,
    );
    const mode = projectVisibleMode(settings?.mode, result.mode);
    const visibleControls = visibleTargetConfirmed
      ? result.controls
      : disabledControls(result.controls);
    const selectedAgents = target && agents.some((agent) => agent.id === target.agentId)
      ? agents.map((agent) => ({ ...agent, focused: agent.id === target.agentId }))
      : agents;
    const view = {
      ...result,
      agents: selectedAgents,
      tasks,
      binding: { state: binding.state, contextId: binding.contextId },
      target,
      mode: mode ? { ...mode, available: modeAvailable } : result.mode,
      model: settings?.model ? { ...settings.model, available: modelAvailable } : undefined,
      reasoning: settings?.reasoning ? {
        ...settings.reasoning,
        available: reasoningAvailable,
        canIncrease: reasoningAvailable && settings.reasoning.canIncrease,
        canDecrease: reasoningAvailable && settings.reasoning.canDecrease,
      } : result.reasoning,
      controls: {
        ...visibleControls,
        "focus-agent": selectedAgents.some((agent) => agent.actionable !== false && !agent.focused),
        "mode-cycle": modeAvailable,
        model: modelAvailable,
        reasoning: reasoningAvailable,
      },
      sources: {
        appServer: { fresh: appServerFresh, observedAt: appServerFresh ? Date.now() : null },
        desktopUI: { fresh: desktopFresh, observedAt: desktopFresh ? Date.now() : null },
      },
    };
    return view;
  }

  get voiceActive() {
    return this.#voiceActive;
  }

  get effectAware() {
    return true;
  }

  async activate() {
    return this.attach();
  }

  async attach(effects = null) {
    if (!this.#threadCatalog) return this.#invoke("attach", [], "", effects);
    return this.#enqueue(async () => {
      const { agents } = await this.#observeAgentsNow({ allowCached: false });
      const focused = agents.filter((agent) => (
        agent.focused === true
        && agent.actionable !== false
        && agent.freshness !== "stale"
      ));
      if (focused.length !== 1) {
        throw new Error("Open exactly one Codex task before attaching Vibe Pocket.");
      }
      const target = await this.#threadCatalog.attachVisible(focused[0].id, effects);
      return {
        ok: true,
        target,
        message: "Bound the visible Codex task without changing its settings.",
      };
    });
  }

  async bindThread(threadId, effects = null) {
    if (!this.#threadCatalog) {
      throw new Error("The native Codex task catalog is required to confirm a desktop task.");
    }
    return this.#enqueue(async () => {
      const requested = typeof this.#threadCatalog.bindThread === "function"
        ? await commitEffect(effects, () => this.#threadCatalog.bindThread(threadId))
        : await this.#threadCatalog.focusThread(threadId, effects);
      if (!requested?.agentId) {
        throw new Error("The native Codex task catalog could not identify the requested task.");
      }
      return {
        ok: true,
        target: requested.target,
        message: "Bound the selected Codex task without requiring desktop focus.",
      };
    });
  }

  applyLifecycleHook() {
    return {
      accepted: false,
      response: Promise.resolve({}),
    };
  }

  async press(control, effects = null) {
    return this.#invoke("control", [control], "", effects);
  }

  async setVoice(active, effects = null) {
    if (typeof active !== "boolean") throw new TypeError("Voice state must be boolean.");
    const result = await this.#invoke(active ? "voice-start" : "voice-stop", [], "", effects);
    this.#voiceActive = active;
    return result;
  }

  async navigate(direction, effects = null) {
    return this.#invoke("navigate", [direction], "", effects);
  }

  async cycleMode() {
    return this.#invoke("plan-mode");
  }

  async cycleAccess(effects = null) {
    return this.#invoke("access-cycle", [], "", effects);
  }

  async openModel(effects = null) {
    return this.#invoke("model-picker", [], "", effects);
  }

  async configureMicro() {
    return this.#invoke("configure-micro-reasoning");
  }

  async selectModel(modelId, target, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex model selection is unavailable.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      const selected = await this.#threadCatalog.validateModel({
        id: modelId,
        target,
      });
      if (!selected?.id) throw new Error("The requested Codex model is unavailable or stale.");
      const settings = await this.#threadCatalog.selectModel({
        id: selected.id,
        target,
        effects,
      });
      return {
        ok: true,
        message: `Selected ${settings.model.label}.`,
        settings,
      };
    });
  }

  async selectMode(modeId, effects) {
    throw new Error("Codex mode selection is disabled by protocol v12.");
  }

  async adjustReasoning(delta, target, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex reasoning selection is unavailable.");
    if (delta !== -1 && delta !== 1) throw new Error("Reasoning adjustment must be one step.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      this.#threadCatalog.requireTarget(target);
      if (!this.#threadCatalog.reasoningTarget(delta)) {
        throw new Error("Codex reasoning cannot move farther in that direction.");
      }
      const level = this.#threadCatalog.reasoningTarget(delta);
      if (!level) throw new Error("Codex reasoning changed before its settings mutation.");
      return this.#selectReasoningNow(level, target, effects);
    });
  }

  async selectReasoning(level, target, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex reasoning selection is unavailable.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      this.#threadCatalog.requireTarget(target);
      if (!this.#threadCatalog.hasReasoningLevel(level)) {
        throw new Error("The requested Codex reasoning level is unavailable or stale.");
      }
      if (!this.#threadCatalog.hasReasoningLevel(level)) {
        throw new Error("The requested Codex reasoning level changed before its settings mutation.");
      }
      return this.#selectReasoningNow(level, target, effects);
    });
  }

  async clearInput(effects = null) {
    return this.#invoke("clear-input", [], "", effects);
  }

  async deleteBackward(effects = null) {
    return this.#invoke("delete-backward", [], "", effects);
  }

  async focusAgent(agentId, effects = null) {
    if (!this.#threadCatalog) return this.#invoke("focus-agent", [agentId], "", effects);
    return this.#enqueue(async () => {
      const requested = await this.#threadCatalog.focusAgent(agentId, effects);
      await this.#leaseVisibleFocus(requested.agentId);
      return {
        ok: true,
        target: requested.target,
        message: "Focused the selected Codex task after native desktop confirmation.",
      };
    });
  }

  async selectAgent(agentId, effects = null) {
    if (!this.#threadCatalog) {
      throw new Error("The native Codex task catalog is required to select a control target.");
    }
    return this.#enqueue(async () => {
      const requested = await this.#threadCatalog.selectAgent(agentId, effects);
      return {
        ok: true,
        target: requested.target,
        message: "Selected the Codex task without changing desktop focus.",
      };
    });
  }

  async workflow(prompt, effects = null) {
    return this.#invoke("workflow", [], prompt, effects);
  }

  async dispose() {
    this.#lastAgents = [];
    await this.#threadCatalog?.dispose?.();
  }

  async #invoke(action, args = [], input = "", effects = null) {
    return this.#enqueue(async () => {
      await this.#visibleFocusLease;
      const target = this.#threadCatalog?.target;
      if (target && this.#confirmedVisibleAgentId !== target.agentId) {
        throw new Error("The bound Codex task is not confirmed as the visible desktop focus.");
      }
      return commitEffect(effects, () => this.#invokeNow(action, args, input));
    });
  }

  async #enqueue(callback) {
    const operation = this.#operationQueue.then(callback);
    this.#operationQueue = operation.catch(() => {});
    return operation;
  }

  async #invokeNow(action, args, input) {
    if (!this.#socketPath) throw new Error("The Vibe Pocket Bridge Host control socket is unavailable.");
    const body = await this.#run(this.#socketPath, action, args, input);
    if (!body.ok) throw new Error(body.message ?? "The macOS desktop controller rejected this action.");
    return body;
  }

  async #observeAgentsNow({ allowCached = true } = {}) {
    const result = await this.#invokeNow("status", [], "");
    if (!this.#threadCatalog) {
      return {
        result,
        agents: result.agents ?? [],
        tasks: { availability: "fresh", message: null },
      };
    }
    let agents;
    let tasks;
    try {
      agents = await this.#threadCatalog.resolveVisibleAgents(result.agents);
      tasks = taskObservation(this.#threadCatalog.freshness);
      if (tasks.availability === "fresh") this.#lastAgents = agents;
    } catch (error) {
      if (!allowCached) throw error;
      agents = this.#lastAgents.map((agent) => ({
        ...agent,
        focused: false,
        freshness: "stale",
        actionable: false,
      }));
      tasks = { availability: "stale", message: error.message ?? "The Codex task list is unavailable." };
    }
    return { result: { ...result, agents, tasks }, agents, tasks };
  }

  async #confirmFocus(agentId) {
    let lastError = null;
    for (let attempt = 0; attempt < this.#focusPollAttempts; attempt += 1) {
      try {
        const { agents } = await this.#observeAgentsNow({ allowCached: false });
        if (agents.some((agent) => agent.id === agentId && agent.focused === true)) return;
      } catch (error) {
        lastError = error;
      }
      if (attempt + 1 < this.#focusPollAttempts) await this.#wait(this.#focusPollIntervalMs);
    }
    throw new Error(
      "ChatGPT did not confirm the requested Codex task before the focus timeout.",
      lastError ? { cause: lastError } : undefined,
    );
  }

  async #leaseVisibleFocus(agentId) {
    this.#expectedVisibleAgentId = agentId;
    const lease = this.#confirmFocus(agentId);
    this.#visibleFocusLease = lease;
    lease.catch(() => {});
    await lease;
    this.#confirmedVisibleAgentId = agentId;
    if (this.#expectedVisibleAgentId === agentId) this.#expectedVisibleAgentId = null;
  }

  #acceptObservedFocus(agents) {
    if (!this.#expectedVisibleAgentId) return;
    if (!agents.some((agent) => (
      agent.id === this.#expectedVisibleAgentId
      && agent.focused === true
      && agent.actionable !== false
      && agent.freshness !== "stale"
    ))) return;
    this.#confirmedVisibleAgentId = this.#expectedVisibleAgentId;
    this.#expectedVisibleAgentId = null;
    this.#visibleFocusLease = Promise.resolve();
  }

  async #selectReasoningNow(level, target, effects) {
    const settings = await this.#threadCatalog.selectReasoning({
      level,
      target,
      effects,
    });
    return {
      ok: true,
      message: `Selected ${settings.reasoning.label} reasoning.`,
      settings,
    };
  }

  async #confirmSettingsBinding(expected, kind) {
    const current = await this.#statusNow();
    const actual = this.#requireSettingsAvailable(current, kind);
    if (
      actual.threadId !== expected.threadId ||
      actual.contextId !== expected.contextId
    ) {
      throw new Error(`The visible Codex ${kind} task changed before its settings mutation.`);
    }
    return { binding: actual, status: current };
  }

  #requireSettingsAvailable(status, kind) {
    const binding = status[MUTATION_BINDING];
    if (!binding) {
      throw new Error(`The visible Codex ${kind} identity is unresolved.`);
    }
    return binding;
  }

}

function unavailableDesktop(error) {
  return {
    foreground: false,
    taskState: "idle",
    binding: { state: "unbound", contextId: null },
    agents: [],
    tasks: { availability: "unavailable", message: error?.message ?? null },
    voice: { available: false, active: false },
    mode: { available: false, id: null, label: "", options: [] },
    access: { available: false, label: "" },
    reasoning: {
      available: false,
      label: "",
      level: null,
      canIncrease: false,
      canDecrease: false,
      options: [],
    },
    controls: {},
    message: error?.message ?? "The desktop UI controller is unavailable.",
  };
}

function taskObservation(value) {
  const availability = value?.state === "fresh"
    ? "fresh"
    : value?.state === "stale" ? "stale" : "unavailable";
  return {
    availability,
    message: typeof value?.error === "string" ? value.error : null,
  };
}

function projectVisibleMode(catalogMode, visibleMode) {
  if (!catalogMode) return visibleMode;
  const visibleLabel = visibleMode?.label?.trim().toLowerCase();
  if (!visibleLabel) return catalogMode;
  const selected = catalogMode.options?.find((option) =>
    option.label?.trim().toLowerCase() === visibleLabel);
  if (!selected) return catalogMode;
  return {
    ...catalogMode,
    id: selected.id,
    label: selected.label,
    options: catalogMode.options.map((option) => ({
      ...option,
      selected: option.id === selected.id,
    })),
  };
}

function bindingObservation(result, agents) {
  const desktopToken = result?.identity?.mutationToken;
  const focusedAgents = Array.isArray(agents) ? agents.filter((agent) =>
    agent.focused === true && agent.actionable !== false && agent.freshness !== "stale") : [];
  if (focusedAgents.length === 0) return { state: "unbound", contextId: null, mutation: null };
  if (focusedAgents.length !== 1) return { state: "conflict", contextId: null, mutation: null };

  const contextId = focusedAgents[0].id;
  const validDesktopToken = typeof desktopToken === "string"
    && /^desktop-[a-f0-9]{24,64}$/.test(desktopToken);
  return {
    state: "confirmed",
    contextId,
    mutation: Object.freeze({
      contextId,
      desktopToken: validDesktopToken ? desktopToken : null,
    }),
  };
}

function requireEffects(value) {
  if (!value || typeof value.commit !== "function") {
    throw new TypeError("A settings effect boundary is required.");
  }
}

function commitEffect(effects, operation) {
  return effects ? effects.commit(operation) : operation();
}

function disabledControls(controls) {
  return Object.fromEntries(
    [...new Set([...Object.keys(controls ?? {}), ...VISIBLE_UI_CONTROLS])]
      .map((key) => [key, false]),
  );
}

export function prepareControlRequest(
  action,
  args,
  input = "",
  timeoutMs = MAXIMUM_CONTROL_REQUEST_LIFETIME_MS,
  now = Date.now,
) {
  const boundedTimeoutMs = Math.min(
    MAXIMUM_CONTROL_REQUEST_LIFETIME_MS,
    Math.max(
      1,
      Number.isFinite(timeoutMs) ? Math.trunc(timeoutMs) : MAXIMUM_CONTROL_REQUEST_LIFETIME_MS,
    ),
  );
  const requestTimeMs = now();
  const deadlineMs = requestTimeMs + boundedTimeoutMs;
  if (!Number.isSafeInteger(requestTimeMs) || !Number.isSafeInteger(deadlineMs)) {
    throw new TypeError("The control request clock must return integer milliseconds.");
  }
  return {
    envelope: {
      action,
      arguments: args,
      input,
      deadlineMs,
    },
    timeoutMs: boundedTimeoutMs,
  };
}

function runSocketRequest(
  socketPath,
  action,
  args,
  input = "",
  timeoutMs = MAXIMUM_CONTROL_REQUEST_LIFETIME_MS,
  now = Date.now,
) {
  const request = prepareControlRequest(action, args, input, timeoutMs, now);
  return new Promise((resolve, reject) => {
    const socket = createConnection({ path: socketPath });
    let response = "";
    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      socket.destroy();
      reject(new Error(`The Vibe Pocket Bridge Host timed out after ${request.timeoutMs} ms.`));
    }, request.timeoutMs);
    const finish = (error, body) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      socket.destroy();
      if (error) reject(error);
      else resolve(body);
    };
    socket.setEncoding("utf8");
    socket.once("connect", () => {
      socket.write(`${JSON.stringify(request.envelope)}\n`);
    });
    socket.on("data", (chunk) => {
      response += chunk;
      if (response.length > 256 * 1024) {
        finish(new Error("The Vibe Pocket Bridge Host returned an oversized response."));
        return;
      }
      const newline = response.indexOf("\n");
      if (newline < 0) return;
      try {
        finish(null, JSON.parse(response.slice(0, newline)));
      } catch {
        finish(new Error("The macOS desktop controller returned an invalid response."));
      }
    });
    socket.once("error", (error) => finish(error));
    socket.once("end", () => {
      if (!settled) finish(new Error("The Vibe Pocket Bridge Host closed without a response."));
    });
  });
}
