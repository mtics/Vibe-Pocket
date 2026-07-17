import { randomUUID } from "node:crypto";
import { EventEmitter } from "node:events";

import { MacCodexDesktopController } from "./macos-codex-desktop.mjs";
import { PocketError } from "./pocket-controller-service.mjs";

const DESKTOP_SESSION_ID = "desktop-codex";

export class DesktopCodexService extends EventEmitter {
  #workspaces;
  #events;
  #desktop;
  #idempotency = new Map();
  #revision = 0;
  #status = { state: "starting", message: null, controls: emptyControls() };
  #session = null;
  #commandQueue = Promise.resolve();

  constructor({ workspaces, events, desktop = new MacCodexDesktopController() }) {
    super();
    this.#workspaces = workspaces;
    this.#events = events;
    this.#desktop = desktop;
    this.#session = {
      id: DESKTOP_SESSION_ID,
      workspaceId: "current desktop task",
      state: "starting",
      terminalTail: "Checking the visible ChatGPT Codex task...",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      canInterrupt: false,
    };
  }

  async start() {
    await this.#refreshAvailability();
    this.#touch("snapshot_changed");
  }

  async dispose() {}

  async snapshot() {
    return {
      revision: `r_${this.#revision}`,
      status: this.#status,
      focusSessionId: this.#session.state === "error" ? null : DESKTOP_SESSION_ID,
      workspaces: Object.keys(this.#workspaces),
      controls: this.#status.controls,
      sessions: [this.#publicSession()],
    };
  }

  async command(command, idempotencyKey) {
    if (!idempotencyKey || idempotencyKey.length > 160) {
      throw new PocketError(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }
    const existing = this.#idempotency.get(idempotencyKey);
    if (existing) return existing;

    const execution = this.#commandQueue
      .then(() => this.#execute(command))
      .then(() => ({ commandId: `cmd_${randomUUID()}`, accepted: true, revision: `r_${this.#revision}` }))
      .catch((error) => {
        this.#idempotency.delete(idempotencyKey);
        throw error;
      });
    this.#commandQueue = execution.catch(() => {});
    this.#idempotency.set(idempotencyKey, execution);
    return execution;
  }

  async #execute(command) {
    if (!command || typeof command !== "object" || Array.isArray(command)) {
      throw new PocketError(400, "invalid_command", "Controller action must be a JSON object.");
    }

    try {
      switch (command.kind) {
        case "start":
        case "attach":
          await this.#desktop.attach();
          this.#recordAction("Attached to the visible ChatGPT Codex task.");
          return;
        case "voice":
          await this.#press("voice", "Enabled Codex dictation on the M5.");
          return;
        case "stop":
        case "interrupt":
          await this.#press("stop", "Pressed Stop in the visible ChatGPT Codex task.");
          return;
        case "accept":
        case "approve":
          await this.#press("approve", "Approved the visible ChatGPT Codex request.");
          return;
        case "reject":
          await this.#press("reject", "Rejected the visible ChatGPT Codex request.");
          return;
        case "new_task":
        case "new_chat":
          await this.#press("new-task", "Created a new visible ChatGPT Codex task.");
          return;
        case "focus":
          if (command.sessionId !== DESKTOP_SESSION_ID) {
            throw new PocketError(404, "unknown_session", "Vibe Pocket is attached only to the current desktop Codex task.");
          }
          await this.#desktop.attach();
          this.#recordAction("Attached to the visible ChatGPT Codex task.");
          return;
        case "focus_next":
          await this.#desktop.attach();
          this.#recordAction("Vibe Pocket controls the one visible ChatGPT Codex task.");
          return;
        default:
          throw new PocketError(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
      }
    } catch (error) {
      if (error instanceof PocketError) throw error;
      const message = error.message || "The visible Codex task did not accept this action.";
      try {
        const availability = await this.#desktop.status();
        if (availability.available !== false) {
          this.#status = { state: "ready", message: availability.message ?? message };
          this.#session.state = "active";
          this.#session.canInterrupt = true;
          this.#session.terminalTail = availability.message ?? message;
        } else {
          throw new Error(availability.message || message);
        }
      } catch {
        this.#status = { state: "degraded", message };
        this.#session.state = "error";
        this.#session.canInterrupt = false;
        this.#session.terminalTail = message;
      }
      this.#session.updatedAt = new Date().toISOString();
      this.#touch("snapshot_changed");
      throw new PocketError(409, "desktop_action_failed", message);
    }
  }

  async #refreshAvailability() {
    try {
      const result = await this.#desktop.status();
      this.#status = {
        state: "ready",
        message: result.message ?? null,
        controls: normalizeControls(result.controls),
      };
      this.#session.state = "active";
      this.#session.canInterrupt = this.#status.controls.stop;
      this.#session.terminalTail = result.message ?? "Ready to control the visible ChatGPT Codex task.";
    } catch (error) {
      this.#status = {
        state: "degraded",
        message: error.message || "Desktop Codex is unavailable.",
        controls: emptyControls(),
      };
      this.#session.state = "error";
      this.#session.canInterrupt = false;
      this.#session.terminalTail = this.#status.message;
    }
    this.#session.updatedAt = new Date().toISOString();
  }

  #recordAction(message) {
    this.#status = { ...this.#status, state: "ready", message };
    this.#session.state = "active";
    this.#session.canInterrupt = this.#status.controls.stop;
    this.#session.terminalTail = message;
    this.#session.updatedAt = new Date().toISOString();
    this.#touch("snapshot_changed");
  }

  #publicSession() {
    return { ...this.#session };
  }

  async #press(control, fallbackMessage) {
    const result = await this.#desktop.press(control);
    await this.#refreshAvailability();
    this.#recordAction(result?.message ?? fallbackMessage);
  }

  #touch(eventType, data = {}) {
    this.#revision += 1;
    this.#events.publish(eventType, { revision: `r_${this.#revision}`, ...data });
  }
}

function emptyControls() {
  return { voice: false, stop: false, "new-task": false, approve: false, reject: false };
}

function normalizeControls(value) {
  const defaults = emptyControls();
  if (!value || typeof value !== "object" || Array.isArray(value)) return defaults;
  return Object.fromEntries(Object.keys(defaults).map((key) => [key, value[key] === true]));
}
