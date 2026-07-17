import assert from "node:assert/strict";
import test from "node:test";
import { EventEmitter } from "node:events";

import { PocketError, PocketService } from "../src/pocket-service.mjs";

class FakeAppServer extends EventEmitter {
  constructor() {
    super();
    this.calls = [];
    this.responses = [];
    this.threads = [];
  }

  async start() {}

  async request(method, params) {
    this.calls.push({ method, params });
    if (method === "thread/start") return { thread: { id: "thread-1" } };
    if (method === "turn/start") return { turn: { id: "turn-1" } };
    if (method === "thread/list") return { data: this.threads };
    return {};
  }

  respond(id, result) {
    this.responses.push({ id, result });
  }

  respondWithError(id, code, message) {
    this.responses.push({ id, error: { code, message } });
  }
}

class FakeEvents {
  constructor() { this.published = []; }
  publish(type, data) { this.published.push({ type, data }); }
}

test("starts only in an M5-configured workspace and retries safely", async () => {
  const appServer = new FakeAppServer();
  const service = new PocketService({
    appServer,
    events: new FakeEvents(),
    workspaces: { research: "/Users/lizhw/Research" },
  });
  await service.start();

  const first = await service.command(
    { kind: "start", workspaceId: "research", prompt: "Review the failing test." },
    "same-request",
  );
  const second = await service.command(
    { kind: "start", workspaceId: "research", prompt: "Review the failing test." },
    "same-request",
  );

  assert.equal(first.commandId, second.commandId);
  assert.deepEqual(appServer.calls.map((call) => call.method), ["thread/start", "turn/start"]);
  assert.equal(appServer.calls[0].params.cwd, "/Users/lizhw/Research");
});

test("approval decisions are single-use and bind the displayed intent", async () => {
  const appServer = new FakeAppServer();
  const service = new PocketService({
    appServer,
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
  });
  await service.start();
  appServer.threads = [{
    id: "thread-1",
    name: "Release verification",
    preview: "Verify the deployment.",
    cwd: "/tmp/project",
    status: { type: "active", activeFlags: [] },
    updatedAt: 1_784_354_400,
  }];
  appServer.emit("serverRequest", {
    id: "rpc-approval-1",
    method: "item/commandExecution/requestApproval",
    params: { threadId: "thread-1", turnId: "turn-1", command: "git status" },
  });
  const snapshot = await service.snapshot();
  const approval = snapshot.threads[0].approval;
  assert.equal(approval.summary, "git status");

  await service.command({
    kind: "resolve_approval",
    approvalId: approval.id,
    intentHash: approval.intentHash,
    decision: "allow",
  }, "allow-once");
  assert.deepEqual(appServer.responses, [{
    id: "rpc-approval-1",
    result: { decision: "accept" },
  }]);

  await assert.rejects(
    () => service.command({
      kind: "resolve_approval",
      approvalId: approval.id,
      intentHash: approval.intentHash,
      decision: "allow",
    }, "allow-again"),
    (error) => error instanceof PocketError && error.code === "approval_already_resolved",
  );
});

test("shows and resumes only threads inside a configured workspace", async () => {
  const appServer = new FakeAppServer();
  const service = new PocketService({
    appServer,
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
  });
  appServer.threads = [
    {
      id: "allowed-thread",
      preview: "A task inside the approved workspace.",
      cwd: "/tmp/project/feature",
      status: { type: "idle" },
      updatedAt: 1_784_354_400,
    },
    {
      id: "blocked-thread",
      preview: "A task in an unrelated workspace.",
      cwd: "/Users/lizhw/Documents/Workspace/Research/Mine/idea4JLU",
      status: { type: "idle" },
      updatedAt: 1_784_354_400,
    },
  ];
  await service.start();

  const snapshot = await service.snapshot();
  assert.equal(snapshot.focusThreadId, null);
  assert.deepEqual(snapshot.threads.map((thread) => thread.id), ["allowed-thread"]);

  await assert.rejects(
    () => service.command({ kind: "message", threadId: "blocked-thread", prompt: "Continue." }, "blocked-message"),
    (error) => error instanceof PocketError && error.code === "thread_not_allowed",
  );
});

test("rejects a command without an idempotency key", async () => {
  const service = new PocketService({
    appServer: new FakeAppServer(),
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
  });
  await assert.rejects(
    () => service.command({ kind: "focus", threadId: "thread-1" }),
    (error) => error instanceof PocketError && error.code === "idempotency_key_required",
  );
});
