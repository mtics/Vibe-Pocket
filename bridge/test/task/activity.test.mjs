import assert from "node:assert/strict";
import { appendFile, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Activity } from "../../src/task/activity.mjs";

function event(type) {
  return `${JSON.stringify({ timestamp: new Date().toISOString(), type: "event_msg", payload: { type } })}\n`;
}

test("derives and incrementally updates running state from Codex rollout lifecycle markers", async (t) => {
  const sessionsRoot = await mkdtemp(join(tmpdir(), "vibe-pocket-rollouts-"));
  t.after(() => rm(sessionsRoot, { recursive: true, force: true }));
  const path = join(sessionsRoot, "rollout-thread-a.jsonl");
  const thread = { id: "thread-a", path };
  const reader = new Activity({ sessionsRoot, chunkSize: 64 });

  await writeFile(path, event("task_started"));
  assert.deepEqual(await reader.statesFor([thread]), new Map([["thread-a", "thinking"]]));

  await appendFile(path, `${JSON.stringify({ type: "event_msg", payload: { type: "agent_reasoning" } })}\n`);
  assert.deepEqual(await reader.statesFor([thread]), new Map([["thread-a", "thinking"]]));

  await appendFile(path, event("task_complete"));
  assert.deepEqual(await reader.statesFor([thread]), new Map());

  await appendFile(path, event("task_started"));
  assert.deepEqual(await reader.statesFor([thread]), new Map([["thread-a", "thinking"]]));

  await appendFile(path, event("turn_aborted"));
  assert.deepEqual(await reader.statesFor([thread]), new Map());
});

test("ignores rollout paths outside the Codex sessions root", async (t) => {
  const sessionsRoot = await mkdtemp(join(tmpdir(), "vibe-pocket-rollouts-root-"));
  const outsideRoot = await mkdtemp(join(tmpdir(), "vibe-pocket-rollouts-outside-"));
  t.after(() => Promise.all([
    rm(sessionsRoot, { recursive: true, force: true }),
    rm(outsideRoot, { recursive: true, force: true }),
  ]));
  const outsidePath = join(outsideRoot, "rollout-thread-a.jsonl");
  await writeFile(outsidePath, event("task_started"));
  const reader = new Activity({ sessionsRoot });

  assert.deepEqual(await reader.statesFor([{ id: "thread-a", path: outsidePath }]), new Map());
});
