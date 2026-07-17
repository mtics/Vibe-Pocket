import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { access } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const helperSource = fileURLToPath(new URL("./macos-codex-helper.swift", import.meta.url));

export class MacCodexDesktopController {
  #binaryPath;
  #swiftCompiler;
  #run;
  #prebuilt;
  #compilePromise = null;
  #operationQueue = Promise.resolve();

  constructor({
    binaryPath = process.env.VIBE_POCKET_HELPER_PATH
      ?? join(tmpdir(), `vibe-pocket-codex-desktop-${process.getuid?.() ?? randomUUID()}`),
    swiftCompiler = process.env.VIBE_POCKET_SWIFTC ?? "swiftc",
    prebuilt = Boolean(process.env.VIBE_POCKET_HELPER_PATH),
    run = runProgram,
  } = {}) {
    this.#binaryPath = binaryPath;
    this.#swiftCompiler = swiftCompiler;
    this.#prebuilt = prebuilt;
    this.#run = run;
  }

  async status() {
    return this.#invoke("status");
  }

  async attach() {
    return this.#invoke("attach");
  }

  async press(control) {
    return this.#invoke("control", [control]);
  }

  async navigate(direction) {
    return this.#invoke("navigate", [direction]);
  }

  async cycleMode() {
    return this.#invoke("mode-cycle");
  }

  async adjustReasoning(delta) {
    return this.#invoke("reasoning", [String(delta)]);
  }

  async clearInput() {
    return this.#invoke("clear-input");
  }

  async focusAgent(index) {
    return this.#invoke("focus-agent", [String(index)]);
  }

  async workflow(prompt) {
    return this.#invoke("workflow", [], prompt);
  }

  async #invoke(action, args = [], input = "") {
    const operation = this.#operationQueue.then(() => this.#invokeNow(action, args, input));
    this.#operationQueue = operation.catch(() => {});
    return operation;
  }

  async #invokeNow(action, args, input) {
    await this.#compile();
    const result = await this.#run(this.#binaryPath, [action, ...args], input);
    let body;
    try {
      body = JSON.parse(result.stdout);
    } catch {
      throw new Error("The macOS desktop controller returned an invalid response.");
    }
    if (!body.ok) throw new Error(body.message ?? "The macOS desktop controller rejected this action.");
    return body;
  }

  async #compile() {
    if (!this.#compilePromise) {
      const prepare = this.#prebuilt
        ? access(this.#binaryPath, fsConstants.X_OK)
        : this.#run(this.#swiftCompiler, [helperSource, "-O", "-o", this.#binaryPath]);
      this.#compilePromise = prepare
        .catch((error) => {
          this.#compilePromise = null;
          const verb = this.#prebuilt ? "use" : "build";
          throw new Error(`Could not ${verb} the macOS desktop controller: ${error.message}`);
        });
    }
    return this.#compilePromise;
  }
}

function runProgram(command, args, input = "", timeoutMs = command === "swiftc" ? 30_000 : 8_000) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["pipe", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const timeout = setTimeout(() => {
      if (settled) return;
      settled = true;
      child.kill("SIGKILL");
      reject(new Error(`${command} timed out after ${timeoutMs} ms.`));
    }, timeoutMs);
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", (error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(error);
    });
    child.once("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (code === 0) {
        resolve({ stdout, stderr });
      } else {
        reject(new Error(stderr.trim() || `${command} exited with code ${code}.`));
      }
    });
    child.stdin.end(input);
  });
}
