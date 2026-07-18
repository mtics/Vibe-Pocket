import { createConnection } from "node:net";

import { openCodexThread } from "./codex-thread-opener.mjs";

export class MacCodexDesktopController {
  #socketPath;
  #run;
  #openThread;
  #wait;
  #voiceActive = false;
  #operationQueue = Promise.resolve();

  constructor({
    socketPath = process.env.VIBE_POCKET_HOST_SOCKET,
    run = runSocketRequest,
    openThread = openCodexThread,
    wait = (milliseconds) => new Promise((resolve) => setTimeout(resolve, milliseconds)),
  } = {}) {
    this.#socketPath = socketPath;
    this.#run = run;
    this.#openThread = openThread;
    this.#wait = wait;
  }

  async status() {
    return this.#invoke("status");
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
    this.#voiceActive = active;
    return {
      message: active
        ? "Started phone dictation for the visible Codex input."
        : "Stopped phone dictation for the visible Codex input.",
    };
  }

  async setDictationDraft(text) {
    const result = await this.#invoke("dictation-draft", [], text);
    this.#voiceActive = false;
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
    return this.#invoke("focus-agent", [agentId]);
  }

  async workflow(prompt) {
    return this.#invoke("workflow", [], prompt);
  }

  async dispose() {}

  async #invoke(action, args = [], input = "") {
    const operation = this.#operationQueue.then(() => this.#invokeNow(action, args, input));
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
