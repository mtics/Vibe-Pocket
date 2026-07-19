import assert from "node:assert/strict";
import { mkdtemp, readFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Credentials, persistCredentials } from "../../src/pairing/credentials.mjs";

const ROOT = "test-token-with-at-least-24-characters";
const ID = "device1234";
const SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";

test("device credentials persist only a hash and survive restart", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-"));
  const path = join(directory, "paired-devices.json");
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => ID,
    randomSecret: () => SECRET,
  });
  const token = credentials.issue();

  assert.equal(token, `vp1.${ID}.${SECRET}`);
  assert.equal(credentials.accepts(ROOT), true);
  assert.equal(credentials.accepts(token), true);
  const stored = await readFile(path, "utf8");
  assert.equal(stored.includes(token), false);
  assert.equal(stored.includes(SECRET), false);
  assert.equal((await stat(path)).mode & 0o777, 0o600);

  const reloaded = new Credentials({ path, rootToken: ROOT });
  assert.equal(reloaded.accepts(token), true);
  assert.equal(reloaded.accepts(`${token}x`), false);
  const principal = reloaded.resolve(token);
  assert.equal(reloaded.resolve(ROOT).role, "root");
  assert.equal(principal.role, "device");
  assert.equal(principal.revocable, true);
  assert.equal(principal.valid(), true);
  assert.equal(reloaded.revoke(token), true);
  assert.equal(principal.valid(), false);
  assert.equal(reloaded.accepts(token), false);
  assert.equal(reloaded.accepts(ROOT), true);
});

test("does not publish an issued credential when durable persistence fails", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-issue-failure-"));
  const path = join(directory, "paired-devices.json");
  const token = `vp1.${ID}.${SECRET}`;
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => ID,
    randomSecret: () => SECRET,
    commit: () => { throw new Error("write failed"); },
  });

  assert.throws(() => credentials.issue(), /write failed/);
  assert.equal(credentials.accepts(token), false);
  const reloaded = new Credentials({ path, rootToken: ROOT });
  assert.equal(reloaded.accepts(token), false);
});

test("keeps memory and restart state aligned when revoke persistence fails", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-revoke-failure-"));
  const path = join(directory, "paired-devices.json");
  let failRename = false;
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => ID,
    randomSecret: () => SECRET,
    commit: (target, entries) => {
      if (failRename) throw new Error("rename failed");
      persistCredentials(target, entries);
    },
  });
  const token = credentials.issue();
  const principal = credentials.resolve(token);
  failRename = true;

  assert.throws(() => credentials.revoke(token), /rename failed/);
  assert.equal(principal.valid(), true);
  assert.equal(credentials.accepts(token), true);
  const reloaded = new Credentials({ path, rootToken: ROOT });
  assert.equal(reloaded.accepts(token), true);
});

test("treats rename as committed when the following directory sync fails", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-directory-sync-"));
  const path = join(directory, "paired-devices.json");
  let failDirectorySync = false;
  let syncCalls = 0;
  const commit = (target, entries) => persistCredentials(target, entries, {
    sync: () => {
      syncCalls += 1;
      if (failDirectorySync && syncCalls % 2 === 0) throw new Error("directory sync failed");
    },
  });
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => ID,
    randomSecret: () => SECRET,
    commit,
  });

  failDirectorySync = true;
  const token = credentials.issue();
  assert.equal(credentials.accepts(token), true);
  assert.equal(new Credentials({ path, rootToken: ROOT }).accepts(token), true);

  assert.equal(credentials.revoke(token), true);
  assert.equal(credentials.accepts(token), false);
  assert.equal(new Credentials({ path, rootToken: ROOT }).accepts(token), false);
  assert.equal(syncCalls, 4);
});

test("invalidates the live principal when device-cap eviction commits", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-eviction-"));
  const path = join(directory, "paired-devices.json");
  let sequence = 0;
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => `device${String(sequence += 1).padStart(4, "0")}`,
    randomSecret: () => SECRET,
  });
  const firstToken = credentials.issue();
  const firstPrincipal = credentials.resolve(firstToken);
  for (let index = 1; index < 25; index += 1) credentials.issue();

  assert.equal(firstPrincipal.valid(), false);
  assert.equal(credentials.accepts(firstToken), false);
});
