import { EventEmitter } from "node:events";
import { spawn } from "node:child_process";

/**
 * Small JSON-RPC client for the locally installed `codex app-server` command.
 * The bridge intentionally owns this experimental protocol so the Android app
 * never has to know Codex's transport, authentication, or version details.
 */
export class CodexAppServer extends EventEmitter {
  #command;
  #args;
  #spawn;
  #child = null;
  #nextRequestId = 1;
  #pending = new Map();
  #stdoutBuffer = "";
  #started = false;

  constructor({
    command = "codex",
    args = ["app-server", "--listen", "stdio://"],
    spawnProcess = spawn,
  } = {}) {
    super();
    this.#command = command;
    this.#args = args;
    this.#spawn = spawnProcess;
  }

  get isRunning() {
    return this.#child !== null && this.#child.exitCode === null;
  }

  async start() {
    if (this.#started) {
      return;
    }

    const child = this.#spawn(this.#command, this.#args, {
      stdio: ["pipe", "pipe", "pipe"],
      env: process.env,
    });
    this.#child = child;
    this.#started = true;

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => this.#onStdout(chunk));

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => this.emit("stderr", chunk));

    child.on("error", (error) => this.#fail(error));
    child.on("exit", (code, signal) => {
      const reason = new Error(
        `Codex app-server exited (${signal ?? `code ${code ?? "unknown"}`}).`,
      );
      this.#fail(reason);
      this.emit("exit", { code, signal });
    });

    await this.request("initialize", {
      clientInfo: {
        name: "vibe-pocket-bridge",
        title: "Vibe Pocket Bridge",
        version: "0.1.0",
      },
      capabilities: {
        experimentalApi: true,
      },
    });
    this.notify("initialized");
  }

  async stop() {
    const child = this.#child;
    this.#child = null;
    this.#started = false;
    if (child && child.exitCode === null) {
      child.kill("SIGTERM");
    }
  }

  request(method, params = {}) {
    const id = this.#nextRequestId++;
    return new Promise((resolve, reject) => {
      this.#pending.set(id, { resolve, reject, method });
      try {
        this.#write({ id, method, params });
      } catch (error) {
        this.#pending.delete(id);
        reject(error);
      }
    });
  }

  respond(id, result) {
    this.#write({ id, result });
  }

  notify(method, params = {}) {
    this.#write({ method, params });
  }

  respondWithError(id, code, message) {
    this.#write({ id, error: { code, message } });
  }

  #write(message) {
    if (!this.isRunning || !this.#child?.stdin.writable) {
      throw new Error("Codex app-server is not running.");
    }
    this.#child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  #onStdout(chunk) {
    this.#stdoutBuffer += chunk;
    let lineEnd = this.#stdoutBuffer.indexOf("\n");
    while (lineEnd !== -1) {
      const line = this.#stdoutBuffer.slice(0, lineEnd).trim();
      this.#stdoutBuffer = this.#stdoutBuffer.slice(lineEnd + 1);
      if (line) {
        this.#onMessage(line);
      }
      lineEnd = this.#stdoutBuffer.indexOf("\n");
    }
  }

  #onMessage(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch (error) {
      this.emit("protocolError", new Error(`Invalid JSON from app-server: ${line}`));
      return;
    }

    if (Object.hasOwn(message, "id") && !Object.hasOwn(message, "method")) {
      const pending = this.#pending.get(message.id);
      if (!pending) {
        this.emit("orphanResponse", message);
        return;
      }
      this.#pending.delete(message.id);
      if (message.error) {
        pending.reject(new JsonRpcError(pending.method, message.error));
      } else {
        pending.resolve(message.result);
      }
      return;
    }

    if (Object.hasOwn(message, "id") && Object.hasOwn(message, "method")) {
      this.emit("serverRequest", message);
      return;
    }

    if (Object.hasOwn(message, "method")) {
      this.emit("notification", message);
      return;
    }

    this.emit("protocolError", new Error(`Unrecognized app-server message: ${line}`));
  }

  #fail(error) {
    for (const { reject } of this.#pending.values()) {
      reject(error);
    }
    this.#pending.clear();
  }
}

export class JsonRpcError extends Error {
  constructor(method, error) {
    super(`Codex app-server rejected ${method}: ${error.message ?? "Unknown error"}`);
    this.name = "JsonRpcError";
    this.code = error.code;
    this.data = error.data;
  }
}
