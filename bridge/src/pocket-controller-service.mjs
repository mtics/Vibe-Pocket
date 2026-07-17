import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";
import { chmodSync, existsSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import * as pty from "node-pty";

const MAX_SESSIONS = 5;
const MAX_TAIL_LINES = 12;
const WORKFLOWS = {
  review: "Review the current change for correctness, security, and regressions. Report concrete findings first.",
  debug: "Investigate the current failure from evidence. Identify the root cause, implement the smallest robust fix, and verify it.",
  implement: "Implement the requested change in the current workspace. Inspect first, keep the edit scoped, and verify it proportionately.",
  test: "Run the most relevant tests for the current change. Diagnose and fix failures before reporting the result.",
};

const NAVIGATION = {
  up: "\u001B[A",
  down: "\u001B[B",
  right: "\u001B[C",
  left: "\u001B[D",
};

const require = createRequire(import.meta.url);
let spawnHelperPrepared = false;

function prepareSpawnHelper() {
  if (spawnHelperPrepared) return;
  spawnHelperPrepared = true;
  try {
    const prebuilds = join(dirname(require.resolve("node-pty/package.json")), "prebuilds");
    for (const target of readdirSync(prebuilds)) {
      const helper = join(prebuilds, target, "spawn-helper");
      if (existsSync(helper)) chmodSync(helper, 0o755);
    }
  } catch {
    // node-pty surfaces a meaningful spawn error if a future package layout changes.
  }
}

export class PocketControllerService extends EventEmitter {
  #workspaces;
  #events;
  #codexCommand;
  #spawnPty;
  #sessions = new Map();
  #focusSessionId = null;
  #idempotency = new Map();
  #revision = 0;
  #status = { state: "starting", message: null };

  constructor({ workspaces, events, codexCommand = "codex", spawnPty = pty.spawn }) {
    super();
    this.#workspaces = workspaces;
    this.#events = events;
    this.#codexCommand = codexCommand;
    this.#spawnPty = spawnPty;
  }

  async start() {
    this.#status = { state: "ready", message: null };
    this.#touch("snapshot_changed");
  }

  async dispose() {
    for (const session of this.#sessions.values()) {
      try {
        session.process.kill();
      } catch {
        // A terminal that already exited needs no cleanup.
      }
    }
  }

  async snapshot() {
    return {
      revision: `r_${this.#revision}`,
      status: this.#status,
      focusSessionId: this.#focusSessionId,
      workspaces: Object.keys(this.#workspaces),
      sessions: [...this.#sessions.values()].map((session) => publicSession(session)),
    };
  }

  async command(command, idempotencyKey) {
    if (!idempotencyKey || idempotencyKey.length > 160) {
      throw new PocketError(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }
    const existing = this.#idempotency.get(idempotencyKey);
    if (existing) return existing;

    const execution = this.#execute(command)
      .then(() => ({ commandId: `cmd_${randomUUID()}`, accepted: true, revision: `r_${this.#revision}` }))
      .catch((error) => {
        this.#idempotency.delete(idempotencyKey);
        throw error;
      });
    this.#idempotency.set(idempotencyKey, execution);
    return execution;
  }

  async #execute(command) {
    if (!command || typeof command !== "object" || Array.isArray(command)) {
      throw new PocketError(400, "invalid_command", "Controller action must be a JSON object.");
    }

    switch (command.kind) {
      case "start":
        this.#startSession(command.workspaceId);
        return;
      case "prompt":
        this.#writePrompt(requiredText(command.text, "text"), command.workspaceId);
        return;
      case "workflow":
        this.#runWorkflow(command.workflowId, command.workspaceId);
        return;
      case "accept":
        this.#writeToFocus("\r", "working");
        return;
      case "reject":
      case "interrupt":
        this.#writeToFocus("\u001B", "active");
        return;
      case "new_chat":
        this.#newChat(command.workspaceId);
        return;
      case "navigate":
        this.#navigate(command.direction);
        return;
      case "focus":
        this.#focus(command.sessionId);
        return;
      case "focus_next":
        this.#focusNext();
        return;
      default:
        throw new PocketError(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
    }
  }

  #startSession(workspaceId) {
    const workspace = this.#workspace(workspaceId);
    if (this.#sessions.size >= MAX_SESSIONS) {
      throw new PocketError(409, "session_limit", `Vibe Pocket supports up to ${MAX_SESSIONS} live sessions.`);
    }

    const id = `vp_${randomUUID()}`;
    const session = {
      id,
      workspaceId,
      state: "starting",
      terminalTail: "Starting a private Codex terminal...",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      process: null,
    };
    try {
      prepareSpawnHelper();
      const ptyProcess = this.#spawnPty(this.#codexCommand, [], {
        name: "xterm-256color",
        cols: 96,
        rows: 28,
        cwd: workspace,
        env: { ...process.env, VIBE_POCKET_SESSION_ID: id },
      });
      session.process = ptyProcess;
      ptyProcess.onData((data) => this.#onTerminalData(id, data));
      ptyProcess.onExit(({ exitCode }) => this.#onTerminalExit(id, exitCode));
    } catch (error) {
      session.state = "error";
      session.terminalTail = `Could not start Codex: ${error.message}`;
    }
    this.#sessions.set(id, session);
    this.#focusSessionId = id;
    this.#touch("snapshot_changed", { sessionId: id });
  }

  #newChat(workspaceId) {
    if (!this.#focusSessionId) {
      this.#startSession(workspaceId ?? Object.keys(this.#workspaces)[0]);
      return;
    }
    this.#writeToFocus("/new\r", "active");
  }

  #runWorkflow(workflowId, workspaceId) {
    if (typeof workflowId !== "string" || !WORKFLOWS[workflowId]) {
      throw new PocketError(400, "unknown_workflow", "Choose a configured Vibe Pocket workflow.");
    }
    this.#writePrompt(WORKFLOWS[workflowId], workspaceId);
  }

  #writePrompt(text, workspaceId) {
    if (!this.#focusSessionId) {
      this.#startSession(workspaceId ?? this.#defaultWorkspaceId());
    }
    this.#writeToFocus(`${text}\r`, "working");
  }

  #navigate(direction) {
    if (typeof direction !== "string" || !NAVIGATION[direction]) {
      throw new PocketError(400, "invalid_navigation", "Navigation must be up, down, left, or right.");
    }
    this.#writeToFocus(NAVIGATION[direction], "active");
  }

  #focus(sessionId) {
    if (typeof sessionId !== "string" || !this.#sessions.has(sessionId)) {
      throw new PocketError(404, "unknown_session", "Choose a live Vibe Pocket session.");
    }
    this.#focusSessionId = sessionId;
    this.#touch("snapshot_changed", { sessionId });
  }

  #focusNext() {
    const sessions = [...this.#sessions.values()].filter((session) => session.state !== "stopped" && session.state !== "error");
    if (sessions.length === 0) {
      throw new PocketError(409, "no_session", "Start a Vibe Pocket session first.");
    }
    const currentIndex = sessions.findIndex((session) => session.id === this.#focusSessionId);
    this.#focusSessionId = sessions[(currentIndex + 1 + sessions.length) % sessions.length].id;
    this.#touch("snapshot_changed", { sessionId: this.#focusSessionId });
  }

  #writeToFocus(bytes, nextState) {
    const session = this.#focusedSession();
    if (!session.process || session.state === "stopped" || session.state === "error") {
      throw new PocketError(409, "session_not_running", "The focused Codex terminal is no longer running.");
    }
    session.process.write(bytes);
    session.state = nextState;
    session.updatedAt = new Date().toISOString();
    this.#touch("snapshot_changed", { sessionId: session.id });
  }

  #focusedSession() {
    const session = this.#focusSessionId ? this.#sessions.get(this.#focusSessionId) : null;
    if (!session) {
      throw new PocketError(409, "no_session", "Start a Vibe Pocket Codex session first.");
    }
    return session;
  }

  #workspace(alias) {
    if (!alias || typeof alias !== "string" || !this.#workspaces[alias]) {
      throw new PocketError(400, "unknown_workspace", "Choose a workspace configured on the M5.");
    }
    return this.#workspaces[alias];
  }

  #defaultWorkspaceId() {
    const workspaceId = Object.keys(this.#workspaces)[0];
    if (!workspaceId) {
      throw new PocketError(500, "workspace_unavailable", "The M5 Bridge has no configured workspace.");
    }
    return workspaceId;
  }

  #onTerminalData(sessionId, data) {
    const session = this.#sessions.get(sessionId);
    if (!session) return;
    const text = terminalText(data);
    if (!text) return;
    session.terminalTail = appendTail(session.terminalTail, text);
    session.updatedAt = new Date().toISOString();
    const lower = session.terminalTail.toLowerCase();
    if (/allow once|allow always|permission|approve|do you want|would you like/.test(lower)) {
      session.state = "waiting";
    } else if (session.state === "starting") {
      session.state = "active";
    }
    this.#touch("snapshot_changed", { sessionId });
  }

  #onTerminalExit(sessionId, exitCode) {
    const session = this.#sessions.get(sessionId);
    if (!session) return;
    session.state = exitCode === 0 ? "stopped" : "error";
    session.terminalTail = appendTail(session.terminalTail, `Codex terminal exited with code ${exitCode}.`);
    session.updatedAt = new Date().toISOString();
    this.#touch("snapshot_changed", { sessionId });
  }

  #touch(eventType, data = {}) {
    this.#revision += 1;
    this.#events.publish(eventType, { revision: `r_${this.#revision}`, ...data });
  }
}

export class PocketError extends Error {
  constructor(status, code, message) {
    super(message);
    this.name = "PocketError";
    this.status = status;
    this.code = code;
  }
}

function publicSession(session) {
  return {
    id: session.id,
    workspaceId: session.workspaceId,
    state: session.state,
    terminalTail: session.terminalTail,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    canInterrupt: session.state === "starting" || session.state === "active" || session.state === "working" || session.state === "waiting",
  };
}

function requiredText(value, field) {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > 12_000) {
    throw new PocketError(400, "invalid_input", `${field} must be a non-empty string up to 12,000 characters.`);
  }
  return value.trim();
}

function terminalText(value) {
  return String(value)
    .replace(/\u001B(?:[@-_]|\[[0-?]*[ -/]*[@-~])/g, "")
    .replace(/\r/g, "\n")
    .replace(/[^\x09\x0A\x20-\x7E]/g, "")
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .join("\n");
}

function appendTail(previous, next) {
  return `${previous}\n${next}`
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(-MAX_TAIL_LINES)
    .join("\n")
    .slice(-1_600);
}
