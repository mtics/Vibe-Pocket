import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdir, mkdtemp, rm, symlink, unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Catalog } from "../../src/task/catalog.mjs";

class FakeAppServer extends EventEmitter {
  constructor(threads, searchResults = {}) {
    super();
    this.threads = threads.map(withDefaultCwd);
    this.searchResults = Object.fromEntries(Object.entries(searchResults).map(([term, results]) => (
      [term, results.map(withDefaultCwd)]
    )));
    this.calls = [];
    this.failure = null;
    this.resumed = {
      model: "gpt-sol",
      reasoningEffort: "medium",
      collaborationMode: { mode: "default" },
    };
  }

  async start() {
    this.calls.push(["start"]);
  }

  async request(method, params) {
    this.calls.push([method, params]);
    if (this.failure) throw this.failure;
    if (method === "thread/read") {
      const searchable = [...this.threads, ...Object.values(this.searchResults).flat()];
      return { thread: searchable.find(({ id }) => id === params.threadId) ?? null };
    }
    if (method === "model/list") {
      return {
        data: [{
          id: "gpt-sol",
          displayName: "Sol",
          hidden: false,
          defaultReasoningEffort: "medium",
          supportedReasoningEfforts: [{ reasoningEffort: "medium" }],
        }],
      };
    }
    if (method === "collaborationMode/list") {
      return {
        data: [
          { name: "Default", mode: "default", model: null, reasoning_effort: null },
          { name: "Plan", mode: "plan", model: null, reasoning_effort: null },
        ],
      };
    }
    if (method === "thread/resume") return this.resumed;
    if (method === "thread/settings/update") {
      if (params.collaborationMode) this.resumed = { ...this.resumed, collaborationMode: params.collaborationMode };
      return {};
    }
    assert.equal(method, "thread/list");
    return { data: params.searchTerm ? (this.searchResults[params.searchTerm] ?? []) : this.threads };
  }

  async stop() {
    this.calls.push(["stop"]);
  }
}

function effectBoundary() {
  return {
    crossed: false,
    async commit(operation) {
      this.crossed = true;
      return operation();
    },
  };
}

function withDefaultCwd(thread) {
  return { cwd: process.cwd(), ...thread };
}

class FakeActivityReader {
  constructor(states = new Map()) {
    this.states = states;
    this.calls = [];
  }

  async statesFor(threads) {
    this.calls.push(threads.map(({ id }) => id));
    return this.states;
  }
}

test("resolves visible task rows to stable native Codex task links", async () => {
  const appServer = new FakeAppServer([
    {
      id: "019f63fc-ac67-73a0-a118-90ef5d4b58a0",
      preview: "Analyze the local branches and merge them into main",
      parentThreadId: null,
    },
    {
      id: "019e9088-b456-7b30-9df2-2ddcaa16d004",
      name: "CETUS",
      preview: "Run the experiment audit.",
      parentThreadId: null,
    },
    {
      id: "019e9088-b456-7b30-9df2-2ddcaa16d005",
      name: "Nested",
      parentThreadId: "019e9088-b456-7b30-9df2-2ddcaa16d004",
    },
  ]);
  const opened = [];
  let now = 1_000;
  const catalog = new Catalog({
    appServer,
    openThread: async (threadId) => { opened.push(threadId); },
    cacheTtlMs: 3_000,
    now: () => now,
  });

  const visible = [
    { id: "agent-aaaaaaaaaaaaaaaaaaaaaaaa", label: "Analyze the local branches and merge them into main", state: "executing", focused: true },
    { id: "agent-bbbbbbbbbbbbbbbbbbbbbbbb", label: "NexusRec", state: "idle", focused: false },
    { id: "agent-cccccccccccccccccccccccc", label: "CETUS", state: "idle", focused: false },
    { id: "agent-dddddddddddddddddddddddd", label: "New task", state: "idle", focused: false },
  ];
  const first = await catalog.resolveVisibleAgents(visible);
  now += 1_000;
  const second = await catalog.resolveVisibleAgents(visible);

  assert.deepEqual(first, second);
  assert.deepEqual(first.map(({ label }) => label), [
    "Analyze the local branches and merge them into main",
    "CETUS",
  ]);
  assert.ok(first.every(({ id }) => /^agent-[a-f0-9]{24}$/.test(id)));
  assert.equal(first[0].focused, true);
  assert.equal(first[0].state, "executing");
  await catalog.focusAgent(first[1].id);
  assert.deepEqual(opened, ["019e9088-b456-7b30-9df2-2ddcaa16d004"]);
  assert.deepEqual(appServer.calls, [
    ["start"],
    ["thread/list", { limit: 100, sortDirection: "desc", sortKey: "recency_at" }],
    ["thread/read", {
      threadId: "019e9088-b456-7b30-9df2-2ddcaa16d004",
      includeTurns: false,
    }],
  ]);

  await catalog.dispose();
  assert.deepEqual(appServer.calls.at(-1), ["stop"]);
});

test("binds a TargetRef and delegates exact model selection through settings", async () => {
  const appServer = new FakeAppServer([
    { id: "thread-model", name: "Model task", parentThreadId: null },
  ]);
  const catalog = new Catalog({ appServer, openThread: async () => {} });
  const agents = await catalog.resolveVisibleAgents([
    { label: "Model task", state: "idle", focused: true },
  ]);
  const target = await catalog.attachVisible(agents[0].id);
  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-model",
      threadSettings: appServer.resumed,
    },
  });
  await catalog.settings({ modeLabel: "Default" });
  const effects = effectBoundary();

  const selected = await catalog.selectModel({ id: "gpt-sol", target, effects });

  assert.deepEqual(selected.target, target);
  assert.equal(selected.model.id, "gpt-sol");
  assert.equal(effects.crossed, true);
  assert.deepEqual(appServer.calls.find(([method]) => method === "thread/settings/update")?.[1], {
    threadId: "thread-model",
    model: "gpt-sol",
  });
  assert.equal(catalog.selectMode, undefined);
});

test("binds an exact task for app-server settings without opening the desktop", async () => {
  const appServer = new FakeAppServer([
    { id: "thread-semantic", name: "Semantic task", parentThreadId: null },
  ]);
  const opened = [];
  const catalog = new Catalog({
    appServer,
    openThread: async (threadId) => { opened.push(threadId); },
  });

  const result = await catalog.bindThread("thread-semantic");

  assert.equal(result.threadId, "thread-semantic");
  assert.equal(result.target.threadId, "thread-semantic");
  assert.equal(result.target.agentId, result.agentId);
  assert.deepEqual(opened, []);
  assert.deepEqual(appServer.calls, [
    ["start"],
    ["thread/read", { threadId: "thread-semantic", includeTurns: false }],
  ]);
});

test("selects an Agent control target without opening its desktop task", async () => {
  const appServer = new FakeAppServer([
    { id: "thread-semantic", name: "Semantic task", parentThreadId: null },
  ]);
  const opened = [];
  const catalog = new Catalog({
    appServer,
    openThread: async (threadId) => { opened.push(threadId); },
  });
  const agents = await catalog.resolveVisibleAgents([]);
  const effects = effectBoundary();

  const selected = await catalog.selectAgent(agents[0].id, effects);

  assert.equal(selected.threadId, "thread-semantic");
  assert.equal(selected.target.agentId, agents[0].id);
  assert.equal(effects.crossed, true);
  assert.deepEqual(opened, []);
});

test("accepts a unique ellipsized title and rejects ambiguous or stale targets", async () => {
  const appServer = new FakeAppServer([
    { id: "thread-00000001", name: "A uniquely identifiable task title", parentThreadId: null },
    { id: "thread-00000002", name: "An ambiguous task title one", parentThreadId: null },
    { id: "thread-00000003", name: "An ambiguous task title two", parentThreadId: null },
  ]);
  const catalog = new Catalog({ appServer, openThread: async () => {} });

  const agents = await catalog.resolveVisibleAgents([
    { label: "A uniquely identifiable…", state: "idle", focused: true },
    { label: "An ambiguous task…", state: "idle", focused: false },
  ]);

  assert.equal(agents.length, 3);
  assert.equal(agents[0].label, "A uniquely identifiable…");
  await assert.rejects(() => catalog.focusAgent("agent-ffffffffffffffffffffffff"), /no longer available/i);
  await assert.rejects(() => catalog.focusAgent("unsafe"), /valid Codex task ID/i);
});

test("matches a desktop title after Codex renders its Markdown links", async () => {
  const catalog = new Catalog({
    appServer: new FakeAppServer([
      {
        id: "thread-markdown",
        name: "给 [TKDE-SANE](paper/TKDE-SANE/) 配置 .gitignore",
        parentThreadId: null,
      },
    ]),
    openThread: async () => {},
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: "给 TKDE-SANE 配置 .gitignore", state: "idle", focused: true },
  ]);

  assert.equal(agents.length, 1);
  assert.equal(agents[0].focused, true);
  assert.match(agents[0].id, /^agent-[a-f0-9]{24}$/);
});

test("reports only observed desktop focus through native task transitions", async () => {
  const catalog = new Catalog({
    appServer: new FakeAppServer([
      { id: "thread-current", name: "Current task", parentThreadId: null },
      { id: "thread-target", name: "Target task", parentThreadId: null },
    ]),
    openThread: async () => {},
  });
  const initial = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);
  const target = initial.find(({ label }) => label === "Target task");
  await catalog.focusAgent(target.id);

  const staleDesktop = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);
  assert.equal(staleDesktop.find(({ focused }) => focused)?.label, "Current task");

  const confirmed = await catalog.resolveVisibleAgents([
    { label: "Target task", state: "idle", focused: true },
  ]);
  assert.equal(confirmed.find(({ focused }) => focused)?.label, "Target task");

  const emptyObservation = await catalog.resolveVisibleAgents([]);
  assert.equal(emptyObservation.some(({ focused }) => focused), false);

  const unresolvedTask = await catalog.resolveVisibleAgents([
    { label: "A task outside the catalog", state: "idle", focused: true },
  ]);
  assert.equal(unresolvedTask.some(({ focused }) => focused), false);
});

test("finds an active visible task beyond the recent catalog through a bounded title search", async () => {
  const title = "Evaluate recommendation harness feasibility";
  const appServer = new FakeAppServer([], {
    [title]: [{ id: "thread-older-active", name: title, parentThreadId: null }],
  });
  const opened = [];
  const catalog = new Catalog({
    appServer,
    openThread: async (threadId) => { opened.push(threadId); },
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: title, state: "executing", focused: false },
    { label: "Unresolved idle project", state: "idle", focused: false },
  ]);

  assert.equal(agents.length, 1);
  await catalog.focusAgent(agents[0].id);
  assert.deepEqual(opened, ["thread-older-active"]);
  assert.deepEqual(appServer.calls, [
    ["start"],
    ["thread/list", { limit: 100, sortDirection: "desc", sortKey: "recency_at" }],
    ["thread/list", {
      limit: 20,
      sortDirection: "desc",
      sortKey: "recency_at",
      searchTerm: title,
      useStateDbOnly: true,
    }],
    ["thread/read", { threadId: "thread-older-active", includeTurns: false }],
  ]);
});

test("adds running tasks that are not mounted in the visible project sidebar", async () => {
  const threads = [
    { id: "thread-current", name: "Current task", parentThreadId: null },
    { id: "thread-waiting", name: "Waiting in another project", parentThreadId: null },
    { id: "thread-thinking", name: "Running in another project", parentThreadId: null },
  ];
  const activityReader = new FakeActivityReader(new Map([
    ["thread-waiting", "waiting"],
    ["thread-thinking", "thinking"],
  ]));
  const opened = [];
  const catalog = new Catalog({
    appServer: new FakeAppServer(threads),
    activityReader,
    openThread: async (threadId) => { opened.push(threadId); },
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);

  assert.deepEqual(agents.map(({ label, state, focused }) => ({ label, state, focused })), [
    { label: "Current task", state: "idle", focused: true },
    { label: "Waiting in another project", state: "waiting", focused: false },
    { label: "Running in another project", state: "thinking", focused: false },
  ]);
  await catalog.focusAgent(agents[2].id);
  assert.deepEqual(opened, ["thread-thinking"]);
  assert.deepEqual(activityReader.calls, [["thread-current", "thread-waiting", "thread-thinking"]]);
});

test("bounds status-ranked Agents while retaining the focused desktop task", async () => {
  const threads = Array.from({ length: 30 }, (_, index) => ({
    id: `thread-${index}`,
    name: index === 0 ? "Current task" : `Background task ${index}`,
    parentThreadId: null,
  }));
  const activityReader = new FakeActivityReader(new Map(
    threads.slice(1).map(({ id }) => [id, "thinking"]),
  ));
  const catalog = new Catalog({
    appServer: new FakeAppServer(threads),
    activityReader,
    openThread: async () => {},
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);

  assert.equal(agents.length, 24);
  assert.equal(agents.filter(({ state }) => state === "thinking").length, 23);
  assert.equal(agents[0].label, "Current task");
  assert.equal(agents[0].focused, true);
});

test("sorts task groups by status and recent activity after the focused task", async () => {
  const threads = [
    { id: "thread-old-idle", name: "Old idle", recencyAt: 10, parentThreadId: null },
    { id: "thread-current", name: "Current task", recencyAt: 5, parentThreadId: null },
    { id: "thread-recent-idle", name: "Recent idle", recencyAt: 40, parentThreadId: null },
    { id: "thread-waiting", name: "Waiting task", recencyAt: 20, parentThreadId: null },
  ];
  const activityReader = new FakeActivityReader(new Map([
    ["thread-waiting", "waiting"],
  ]));
  const catalog = new Catalog({
    appServer: new FakeAppServer(threads),
    activityReader,
    openThread: async () => {},
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);

  assert.deepEqual(agents.map(({ label }) => label), [
    "Current task",
    "Waiting task",
    "Recent idle",
    "Old idle",
  ]);
});

test("filters canonical workspace escapes and rechecks focus targets before opening", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-catalog-scope-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const workspace = join(root, "workspace");
  const project = join(workspace, "project");
  const outside = join(root, "outside");
  const current = join(workspace, "current");
  await mkdir(project, { recursive: true });
  await mkdir(outside);
  await symlink(project, current, "dir");

  const appServer = new FakeAppServer([
    { id: "thread-allowed", name: "Allowed task", cwd: current, parentThreadId: null },
    { id: "thread-outside", name: "Outside task", cwd: outside, parentThreadId: null },
  ]);
  const opened = [];
  const catalog = new Catalog({
    appServer,
    workspaces: { research: workspace },
    openThread: async (threadId) => { opened.push(threadId); },
  });

  const agents = await catalog.resolveVisibleAgents([]);
  assert.deepEqual(agents.map(({ label }) => label), ["Allowed task"]);

  await unlink(current);
  await symlink(outside, current, "dir");
  await assert.rejects(
    () => catalog.focusAgent(agents[0].id),
    /outside the configured Vibe Pocket workspaces/i,
  );
  await assert.rejects(
    () => catalog.focusThread("thread-outside"),
    /outside the configured Vibe Pocket workspaces/i,
  );
  assert.deepEqual(opened, []);
});

test("never admits a visible task outside configured workspaces", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-visible-scope-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const workspace = join(root, "workspace");
  const outside = join(root, "outside");
  await mkdir(workspace);
  await mkdir(outside);
  const title = "Visible task in another project";
  const outsideThread = {
    id: "thread-visible-outside",
    name: title,
    cwd: outside,
    parentThreadId: null,
  };
  const appServer = new FakeAppServer([outsideThread]);
  const opened = [];
  const catalog = new Catalog({
    appServer,
    workspaces: { configured: workspace },
    openThread: async (threadId) => { opened.push(threadId); },
  });

  const visible = await catalog.resolveVisibleAgents([
    { label: title, state: "idle", focused: true },
  ]);

  assert.deepEqual(visible, []);
  assert.equal(appServer.calls.some(([, params]) => params?.searchTerm), true);
  await assert.rejects(
    () => catalog.focusThread(outsideThread.id),
    /outside the configured Vibe Pocket workspaces/i,
  );
  assert.deepEqual(opened, []);
});

test("drops historical focus when a formerly unique title becomes ambiguous", async () => {
  const title = "A duplicate task title that remains readable in the controller";
  const appServer = new FakeAppServer([
    { id: "thread-duplicate-one", name: title, parentThreadId: null },
  ]);
  const catalog = new Catalog({ appServer, cacheTtlMs: 0, openThread: async () => {} });

  const initial = await catalog.resolveVisibleAgents([
    { label: title, state: "idle", focused: true },
  ]);
  assert.equal(initial[0].focused, true);
  appServer.threads.push(withDefaultCwd({
    id: "thread-duplicate-two",
    name: title,
    parentThreadId: null,
  }));

  const ambiguous = await catalog.resolveVisibleAgents([
    { label: "A duplicate task title that remains…", state: "idle", focused: true },
  ]);
  assert.equal(ambiguous.length, 2);
  assert.equal(ambiguous.some(({ focused }) => focused), false);
  assert.equal(new Set(ambiguous.map(({ label }) => label)).size, 2);
  assert.ok(ambiguous.every(({ label }) => label.length <= 64 && /\[default:[a-f0-9]{6}\]$/.test(label)));

  const settings = await catalog.settings({ modelLabel: "Sol", level: "medium" });
  assert.equal(settings.target, null);
  assert.equal(settings.model.available, false);
  assert.equal(settings.reasoning.available, false);

  const unknown = new Catalog({ appServer, cacheTtlMs: 0, openThread: async () => {} });
  const unresolved = await unknown.resolveVisibleAgents([
    { label: "A duplicate task title that remains…", state: "idle", focused: true },
  ]);
  assert.equal(unresolved.some(({ focused }) => focused), false);
  assert.equal(new Set(unresolved.map(({ label }) => label)).size, 2);
});

test("marks a short fallback stale, clears focus actions, and expires it", async () => {
  let now = 1_000;
  const appServer = new FakeAppServer([
    { id: "thread-freshness", name: "Fresh task", parentThreadId: null },
  ]);
  const opened = [];
  const catalog = new Catalog({
    appServer,
    cacheTtlMs: 0,
    fallbackTtlMs: 500,
    now: () => now,
    openThread: async (threadId) => { opened.push(threadId); },
  });
  const fresh = await catalog.resolveVisibleAgents([
    { label: "Fresh task", state: "idle", focused: true },
  ]);
  assert.equal(fresh[0].freshness, "fresh");
  assert.equal(catalog.freshness.state, "fresh");

  appServer.failure = new Error("catalog offline");
  now = 1_100;
  const stale = await catalog.resolveVisibleAgents([]);
  assert.deepEqual(stale.map(({ focused, freshness, actionable }) => ({ focused, freshness, actionable })), [
    { focused: false, freshness: "stale", actionable: false },
  ]);
  assert.equal(catalog.freshness.state, "stale");
  await assert.rejects(() => catalog.focusAgent(fresh[0].id), /no longer available/i);
  assert.deepEqual(opened, []);

  now = 1_600;
  assert.deepEqual(await catalog.resolveVisibleAgents([]), []);
  assert.equal(catalog.freshness.state, "expired");
});
