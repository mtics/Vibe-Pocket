import assert from "node:assert/strict";
import { mkdtemp, readFile, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Credentials } from "../../src/pairing/credentials.mjs";

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
  assert.equal(reloaded.revoke(token), true);
  assert.equal(reloaded.accepts(token), false);
  assert.equal(reloaded.accepts(ROOT), true);
});
