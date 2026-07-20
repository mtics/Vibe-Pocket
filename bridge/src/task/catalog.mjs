import { createHash } from "node:crypto";
import { realpathSync, statSync } from "node:fs";
import { isAbsolute, relative, sep } from "node:path";

import { open } from "./open.mjs";
import { Activity } from "./activity.mjs";
import { Settings } from "./settings.mjs";

const MAX_THREADS = 100;
const MAX_AGENT_COUNT = 24;
const MAX_AGENT_LABEL_LENGTH = 64;
const DEFAULT_FALLBACK_TTL_MS = 1_000;
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
  #fallbackTtlMs;
  #now;
  #workspaces;
  #started = false;
  #startPromise = null;
  #cachedThreads = [];
  #cacheExpiresAt = 0;
  #agentThreads = new Map();
  #activityReader;
  #settings;
  #focusedThreadId = null;
  #lastSuccessfulAgents = [];
  #failureStartedAt = null;
  #freshness = { state: "unavailable", checkedAt: null, expiresAt: null };

  constructor({
    appServer,
    openThread = open,
    activityReader = new Activity(),
    cacheTtlMs = 3_000,
    fallbackTtlMs = DEFAULT_FALLBACK_TTL_MS,
    now = Date.now,
    workspaces = { default: process.cwd() },
  }) {
    if (!appServer) throw new TypeError("Catalog requires an app-server client.");
    this.#appServer = appServer;
    this.#openThread = openThread;
    this.#activityReader = activityReader;
    this.#settings = new Settings({ appServer });
    this.#cacheTtlMs = cacheTtlMs;
    this.#fallbackTtlMs = fallbackTtlMs;
    this.#now = now;
    this.#workspaces = canonicalWorkspaces(workspaces);
  }

  get freshness() {
    return { ...this.#freshness };
  }

  async resolveVisibleAgents(visibleAgents) {
    try {
      return await this.#resolveVisibleAgents(visibleAgents);
    } catch (error) {
      return this.#fallbackAgents(error);
    }
  }

  async #resolveVisibleAgents(visibleAgents) {
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
      const thread = resolvedMatch(agent, threads);
      if (!thread || assignedThreadIds.has(thread.id)) continue;
      const id = agentIdForThread(thread.id);
      assignedThreadIds.add(thread.id);
      resolved.push({
        ...agent,
        id,
        state: agent.focused === true
          ? agent.state
          : (activityStates.get(thread.id) ?? agent.state),
        _threadId: thread.id,
        _workspaceAlias: thread._catalogWorkspaceAlias,
        _labelKey: normalizeLabel(threadLabel(thread)),
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
        _workspaceAlias: thread._catalogWorkspaceAlias,
        _labelKey: normalizeLabel(label),
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
    selected = disambiguateLabels(selected);
    const selectedThreads = new Map(threads.map((thread) => [thread.id, thread]));
    this.#agentThreads = new Map(selected.map((agent) => [agent.id, selectedThreads.get(agent._threadId)]));
    const agents = selected.map(({ _threadId, _workspaceAlias, _labelKey, _recencyAt, _sourceIndex, ...agent }) => ({
      ...agent,
      freshness: "fresh",
      actionable: true,
    }));
    const checkedAt = this.#now();
    this.#lastSuccessfulAgents = agents;
    this.#failureStartedAt = null;
    this.#freshness = {
      state: "fresh",
      checkedAt,
      expiresAt: this.#cacheExpiresAt,
    };
    return agents;
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
    const candidate = this.#agentThreads.get(agentId);
    if (!candidate || this.#freshness.state !== "fresh") {
      throw new Error("That Codex task is no longer available.");
    }
    const thread = await this.#threadForOpen(candidate.id);
    await this.#openThread(thread.id);
    return {
      ok: true,
      agentId,
      threadId: thread.id,
      message: "Opened the selected Codex task through its native task link.",
    };
  }

  async focusThread(threadId) {
    const thread = await this.#threadForOpen(threadId);
    await this.#openThread(thread.id);
    return {
      ok: true,
      agentId: agentIdForThread(thread.id),
      threadId: thread.id,
      message: "Opened the requested Codex task through its native task link.",
    };
  }

  async dispose() {
    this.#agentThreads.clear();
    this.#focusedThreadId = null;
    this.#cachedThreads = [];
    this.#cacheExpiresAt = 0;
    this.#lastSuccessfulAgents = [];
    this.#failureStartedAt = null;
    this.#freshness = { state: "unavailable", checkedAt: null, expiresAt: null };
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
    this.#cachedThreads = this.#scopedThreads(result?.data);
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
    return this.#scopedThreads(result?.data);
  }

  async #threadForOpen(threadId) {
    try {
      await this.#ensureStarted();
      const result = await this.#appServer.request("thread/read", {
        threadId,
        includeTurns: false,
      });
      const thread = result?.thread;
      if (!thread || thread.id !== threadId || thread.parentThreadId) {
        throw new Error("That Codex task is no longer available.");
      }
      const scoped = this.#scopeThread(thread);
      if (!scoped) {
        throw new Error("That Codex task is outside the configured Vibe Pocket workspaces.");
      }
      return scoped;
    } catch (error) {
      this.#markFailure(error);
      throw error;
    }
  }

  #scopedThreads(value) {
    return uniqueTopLevelThreads(value)
      .map((thread) => this.#scopeThread(thread))
      .filter(Boolean);
  }

  #scopeThread(thread) {
    const workspace = workspaceForPath(this.#workspaces, thread?.cwd);
    if (!workspace) return null;
    return {
      ...thread,
      _catalogCanonicalCwd: workspace.canonicalPath,
      _catalogWorkspaceAlias: workspace.alias,
    };
  }

  #fallbackAgents(error) {
    const timestamp = this.#markFailure(error);
    const expiresAt = this.#failureStartedAt + this.#fallbackTtlMs;
    if (this.#lastSuccessfulAgents.length === 0 || timestamp >= expiresAt) {
      this.#lastSuccessfulAgents = [];
      this.#freshness = { state: "expired", checkedAt: timestamp, expiresAt };
      return [];
    }
    this.#freshness = { state: "stale", checkedAt: timestamp, expiresAt };
    return this.#lastSuccessfulAgents.map((agent) => ({
      ...agent,
      focused: false,
      freshness: "stale",
      actionable: false,
    }));
  }

  #markFailure(error) {
    const timestamp = this.#now();
    this.#failureStartedAt ??= timestamp;
    this.#agentThreads.clear();
    this.#focusedThreadId = null;
    this.#cacheExpiresAt = 0;
    this.#freshness = {
      state: "stale",
      checkedAt: timestamp,
      expiresAt: this.#failureStartedAt + this.#fallbackTtlMs,
      error: error?.message ?? "The Codex task catalog is unavailable.",
    };
    return timestamp;
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

function resolvedMatch(agent, threads) {
  const matches = uniqueMatches(agent, threads);
  if (matches.length === 1) return matches[0];
  return null;
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

function canonicalWorkspaces(workspaces) {
  if (!workspaces || Array.isArray(workspaces) || typeof workspaces !== "object") {
    throw new TypeError("Catalog workspaces must be an alias-to-path object.");
  }
  const entries = Object.entries(workspaces);
  if (entries.length === 0) throw new TypeError("Catalog requires at least one workspace.");
  return entries.map(([alias, path]) => {
    if (typeof path !== "string" || path.length === 0) {
      throw new TypeError("Catalog workspace paths must be non-empty strings.");
    }
    let canonicalPath;
    let identity;
    try {
      canonicalPath = realpathSync(path);
      identity = statSync(canonicalPath);
    } catch (error) {
      throw new Error(`Catalog workspace could not be resolved safely: ${path}`, { cause: error });
    }
    if (!identity.isDirectory()) throw new Error(`Catalog workspace is not a directory: ${path}`);
    return { alias, canonicalPath, dev: identity.dev, ino: identity.ino };
  });
}

function workspaceForPath(workspaces, path) {
  if (typeof path !== "string" || path.length === 0) return null;
  let canonicalPath;
  try {
    canonicalPath = realpathSync(path);
    if (!statSync(canonicalPath).isDirectory()) return null;
  } catch {
    return null;
  }
  return workspaces
    .filter((workspace) => {
      try {
        const current = statSync(workspace.canonicalPath);
        return current.isDirectory()
          && current.dev === workspace.dev
          && current.ino === workspace.ino
          && isWithin(workspace.canonicalPath, canonicalPath);
      } catch {
        return false;
      }
    })
    .toSorted((left, right) => right.canonicalPath.length - left.canonicalPath.length)
    .map((workspace) => ({ ...workspace, canonicalPath }))
    .at(0) ?? null;
}

function isWithin(root, destination) {
  const path = relative(root, destination);
  return path === "" || (
    !isAbsolute(path)
    && path !== ".."
    && !path.startsWith(`..${sep}`)
  );
}

function disambiguateLabels(agents) {
  const counts = new Map();
  for (const agent of agents) {
    const key = agent._labelKey || normalizeLabel(agent.label);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return agents.map((agent) => {
    const key = agent._labelKey || normalizeLabel(agent.label);
    if ((counts.get(key) ?? 0) < 2) return agent;
    const suffix = ` [${agent._workspaceAlias}:${shortThreadKey(agent._threadId)}]`;
    const available = Math.max(1, MAX_AGENT_LABEL_LENGTH - suffix.length);
    const label = agent.label.length > available
      ? `${agent.label.slice(0, Math.max(1, available - 3)).trimEnd()}...`
      : agent.label;
    return { ...agent, label: `${label}${suffix}` };
  });
}

function shortThreadKey(threadId) {
  return createHash("sha256").update(threadId).digest("hex").slice(0, 6);
}

export function agentIdForThread(threadId) {
  return `agent-${createHash("sha256").update(threadId).digest("hex").slice(0, 24)}`;
}
