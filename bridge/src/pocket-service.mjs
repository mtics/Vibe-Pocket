import { createHash, randomUUID, timingSafeEqual } from "node:crypto";
import { EventEmitter } from "node:events";
import { isAbsolute, relative } from "node:path";

const COMMAND_APPROVAL = "item/commandExecution/requestApproval";
const FILE_APPROVAL = "item/fileChange/requestApproval";
const PERMISSIONS_APPROVAL = "item/permissions/requestApproval";
const SNAPSHOT_CACHE_MS = 15_000;

export class PocketService extends EventEmitter {
  #appServer;
  #workspaces;
  #events;
  #revision = 0;
  #focusThreadId = null;
  #activeTurns = new Map();
  #approvals = new Map();
  #idempotency = new Map();
  #status = { state: "starting", message: null };
  #cachedThreads = null;
  #cacheExpiresAt = 0;
  #threadListPromise = null;
  #visibleThreadIds = new Set();

  constructor({ appServer, workspaces, events }) {
    super();
    this.#appServer = appServer;
    this.#workspaces = workspaces;
    this.#events = events;
    this.#appServer.on("notification", (message) => this.#onNotification(message));
    this.#appServer.on("serverRequest", (message) => this.#onServerRequest(message));
    this.#appServer.on("exit", () => this.#degrade("Codex app-server stopped."));
    this.#appServer.on("protocolError", () => this.#degrade("Codex protocol changed or emitted invalid data."));
  }

  async start() {
    try {
      await this.#appServer.start();
      this.#status = { state: "ready", message: null };
      this.#touch("bridge_ready");
    } catch (error) {
      this.#degrade(error.message);
      throw error;
    }
  }

  async snapshot() {
    const threads = await this.#listThreads();
    if (this.#focusThreadId && !this.#visibleThreadIds.has(this.#focusThreadId)) {
      this.#focusThreadId = null;
    }
    return {
      revision: `r_${this.#revision}`,
      status: this.#status,
      focusThreadId: this.#focusThreadId,
      workspaces: Object.keys(this.#workspaces),
      threads,
    };
  }

  async #listThreads() {
    if (this.#cachedThreads && Date.now() < this.#cacheExpiresAt) {
      return this.#cachedThreads;
    }
    if (!this.#threadListPromise) {
      this.#threadListPromise = this.#appServer.request("thread/list", {
        limit: 24,
        sortDirection: "desc",
        sortKey: "recency_at",
        sourceKinds: ["cli", "appServer"],
      })
        .then((result) => {
          const allowedThreads = (result.data ?? []).filter((thread) => this.#isApprovedWorkspace(thread.cwd));
          this.#visibleThreadIds = new Set(allowedThreads.map((thread) => thread.id));
          this.#cachedThreads = allowedThreads.map((thread) => this.#toPocketThread(thread));
          this.#cacheExpiresAt = Date.now() + SNAPSHOT_CACHE_MS;
          return this.#cachedThreads;
        })
        .finally(() => {
          this.#threadListPromise = null;
        });
    }
    return this.#threadListPromise;
  }

  async command(command, idempotencyKey) {
    if (!idempotencyKey || idempotencyKey.length > 160) {
      throw new PocketError(400, "idempotency_key_required", "Every command needs an Idempotency-Key header.");
    }

    const existing = this.#idempotency.get(idempotencyKey);
    if (existing) {
      return existing;
    }
    const execution = this.#executeCommand(command)
      .then(() => this.#acknowledgement())
      .catch((error) => {
        this.#idempotency.delete(idempotencyKey);
        throw error;
      });
    this.#idempotency.set(idempotencyKey, execution);
    return execution;
  }

  async #executeCommand(command) {
    if (!command || typeof command !== "object" || Array.isArray(command)) {
      throw new PocketError(400, "invalid_command", "Command must be a JSON object.");
    }

    switch (command.kind) {
      case "start":
        await this.#start(command);
        return;
      case "message":
        await this.#message(command);
        return;
      case "interrupt":
        await this.#interrupt(command);
        return;
      case "focus":
        await this.#focus(command);
        return;
      case "resolve_approval":
        this.#resolveApproval(command);
        return;
      default:
        throw new PocketError(400, "unsupported_command", "This Vibe Pocket command is not supported.");
    }
  }

  async #start(command) {
    const workspace = this.#workspace(command.workspaceId);
    const prompt = requiredText(command.prompt, "prompt");
    const started = await this.#appServer.request("thread/start", { cwd: workspace });
    const threadId = started.thread.id;
    const turn = await this.#appServer.request("turn/start", textTurn(threadId, prompt));
    this.#activeTurns.set(threadId, turn.turn.id);
    this.#focusThreadId = threadId;
    this.#invalidateThreadCache();
    this.#touch("run_started", { threadId });
  }

  async #message(command) {
    const threadId = requiredText(command.threadId, "threadId");
    const prompt = requiredText(command.prompt, "prompt");
    await this.#requireVisibleThread(threadId);
    await this.#appServer.request("thread/resume", { threadId, excludeTurns: true });
    const activeTurnId = this.#activeTurns.get(threadId);
    const turn = activeTurnId
      ? await this.#appServer.request("turn/steer", {
          expectedTurnId: activeTurnId,
          input: [{ type: "text", text: prompt }],
          threadId,
        })
      : await this.#appServer.request("turn/start", textTurn(threadId, prompt));
    this.#activeTurns.set(threadId, turn.turn.id);
    this.#focusThreadId = threadId;
    this.#invalidateThreadCache();
    this.#touch("run_steered", { threadId });
  }

  async #interrupt(command) {
    const threadId = requiredText(command.threadId, "threadId");
    const turnId = this.#activeTurns.get(threadId);
    if (!turnId) {
      throw new PocketError(409, "no_active_turn", "This run cannot be interrupted from Vibe Pocket.");
    }
    await this.#appServer.request("turn/interrupt", { threadId, turnId });
    this.#activeTurns.delete(threadId);
    this.#invalidateThreadCache();
    this.#touch("run_interrupted", { threadId });
  }

  async #focus(command) {
    const threadId = requiredText(command.threadId, "threadId");
    await this.#requireVisibleThread(threadId);
    this.#focusThreadId = threadId;
    this.#touch("focus_changed", { threadId });
  }

  #resolveApproval(command) {
    const approvalId = requiredText(command.approvalId, "approvalId");
    const intentHash = requiredText(command.intentHash, "intentHash");
    const pending = this.#approvals.get(approvalId);
    if (!pending) {
      throw new PocketError(409, "approval_already_resolved", "This approval is no longer pending.");
    }
    if (!safeEqual(intentHash, pending.intentHash)) {
      throw new PocketError(409, "approval_changed", "The approval changed; refresh before deciding.");
    }
    if (command.decision !== "allow" && command.decision !== "deny") {
      throw new PocketError(400, "invalid_decision", "Only allow-once or deny is permitted.");
    }
    this.#appServer.respond(pending.requestId, {
      decision: command.decision === "allow" ? "accept" : "decline",
    });
    this.#approvals.delete(approvalId);
    this.#touch("approval_resolved", { approvalId, threadId: pending.threadId });
  }

  #onServerRequest(message) {
    if (message.method === PERMISSIONS_APPROVAL) {
      this.#appServer.respondWithError(
        message.id,
        -32001,
        "Permission profile changes must be reviewed on the M5.",
      );
      this.#touch("policy_blocked", { reason: "Permission escalation requires the M5." });
      return;
    }
    if (message.method !== COMMAND_APPROVAL && message.method !== FILE_APPROVAL) {
      this.#appServer.respondWithError(message.id, -32601, "Unsupported Vibe Pocket server request.");
      return;
    }

    const approval = normalizeApproval(message);
    this.#approvals.set(approval.id, approval);
    this.#touch("approval_requested", {
      approval: publicApproval(approval),
      threadId: approval.threadId,
    });
  }

  #onNotification(message) {
    const params = message.params ?? {};
    const threadId = params.threadId ?? params.thread?.id;
    if (message.method.includes("turn/completed") || message.method.includes("turn/failed")) {
      if (threadId) this.#activeTurns.delete(threadId);
    }
    this.#touch("snapshot_changed", { method: message.method, threadId: threadId ?? null });
  }

  #toPocketThread(thread) {
    const approval = [...this.#approvals.values()].find((item) => item.threadId === thread.id);
    const active = this.#activeTurns.has(thread.id) || thread.status?.type === "active";
    return {
      id: thread.id,
      title: truncate(thread.name || thread.preview || "Untitled task", 96),
      state: approval ? "needs_approval" : active ? "working" : thread.status?.type === "systemError" ? "failed" : "idle",
      updatedAt: new Date((thread.updatedAt ?? 0) * 1000).toISOString(),
      preview: truncate(thread.preview || "No prompt yet."),
      canInterrupt: this.#activeTurns.has(thread.id),
      approval: approval ? publicApproval(approval) : null,
    };
  }

  #workspace(alias) {
    if (!alias || typeof alias !== "string" || !this.#workspaces[alias]) {
      throw new PocketError(400, "unknown_workspace", "Choose a workspace configured on the M5.");
    }
    return this.#workspaces[alias];
  }

  async #requireVisibleThread(threadId) {
    if (!this.#visibleThreadIds.has(threadId)) {
      this.#invalidateThreadCache();
      await this.#listThreads();
    }
    if (!this.#visibleThreadIds.has(threadId)) {
      throw new PocketError(403, "thread_not_allowed", "Choose a task in a workspace configured on the M5.");
    }
  }

  #isApprovedWorkspace(cwd) {
    if (typeof cwd !== "string" || cwd.length === 0) return false;
    return Object.values(this.#workspaces).some((workspace) => {
      const pathFromWorkspace = relative(workspace, cwd);
      return pathFromWorkspace === "" || (!pathFromWorkspace.startsWith("..") && !isAbsolute(pathFromWorkspace));
    });
  }

  #acknowledgement() {
    return {
      commandId: `cmd_${randomUUID()}`,
      accepted: true,
      revision: `r_${this.#revision}`,
    };
  }

  #degrade(message) {
    this.#status = { state: "degraded", message: truncate(message) };
    this.#touch("bridge_degraded", { message: this.#status.message });
  }

  #invalidateThreadCache() {
    this.#cachedThreads = null;
    this.#cacheExpiresAt = 0;
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

function textTurn(threadId, prompt) {
  return { input: [{ type: "text", text: prompt }], threadId };
}

function normalizeApproval(message) {
  const params = message.params ?? {};
  const kind = message.method === COMMAND_APPROVAL ? "command" : "file_change";
  const summary = kind === "command"
    ? truncate(params.command ?? params.reason ?? "Review a command execution request.", 420)
    : truncate(params.reason ?? "Review a file change request.", 420);
  const threadId = params.threadId ?? "unknown";
  const turnId = params.turnId ?? "unknown";
  const requestId = message.id;
  const intentHash = createHash("sha256")
    .update(JSON.stringify({ kind, summary, threadId, turnId }))
    .digest("hex");
  return {
    id: `ap_${randomUUID()}`,
    intentHash,
    kind,
    requestId,
    summary,
    threadId,
    turnId,
  };
}

function publicApproval(approval) {
  return {
    id: approval.id,
    intentHash: approval.intentHash,
    kind: approval.kind,
    summary: approval.summary,
    choices: ["allow", "deny"],
  };
}

function requiredText(value, field) {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > 12_000) {
    throw new PocketError(400, "invalid_input", `${field} must be a non-empty string up to 12,000 characters.`);
  }
  return value.trim();
}

function safeEqual(left, right) {
  const a = Buffer.from(left);
  const b = Buffer.from(right);
  return a.length === b.length && timingSafeEqual(a, b);
}

function truncate(value, length = 280) {
  const text = String(value).replaceAll(/\s+/g, " ").trim();
  return text.length > length ? `${text.slice(0, length - 1)}…` : text;
}
