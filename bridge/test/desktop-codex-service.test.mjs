import assert from "node:assert/strict";
import test from "node:test";

import { DesktopCodexService } from "../src/desktop-codex-service.mjs";

class FakeEvents {
  published = [];
  publish(type, data) { this.published.push({ type, data }); }
}

class FakeDesktop {
  calls = [];
  async status() {
    return {
      available: true,
      message: "Desktop task ready.",
      controls: { voice: true, stop: true, "new-task": true, approve: false, reject: false },
    };
  }
  async attach() { this.calls.push(["attach"]); }
  async press(control) { this.calls.push(["press", control]); }
}

test("routes fixed Vibe Pocket hardware controls into the visible desktop Codex task", async () => {
  const desktop = new FakeDesktop();
  const service = new DesktopCodexService({
    desktop,
    events: new FakeEvents(),
    workspaces: { research: "/Users/lizhw/Research" },
  });
  await service.start();

  const snapshot = await service.snapshot();
  assert.equal(snapshot.focusSessionId, "desktop-codex");
  assert.equal(snapshot.sessions.length, 1);
  assert.equal(snapshot.sessions[0].workspaceId, "current desktop task");
  assert.equal(snapshot.controls.voice, true);

  await service.command({ kind: "start", workspaceId: "research" }, "attach-1");
  await service.command({ kind: "voice" }, "voice-1");
  await service.command({ kind: "stop" }, "stop-1");
  await service.command({ kind: "accept" }, "accept-1");
  await service.command({ kind: "reject" }, "reject-1");
  await service.command({ kind: "new_task" }, "new-1");

  assert.deepEqual(desktop.calls, [
    ["attach"],
    ["press", "voice"],
    ["press", "stop"],
    ["press", "approve"],
    ["press", "reject"],
    ["press", "new-task"],
  ]);
});

test("returns a precise error when a caller selects an imaginary desktop session", async () => {
  const service = new DesktopCodexService({
    desktop: new FakeDesktop(),
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
  });
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "focus", sessionId: "history-thread-id" }, "focus-foreign"),
    (error) => error.code === "unknown_session",
  );
});

test("keeps the desktop target available when an individual control is absent", async () => {
  class PendingControlDesktop extends FakeDesktop {
    async press() { throw new Error("The ChatGPT Codex Stop control is not currently visible."); }
  }

  const service = new DesktopCodexService({
    desktop: new PendingControlDesktop(),
    events: new FakeEvents(),
    workspaces: { default: "/tmp/project" },
  });
  await service.start();

  await assert.rejects(
    () => service.command({ kind: "stop" }, "stop-missing-control"),
    (error) => error.code === "desktop_action_failed",
  );
  const snapshot = await service.snapshot();
  assert.equal(snapshot.status.state, "ready");
  assert.equal(snapshot.sessions[0].state, "active");
});
