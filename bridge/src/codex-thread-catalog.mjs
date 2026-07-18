import { createHash } from "node:crypto";

import { openCodexThread } from "./codex-thread-opener.mjs";
import { RolloutActivityReader } from "./rollout-activity-reader.mjs";

const MAX_THREADS = 100;
const MAX_AGENT_COUNT = 24;
const AGENT_ID_PATTERN = /^agent-[a-f0-9]{24}$/;
const ACTIVE_AGENT_STATES = new Set(["waiting", "error", "executing", "thinking", "unread"]);
const AGENT_STATE_PRIORITY = new Map([
  ["waiting", 0],
  ["error", 1],
  ["executing", 2],
  ["thinking", 3],
  ["unread", 4],
  ["complete", 6],
  ["idle", 7],
]);

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
  #activityReader;

  constructor({
    appServer,
    openThread = openCodexThread,
    activityReader = new RolloutActivityReader(),
    cacheTtlMs = 3_000,
    now = Date.now,
  }) {
    if (!appServer) throw new TypeError("CodexThreadCatalog requires an app-server client.");
    this.#appServer = appServer;
    this.#openThread = openThread;
    this.#activityReader = activityReader;
    this.#cacheTtlMs = cacheTtlMs;
    this.#now = now;
  }

  async resolveVisibleAgents(visibleAgents) {
    visibleAgents = Array.isArray(visibleAgents) ? visibleAgents : [];

    let threads = await this.#threads();
    const targetedAgents = visibleAgents.filter((agent) => {
      if (!agent || typeof agent.label !== "string") return false;
      if (uniqueMatches(agent, threads).length === 1) return false;
      return agent.focused === true || ACTIVE_AGENT_STATES.has(agent.state);
    });
    const searchTerms = [...new Set(targetedAgents.map(({ label }) => searchTermForLabel(label)).filter(Boolean))];
    if (searchTerms.length > 0) {
      const searched = await Promise.all(searchTerms.map((term) => this.#searchThreads(term)));
      threads = uniqueTopLevelThreads([...threads, ...searched.flat()]);
      this.#cachedThreads = threads;
    }
    let activityStates = new Map();
    try {
      activityStates = await this.#activityReader.statesFor(threads);
    } catch {
      activityStates = new Map();
    }
    const assignedThreadIds = new Set();
    const resolved = [];
    for (const agent of visibleAgents) {
      if (!agent || typeof agent.label !== "string") continue;
      const matches = uniqueMatches(agent, threads);
      if (matches.length !== 1 || assignedThreadIds.has(matches[0].id)) continue;
      const thread = matches[0];
      const id = agentIdForThread(thread.id);
      assignedThreadIds.add(thread.id);
      resolved.push({ ...agent, id });
    }
    for (const thread of threads) {
      const state = activityStates.get(thread.id);
      const label = threadLabel(thread);
      if (!state || !label || assignedThreadIds.has(thread.id)) continue;
      assignedThreadIds.add(thread.id);
      resolved.push({ id: agentIdForThread(thread.id), label, state, focused: false });
    }
    const ordered = resolved.toSorted((left, right) => {
      const priority = (AGENT_STATE_PRIORITY.get(left.state) ?? 8)
        - (AGENT_STATE_PRIORITY.get(right.state) ?? 8);
      if (priority !== 0) return priority;
      if (left.focused !== right.focused) return left.focused ? -1 : 1;
      return 0;
    });
    const selected = ordered.slice(0, MAX_AGENT_COUNT);
    const focused = ordered.find((agent) => agent.focused);
    if (focused && !selected.some((agent) => agent.id === focused.id)) {
      selected[MAX_AGENT_COUNT - 1] = focused;
    }
    const threadsByAgentID = new Map(threads.map((thread) => [agentIdForThread(thread.id), thread.id]));
    this.#agentThreads = new Map(selected.map((agent) => [agent.id, threadsByAgentID.get(agent.id)]));
    return selected;
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

  async #searchThreads(searchTerm) {
    const result = await this.#appServer.request("thread/list", {
      limit: 20,
      sortDirection: "desc",
      sortKey: "recency_at",
      searchTerm,
      useStateDbOnly: true,
    });
    return uniqueTopLevelThreads(result?.data);
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

function uniqueMatches(agent, threads) {
  return threads.filter((thread) => labelMatchesThread(agent.label, thread));
}

function searchTermForLabel(label) {
  const term = label.replace(/(?:\.\.\.|…)$/, "").trim();
  return term.length >= 4 ? term : null;
}

function normalizeLabel(value) {
  return typeof value === "string" ? value.replaceAll(/\s+/g, " ").trim() : "";
}

function threadLabel(thread) {
  return [thread.agentNickname, thread.name, thread.preview]
    .map(normalizeLabel)
    .find(Boolean)
    ?.slice(0, 200) ?? "";
}

function agentIdForThread(threadId) {
  return `agent-${createHash("sha256").update(threadId).digest("hex").slice(0, 24)}`;
}
