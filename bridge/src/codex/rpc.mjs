import { EventEmitter } from "node:events";
import { spawn } from "node:child_process";

const READ_ONLY_METHODS = new Set(["model/list", "thread/list", "thread/resume"]);

/**
 * Small JSON-RPC client for the locally installed `codex app-server` command.
 * The bridge intentionally owns this experimental protocol so the Android app
 * never has to know Codex's transport, authentication, or version details.
 */
export class Rpc extends EventEmitter {
  #command;
  #args;
  #spawn;
  #requestTimeoutMs;
  #child = null;
  #startPromise = null;
  #initialized = false;
  #nextRequestId = 1;
  #pending = new Map();
  #serverRequests = new Map();

  constructor({
    command = "codex",
    args = ["app-server", "--listen", "stdio://"],
    spawnProcess = spawn,
    requestTimeoutMs = 10_000,
  } = {}) {
    super();
    this.#command = command;
    this.#args = args;
    this.#spawn = spawnProcess;
    this.#requestTimeoutMs = requestTimeoutMs;
  }

  get isRunning() {
    return this.#child !== null && this.#child.exitCode == null;
  }

  async start() {
    if (this.#initialized && this.isRunning) return;
    if (this.#startPromise) {
      const inFlight = this.#startPromise;
      await inFlight;
      if (this.#initialized && this.isRunning) return;
      if (this.#startPromise === inFlight) this.#startPromise = null;
    }
    if (!this.#startPromise) {
      const starting = this.#startChild();
      this.#startPromise = starting;
      starting.finally(() => {
        if (this.#startPromise === starting) this.#startPromise = null;
      }).catch(() => {});
    }
    const starting = this.#startPromise;
    await starting;
    if (!this.#initialized || !this.isRunning) {
      throw new RpcTransportError("Codex app-server exited during startup.");
    }
  }

  async stop() {
    const child = this.#child;
    if (child) {
      this.#invalidateChild(child, new RpcTransportError("Codex app-server was stopped."));
      if (child.exitCode == null) child.kill("SIGTERM");
    }
    if (this.#startPromise) {
      try {
        await this.#startPromise;
      } catch {
        // Stopping an initializing child intentionally rejects initialize.
      }
    }
  }

  async request(method, params = {}) {
    const mayRetry = READ_ONLY_METHODS.has(method);
    for (let attempt = 0; ; attempt += 1) {
      try {
        await this.start();
        const child = this.#child;
        if (!child) throw new RpcTransportError("Codex app-server is not running.");
        return await this.#requestOnce(child, method, params);
      } catch (error) {
        if (!mayRetry || attempt >= 1 || !(error instanceof RpcTransportError)) throw error;
        const child = this.#child;
        if (child) this.#terminateChild(child, error);
      }
    }
  }

  respond(id, result) {
    const child = this.#serverRequests.get(id);
    if (!child || child !== this.#child) {
      throw new RpcTransportError("The Codex server request is no longer active.");
    }
    this.#serverRequests.delete(id);
    this.#write(child, { id, result });
  }

  notify(method, params = {}) {
    const child = this.#child;
    if (!child) throw new RpcTransportError("Codex app-server is not running.");
    this.#write(child, { method, params });
  }

  respondWithError(id, code, message) {
    const child = this.#serverRequests.get(id);
    if (!child || child !== this.#child) {
      throw new RpcTransportError("The Codex server request is no longer active.");
    }
    this.#serverRequests.delete(id);
    this.#write(child, { id, error: { code, message } });
  }

  async #startChild() {
    let child;
    try {
      child = this.#spawn(this.#command, this.#args, {
        stdio: ["pipe", "pipe", "pipe"],
        env: process.env,
      });
    } catch (error) {
      throw new RpcTransportError("Codex app-server could not be started.", { cause: error });
    }
    this.#child = child;
    this.#initialized = false;
    const transport = { child, stdoutBuffer: "" };

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => this.#onStdout(transport, chunk));
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      if (this.#child === child) this.emit("stderr", chunk);
    });
    child.stdin.on?.("error", (error) => {
      this.#terminateChild(child, new RpcTransportError("Codex app-server input failed.", { cause: error }));
    });
    child.on("error", (error) => {
      this.#terminateChild(child, new RpcTransportError("Codex app-server failed.", { cause: error }));
      this.emit("childError", error);
    });
    child.on("exit", (code, signal) => {
      const reason = new RpcTransportError(
        `Codex app-server exited (${signal ?? `code ${code ?? "unknown"}`}).`,
      );
      this.#invalidateChild(child, reason);
      this.emit("exit", { code, signal });
    });

    try {
      await this.#requestOnce(child, "initialize", {
        clientInfo: {
          name: "vibe-pocket-bridge",
          title: "Vibe Pocket Bridge",
          version: "0.1.0",
        },
        capabilities: {
          experimentalApi: true,
        },
      });
      if (this.#child !== child || child.exitCode != null) {
        throw new RpcTransportError("Codex app-server exited during initialization.");
      }
      this.#initialized = true;
      this.#write(child, { method: "initialized", params: {} });
    } catch (error) {
      this.#terminateChild(child, asTransportError(error));
      throw error;
    }
  }

  #requestOnce(child, method, params) {
    const id = this.#nextRequestId++;
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        if (!this.#pending.has(id)) return;
        const error = new RpcTransportError(
          `Codex app-server timed out during ${method} after ${this.#requestTimeoutMs} ms.`,
        );
        this.#terminateChild(child, error);
      }, this.#requestTimeoutMs);
      this.#pending.set(id, { child, resolve, reject, method, timeout });
      try {
        this.#write(child, { id, method, params });
      } catch (error) {
        const pending = this.#pending.get(id);
        if (pending) {
          clearTimeout(pending.timeout);
          this.#pending.delete(id);
          reject(asTransportError(error));
        }
      }
    });
  }

  #write(child, message) {
    if (child !== this.#child || child.exitCode != null || !child.stdin.writable) {
      throw new RpcTransportError("Codex app-server is not running.");
    }
    try {
      child.stdin.write(`${JSON.stringify(message)}\n`);
    } catch (error) {
      throw new RpcTransportError("Codex app-server input failed.", { cause: error });
    }
  }

  #onStdout(transport, chunk) {
    if (transport.child !== this.#child) return;
    transport.stdoutBuffer += chunk;
    let lineEnd = transport.stdoutBuffer.indexOf("\n");
    while (lineEnd !== -1) {
      const line = transport.stdoutBuffer.slice(0, lineEnd).trim();
      transport.stdoutBuffer = transport.stdoutBuffer.slice(lineEnd + 1);
      if (line) this.#onMessage(transport.child, line);
      lineEnd = transport.stdoutBuffer.indexOf("\n");
    }
  }

  #onMessage(child, line) {
    if (child !== this.#child) return;
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      this.emit("protocolError", new Error(`Invalid JSON from app-server: ${line}`));
      return;
    }

    if (Object.hasOwn(message, "id") && !Object.hasOwn(message, "method")) {
      const pending = this.#pending.get(message.id);
      if (!pending || pending.child !== child) {
        this.emit("orphanResponse", message);
        return;
      }
      clearTimeout(pending.timeout);
      this.#pending.delete(message.id);
      if (message.error) pending.reject(new RpcError(pending.method, message.error));
      else pending.resolve(message.result);
      return;
    }

    if (Object.hasOwn(message, "id") && Object.hasOwn(message, "method")) {
      this.#serverRequests.set(message.id, child);
      this.emit("serverRequest", message);
      return;
    }

    if (Object.hasOwn(message, "method")) {
      this.emit("notification", message);
      return;
    }

    this.emit("protocolError", new Error(`Unrecognized app-server message: ${line}`));
  }

  #terminateChild(child, error) {
    this.#invalidateChild(child, error);
    if (child.exitCode == null) child.kill("SIGTERM");
  }

  #invalidateChild(child, error) {
    const wasActive = this.#child === child;
    if (wasActive) {
      this.#child = null;
      this.#initialized = false;
      this.emit("transportReset", { reason: error });
    }
    for (const [id, pending] of this.#pending) {
      if (pending.child !== child) continue;
      clearTimeout(pending.timeout);
      this.#pending.delete(id);
      pending.reject(error);
    }
    for (const [id, requestChild] of this.#serverRequests) {
      if (requestChild === child) this.#serverRequests.delete(id);
    }
  }
}

class RpcTransportError extends Error {
  constructor(message, options) {
    super(message, options);
    this.name = "RpcTransportError";
  }
}

function asTransportError(error) {
  return error instanceof RpcTransportError
    ? error
    : new RpcTransportError(error?.message ?? "Codex app-server transport failed.", { cause: error });
}

export class RpcError extends Error {
  constructor(method, error) {
    super(`Codex app-server rejected ${method}: ${error.message ?? "Unknown error"}`);
    this.name = "RpcError";
    this.code = error.code;
    this.data = error.data;
  }
}
