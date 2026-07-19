import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Credentials, persistCredentials } from "../../src/pairing/credentials.mjs";
import { Failure } from "../../src/server/failure.mjs";

const ROOT = "test-token-with-at-least-24-characters";
const ID = "device1234";
const SECRET = "abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
const EXPIRY = "2099-01-01T00:05:00.000Z";

test("pending credentials persist only a hash, survive restart, and activate durably", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-"));
  const path = join(directory, "paired-devices.json");
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => ID,
    randomSecret: () => SECRET,
  });
  const token = credentials.issue(EXPIRY);

  assert.equal(token, `vp1.${ID}.${SECRET}`);
  assert.equal(credentials.accepts(ROOT), true);
  assert.equal(credentials.accepts(token), true);
  const stored = await readFile(path, "utf8");
  assert.equal(stored.includes(token), false);
  assert.equal(stored.includes(SECRET), false);
  assert.deepEqual(JSON.parse(stored).devices[0].state, "pending");
  assert.equal(JSON.parse(stored).version, 2);
  assert.equal((await stat(path)).mode & 0o777, 0o600);

  const reloaded = new Credentials({ path, rootToken: ROOT });
  assert.equal(reloaded.accepts(token), true);
  assert.equal(reloaded.accepts(`${token}x`), false);
  const principal = reloaded.resolve(token);
  assert.equal(reloaded.resolve(ROOT).role, "root");
  assert.equal(principal.role, "device");
  assert.equal(principal.state, "pending");
  assert.equal(principal.expiresAt, EXPIRY);
  assert.equal(principal.revocable, true);
  assert.equal(principal.valid(), true);
  assert.equal(reloaded.activate(token), true);
  assert.equal(reloaded.activate(token), true);
  assert.equal(reloaded.resolve(token).state, "active");
  const activated = JSON.parse(await readFile(path, "utf8")).devices[0];
  assert.equal(activated.state, "active");
  assert.equal("expiresAt" in activated, false);
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

  assert.throws(() => credentials.issue(EXPIRY), /write failed/);
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
  const token = credentials.issue(EXPIRY);
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
  const token = credentials.issue(EXPIRY);
  assert.equal(credentials.accepts(token), true);
  assert.equal(new Credentials({ path, rootToken: ROOT }).accepts(token), true);

  assert.equal(credentials.revoke(token), true);
  assert.equal(credentials.accepts(token), false);
  assert.equal(new Credentials({ path, rootToken: ROOT }).accepts(token), false);
  assert.equal(syncCalls, 4);
});

test("migrates v1 credentials as active schema-v2 records", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-v1-"));
  const path = join(directory, "paired-devices.json");
  const token = `vp1.${ID}.${SECRET}`;
  await writeFile(path, `${JSON.stringify({
    version: 1,
    devices: [{ id: ID, digest: hash(token), createdAt: "2026-01-01T00:00:00.000Z" }],
  })}\n`);

  const credentials = new Credentials({ path, rootToken: ROOT });

  assert.equal(credentials.resolve(token).state, "active");
  const migrated = JSON.parse(await readFile(path, "utf8"));
  assert.equal(migrated.version, 2);
  assert.equal(migrated.devices[0].state, "active");
});

test("pending expiry invalidates live principals and is pruned on restart", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-expiry-"));
  const path = join(directory, "paired-devices.json");
  let now = 1_000;
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    now: () => now,
    randomId: () => ID,
    randomSecret: () => SECRET,
  });
  const token = credentials.issue(1_100);
  const principal = credentials.resolve(token);
  now = 1_100;

  assert.equal(principal.valid(), false);
  assert.equal(credentials.accepts(token), false);
  const reloaded = new Credentials({ path, rootToken: ROOT, now: () => now });
  assert.equal(reloaded.resolve(token), null);
  assert.deepEqual(JSON.parse(await readFile(path, "utf8")).devices, []);
});

test("never evicts active credentials when capacity is reached", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-capacity-"));
  const path = join(directory, "paired-devices.json");
  let sequence = 0;
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => `device${String(sequence += 1).padStart(4, "0")}`,
    randomSecret: () => SECRET,
  });
  const firstToken = credentials.issue(EXPIRY);
  credentials.activate(firstToken);
  const firstPrincipal = credentials.resolve(firstToken);
  for (let index = 1; index < 24; index += 1) {
    const token = credentials.issue(EXPIRY);
    credentials.activate(token);
  }

  assert.throws(
    () => credentials.issue(EXPIRY),
    (error) => error instanceof Failure && error.code === "device_capacity_reached",
  );
  assert.equal(firstPrincipal.valid(), true);
  assert.equal(credentials.accepts(firstToken), true);
  assert.equal(JSON.parse(await readFile(path, "utf8")).devices.length, 24);
});

test("keeps a credential pending when activation persistence fails", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-credentials-activate-failure-"));
  const path = join(directory, "paired-devices.json");
  let fail = false;
  const credentials = new Credentials({
    path,
    rootToken: ROOT,
    randomId: () => ID,
    randomSecret: () => SECRET,
    commit: (target, entries) => {
      if (fail) throw new Error("activation write failed");
      persistCredentials(target, entries);
    },
  });
  const token = credentials.issue(EXPIRY);
  fail = true;

  assert.throws(() => credentials.activate(token), /activation write failed/);
  assert.equal(credentials.resolve(token).state, "pending");
  assert.equal(new Credentials({ path, rootToken: ROOT }).resolve(token).state, "pending");
});

function hash(value) {
  return createHash("sha256").update(value).digest("base64url");
}
