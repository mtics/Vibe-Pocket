import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { EVENTS, install, remove } from "../../src/codex/hooks.mjs";

test("atomically merges Codex hooks, preserves foreign groups, and avoids unchanged rewrites", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-hooks-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const hooksPath = join(directory, "hooks.json");
  const reporterPath = "/Library/Application Support/Vibe Pocket/report-codex-hook.sh";
  const foreign = { matcher: "shell", hooks: [{ type: "command", command: "foreign-hook" }] };
  await writeFile(hooksPath, `${JSON.stringify({ custom: true, hooks: { PreToolUse: [foreign] } }, null, 2)}\n`);

  assert.equal(await install({ hooksPath, reporterPath }), "changed");
  const installed = JSON.parse(await readFile(hooksPath, "utf8"));
  assert.equal(installed.custom, true);
  assert.deepEqual(installed.hooks.PreToolUse[0], foreign);
  assert.deepEqual(Object.keys(installed.hooks).sort(), EVENTS.toSorted());
  for (const event of EVENTS) {
    const ours = installed.hooks[event].at(-1).hooks[0];
    assert.match(ours.command, /VIBE_POCKET_CODEX_HOOK=1/);
    assert.match(ours.command, new RegExp(`'${event}'$`));
    assert.equal(ours.timeout, event === "PermissionRequest" ? 135 : 10);
  }

  const before = await stat(hooksPath, { bigint: true });
  assert.equal(await install({ hooksPath, reporterPath }), "unchanged");
  const after = await stat(hooksPath, { bigint: true });
  assert.equal(after.mtimeNs, before.mtimeNs);

  assert.equal(await remove({ hooksPath }), "changed");
  const removed = JSON.parse(await readFile(hooksPath, "utf8"));
  assert.equal(removed.custom, true);
  assert.deepEqual(removed.hooks, { PreToolUse: [foreign] });
  const removedBefore = await stat(hooksPath, { bigint: true });
  assert.equal(await remove({ hooksPath }), "unchanged");
  const removedAfter = await stat(hooksPath, { bigint: true });
  assert.equal(removedAfter.mtimeNs, removedBefore.mtimeNs);
});

test("refuses to replace malformed Codex hook configuration", async (t) => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-hooks-bad-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  const hooksPath = join(directory, "hooks.json");
  await writeFile(hooksPath, "{ definitely not json\n");

  await assert.rejects(
    install({ hooksPath, reporterPath: "/tmp/reporter.sh" }),
    /left the existing Codex hooks untouched/,
  );
  assert.equal(await readFile(hooksPath, "utf8"), "{ definitely not json\n");
});
