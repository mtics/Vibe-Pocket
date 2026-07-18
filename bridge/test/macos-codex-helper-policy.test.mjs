import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const helperUrl = new URL("../src/macos-codex-helper.swift", import.meta.url);
const hostUrl = new URL("../src/macos-bridge-host.swift", import.meta.url);
const installerUrl = new URL("../bin/install-launch-agent.sh", import.meta.url);
const execFileAsync = promisify(execFile);

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

test("composer controls and Agent focus identity use the focused Codex window", async () => {
  const source = await readFile(helperUrl, "utf8");

  assert.match(source, /kAXFocusedWindowAttribute/);
  assert.match(source, /codexArea\(in: codexScope\(for: application\)\)/);
  assert.match(source, /let selectedTaskTitle = focusedTaskTitle\(in: codexScope\(for: application\)\)/);
  assert.match(source, /let root = AXUIElementCreateApplication\(application\.processIdentifier\)[\s\S]*?focusedTaskTitle: selectedTaskTitle/);
});

test("production Swift semantics keep minimum reasoning adjustable upward", async (context) => {
  if (process.platform !== "darwin") {
    context.skip("The macOS Accessibility helper is compiled only on macOS.");
    return;
  }
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-reasoning-"));
  try {
    const source = await readFile(helperUrl, "utf8");
    const probeSource = `${source}

guard let minimum = ReasoningLevel.parse(from: "5.6 Sol 最小") else {
  fatalError("The localized minimum label was not parsed.")
}
precondition(minimum == .minimal)
precondition(minimum.canIncrease)
precondition(!minimum.canDecrease)

guard let medium = ReasoningLevel.parse(from: "5.6 Luna 中") else {
  fatalError("The localized medium label was not parsed.")
}
precondition(medium == .medium)
precondition(medium.canIncrease && medium.canDecrease)
precondition(ReasoningLevel.modelLabel(from: "5.6 Luna 中") == "5.6 Luna")
precondition(ReasoningLevel.parse(from: "5.7 Preview") == nil)
precondition(agentStatePriority("waiting") < agentStatePriority("executing"))
precondition(agentStatePriority("executing") < agentStatePriority("idle"))
precondition(maxAgentTargets == 24)
`;
    const mainPath = join(directory, "main.swift");
    const executablePath = join(directory, "reasoning-probe");
    await writeFile(mainPath, probeSource);
    await execFileAsync("/usr/bin/swiftc", [mainPath, "-o", executablePath]);
    await execFileAsync(executablePath);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("reasoning availability comes from structure before directional semantics", async () => {
  const source = await readFile(helperUrl, "utf8");

  assert.match(
    source,
    /"reasoning": reasoningControl\(in: index\) != nil && !isExecuting/,
  );
  assert.match(source, /let reasoningAvailable = controls\["reasoning"\] == true/);
  assert.match(
    source,
    /"canIncrease": reasoningAvailable && \(reasoning\?\.level\?\.canIncrease \?\? true\)/,
  );
  assert.match(
    source,
    /"canDecrease": reasoningAvailable && \(reasoning\?\.level\?\.canDecrease \?\? true\)/,
  );
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
