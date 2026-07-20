import { createConnection } from "node:net";

import { open } from "../task/open.mjs";

const MUTATION_BINDING = Symbol("desktopMutationBinding");
const MAXIMUM_CONTROL_REQUEST_LIFETIME_MS = 10_000;

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
    const { result, agents, tasks } = await this.#observeAgentsNow();
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
    const binding = mutationBinding(result, settings, agents);
    const modelAvailable = Boolean(
      binding && settings?.model.available === true && result.controls?.["model-picker"] === true,
    );
    const reasoningAvailable = Boolean(
      binding && settings?.reasoning.available === true && result.controls?.reasoning === true,
    );
    const modeAvailable = Boolean(binding && settings?.mode?.available === true);
    const view = {
      ...result,
      agents,
      tasks,
      mode: settings?.mode ? { ...settings.mode, available: modeAvailable } : result.mode,
      model: settings?.model ? { ...settings.model, available: modelAvailable } : undefined,
      reasoning: settings?.reasoning ? {
        ...settings.reasoning,
        available: reasoningAvailable,
        canIncrease: reasoningAvailable && settings.reasoning.canIncrease,
        canDecrease: reasoningAvailable && settings.reasoning.canDecrease,
      } : result.reasoning,
      controls: {
        ...result.controls,
        "focus-agent": agents.some((agent) => agent.actionable !== false && !agent.focused),
        "mode-cycle": modeAvailable,
        model: modelAvailable,
        reasoning: reasoningAvailable,
      },
    };
    Object.defineProperty(view, MUTATION_BINDING, { value: binding });
    return view;
  }

  get voiceActive() {
    return this.#voiceActive;
  }

  async activate() {
    return this.attach();
  }

  async attach() {
    return this.#invoke("attach");
  }

  async bindThread(threadId) {
    if (!this.#threadCatalog) {
      throw new Error("The native Codex task catalog is required to confirm a desktop task.");
    }
    return this.#enqueue(async () => {
      const requested = typeof this.#threadCatalog.focusThread === "function"
        ? await this.#threadCatalog.focusThread(threadId)
        : await this.#openThread(threadId).then(() => ({ agentId: null }));
      if (!requested?.agentId) {
        throw new Error("The native Codex task catalog could not identify the requested desktop task.");
      }
      await this.#confirmFocus(requested.agentId);
      return this.#invokeNow("attach", [], "");
    });
  }

  applyLifecycleHook() {
    return {
      accepted: false,
      response: Promise.resolve({}),
    };
  }

  async press(control) {
    return this.#invoke("control", [control]);
  }

  async setVoice(active) {
    if (typeof active !== "boolean") throw new TypeError("Voice state must be boolean.");
    const result = await this.#invoke(active ? "voice-start" : "voice-stop");
    this.#voiceActive = active;
    return result;
  }

  async navigate(direction) {
    return this.#invoke("navigate", [direction]);
  }

  async cycleMode() {
    return this.#invoke("plan-mode");
  }

  async cycleAccess() {
    return this.#invoke("access-cycle");
  }

  async openModel() {
    return this.#invoke("model-picker");
  }

  async selectModel(modelId, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex model selection is unavailable.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      const current = await this.#statusNow();
      const binding = this.#requireSettingsAvailable(current, "model");
      const selected = await this.#threadCatalog.validateModel({
        id: modelId,
        threadId: binding.threadId,
      });
      if (!selected?.id) throw new Error("The requested Codex model is unavailable or stale.");
      await this.#confirmSettingsBinding(binding, "model");
      const settings = await this.#threadCatalog.selectModel({
        id: selected.id,
        threadId: binding.threadId,
        effects,
      });
      return { ok: true, message: `Selected ${settings.model.label}.`, settings };
    });
  }

  async selectMode(modeId, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex mode selection is unavailable.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      const current = await this.#statusNow();
      const binding = this.#requireSettingsAvailable(current, "mode");
      await this.#confirmSettingsBinding(binding, "mode");
      const settings = await this.#threadCatalog.selectMode({
        id: modeId,
        threadId: binding.threadId,
        effects,
      });
      return { ok: true, message: `Selected ${settings.mode.label} mode.`, settings };
    });
  }

  async adjustReasoning(delta, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex reasoning selection is unavailable.");
    if (delta !== -1 && delta !== 1) throw new Error("Reasoning adjustment must be one step.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      const current = await this.#statusNow();
      const binding = this.#requireSettingsAvailable(current, "reasoning");
      if (!this.#threadCatalog.reasoningTarget(delta)) {
        throw new Error("Codex reasoning cannot move farther in that direction.");
      }
      await this.#confirmSettingsBinding(binding, "reasoning");
      const target = this.#threadCatalog.reasoningTarget(delta);
      if (!target) throw new Error("Codex reasoning changed before its settings mutation.");
      return this.#selectReasoningNow(target, binding, effects);
    });
  }

  async selectReasoning(level, effects) {
    if (!this.#threadCatalog) throw new Error("Native Codex reasoning selection is unavailable.");
    requireEffects(effects);
    return this.#enqueue(async () => {
      const current = await this.#statusNow();
      const binding = this.#requireSettingsAvailable(current, "reasoning");
      if (!this.#threadCatalog.hasReasoningLevel(level)) {
        throw new Error("The requested Codex reasoning level is unavailable or stale.");
      }
      await this.#confirmSettingsBinding(binding, "reasoning");
      if (!this.#threadCatalog.hasReasoningLevel(level)) {
        throw new Error("The requested Codex reasoning level changed before its settings mutation.");
      }
      return this.#selectReasoningNow(level, binding, effects);
    });
  }

  async clearInput() {
    return this.#invoke("clear-input");
  }

  async deleteBackward() {
    return this.#invoke("delete-backward");
  }

  async focusAgent(agentId) {
    if (!this.#threadCatalog) return this.#invoke("focus-agent", [agentId]);
    return this.#enqueue(async () => {
      const requested = await this.#threadCatalog.focusAgent(agentId);
      await this.#confirmFocus(requested?.agentId ?? agentId);
      return {
        ok: true,
        message: "Focused the selected Codex task after native desktop confirmation.",
      };
    });
  }

  async workflow(prompt) {
    return this.#invoke("workflow", [], prompt);
  }

  async dispose() {
    this.#lastAgents = [];
    await this.#threadCatalog?.dispose?.();
  }

  async #invoke(action, args = [], input = "") {
    return this.#enqueue(() => this.#invokeNow(action, args, input));
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

  async #selectReasoningNow(level, binding, effects) {
    const settings = await this.#threadCatalog.selectReasoning({
      level,
      threadId: binding.threadId,
      effects,
    });
    return { ok: true, message: `Selected ${settings.reasoning.label} reasoning.`, settings };
  }

  async #confirmSettingsBinding(expected, kind) {
    const current = await this.#statusNow();
    const actual = this.#requireSettingsAvailable(current, kind);
    if (actual.threadId !== expected.threadId || actual.desktopToken !== expected.desktopToken) {
      throw new Error(`The visible Codex ${kind} task changed before its settings mutation.`);
    }
  }

  #requireSettingsAvailable(status, kind) {
    if (status.taskState === "executing" || status.controls?.stop === true) {
      throw new Error(`Codex ${kind} cannot be changed while the visible task is running.`);
    }
    if (status.controls?.[kind] !== true) {
      throw new Error(`The visible Codex ${kind} control is unavailable.`);
    }
    const binding = status[MUTATION_BINDING];
    if (!binding) {
      throw new Error(`The visible Codex ${kind} identity is unresolved.`);
    }
    return binding;
  }

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

function mutationBinding(result, settings, agents) {
  const desktopToken = result?.identity?.mutationToken;
  const threadId = settings?.binding?.threadId;
  const focusedAgents = Array.isArray(agents) ? agents.filter(({ focused }) => focused === true) : [];
  if (typeof desktopToken !== "string" || !/^desktop-[a-f0-9]{24,64}$/.test(desktopToken)) return null;
  if (typeof threadId !== "string" || !threadId || focusedAgents.length !== 1) return null;
  return Object.freeze({ desktopToken, threadId });
}

function requireEffects(value) {
  if (!value || typeof value.commit !== "function") {
    throw new TypeError("A settings effect boundary is required.");
  }
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
