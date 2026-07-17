import assert from "node:assert/strict";
import test from "node:test";
import { EventEmitter } from "node:events";

import { PocketControllerService, PocketError } from "../src/pocket-controller-service.mjs";

class FakePty extends EventEmitter {
  writes = [];
  onData(callback) { this.on("data", callback); }
  onExit(callback) { this.on("exit", callback); }
  write(value) { this.writes.push(value); }
  kill() { this.emit("exit", { exitCode: 0 }); }
}

class FakeEvents {
  published = [];
  publish(type, data) { this.published.push({ type, data }); }
}

test("starts a Codex PTY only in a configured workspace and routes controller bytes", async () => {
  const ptys = [];
  const service = new PocketControllerService({
    events: new FakeEvents(),
    workspaces: { research: "/Users/lizhw/Research" },
    spawnPty: (_command, _args, options) => {
      assert.equal(options.cwd, "/Users/lizhw/Research");
      const process = new FakePty();
      ptys.push(process);
      return process;
    },
  });
  await service.start();

  await service.command({ kind: "start", workspaceId: "research" }, "start-1");
  await service.command({ kind: "workflow", workflowId: "review" }, "workflow-1");
  await service.command({ kind: "navigate", direction: "down" }, "nav-1");
  await service.command({ kind: "accept" }, "accept-1");
  await service.command({ kind: "reject" }, "reject-1");

  assert.equal(ptys.length, 1);
  assert.match(ptys[0].writes[0], /Review the current change/);
  assert.equal(ptys[0].writes[1], "\u001B[B");
  assert.equal(ptys[0].writes[2], "\r");
  assert.equal(ptys[0].writes[3], "\u001B");
});

test("never exposes historical Codex threads and keeps focus within owned PTY sessions", async () => {
  const service = new PocketControllerService({
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
    spawnPty: () => new FakePty(),
  });
  await service.start();

  const empty = await service.snapshot();
  assert.deepEqual(empty.sessions, []);
  assert.equal(empty.focusSessionId, null);

  await assert.rejects(
    () => service.command({ kind: "focus", sessionId: "history-thread-id" }, "focus-foreign"),
    (error) => error instanceof PocketError && error.code === "unknown_session",
  );
});

test("a first prompt starts an owned PTY in the default workspace", async () => {
  const process = new FakePty();
  const service = new PocketControllerService({
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
    spawnPty: (_command, _args, options) => {
      assert.equal(options.cwd, "/tmp/project");
      return process;
    },
  });
  await service.start();

  await service.command({ kind: "prompt", text: "Inspect the repository." }, "first-prompt");
  const snapshot = await service.snapshot();
  assert.equal(snapshot.sessions.length, 1);
  assert.equal(process.writes[0], "Inspect the repository.\r");
});
