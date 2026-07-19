import { createConnection } from "node:net";

import { open } from "../task/open.mjs";

export class Desktop {
  #socketPath;
  #run;
  #openThread;
  #threadCatalog;
  #wait;
  #voiceActive = false;
  #lastAgents = [];
  #operationQueue = Promise.resolve();

  constructor({
    socketPath = process.env.VIBE_POCKET_HOST_SOCKET,
    run = runSocketRequest,
    openThread = open,
    threadCatalog = null,
    wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  } = {}) {
    this.#socketPath = socketPath;
    this.#run = run;
    this.#openThread = openThread;
    this.#threadCatalog = threadCatalog;
    this.#wait = wait;
  }

  async status() {
    const result = await this.#invoke("status");
    if (!this.#threadCatalog) return result;
    let agents;
    try {
      agents = await this.#threadCatalog.resolveVisibleAgents(result.agents);
      this.#lastAgents = agents;
    } catch {
      agents = this.#lastAgents;
    }
    let settings = null;
    try {
      settings = await this.#threadCatalog.settings(result.reasoning);
    } catch {
      settings = null;
    }
    const modelAvailable = settings?.model.available === true && result.controls?.["model-picker"] === true;
    const reasoningAvailable = settings?.reasoning.available === true && result.controls?.reasoning === true;
    return {
      ...result,
      agents,
      model: settings?.model ? { ...settings.model, available: modelAvailable } : undefined,
      reasoning: settings?.reasoning ? {
        ...settings.reasoning,
        available: reasoningAvailable,
        canIncrease: reasoningAvailable && settings.reasoning.canIncrease,
        canDecrease: reasoningAvailable && settings.reasoning.canDecrease,
      } : result.reasoning,
      controls: {
        ...result.controls,
        "focus-agent": agents.some((agent) => !agent.focused),
        model: modelAvailable,
        reasoning: reasoningAvailable,
      },
    };
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
    await this.#openThread(threadId);
    await this.#wait(700);
    return this.attach();
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

  async selectModel(modelId) {
    if (!this.#threadCatalog) throw new Error("Native Codex model selection is unavailable.");
    await this.#invoke("select-model", [modelId]);
    const current = await this.status();
    const settings = { model: current.model, reasoning: current.reasoning };
    return { ok: true, message: `Selected ${settings.model.label}.`, settings };
  }

  async adjustReasoning(delta) {
    if (!this.#threadCatalog) throw new Error("Native Codex reasoning selection is unavailable.");
    await this.#invoke("reasoning", [`${delta}`]);
    const current = await this.status();
    const settings = { model: current.model, reasoning: current.reasoning };
    return { ok: true, message: `Selected ${settings.reasoning.label} reasoning.`, settings };
  }

  async clearInput() {
    return this.#invoke("clear-input");
  }

  async deleteBackward() {
    return this.#invoke("delete-backward");
  }

  async focusAgent(agentId) {
    if (!this.#threadCatalog) return this.#invoke("focus-agent", [agentId]);
    return this.#threadCatalog.focusAgent(agentId);
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

}

function runSocketRequest(socketPath, action, args, input = "", timeoutMs = 10_000) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ path: socketPath });
    let response = "";
    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      socket.destroy();
      reject(new Error(`The Vibe Pocket Bridge Host timed out after ${timeoutMs} ms.`));
    }, timeoutMs);
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
      socket.write(`${JSON.stringify({ action, arguments: args, input })}\n`);
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
