import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const helperUrl = new URL("../src/macos-codex-helper.swift", import.meta.url);
const hostUrl = new URL("../src/macos-bridge-host.swift", import.meta.url);
const installerUrl = new URL("../bin/install-launch-agent.sh", import.meta.url);

test("never synthesizes pointer movement for Codex controls", async () => {
  const source = await readFile(helperUrl, "utf8");

  assert.doesNotMatch(source, /CGWarpMouseCursorPosition|mouseMoved|leftMouseDown|leftMouseUp/);
});

test("only the explicit attach action may activate ChatGPT", async () => {
  const source = await readFile(helperUrl, "utf8");
  const activations = source.match(/desktop\(activateDesktop: true\)/g) ?? [];

  assert.equal(activations.length, 1);
  assert.match(source, /case "attach":[\s\S]*?desktop\(activateDesktop: true\)/);
});

test("bridge child inherits normal termination signals", async () => {
  const source = await readFile(hostUrl, "utf8");
  const childRun = source.indexOf("try child.run()");
  const ignoreTermination = source.indexOf("signal(SIGTERM, SIG_IGN)");

  assert.notEqual(childRun, -1);
  assert.notEqual(ignoreTermination, -1);
  assert.ok(childRun < ignoreTermination);
  assert.match(source, /asyncAfter[\s\S]*?Darwin\.kill\(childPID, SIGKILL\)/);
});

test("installer cleans only an exact stale bridge listener", async () => {
  const source = await readFile(installerUrl, "utf8");

  assert.match(source, /PROCESS_CWD[\s\S]*?RUNTIME_DIR/);
  assert.match(source, /PROCESS_COMMAND[\s\S]*?node src\/index\.mjs/);
});
