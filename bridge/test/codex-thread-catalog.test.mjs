import assert from "node:assert/strict";
import test from "node:test";

import { CodexThreadCatalog } from "../src/codex-thread-catalog.mjs";

class FakeAppServer {
  constructor(threads, searchResults = {}) {
    this.threads = threads;
    this.searchResults = searchResults;
    this.calls = [];
  }

  async start() {
    this.calls.push(["start"]);
  }

  async request(method, params) {
    this.calls.push([method, params]);
    assert.equal(method, "thread/list");
    return { data: params.searchTerm ? (this.searchResults[params.searchTerm] ?? []) : this.threads };
  }

  async stop() {
    this.calls.push(["stop"]);
  }
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
  const catalog = new CodexThreadCatalog({
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
  ]);

  await catalog.dispose();
  assert.deepEqual(appServer.calls.at(-1), ["stop"]);
});

test("accepts a unique ellipsized title and rejects ambiguous or stale targets", async () => {
  const appServer = new FakeAppServer([
    { id: "thread-00000001", name: "A uniquely identifiable task title", parentThreadId: null },
    { id: "thread-00000002", name: "An ambiguous task title one", parentThreadId: null },
    { id: "thread-00000003", name: "An ambiguous task title two", parentThreadId: null },
  ]);
  const catalog = new CodexThreadCatalog({ appServer, openThread: async () => {} });

  const agents = await catalog.resolveVisibleAgents([
    { label: "A uniquely identifiable…", state: "idle", focused: true },
    { label: "An ambiguous task…", state: "idle", focused: false },
  ]);

  assert.equal(agents.length, 1);
  assert.equal(agents[0].label, "A uniquely identifiable…");
  await assert.rejects(() => catalog.focusAgent("agent-ffffffffffffffffffffffff"), /no longer available/i);
  await assert.rejects(() => catalog.focusAgent("unsafe"), /valid Codex task ID/i);
});

test("finds an active visible task beyond the recent catalog through a bounded title search", async () => {
  const title = "Evaluate recommendation harness feasibility";
  const appServer = new FakeAppServer([], {
    [title]: [{ id: "thread-older-active", name: title, parentThreadId: null }],
  });
  const opened = [];
  const catalog = new CodexThreadCatalog({
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
  const catalog = new CodexThreadCatalog({
    appServer: new FakeAppServer(threads),
    activityReader,
    openThread: async (threadId) => { opened.push(threadId); },
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);

  assert.deepEqual(agents.map(({ label, state, focused }) => ({ label, state, focused })), [
    { label: "Waiting in another project", state: "waiting", focused: false },
    { label: "Running in another project", state: "thinking", focused: false },
    { label: "Current task", state: "idle", focused: true },
  ]);
  await catalog.focusAgent(agents[1].id);
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
  const catalog = new CodexThreadCatalog({
    appServer: new FakeAppServer(threads),
    activityReader,
    openThread: async () => {},
  });

  const agents = await catalog.resolveVisibleAgents([
    { label: "Current task", state: "idle", focused: true },
  ]);

  assert.equal(agents.length, 24);
  assert.equal(agents.filter(({ state }) => state === "thinking").length, 23);
  assert.equal(agents.at(-1).label, "Current task");
  assert.equal(agents.at(-1).focused, true);
});
