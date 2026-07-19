import assert from "node:assert/strict";
import { appendFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Context } from "../../src/task/context.mjs";

function settings(model, reasoningEffort) {
  return `${JSON.stringify({
    type: "event_msg",
    payload: {
      type: "thread_settings_applied",
      thread_settings: { model, reasoning_effort: reasoningEffort },
    },
  })}\n`;
}

test("reads and incrementally refreshes the authoritative task settings", async (t) => {
  const sessionsRoot = await mkdtemp(join(tmpdir(), "vibe-pocket-context-"));
  t.after(() => rm(sessionsRoot, { recursive: true, force: true }));
  const path = join(sessionsRoot, "rollout-thread-a.jsonl");
  const context = new Context({ sessionsRoot });

  await writeFile(path, settings("gpt-sol", "xhigh"));
  assert.deepEqual(await context.read({ path }), { model: "gpt-sol", reasoningEffort: "xhigh" });

  await appendFile(path, `${JSON.stringify({ type: "event_msg", payload: { type: "task_started" } })}\n`);
  assert.deepEqual(await context.read({ path }), { model: "gpt-sol", reasoningEffort: "xhigh" });

  await appendFile(path, settings("gpt-luna", "ultra"));
  assert.deepEqual(await context.read({ path }), { model: "gpt-luna", reasoningEffort: "ultra" });
});

test("ignores rollout paths outside the Codex sessions root", async (t) => {
  const sessionsRoot = await mkdtemp(join(tmpdir(), "vibe-pocket-context-root-"));
  const outsideRoot = await mkdtemp(join(tmpdir(), "vibe-pocket-context-outside-"));
  t.after(() => Promise.all([
    rm(sessionsRoot, { recursive: true, force: true }),
    rm(outsideRoot, { recursive: true, force: true }),
  ]));
  const path = join(outsideRoot, "rollout-thread-a.jsonl");
  await writeFile(path, settings("gpt-sol", "high"));

  assert.equal(await new Context({ sessionsRoot }).read({ path }), null);
});
