import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import test from "node:test";

const helper = new URL("../bin/replace-runtime.sh", import.meta.url);

test("replaces the deployed runtime with an exact source snapshot", () => {
  const root = mkdtempSync(join(tmpdir(), "vibe-pocket-runtime-"));
  const source = join(root, "source");
  const target = join(root, "runtime");
  mkdirSync(join(source, "src"), { recursive: true });
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
