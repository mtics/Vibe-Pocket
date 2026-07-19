import { createConnection } from "node:net";

import { openCodexThread } from "./codex-thread-opener.mjs";

export class MacCodexDesktopController {
  #socketPath;
  #run;
  #openThread;
  #threadCatalog;
  #wait;
  #voiceActive = false;
  #foreground = false;
  #lastAgents = [];
  #operationQueue = Promise.resolve();

  constructor({
    socketPath = process.env.VIBE_POCKET_HOST_SOCKET,
    run = runSocketRequest,
    openThread = openCodexThread,
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
    this.#foreground = result.foreground === true;
    if (!this.#threadCatalog) return result;
    try {
      const agents = await this.#threadCatalog.resolveVisibleAgents(result.agents);
      this.#lastAgents = agents;
      return {
        ...result,
        agents,
        controls: {
          ...result.controls,
          "focus-agent": this.#foreground && agents.some((agent) => !agent.focused),
        },
      };
    } catch {
      const agents = this.#lastAgents;
      return {
        ...result,
        agents,
        controls: {
          ...result.controls,
          "focus-agent": this.#foreground && agents.some((agent) => !agent.focused),
        },
      };
    }
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

  async adjustReasoning(delta) {
    return this.#invoke("reasoning", [String(delta)]);
  }

  async clearInput() {
    return this.#invoke("clear-input");
  }

  async focusAgent(agentId) {
    if (!this.#threadCatalog) return this.#invoke("focus-agent", [agentId]);
    if (!this.#foreground) {
      throw new Error("Open Codex on the Mac before selecting another task.");
    }
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
