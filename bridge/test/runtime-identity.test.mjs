import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import test from "node:test";

import { readyPayload, readyPayloadMatches } from "../src/runtime/identity.mjs";

const identityTool = new URL("../src/runtime/identity.mjs", import.meta.url);
const installer = new URL("../bin/install-launch-agent.sh", import.meta.url);
const RUNTIME_IDENTITY = `sha256:${"c".repeat(64)}`;

test("readiness matching requires the exact bounded identity and protocol payload", () => {
  const expected = readyPayload(RUNTIME_IDENTITY, 9);
  assert.equal(readyPayloadMatches(expected, { ...expected }), true);
  assert.equal(readyPayloadMatches(expected, {
    ...expected,
    runtimeIdentity: `sha256:${"d".repeat(64)}`,
  }), false);
  assert.equal(readyPayloadMatches(expected, { ...expected, protocolVersion: 8 }), false);
  assert.equal(readyPayloadMatches(expected, { ...expected, controller: {} }), false);
});

test("readiness matcher CLI fails closed on identity mismatch", () => {
  const expected = readyPayload(RUNTIME_IDENTITY, 9);
  const mismatch = { ...expected, runtimeIdentity: `sha256:${"e".repeat(64)}` };
  const result = spawnSync(process.execPath, [identityTool.pathname, "matches", JSON.stringify(expected)], {
    input: JSON.stringify(mismatch),
    encoding: "utf8",
  });

  assert.equal(result.status, 1, result.stderr);
});

test("installer waits for an exact /readyz response from the staged runtime", async () => {
  const source = await readFile(installer, "utf8");
  assert.match(source, /IDENTITY_TOOL=.*runtime\/identity\.mjs/);
  assert.match(source, /IDENTITY_TOOL\" expected \"\$RUNTIME_DIR/);
  assert.match(source, /\/readyz/);
  assert.match(source, /IDENTITY_TOOL\" matches \"\$EXPECTED_READY/);
  const readinessLoop = source.slice(source.indexOf("READY=0"));
  assert.doesNotMatch(readinessLoop, /\/healthz/);
});
