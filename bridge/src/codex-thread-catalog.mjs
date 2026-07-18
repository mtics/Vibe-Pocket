import { createHash } from "node:crypto";

import { openCodexThread } from "./codex-thread-opener.mjs";

const MAX_THREADS = 100;
const AGENT_ID_PATTERN = /^agent-[a-f0-9]{24}$/;

export class CodexThreadCatalog {
  #appServer;
  #openThread;
  #cacheTtlMs;
  #now;
  #started = false;
  #startPromise = null;
  #cachedThreads = [];
  #cacheExpiresAt = 0;
  #agentThreads = new Map();

  constructor({
    appServer,
    openThread = openCodexThread,
    cacheTtlMs = 3_000,
    now = Date.now,
  }) {
    if (!appServer) throw new TypeError("CodexThreadCatalog requires an app-server client.");
    this.#appServer = appServer;
    this.#openThread = openThread;
    this.#cacheTtlMs = cacheTtlMs;
    this.#now = now;
  }

  async resolveVisibleAgents(visibleAgents) {
    if (!Array.isArray(visibleAgents) || visibleAgents.length === 0) {
      this.#agentThreads.clear();
      return [];
    }

    const threads = await this.#threads();
    const nextAgentThreads = new Map();
    const assignedThreadIds = new Set();
    const resolved = [];
    for (const agent of visibleAgents) {
      if (!agent || typeof agent.label !== "string") continue;
      const matches = threads.filter((thread) => labelMatchesThread(agent.label, thread));
      if (matches.length !== 1 || assignedThreadIds.has(matches[0].id)) continue;
      const thread = matches[0];
      const id = agentIdForThread(thread.id);
      nextAgentThreads.set(id, thread.id);
      assignedThreadIds.add(thread.id);
      resolved.push({ ...agent, id });
    }
    this.#agentThreads = nextAgentThreads;
    return resolved;
  }

  async focusAgent(agentId) {
    if (typeof agentId !== "string" || !AGENT_ID_PATTERN.test(agentId)) {
      throw new Error("A valid Codex task ID is required.");
    }
    const threadId = this.#agentThreads.get(agentId);
    if (!threadId) throw new Error("That Codex task is no longer available.");
    await this.#openThread(threadId);
    return { ok: true, message: "Opened the selected Codex task through its native task link." };
  }

  async dispose() {
    this.#agentThreads.clear();
    this.#cachedThreads = [];
    this.#cacheExpiresAt = 0;
    if (!this.#started && !this.#startPromise) return;
    try {
      await this.#startPromise;
    } catch {
      return;
    }
    this.#started = false;
    await this.#appServer.stop();
  }

  async #threads() {
    if (this.#now() < this.#cacheExpiresAt) return this.#cachedThreads;
    await this.#ensureStarted();
    const result = await this.#appServer.request("thread/list", {
      limit: MAX_THREADS,
      sortDirection: "desc",
      sortKey: "recency_at",
    });
    this.#cachedThreads = uniqueTopLevelThreads(result?.data);
    this.#cacheExpiresAt = this.#now() + this.#cacheTtlMs;
    return this.#cachedThreads;
  }

  async #ensureStarted() {
    if (this.#started) return;
    if (!this.#startPromise) {
      this.#startPromise = this.#appServer.start()
        .then(() => { this.#started = true; })
        .finally(() => { this.#startPromise = null; });
    }
    await this.#startPromise;
  }
}

function uniqueTopLevelThreads(value) {
  if (!Array.isArray(value)) return [];
  const threads = value.filter((thread) => (
    thread
    && typeof thread.id === "string"
    && !thread.parentThreadId
  ));
  return [...new Map(threads.map((thread) => [thread.id, thread])).values()];
}

function labelMatchesThread(visibleLabel, thread) {
  const visible = normalizeLabel(visibleLabel);
  if (!visible) return false;
  const aliases = [thread.agentNickname, thread.name, thread.preview]
    .map(normalizeLabel)
    .filter(Boolean);
  if (aliases.includes(visible)) return true;
  const prefix = visible.replace(/(?:\.\.\.|…)$/, "").trim();
  return prefix.length >= 8 && prefix.length < visible.length
    && aliases.some((alias) => alias.startsWith(prefix));
}

function normalizeLabel(value) {
  return typeof value === "string" ? value.replaceAll(/\s+/g, " ").trim() : "";
}

function agentIdForThread(threadId) {
  return `agent-${createHash("sha256").update(threadId).digest("hex").slice(0, 24)}`;
}
