import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import test from "node:test";

const helperUrl = new URL("../../src/macos/helper.swift", import.meta.url);
const hostUrl = new URL("../../src/macos/host.swift", import.meta.url);
const installerUrl = new URL("../../bin/install-launch-agent.sh", import.meta.url);
const launchScriptUrl = new URL("../../bin/run-launchd.sh", import.meta.url);
const cleanupScriptUrl = new URL("../../bin/cleanup-stale-listener.sh", import.meta.url);
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

test("mode switching targets ChatGPT without taking the foreground", async () => {
  const source = await readFile(helperUrl, "utf8");
  const modeCase = source.slice(
    source.indexOf('case "plan-mode"'),
    source.indexOf('case "model-picker"'),
  );

  assert.match(source, /postToPid\(processIdentifier\)/);
  assert.match(modeCase, /togglePlanMode\(in: application\)/);
  assert.match(modeCase, /planModeIsActive[\s\S]*?confirmed/);
  assert.match(modeCase, /"settings": \["mode": \["available": true, "label": confirmed\]\]/);
  assert.doesNotMatch(modeCase, /verifyForeground|activate\(/);
});

test("composer controls and Agent focus identity use the focused Codex window", async () => {
  const source = await readFile(helperUrl, "utf8");
  const statusReply = source.slice(
    source.indexOf("private func statusReply"),
    source.indexOf("private func press", source.indexOf("private func statusReply")),
  );

  assert.match(source, /kAXFocusedWindowAttribute/);
  assert.match(source, /codexArea\(in: codexScope\(for: application\)\)/);
  assert.match(statusReply, /let scope = codexScope\(for: application\)/);
  assert.match(statusReply, /focusedAgentTarget\(in: scope/);
  assert.doesNotMatch(statusReply, /agentTargets\(/);
  assert.doesNotMatch(statusReply, /AXUIElementCreateApplication/);
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
precondition(ReasoningLevel.parse(from: "5.6 Sol Max") == .max)
precondition(ReasoningLevel.parse(from: "5.6 Sol Ultra") == .ultra)
precondition(!ReasoningLevel.ultra.canIncrease)
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

test("model selection uses a semantic menu target and backward delete has a dedicated key", async () => {
  const source = await readFile(helperUrl, "utf8");

  assert.match(source, /case "model-picker":[\s\S]*?openComposerMenu\(control\)/);
  assert.match(source, /case "select-model":[\s\S]*?selectModel\(modelID/);
  assert.match(source, /private func selectModel[\s\S]*?openSubmenu\(menuItem\)[\s\S]*?modelOption[\s\S]*?chooseMenuItem\(targetElement\)/);
  assert.match(source, /private func selectModel[\s\S]*?postKey\(53, to: application\.processIdentifier\)/);
  assert.match(source, /private func selectReasoning[\s\S]*?postKey\(53, to: application\.processIdentifier\)/);
  assert.match(source, /case "select-reasoning":[\s\S]*?selectReasoning\(level/);
  assert.match(source, /observedLevel == requested/);
  assert.doesNotMatch(source, /reasoningMenuItem\(in: root\) == nil \? true : nil/);
  assert.match(source, /case "delete-backward":[\s\S]*?focusPrompt[\s\S]*?postKey\(51\)/);
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

test("every launch cleans only an exact orphaned bridge listener", async () => {
  const installer = await readFile(installerUrl, "utf8");
  const launcher = await readFile(launchScriptUrl, "utf8");
  const cleanup = await readFile(cleanupScriptUrl, "utf8");

  assert.match(installer, /cleanup-stale-listener\.sh[\s\S]*?--all-exact/);
  assert.match(launcher, /cleanup-stale-listener\.sh/);
  assert.match(cleanup, /processCwd[\s\S]*?RUNTIME_DIR/);
  assert.match(cleanup, /processCommand[\s\S]*?node\\ src\/index\.mjs/);
  assert.match(cleanup, /PROCESS_PARENT[\s\S]*?"1"/);
});
