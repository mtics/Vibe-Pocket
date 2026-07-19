import { createHash } from "node:crypto";
import { isAbsolute, relative } from "node:path";

const SOURCES = [
  "subAgent",
  "subAgentReview",
  "subAgentCompact",
  "subAgentThreadSpawn",
  "subAgentOther",
];
const LIMIT = 6;
const ID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

export class Tasks {
  #rpc;
  #workspaces;
  #ownership;
  #open;
  #settings;
  #read;
  #threads = [];
  #slots = [];
  #roots = new Map();
  #ownedRoots = new Set();
  #ownedSessions = new Set();
  #loaded = new Set();
  #statuses = new Map();
  #agents = new Map();
  #focus = null;

  constructor({ rpc, workspaces, ownership, open, settings, read }) {
    this.#rpc = rpc;
    this.#workspaces = workspaces;
    this.#ownership = ownership;
    this.#open = open;
    this.#settings = settings;
    this.#read = read;
  }

  get focus() {
    return this.#focus;
  }

  async start() {
    for (const threadId of await this.#ownership?.load?.() ?? []) this.#ownedRoots.add(threadId);
    await this.#hydrate();
  }

  async refresh() {
    await this.#hydrate();
    const allRoots = [...this.#roots.values()]
      .filter((thread) => this.allows(thread.cwd))
      .sort((left, right) => recency(right) - recency(left));
    const roots = allRoots.slice(0, LIMIT);
    this.#ownedSessions = new Set(roots.map((thread) => thread.sessionId).filter(Boolean));

    const results = await Promise.all(roots.map((thread) => this.#rpc.request("thread/list", {
      ancestorThreadId: thread.id,
      limit: 48,
      sortDirection: "desc",
      sortKey: "recency_at",
      sourceKinds: SOURCES,
      useStateDbOnly: true,
    })));
    const descendants = results.flatMap((result) => result.data ?? [])
      .filter((thread) => this.#ownedSessions.has(thread.sessionId) && this.allows(thread.cwd))
      .sort((left, right) => recency(right) - recency(left));
    const candidates = unique([...allRoots, ...descendants]);
    const byId = new Map(candidates.map((thread) => [thread.id, thread]));
    const retained = this.#slots.filter((threadId) => byId.has(threadId));
    const retainedIds = new Set(retained);
    for (const thread of candidates) {
      if (retained.length >= LIMIT) break;
      if (!retainedIds.has(thread.id)) {
        retained.push(thread.id);
        retainedIds.add(thread.id);
      }
    }
    this.#slots = retained;
    this.#threads = retained.map((threadId) => byId.get(threadId));
    for (const thread of this.#threads) {
      if (!this.#statuses.has(thread.id)) this.#statuses.set(thread.id, thread.status);
    }
    if (!this.#threads.some((thread) => thread.id === this.#focus)) {
      this.#focus = this.#threads[0]?.id ?? null;
    }
  }

  async attach() {
    await this.refresh();
    const threadId = await this.ensureFocus();
    await this.ensureLoaded(threadId);
    await this.show(threadId);
    return "Focused the selected Vibe Pocket Codex task.";
  }

  async bind(threadId) {
    if (typeof threadId !== "string" || !ID.test(threadId)) {
      throw new Error("A valid Codex desktop task ID is required.");
    }
    const result = await this.#rpc.request("thread/read", { threadId, includeTurns: false });
    const thread = result?.thread;
    if (!thread || thread.id !== threadId) throw new Error("The requested Codex desktop task does not exist.");
    if (thread.parentThreadId) throw new Error("Only a top-level Codex desktop task can be attached.");
    if (!this.allows(thread.cwd)) {
      throw new Error("The requested Codex desktop task is outside the configured Vibe Pocket workspaces.");
    }

    this.#ownedRoots.add(threadId);
    this.#roots.set(threadId, thread);
    await this.#ownership?.add?.(threadId);
    this.#assign(threadId, { replaceLast: true });
    this.receive(thread);
    await this.select(threadId);
    await this.show(threadId);
    return `Attached the current Codex desktop task: ${label(thread)}.`;
  }

  async ensureFocus() {
    if (this.#focus) return this.#focus;
    return this.create();
  }

  async create() {
    const cwd = Object.values(this.#workspaces)[0];
    const { request, fallback } = this.#settings.creation(cwd);
    const result = await this.#rpc.request("thread/start", request);
    this.#ownedRoots.add(result.thread.id);
    this.#roots.set(result.thread.id, result.thread);
    await this.#ownership?.add?.(result.thread.id);
    this.#assign(result.thread.id, { replaceLast: true });
    this.receive(result.thread);
    this.#loaded.add(result.thread.id);
    this.#settings.created(result.thread.id, result, fallback);
    await this.select(result.thread.id, { resume: false });
    return result.thread.id;
  }

  async ensureLoaded(threadId) {
    if (this.#loaded.has(threadId)) return;
    const result = await this.#rpc.request("thread/resume", { threadId });
    this.#loaded.add(threadId);
    if (result.thread) this.receive(result.thread);
    this.#settings.resumed(threadId, result);
  }

  async select(threadId, { resume = true, read = true } = {}) {
    if (!threadId) throw new Error("No Vibe Pocket Codex task is available.");
    if (resume) await this.ensureLoaded(threadId);
    this.#focus = threadId;
    if (read) this.#read(threadId);
  }

  async show(threadId) {
    await this.#open(threadId);
  }

  agents(state) {
    this.#agents.clear();
    return this.#threads.map((thread) => {
      const id = agentId(thread.id);
      this.#agents.set(id, thread.id);
      return {
        id,
        label: label(thread),
        state: state(thread.id),
        focused: thread.id === this.#focus,
      };
    });
  }

  async focusAgent(id, state) {
    await this.refresh();
    this.agents(state);
    const threadId = this.#agents.get(id);
    if (!threadId) throw new Error("That Codex task is no longer available.");
    await this.select(threadId);
    await this.show(threadId);
    return `Focused Codex task ${label(this.thread(threadId))}.`;
  }

  async navigate(direction, state) {
    const agents = this.agents(state);
    if (agents.length === 0) throw new Error("There are no Vibe Pocket Codex tasks to navigate.");
    const current = agents.findIndex((agent) => agent.focused);
    const delta = direction === "up" || direction === "left" ? -1 : 1;
    const next = (Math.max(current, 0) + delta + agents.length) % agents.length;
    await this.select(this.#agents.get(agents[next].id));
    await this.show(this.#focus);
    return `Focused Codex task ${agents[next].label}.`;
  }

  receive(thread) {
    if (!thread || !this.allows(thread.cwd)) return;
    if (thread.sessionId) this.#ownedSessions.add(thread.sessionId);
    if (!thread.parentThreadId && this.#ownedRoots.has(thread.id)) this.#roots.set(thread.id, thread);
    this.#assign(thread.id);
    const byId = new Map(this.#threads.map((candidate) => [candidate.id, candidate]));
    byId.set(thread.id, thread);
    this.#threads = this.#slots.map((threadId) => byId.get(threadId)).filter(Boolean);
    if (thread.status) this.#statuses.set(thread.id, thread.status);
  }

  receiveStarted(thread) {
    if (this.#ownedRoots.has(thread.id) || this.#ownedSessions.has(thread.sessionId)) this.receive(thread);
  }

  updateStatus(threadId, status) {
    this.#statuses.set(threadId, status);
  }

  status(threadId) {
    return this.#statuses.get(threadId) ?? this.thread(threadId)?.status;
  }

  thread(threadId) {
    return this.#threads.find((thread) => thread.id === threadId) ?? this.#roots.get(threadId) ?? null;
  }

  allows(cwd) {
    if (typeof cwd !== "string" || cwd.length === 0) return false;
    return Object.values(this.#workspaces).some((workspace) => {
      const path = relative(workspace, cwd);
      return path === "" || (!path.startsWith("..") && !isAbsolute(path));
    });
  }

  authorized(threadId) {
    return this.#ownedRoots.has(threadId) || this.#threads.some((thread) => thread.id === threadId);
  }

  #assign(threadId, { replaceLast = false } = {}) {
    if (this.#slots.includes(threadId)) return;
    if (this.#slots.length < LIMIT) this.#slots.push(threadId);
    else if (replaceLast) this.#slots[LIMIT - 1] = threadId;
  }

  async #hydrate() {
    const missing = [...this.#ownedRoots].filter((threadId) => !this.#roots.has(threadId));
    if (missing.length === 0) return;
    const results = await Promise.all(missing.map(async (threadId) => {
      try {
        return await this.#rpc.request("thread/read", { threadId, includeTurns: false });
      } catch {
        return null;
      }
    }));
    const invalid = [];
    results.forEach((result, index) => {
      const threadId = missing[index];
      if (result?.thread && this.allows(result.thread.cwd)) {
        this.#roots.set(threadId, result.thread);
        this.receive(result.thread);
      } else {
        invalid.push(threadId);
        this.#ownedRoots.delete(threadId);
      }
    });
    if (invalid.length > 0) await this.#ownership?.replace?.([...this.#ownedRoots]);
  }
}

function unique(threads) {
  return [...new Map(threads.map((thread) => [thread.id, thread])).values()];
}

function agentId(threadId) {
  return `agent-${createHash("sha256").update(threadId).digest("hex").slice(0, 24)}`;
}

function label(thread) {
  const value = String(thread?.agentNickname || thread?.name || thread?.preview || "Untitled Codex task")
    .replaceAll(/\s+/g, " ")
    .trim();
  return value.slice(0, 64) || "Untitled Codex task";
}

function recency(thread) {
  return thread.recencyAt ?? thread.updatedAt ?? 0;
}
