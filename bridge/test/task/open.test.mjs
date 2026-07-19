import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import { open } from "../../src/task/open.mjs";

function recorder() {
  const calls = [];
  return {
    calls,
    spawnProcess(command, args, options) {
      calls.push({ command, args, options });
      const child = new EventEmitter();
      queueMicrotask(() => child.emit("exit", 0, null));
      return child;
    },
  };
}

test("opens an exact Codex thread through each platform URI handler", async () => {
  const cases = [
    ["darwin", "open", ["-g", "-b", "com.openai.codex", "codex://threads/019f-test"]],
    ["win32", "cmd.exe", ["/d", "/s", "/c", "start", "", "codex://threads/019f-test"]],
    ["linux", "xdg-open", ["codex://threads/019f-test"]],
  ];

  for (const [platform, command, args] of cases) {
    const recorded = recorder();
    assert.deepEqual(await open("019f-test", { platform, spawnProcess: recorded.spawnProcess }), {
      url: "codex://threads/019f-test",
    });
    assert.deepEqual(recorded.calls, [{ command, args, options: { stdio: "ignore" } }]);
  }
});

test("rejects unsafe thread IDs before launching a URI handler", async () => {
  const recorded = recorder();
  await assert.rejects(
    () => open("thread/../../settings", { spawnProcess: recorded.spawnProcess }),
    /invalid thread ID/i,
  );
  assert.deepEqual(recorded.calls, []);
});
