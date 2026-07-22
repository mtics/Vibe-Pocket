import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import { Settings } from "../../src/task/settings.mjs";

const TARGET = Object.freeze({
  threadId: "thread-a",
  agentId: "agent-aaaaaaaaaaaaaaaaaaaaaaaa",
  bindingEpoch: 1,
  bridgeInstanceId: "bridge-aaaaaaaaaaaaaaaa",
  appServerGeneration: 0,
  canonicalWorkspaceId: "workspace-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
});

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

function queuedEffectBoundary() {
  const entered = deferred();
  const queued = deferred();
  let crossed = false;
  return {
    get crossed() { return crossed; },
    entered: entered.promise,
    release: queued.resolve,
    async commit(operation) {
      assert.equal(crossed, false);
      entered.resolve();
      await queued.promise;
      crossed = true;
      return operation();
    },
  };
}

class FakeAppServer extends EventEmitter {
  calls = [];
  applyUpdates = true;
  modelListResponse = null;
  resumeResponse = null;
  resumeResponses = [];
  resumeError = null;
  updateError = null;
  updateResponse = null;
  omitModeFromResume = false;
  emitSettingsNotification = false;
  loaded = true;

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
      if (this.modelListResponse) return this.modelListResponse;
      return {
        data: this.models,
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
    if (method === "thread/resume" && this.resumeResponses.length > 0) {
      this.loaded = true;
      return this.resumeResponses.shift();
    }
    if (method === "thread/resume" && this.resumeError) throw this.resumeError;
    if (method === "thread/resume" && this.resumeResponse) return this.resumeResponse;
    if (method === "thread/resume" && this.resumed) {
      this.loaded = true;
      if (!this.omitModeFromResume) return this.resumed;
      const { collaborationMode: _mode, ...withoutMode } = this.resumed;
      return withoutMode;
    }
    if (method === "thread/settings/update") {
      if (!this.loaded) throw new Error(`Codex app-server rejected thread/settings/update: thread not found: ${params.threadId}`);
      if (this.updateError) throw this.updateError;
      if (this.applyUpdates) {
        this.resumed = {
          ...this.resumed,
          ...(params.model ? { model: params.model } : {}),
          ...(params.effort ? { reasoningEffort: params.effort } : {}),
          ...(params.collaborationMode ? { collaborationMode: params.collaborationMode } : {}),
        };
        if (this.emitSettingsNotification) {
          this.emit("notification", {
            method: "thread/settings/updated",
            params: { threadId: params.threadId, threadSettings: this.resumed },
          });
        }
      }
      if (this.updateResponse) return this.updateResponse;
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

function deferred() {
  let resolve;
  const promise = new Promise((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

async function waitForCalls(appServer, method, count) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    if (appServer.calls.filter(([called]) => called === method).length >= count) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.fail(`Timed out waiting for ${count} ${method} calls`);
}

test("uses the exact rollout observation without resuming the task", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "ultra" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "ultra" }),
  });
  await settings.start();

  const status = await settings.projection(
    { id: "thread-a", path: "/rollout.jsonl" },
    { modelLabel: "Sol", level: "xhigh", ambiguous: true },
    TARGET,
  );

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
    increaseTo: null,
    decreaseTo: "max",
    options: ["minimal", "low", "medium", "high", "xhigh", "max", "ultra"],
  });
  assert.deepEqual(
    appServer.calls.map(([method]) => method).sort(),
    ["model/list"],
  );
});

test("uses the rollout observation ahead of a stale app-server cache", async () => {
  const appServer = new FakeAppServer({ model: "gpt-luna", reasoningEffort: "xhigh" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "high" }),
  });
  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-a",
      threadSettings: { model: "gpt-luna", effort: "xhigh" },
    },
  });

  const status = await settings.projection(
    { id: "thread-a", path: "/rollout.jsonl" },
    { modelLabel: "Sol", level: "high", ambiguous: false },
    TARGET,
  );

  assert.equal(status.model.id, "gpt-sol");
  assert.equal(status.reasoning.level, "high");
  assert.equal(appServer.calls.some(([method]) => method === "thread/resume"), false);
});

test("disables stale cached settings without resuming the task", async () => {
  let now = 1_000;
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "low" });
  const settings = new Settings({
    appServer,
    context: new FakeContext(),
    now: () => now,
    settingsFreshnessMs: 100,
  });
  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-a",
      threadSettings: { model: "gpt-sol", effort: "low" },
    },
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };

  const cached = await settings.projection(thread, { modelLabel: "Sol" }, TARGET);
  assert.equal(cached.reasoning.level, "low");
  assert.equal(appServer.calls.some(([method]) => method === "thread/resume"), false);

  now += 100;
  const expired = await settings.projection(thread, { modelLabel: "Sol" }, TARGET);
  const stillExpired = await settings.projection(thread, { modelLabel: "Sol" }, TARGET);

  assert.equal(expired.fresh, false);
  assert.equal(expired.reasoning.available, false);
  assert.equal(stillExpired.fresh, false);
  assert.equal(appServer.calls.some(([method]) => method === "thread/resume"), false);
});

test("keeps an exact rollout mutable without background task resume", async () => {
  const appServer = new FakeAppServer();
  appServer.resumeError = new Error("thread/resume failed");
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-luna", reasoningEffort: "xhigh" }),
  });

  const status = await settings.projection(
    { id: "thread-a", path: "/rollout.jsonl" },
    { modelLabel: "Sol", level: "high" },
    TARGET,
  );

  assert.deepEqual(status.target, TARGET);
  assert.equal(status.fresh, true);
  assert.equal(status.model.id, "gpt-luna");
  assert.equal(status.reasoning.level, "xhigh");
  assert.equal(status.model.available, true);
  assert.equal(status.reasoning.available, true);
  assert.equal(appServer.calls.some(([method]) => method === "thread/resume"), false);
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

test("never resumes a bound task when no settings observation exists", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "high" });
  const settings = new Settings({ appServer, context: new FakeContext() });

  const status = await settings.projection(
    { id: "thread-a", path: "/missing-rollout.jsonl" },
    { modelLabel: "", level: null },
    TARGET,
  );

  assert.equal(status.fresh, false);
  assert.equal(status.model.available, false);
  assert.equal(status.reasoning.available, false);
  assert.equal(appServer.calls.some(([method]) => method === "thread/resume"), false);
});

test("uses the read-only desktop mode without loading the task", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "high" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "high" }),
  });

  const status = await settings.projection(
    { id: "thread-a", path: "/rollout.jsonl" },
    { modelLabel: "Sol", modeLabel: "Default" },
    TARGET,
  );

  assert.equal(status.mode.available, false);
  assert.equal(status.mode.id, "default");
  assert.equal(appServer.calls.some(([method]) => method === "thread/resume"), false);
});

test("writes model and every advertised reasoning level through app-server", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  appServer.emitSettingsNotification = true;
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "xhigh" }),
  });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" }, TARGET);

  assert.equal(settings.reasoningTarget(1), "max");
  const reasoning = await settings.adjustReasoning(1, TARGET, effectBoundary());
  assert.equal(reasoning.reasoning.level, "max");
  assert.equal((await settings.projection(thread, { modelLabel: "Sol" }, TARGET)).reasoning.level, "max");
  assert.equal(settings.reasoningTarget(1), "ultra");
  const ultra = await settings.adjustReasoning(1, TARGET, effectBoundary());
  assert.equal(ultra.reasoning.level, "ultra");

  const selected = await settings.selectModel({
    id: "gpt-luna",
    target: TARGET,
    effects: effectBoundary(),
  });
  assert.equal(selected.model.id, "gpt-luna");
  assert.equal(selected.reasoning.level, "low");

  assert.deepEqual(appServer.calls.filter(([method]) => method.startsWith("thread/")), [
    ["thread/settings/update", { threadId: "thread-a", effort: "max" }],
    ["thread/resume", { threadId: "thread-a" }],
    ["thread/settings/update", { threadId: "thread-a", effort: "ultra" }],
    ["thread/resume", { threadId: "thread-a" }],
    ["thread/settings/update", { threadId: "thread-a", model: "gpt-luna", effort: "low" }],
    ["thread/resume", { threadId: "thread-a" }],
  ]);
});

test("keeps the visible mode when a model update omits that unrelated field", async () => {
  const appServer = new FakeAppServer({
    model: "gpt-sol",
    reasoningEffort: "xhigh",
    collaborationMode: { mode: "default" },
  });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "xhigh" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  const before = await settings.projection(thread, { modelLabel: "Sol", modeLabel: "Default" }, TARGET);
  assert.equal(before.mode.id, "default");
  appServer.omitModeFromResume = true;

  const selected = await settings.selectModel({
    id: "gpt-luna",
    target: TARGET,
    effects: effectBoundary(),
  });

  assert.equal(selected.model.id, "gpt-luna");
  assert.equal(selected.mode.id, "default");
  assert.equal(selected.mode.available, false);
});

test("keeps collaboration mode read-only under protocol v12", async () => {
  const appServer = new FakeAppServer({
    model: "gpt-sol",
    reasoningEffort: "high",
    collaborationMode: { mode: "default" },
  });
  appServer.omitModeFromResume = true;
  appServer.emitSettingsNotification = true;
  const settings = new Settings({ appServer, context: new FakeContext() });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" }, TARGET);

  await assert.rejects(() => settings.selectMode({
    id: "plan",
    target: TARGET,
    effects: effectBoundary(),
  }), /disabled/i);
  assert.equal(appServer.calls.some(([method]) => method === "thread/settings/update"), false);
});

test("does not expose a requested setting when the app-server update times out", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "xhigh" }),
  });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" }, TARGET);
  appServer.updateError = new Error("thread/settings/update timed out");

  await assert.rejects(
    () => settings.selectReasoning({
      level: "max",
      target: TARGET,
      effects: effectBoundary(),
    }),
    /timed out/i,
  );

  appServer.updateError = null;
  const observed = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true }, TARGET);
  assert.equal(observed.reasoning.level, "xhigh");
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 0);
});

test("rejects an accepted model update that cannot be observed", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  appServer.applyUpdates = false;
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "xhigh" }),
  });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" }, TARGET);

  await assert.rejects(
    () => settings.selectModel({
      id: "gpt-luna",
      target: TARGET,
      effects: effectBoundary(),
    }),
    /did not confirm/i,
  );

  const observed = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true }, TARGET);
  assert.equal(observed.model.id, "gpt-sol");
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 1);
});

test("uses rollout state to disambiguate a closed xhigh-like selector", async () => {
  const settings = new Settings({
    appServer: new FakeAppServer({ model: "gpt-sol", reasoningEffort: "ultra" }),
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "ultra" }),
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

test("invalidates thread settings and the model catalog when the app-server transport resets", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "xhigh" });
  const context = new FakeContext({ model: "gpt-sol", reasoningEffort: "xhigh" });
  const settings = new Settings({ appServer, context });
  await settings.start();
  const thread = { id: "thread-a", path: "/rollout.jsonl" };

  const before = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true });
  assert.equal(before.reasoning.level, "xhigh");

  appServer.models = [model("gpt-sol", "GPT-Sol", ["low", "medium"])];
  context.value = { model: "gpt-sol", reasoningEffort: "medium" };
  appServer.emit("transportReset", { reason: new Error("restarted") });
  const after = await settings.projection(thread, { modelLabel: "Sol", ambiguous: true });

  assert.equal(after.model.id, "gpt-sol");
  assert.deepEqual(after.model.options.map(({ id }) => id), ["gpt-sol"]);
  assert.equal(after.reasoning.level, "medium");
  assert.equal(after.reasoning.canIncrease, false);
  assert.equal(settings.hasReasoningLevel("xhigh"), false);
  assert.equal(appServer.calls.filter(([method]) => method === "model/list").length, 2);
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 0);
});

test("does not let a delayed pre-reset model load replace the new catalog", async () => {
  const oldLoad = deferred();
  const appServer = new FakeAppServer();
  appServer.modelListResponse = oldLoad.promise;
  const settings = new Settings({ appServer, context: new FakeContext() });

  const staleStart = settings.start();
  await waitForCalls(appServer, "model/list", 1);

  appServer.models = [model("gpt-luna", "GPT-Luna", ["low", "medium"])];
  appServer.modelListResponse = null;
  appServer.emit("transportReset", { reason: new Error("restarted") });
  await Promise.all([settings.start(), settings.start()]);
  assert.equal(appServer.calls.filter(([method]) => method === "model/list").length, 2);

  oldLoad.resolve({ data: [model("gpt-sol", "GPT-Sol", ["xhigh", "ultra"])] });
  await staleStart;

  await assert.rejects(() => settings.modelOption("gpt-sol"), /unavailable or stale/i);
  assert.deepEqual(await settings.modelOption("gpt-luna"), { id: "gpt-luna", label: "Luna" });
});

test("concurrent projections never resume the task", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "ultra" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "ultra" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  const projections = await Promise.all([
    settings.projection(thread, { modelLabel: "Sol", ambiguous: true }),
    settings.projection(thread, { modelLabel: "Sol", ambiguous: true }),
  ]);

  assert.deepEqual(projections.map(({ reasoning }) => reasoning.level), ["ultra", "ultra"]);
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 0);
});

test("does not let a stale notification revert an acknowledged mutation", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "low" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "low" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol", level: "low" }, TARGET);
  const update = deferred();
  appServer.updateResponse = update.promise;

  const mutation = settings.selectModel({
    id: "gpt-luna",
    target: TARGET,
    effects: effectBoundary(),
  });
  await waitForCalls(appServer, "thread/settings/update", 1);

  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-a",
      threadSettings: { model: "gpt-sol", effort: "low" },
    },
  });
  update.resolve({});
  const selected = await mutation;
  const observed = await settings.projection(thread, { modelLabel: "Sol", level: "low" }, TARGET);

  assert.equal(selected.model.id, "gpt-luna");
  assert.equal(selected.reasoning.level, "low");
  assert.equal(observed.model.id, "gpt-luna");
  assert.equal(observed.reasoning.level, "low");
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 1);
});

test("does not confirm from a matching notification observed before the update is submitted", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "low" });
  appServer.applyUpdates = false;
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "low" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol", level: "low" }, TARGET);
  const effects = queuedEffectBoundary();

  const mutation = settings.selectReasoning({ level: "medium", target: TARGET, effects });
  await effects.entered;
  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-a",
      threadSettings: { model: "gpt-sol", effort: "medium" },
    },
  });
  effects.release();

  await assert.rejects(mutation, /did not confirm/i);
  assert.equal(appServer.calls.filter(([method]) => method === "thread/settings/update").length, 1);
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 1);
});

test("does not confirm from a matching notification while the update request is pending", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "low" });
  appServer.applyUpdates = false;
  const update = deferred();
  appServer.updateResponse = update.promise;
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "low" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol", level: "low" }, TARGET);

  const mutation = settings.selectReasoning({
    level: "medium",
    target: TARGET,
    effects: effectBoundary(),
  });
  await waitForCalls(appServer, "thread/settings/update", 1);
  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-a",
      threadSettings: { model: "gpt-sol", effort: "medium" },
    },
  });
  update.resolve({});

  await assert.rejects(mutation, /did not confirm/i);
  assert.equal(appServer.calls.filter(([method]) => method === "thread/settings/update").length, 1);
  assert.equal(appServer.calls.filter(([method]) => method === "thread/resume").length, 1);
});

test("does not confirm from the resume used to load a thread before retrying the update", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "high" });
  appServer.loaded = false;
  appServer.applyUpdates = false;
  appServer.resumeResponses = [
    { model: "gpt-sol", reasoningEffort: "xhigh" },
    { model: "gpt-sol", reasoningEffort: "high" },
  ];
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "high" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol", level: "high" }, TARGET);

  await assert.rejects(
    settings.selectReasoning({
      level: "xhigh",
      target: TARGET,
      effects: effectBoundary(),
    }),
    /did not confirm/i,
  );

  assert.deepEqual(appServer.calls.filter(([method]) => method.startsWith("thread/")), [
    ["thread/settings/update", { threadId: "thread-a", effort: "xhigh" }],
    ["thread/resume", { threadId: "thread-a" }],
    ["thread/settings/update", { threadId: "thread-a", effort: "xhigh" }],
    ["thread/resume", { threadId: "thread-a" }],
  ]);
});

test("does not let a delayed resume overwrite a newer settings notification", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "low" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "low" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol", level: "low" }, TARGET);
  const resume = deferred();
  appServer.resumeResponse = resume.promise;

  const mutation = settings.selectReasoning({
    level: "medium",
    target: TARGET,
    effects: effectBoundary(),
  });
  await waitForCalls(appServer, "thread/resume", 1);
  appServer.emit("notification", {
    method: "thread/settings/updated",
    params: {
      threadId: "thread-a",
      threadSettings: { model: "gpt-sol", effort: "medium" },
    },
  });
  resume.resolve({ model: "gpt-sol", reasoningEffort: "low" });

  const selected = await mutation;
  const observed = await settings.projection(thread, { modelLabel: "Sol", level: "low" }, TARGET);
  assert.equal(selected.reasoning.level, "medium");
  assert.equal(observed.reasoning.level, "medium");
});

test("rejects a queued settings mutation after its complete target changes", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "low" });
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "low" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" }, TARGET);
  const effects = queuedEffectBoundary();
  const mutation = settings.selectReasoning({ level: "medium", target: TARGET, effects });
  await effects.entered;

  await settings.projection(thread, { modelLabel: "Sol" }, {
    ...TARGET,
    bindingEpoch: TARGET.bindingEpoch + 1,
  });
  const rejection = assert.rejects(mutation, /changed at bindingEpoch/i);
  effects.release();
  await rejection;

  assert.equal(appServer.calls.some(([method]) => method === "thread/settings/update"), false);
});

test("loads an absent thread once only after an explicit settings action", async () => {
  const appServer = new FakeAppServer({ model: "gpt-sol", reasoningEffort: "high" });
  appServer.loaded = false;
  const settings = new Settings({
    appServer,
    context: new FakeContext({ model: "gpt-sol", reasoningEffort: "high" }),
  });
  const thread = { id: "thread-a", path: "/rollout.jsonl" };
  await settings.projection(thread, { modelLabel: "Sol" }, TARGET);

  const selected = await settings.selectReasoning({
    level: "xhigh",
    target: TARGET,
    effects: effectBoundary(),
  });

  assert.equal(selected.reasoning.level, "xhigh");
  assert.deepEqual(appServer.calls.filter(([method]) => method.startsWith("thread/")), [
    ["thread/settings/update", { threadId: "thread-a", effort: "xhigh" }],
    ["thread/resume", { threadId: "thread-a" }],
    ["thread/settings/update", { threadId: "thread-a", effort: "xhigh" }],
    ["thread/resume", { threadId: "thread-a" }],
  ]);
});
