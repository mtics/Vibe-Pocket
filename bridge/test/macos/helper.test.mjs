import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

const helperUrl = new URL("../../src/macos/helper.swift", import.meta.url);
const hostUrl = new URL("../../src/macos/host.swift", import.meta.url);
const installerUrl = new URL("../../bin/install-launch-agent.sh", import.meta.url);
const launchScriptUrl = new URL("../../bin/run-launchd.sh", import.meta.url);
const cleanupScriptUrl = new URL("../../bin/cleanup-stale-listener.sh", import.meta.url);
const execFileAsync = promisify(execFile);

function callsNamed(source, name) {
  const calls = [];
  const pattern = new RegExp(`\\b${name}\\s*\\(`, "g");
  for (const match of source.matchAll(pattern)) {
    const lineStart = source.lastIndexOf("\n", match.index) + 1;
    if (source.slice(lineStart, match.index).includes("func ")) continue;
    const open = source.indexOf("(", match.index);
    let depth = 0;
    for (let index = open; index < source.length; index += 1) {
      if (source[index] === "(") depth += 1;
      if (source[index] === ")") depth -= 1;
      if (depth === 0) {
        calls.push(source.slice(match.index, index + 1));
        break;
      }
    }
  }
  return calls;
}

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

test("finds only the official ChatGPT bundle at its expected installed path", async () => {
  const source = await readFile(helperUrl, "utf8");
  const lookup = source.slice(
    source.indexOf("private func findChatGPT"),
    source.indexOf("private func codexArea", source.indexOf("private func findChatGPT")),
  );

  assert.match(source, /chatGPTBundleIdentifier = "com\.openai\.codex"/);
  assert.match(source, /chatGPTApplicationPath = "\/Applications\/ChatGPT\.app"/);
  assert.match(lookup, /application\.bundleIdentifier == chatGPTBundleIdentifier/);
  assert.match(lookup, /bundleURL\.standardizedFileURL\.path == chatGPTApplicationPath/);
  assert.doesNotMatch(lookup, /localizedName|localizedCaseInsensitiveContains/);
});

test("all synthetic key delivery is PID-scoped and target revalidated", async () => {
  const source = await readFile(helperUrl, "utf8");
  const calls = [...callsNamed(source, "postKey"), ...callsNamed(source, "postChord")];
  const approval = source.slice(source.indexOf("private func press"), source.indexOf("private func postKey"));
  const workflow = source.slice(source.indexOf("private func launchWorkflow"), source.indexOf("private func readStandardInput"));
  const navigation = source.slice(source.indexOf('case "navigate"'), source.indexOf('case "access-cycle"'));
  const deletion = source.slice(source.indexOf('case "delete-backward"'), source.indexOf('case "clear-input"'));
  const targetValidation = source.slice(
    source.indexOf("private func revalidateFocusedDesktopTarget"),
    source.indexOf("private func mutationDesktopSnapshot"),
  );

  assert.ok(calls.length > 0);
  for (const call of calls) assert.match(call, /,\s*to:/, `Unscoped synthetic key call: ${call}`);
  assert.doesNotMatch(source, /to processIdentifier: pid_t\?/);
  assert.doesNotMatch(source, /\.post\(tap:/);
  assert.match(targetValidation, /CFEqual\(current\.window, expected\.window\)/);
  assert.match(targetValidation, /expected\.mutationToken[\s\S]*?current\.mutationToken != expectedToken/);
  assert.match(approval, /revalidateFocusedDesktopTarget[\s\S]*?focusPrompt[\s\S]*?revalidateFocusedDesktopTarget[\s\S]*?postKey\(36, to: target\.application\.processIdentifier\)/);
  assert.match(workflow, /revalidateFocusedDesktopTarget\(nextTarget[\s\S]*?postKey\(36, to: application\.processIdentifier\)/);
  assert.match(navigation, /revalidateFocusedDesktopTarget[\s\S]*?postKey\(try keyCode\(for: direction\), to: application\.processIdentifier\)/);
  assert.match(deletion, /focusPrompt[\s\S]*?revalidateFocusedDesktopTarget[\s\S]*?postKey\(51, to: application\.processIdentifier\)/);
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

test("production Swift selector semantics parse and require native ambiguity confirmation", async (context) => {
  if (process.platform !== "darwin") {
    context.skip("The macOS Accessibility helper is compiled only on macOS.");
    return;
  }
  const source = await readFile(helperUrl, "utf8");
  await execFileAsync("/usr/bin/swiftc", ["-frontend", "-parse", fileURLToPath(helperUrl)]);
  assert.match(source, /case xhigh[\s\S]*?case max[\s\S]*?case ultra/);
  assert.match(source, /needsNativeConfirmation: Bool \{ self == \.xhigh \|\| self == \.ultra \}/);
  assert.match(source, /ambiguous: level == \.xhigh/);
  assert.match(source, /if requested\.needsNativeConfirmation \{ return false \}/);
  assert.doesNotMatch(source, /desktopLevels|shifted\(by/);
  assert.match(source, /modelMatches\(requested: String, candidate: String\)[\s\S]*?modelKey\(candidate\) == target/);
  assert.doesNotMatch(source, /candidate\.contains\(target\)/);
});

test("reasoning availability comes from structure before directional semantics", async () => {
  const source = await readFile(helperUrl, "utf8");

  assert.match(
    source,
    /"reasoning": reasoningControl\(in: index\) != nil && !isExecuting/,
  );
  assert.match(source, /let reasoningAvailable = controls\["reasoning"\] == true/);
  assert.match(source, /"ambiguous": reasoning\?\.ambiguous \?\? false/);
  assert.match(source, /"canIncrease": reasoningAvailable/);
  assert.match(source, /"canDecrease": reasoningAvailable/);
});

test("menu selection revalidates foreground and window with deferred PID-scoped cleanup", async () => {
  const source = await readFile(helperUrl, "utf8");
  const interaction = source.slice(
    source.indexOf("private final class MenuInteraction"),
    source.indexOf("private func elementFrame"),
  );
  const modelSelection = source.slice(
    source.indexOf("private func selectModel"),
    source.indexOf("private func selectReasoning"),
  );
  const reasoningSelection = source.slice(
    source.indexOf("private func selectReasoning"),
    source.indexOf("private func focusPrompt"),
  );
  const accessSelection = source.slice(
    source.indexOf("private func selectNextAccessMode"),
    source.indexOf("private func waitForValue"),
  );

  assert.match(source, /case "model-picker":[\s\S]*?openComposerMenu\(control\)/);
  assert.match(source, /case "select-model":[\s\S]*?selectModel\([\s\S]*?modelID/);
  assert.match(modelSelection, /defer \{ interaction\.cleanup\(\) \}/);
  assert.match(reasoningSelection, /defer \{ interaction\.cleanup\(\) \}/);
  assert.match(accessSelection, /defer \{ interaction\.cleanup\(\) \}/);
  assert.match(interaction, /waitForValue[\s\S]*?find: \(AXUIElement\)[\s\S]*?try revalidate\(\)[\s\S]*?find\(window\)/);
  assert.match(interaction, /try verifyForeground\(application\)[\s\S]*?focusedWindow[\s\S]*?CFEqual/);
  assert.match(interaction, /AXUIElementPerformAction[\s\S]*?postKey\(fallbackKey, to: application\.processIdentifier\)/);
  assert.match(interaction, /postKey\(53, to: application\.processIdentifier\)/);
  assert.doesNotMatch(source, /postKey\(53\)(?!,)/);
  assert.match(source, /case "select-reasoning":[\s\S]*?selectReasoning\([\s\S]*?level/);
  assert.match(source, /observedLevel == requested/);
  assert.match(source, /modelMatches\(requested: requested, candidate:/);
  assert.doesNotMatch(modelSelection, /AXUIElementCreateApplication/);
  assert.doesNotMatch(reasoningSelection, /AXUIElementCreateApplication/);
  assert.doesNotMatch(accessSelection, /AXUIElementCreateApplication/);
  assert.match(modelSelection, /modelMenuItem\(in: window\)[\s\S]*?modelOptions\(in: window/);
  assert.match(reasoningSelection, /reasoningMenuItem\(in: window\)[\s\S]*?reasoningOption\(in: window/);
  assert.match(accessSelection, /descendants\(of: window\)/);
  assert.match(source, /case "delete-backward":[\s\S]*?focusPrompt[\s\S]*?postKey\(51, to: application\.processIdentifier\)/);
});

test("both exact and delta reasoning entry points reject a running turn", async () => {
  const source = await readFile(helperUrl, "utf8");
  const exact = source.slice(source.indexOf('case "select-reasoning"'), source.indexOf('case "reasoning"'));
  const delta = source.slice(source.indexOf('case "reasoning"'), source.indexOf('case "delete-backward"'));

  assert.match(exact, /controlButton\(\.stop, in: snapshot\.area\) == nil/);
  assert.match(delta, /controlButton\(\.stop, in: snapshot\.area\) == nil/);
  assert.match(delta, /exact advertised target level/);
});

test("bridge child inherits normal termination signals", async () => {
  const source = await readFile(hostUrl, "utf8");
  const childRun = source.indexOf("try child.run()");
  const ignoreTermination = source.indexOf("signal(SIGTERM, SIG_IGN)");

  assert.notEqual(childRun, -1);
  assert.notEqual(ignoreTermination, -1);
  assert.ok(childRun < ignoreTermination);
  assert.match(source, /asyncAfter[\s\S]*?forceKill\(rootPID: rootPID\)/);
  assert.match(source, /if includeRoot \{ Darwin\.kill\(rootPID, SIGKILL\) \}/);
});

test("bridge child starts from an allowlisted environment without user shell startup files", async () => {
  const source = await readFile(hostUrl, "utf8");
  const environment = source.slice(
    source.indexOf("private func bridgeEnvironment"),
    source.indexOf("private func runBridge"),
  );
  const launch = source.slice(
    source.indexOf("private func runBridge"),
    source.indexOf("private func runCodexControl"),
  );

  assert.match(environment, /"HOME": FileManager\.default\.homeDirectoryForCurrentUser\.path/);
  assert.match(environment, /"PATH": "\/opt\/homebrew\/bin:[^"]+"/);
  assert.match(environment, /"VIBE_POCKET_HOST_SOCKET": controlSocketPath\(\)/);
  assert.match(environment, /inherited\["VIBE_POCKET_CONFIG_FILE"\]/);
  assert.doesNotMatch(environment, /var environment = ProcessInfo\.processInfo\.environment/);
  assert.match(launch, /child\.arguments = \["-f", scriptPath\]/);
  assert.match(launch, /child\.environment = bridgeEnvironment\(\)/);
});

test("control socket startup closes partial state and protects disconnected writes", async () => {
  const source = await readFile(hostUrl, "utf8");
  const start = source.slice(source.indexOf("func start() throws"), source.indexOf("func stop()"));

  await execFileAsync("/usr/bin/swiftc", ["-frontend", "-parse", fileURLToPath(hostUrl)]);
  assert.match(start, /defer \{[\s\S]*?Darwin\.close\(descriptor\)[\s\S]*?if bound \{ unlinkOwnedSocket\(\) \}/);
  assert.match(start, /bound = true[\s\S]*?listening = true/);
  assert.match(source, /socketIdentityIfPresent\(\)\) == ownedSocketIdentity[\s\S]*?Darwin\.unlink\(socketPath\)/);
  assert.match(source, /Darwin\.setsockopt\([\s\S]*?client,[\s\S]*?SOL_SOCKET,[\s\S]*?SO_NOSIGPIPE/);
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
