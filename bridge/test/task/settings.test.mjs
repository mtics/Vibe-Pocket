import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import { Settings } from "../../src/task/settings.mjs";

function effectBoundary() {
  let crossed = false;
  return {
    get crossed() { return crossed; },
    async commit(operation) {
      assert.equal(crossed, false);
      crossed = true;
      return operation();
    },
  };
}

class FakeAppServer extends EventEmitter {
  calls = [];
  applyUpdates = true;
  updateError = null;

  constructor(resumed = null) {
    super();
    this.resumed = resumed;
    this.models = [
      model("gpt-sol", "GPT-Sol", ["minimal", "low", "medium", "high", "xhigh", "max", "ultra"]),
      model("gpt-luna", "GPT-Luna", ["low", "medium", "high", "xhigh"]),
      { ...model("hidden", "Hidden", ["low"]), hidden: true },
    ];
  }

  async request(method, params) {
    this.calls.push([method, params]);
    if (method === "model/list") {
      return {
        data: this.models,
      };
    }
    if (method === "thread/resume" && this.resumed) return this.resumed;
    if (method === "thread/settings/update") {
      if (this.updateError) throw this.updateError;
      if (this.applyUpdates) {
        this.resumed = {
          ...this.resumed,
          ...(params.model ? { model: params.model } : {}),
          ...(params.effort ? { reasoningEffort: params.effort } : {}),
        };
      }
      return {};
    }
    throw new Error(`Unexpected method ${method}`);
  }
}

class FakeContext {
  constructor(value = null) {
    this.value = value;
  }

  async read() {
    return this.value;
  }
}

function model(id, displayName, efforts) {
  return {
    id,
    displayName,
    hidden: false,
    defaultReasoningEffort: efforts[0],
    supportedReasoningEfforts: efforts.map((reasoningEffort) => ({ reasoningEffort })),
  };
}

test("uses the exact thread observation ahead of a stale visible selector", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "ultra" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "ultra" }),
  });
  await settings.start();

  const status = await settings.projection({ id: "thread-a", path: "/rollout.jsonl" }, { modelLabel: "Sol", level: "xhigh" });

  assert.equal(status.model.available, true);
  assert.equal(status.model.id, "gpt-sol");
  assert.equal(status.model.label, "Sol");
  assert.deepEqual(status.model.options.map(({ id }) => id), ["gpt-sol", "gpt-luna"]);
  assert.deepEqual(status.reasoning, {
    available: true,
    label: "Ultra",
    level: "ultra",
    canIncrease: false,
    canDecrease: true,
  });
  assert.deepEqual(appServer.calls.map(([method]) => method), ["model/list", "thread/resume"]);
});

test("keeps the catalog visible but disables selection without a matched desktop task", async () => {
  const appServer = new FakeAppServer();
  const settings = new Settings({ appServer, context: new FakeContext() });
  await settings.start();

  const status = await settings.projection(null, { modelLabel: "Sol" });

  assert.equal(status.model.available, false);
  assert.equal(status.model.id, "gpt-sol");
  assert.equal(status.model.options.length, 2);
});

test("writes model and every advertised reasoning level through app-server", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "xhigh" }),
  });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" });

  assert.equal(settings.reasoningTarget(1), "max");
  const reasoning = await settings.adjustReasoning(1, effectBoundary());
  assert.equal(reasoning.reasoning.level, "max");
  assert.equal((await settings.projection(thread, { modelLabel: "Sol" })).reasoning.level, "max");
  assert.equal(settings.reasoningTarget(1), "ultra");
  const ultra = await settings.adjustReasoning(1, effectBoundary());
  assert.equal(ultra.reasoning.level, "ultra");

  const selected = await settings.selectModel({
    id: "gpt-luna",
    threadId: "thread-a",
    effects: effectBoundary(),
  });
  assert.equal(selected.model.id, "gpt-luna");
  assert.equal(selected.reasoning.level, "low");

  assert.deepEqual(appServer.calls.slice(2), [
    ["thread/settings/update", { threadId: "thread-a", effort: "max" }],
    ["thread/resume", { threadId: "thread-a" }],
    ["thread/settings/update", { threadId: "thread-a", effort: "ultra" }],
    ["thread/resume", { threadId: "thread-a" }],
    ["thread/settings/update", { threadId: "thread-a", model: "gpt-luna", effort: "low" }],
    ["thread/resume", { threadId: "thread-a" }],
  ]);
});

test("invalidates cached settings when an app-server update times out", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  const settings = new Settings({ appServer, context: new FakeContext() });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" });
  appServer.updateError = new Error("thread/settings/update timed out");

  await assert.rejects(
    () => settings.selectReasoning({
      level: "max",
      threadId: "thread-a",
      effects: effectBoundary(),
    }),
    /timed out/i,
  );

  appServer.updateError = null;
  const observed = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true });
  assert.equal(observed.reasoning.level, "xhigh");
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 2);
});

test("rejects an observation mismatch without exposing the requested model", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  appServer.applyUpdates = false;
  const settings = new Settings({ appServer, context: new FakeContext() });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" });

  await assert.rejects(
    () => settings.selectModel({
      id: "gpt-luna",
      threadId: "thread-a",
      effects: effectBoundary(),
    }),
    /did not confirm/i,
  );

  const observed = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true });
  assert.equal(observed.model.id, "gpt-sol");
  assert.equal(observed.reasoning.level, "xhigh");
});

test("uses native task state to disambiguate a closed xhigh-like selector", async () => {
  const settings = new Settings({
    appServer: new FakeAppServer({ model: "gpt-sol", reasoningEffort: "ultra" }),
    context: new FakeContext(),
  });
  await settings.start();

  const status = await settings.projection(
    { id: "thread-a", path: "/rollout.jsonl" },
    { modelLabel: "Sol", level: "xhigh", ambiguous: true },
  );

  assert.equal(status.reasoning.level, "ultra");
  assert.equal(status.reasoning.label, "Ultra");
});

test("refreshes the exact model catalog and rejects a stale ID", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "medium" });
  const settings = new Settings({ appServer, context: new FakeContext() });
  await settings.start();
  appServer.models = [model("gpt-solar", "GPT-Solar", ["low", "medium"])];

  await assert.rejects(
    () => settings.modelOption("gpt-sol", { refresh: true }),
    /unavailable or stale/i,
  );
  assert.deepEqual(await settings.modelOption("gpt-solar"), { id: "gpt-solar", label: "Solar" });
});

test("invalidates thread settings when the app-server transport resets", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  const settings = new Settings({ appServer, context: new FakeContext() });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };

  const before = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true });
  assert.equal(before.reasoning.level, "xhigh");

  appServer.resumed = { model: "gpt-sol", reasoningEffort: "ultra" };
  appServer.emit("transportReset", { reason: new Error("restarted") });
  const after = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true });

  assert.equal(after.reasoning.level, "ultra");
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 2);
});
