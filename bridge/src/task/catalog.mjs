import { createHash } from "node:crypto";

import { open } from "./open.mjs";
import { Activity } from "./activity.mjs";
import { Settings } from "./settings.mjs";

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

export class Catalog {
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
  #settings;
  #focusedThreadId = null;

  constructor({
    appServer,
    openThread = open,
    activityReader = new Activity(),
    cacheTtlMs = 3_000,
    now = Date.now,
  }) {
    if (!appServer) throw new TypeError("Catalog requires an app-server client.");
    this.#appServer = appServer;
    this.#openThread = openThread;
    this.#activityReader = activityReader;
    this.#settings = new Settings({ appServer });
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
      resolved.push({
        ...agent,
        id,
        state: agent.focused === true
          ? agent.state
          : (activityStates.get(thread.id) ?? agent.state),
        _threadId: thread.id,
        _recencyAt: threadRecency(thread),
        _sourceIndex: resolved.length,
      });
    }
    for (const thread of threads) {
      const label = threadLabel(thread);
      if (!label || assignedThreadIds.has(thread.id)) continue;
      assignedThreadIds.add(thread.id);
      resolved.push({
        id: agentIdForThread(thread.id),
        label,
        state: activityStates.get(thread.id) ?? "idle",
        focused: false,
        _threadId: thread.id,
        _recencyAt: threadRecency(thread),
        _sourceIndex: resolved.length,
      });
    }
    const ordered = resolved.toSorted((left, right) => {
      if (left.focused !== right.focused) return left.focused ? -1 : 1;
      const priority = (AGENT_STATE_PRIORITY.get(left.state) ?? 8)
        - (AGENT_STATE_PRIORITY.get(right.state) ?? 8);
      if (priority !== 0) return priority;
      const recency = right._recencyAt - left._recencyAt;
      if (recency !== 0) return recency;
      return left._sourceIndex - right._sourceIndex;
    });
    const actualFocus = resolved.find(({ focused }) => focused)?._threadId ?? null;
    let selected = ordered.slice(0, MAX_AGENT_COUNT);
    if (actualFocus) {
      this.#focusedThreadId = actualFocus;
      selected = selected.map((agent) => ({ ...agent, focused: agent._threadId === actualFocus }));
    } else {
      this.#focusedThreadId = null;
      selected = selected.map((agent) => ({ ...agent, focused: false }));
    }
    this.#agentThreads = new Map(selected.map((agent) => [agent.id, agent._threadId]));
    return selected.map(({ _threadId, _recencyAt, _sourceIndex, ...agent }) => agent);
  }

  async settings(visible) {
    await this.#ensureStarted();
    await this.#settings.start();
    const thread = this.#cachedThreads.find(({ id }) => id === this.#focusedThreadId) ?? null;
    return this.#settings.projection(thread, visible);
  }

  async selectModel(modelId) {
    await this.#ensureStarted();
    await this.#settings.start();
    return this.#settings.selectModel(modelId);
  }

  async validateModel(modelId) {
    await this.#ensureStarted();
    return this.#settings.modelOption(modelId, { refresh: true });
  }

  async adjustReasoning(delta) {
    await this.#ensureStarted();
    await this.#settings.start();
    return this.#settings.adjustReasoning(delta);
  }

  async selectReasoning(level) {
    await this.#ensureStarted();
    await this.#settings.start();
    return this.#settings.selectReasoning(level);
  }

  reasoningTarget(delta) {
    return this.#settings.reasoningTarget(delta);
  }

  hasReasoningLevel(level) {
    return this.#settings.hasReasoningLevel(level);
  }

  async focusAgent(agentId) {
    if (typeof agentId !== "string" || !AGENT_ID_PATTERN.test(agentId)) {
      throw new Error("A valid Codex task ID is required.");
    }
    const threadId = this.#agentThreads.get(agentId);
    if (!threadId) throw new Error("That Codex task is no longer available.");
    await this.#openThread(threadId);
    return {
      ok: true,
      agentId,
      threadId,
      message: "Opened the selected Codex task through its native task link.",
    };
  }

  async focusThread(threadId) {
    await this.#openThread(threadId);
    return {
      ok: true,
      agentId: agentIdForThread(threadId),
      threadId,
      message: "Opened the requested Codex task through its native task link.",
    };
  }

  async dispose() {
    this.#agentThreads.clear();
    this.#focusedThreadId = null;
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
  if (typeof value !== "string") return "";
  return value
    .replaceAll(/\[([^\]\n]+)\]\((?:\\.|[^)\n])+\)/g, "$1")
    .replaceAll(/\s+/g, " ")
    .trim();
}

function threadLabel(thread) {
  return [thread.agentNickname, thread.name, thread.preview]
    .map(normalizeLabel)
    .find(Boolean)
    ?.slice(0, 200) ?? "";
}

function threadRecency(thread) {
  for (const value of [thread.recencyAt, thread.updatedAt, thread.createdAt]) {
    if (Number.isFinite(value)) return value;
    if (typeof value === "string") {
      const timestamp = Date.parse(value);
      if (Number.isFinite(timestamp)) return timestamp;
    }
  }
  return 0;
}

export function agentIdForThread(threadId) {
  return `agent-${createHash("sha256").update(threadId).digest("hex").slice(0, 24)}`;
}
