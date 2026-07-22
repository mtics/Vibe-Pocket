import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

import { readRuntimeIdentity, resolveRuntimeIdentity } from "../src/runtime/identity.mjs";

const helper = new URL("../bin/replace-runtime.sh", import.meta.url);

test("replaces the deployed runtime with an exact identified source snapshot", async () => {
  const root = mkdtempSync(join(tmpdir(), "vibe-pocket-runtime-"));
  const source = join(root, "source");
  const target = join(root, "runtime");
  createRuntime(source);
  mkdirSync(join(source, "node_modules", "ignored"), { recursive: true });
  mkdirSync(join(target, "src"), { recursive: true });
  writeFileSync(join(source, "src", "current.mjs"), "current\n");
  writeFileSync(join(source, "node_modules", "ignored", "index.js"), "ignored\n");
  writeFileSync(join(target, "src", "obsolete.mjs"), "obsolete\n");

  const result = spawnSync("/bin/zsh", [helper.pathname, source, target], { encoding: "utf8" });

  assert.equal(result.status, 0, result.stderr);
  assert.equal(readFileSync(join(target, "src", "current.mjs"), "utf8"), "current\n");
  assert.throws(() => readFileSync(join(target, "src", "obsolete.mjs"), "utf8"), { code: "ENOENT" });
  assert.throws(() => readFileSync(join(target, "node_modules", "ignored", "index.js"), "utf8"), { code: "ENOENT" });
  const manifest = JSON.parse(readFileSync(join(target, "runtime-identity.json"), "utf8"));
  assert.match(manifest.runtimeIdentity, /^sha256:[0-9a-f]{64}$/);
  assert.equal(await readRuntimeIdentity(target), manifest.runtimeIdentity);
  const expected = spawnSync(process.execPath, [
    join(target, "src", "runtime", "identity.mjs"),
    "expected",
    target,
  ], { encoding: "utf8" });
  assert.equal(expected.status, 0, expected.stderr);
  assert.deepEqual(JSON.parse(expected.stdout), {
    ok: true,
    service: "vibe-pocket-bridge",
    runtimeIdentity: manifest.runtimeIdentity,
    protocolVersion: 12,
  });
});

test("runtime identity is deterministic and changes with exact staged contents", async () => {
  const root = mkdtempSync(join(tmpdir(), "vibe-pocket-runtime-identity-"));
  const source = join(root, "source");
  const firstTarget = join(root, "first");
  const secondTarget = join(root, "second");
  const changedTarget = join(root, "changed");
  createRuntime(source);
  writeFileSync(join(source, "src", "current.mjs"), "same bytes\n");

  replace(source, firstTarget);
  utimesSync(join(source, "src", "current.mjs"), new Date(1_000), new Date(2_000));
  replace(source, secondTarget);
  const first = await readRuntimeIdentity(firstTarget);
  const second = await readRuntimeIdentity(secondTarget);
  assert.equal(second, first);

  writeFileSync(join(source, "src", "current.mjs"), "changed bytes\n");
  replace(source, changedTarget);
  assert.notEqual(await readRuntimeIdentity(changedTarget), first);
});

test("runtime identity verification rejects deployed content mismatch", async () => {
  const root = mkdtempSync(join(tmpdir(), "vibe-pocket-runtime-mismatch-"));
  const source = join(root, "source");
  const target = join(root, "runtime");
  createRuntime(source);
  replace(source, target);

  writeFileSync(join(target, "src", "index.mjs"), "tampered\n");

  await assert.rejects(
    () => resolveRuntimeIdentity(target),
    /does not match the deployed runtime contents/,
  );
});

test("rejects an incomplete snapshot before moving the healthy runtime", () => {
  const root = mkdtempSync(join(tmpdir(), "vibe-pocket-runtime-"));
  const source = join(root, "source");
  const target = join(root, "runtime");
  mkdirSync(join(source, "src"), { recursive: true });
  mkdirSync(target, { recursive: true });
  writeFileSync(join(source, "src", "index.mjs"), "incomplete\n");
  writeFileSync(join(target, "healthy.mjs"), "healthy\n");

  const result = spawnSync("/bin/zsh", [helper.pathname, source, target], { encoding: "utf8" });

  assert.equal(result.status, 65);
  assert.match(result.stderr, /Runtime source is incomplete or unsafe/);
  assert.equal(readFileSync(join(target, "healthy.mjs"), "utf8"), "healthy\n");
});

test("leaves the previous runtime intact when the source is invalid", () => {
  const root = mkdtempSync(join(tmpdir(), "vibe-pocket-runtime-"));
  const target = join(root, "runtime");
  mkdirSync(target, { recursive: true });
  writeFileSync(join(target, "healthy.mjs"), "healthy\n");

  const result = spawnSync("/bin/zsh", [helper.pathname, join(root, "missing"), target], { encoding: "utf8" });

  assert.equal(result.status, 64);
  assert.equal(readFileSync(join(target, "healthy.mjs"), "utf8"), "healthy\n");
});

function createRuntime(root) {
  for (const path of [
    "package.json",
    "src/index.mjs",
    "src/protocol.mjs",
    "src/runtime/identity.mjs",
    "src/server/readiness.mjs",
    "bin/run-launchd.sh",
    "src/macos/host.swift",
    "src/macos/helper.swift",
    "src/macos/pairing.swift",
  ]) {
    const destination = join(root, path);
    mkdirSync(join(destination, ".."), { recursive: true });
    const moduleSources = {
      "src/protocol.mjs": new URL("../src/protocol.mjs", import.meta.url),
      "src/runtime/identity.mjs": new URL("../src/runtime/identity.mjs", import.meta.url),
      "src/server/readiness.mjs": new URL("../src/server/readiness.mjs", import.meta.url),
    };
    const contents = moduleSources[path]
      ? readFileSync(moduleSources[path], "utf8")
      : `${path}\n`;
    writeFileSync(destination, contents);
  }
}

function replace(source, target) {
  const result = spawnSync("/bin/zsh", [helper.pathname, source, target], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr);
}
