import assert from "node:assert/strict";
import { chmod, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { OwnedThreadStore } from "../src/owned-thread-store.mjs";

test("persists only bounded unique opaque thread IDs", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-owned-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const path = join(root, "private", "owned-threads.json");
  const store = new OwnedThreadStore({ path });

  await store.replace([
    "thread-0001",
    "invalid id",
    "thread-0001",
    ...Array.from({ length: 30 }, (_, index) => `thread-${String(index + 2).padStart(4, "0")}`),
  ]);

  const loaded = await store.load();
  assert.equal(loaded.length, 24);
  assert.equal(loaded[0], "thread-0001");
  assert.equal(new Set(loaded).size, loaded.length);
  assert.equal(JSON.parse(await readFile(path, "utf8")).version, 1);
});

test("adds newest IDs first and safely ignores a damaged registry", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-owned-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const path = join(root, "owned-threads.json");
  const store = new OwnedThreadStore({ path });

  await store.add("thread-0001");
  await store.add("thread-0002");
  assert.deepEqual(await store.load(), ["thread-0002", "thread-0001"]);

  await chmod(path, 0o600);
  await writeFile(path, "not json");
  assert.deepEqual(await store.load(), []);
});
