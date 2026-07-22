import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import test from "node:test";

const helperUrl = new URL("../../src/macos/helper.swift", import.meta.url);
const hostUrl = new URL("../../src/macos/host.swift", import.meta.url);
const pairingUrl = new URL("../../src/macos/pairing.swift", import.meta.url);
const installerUrl = new URL("../../bin/install-launch-agent.sh", import.meta.url);
const launchScriptUrl = new URL("../../bin/run-launchd.sh", import.meta.url);
const cleanupScriptUrl = new URL("../../bin/cleanup-stale-listener.sh", import.meta.url);
const execFileAsync = promisify(execFile);

test("signed host sources type-check together", async () => {
  await execFileAsync("/usr/bin/swiftc", [
    "-typecheck",
    fileURLToPath(hostUrl),
    fileURLToPath(helperUrl),
    fileURLToPath(pairingUrl),
  ], {
    env: {
      ...process.env,
      CLANG_MODULE_CACHE_PATH: "/tmp/vibe-pocket-clang-module-cache",
      SWIFT_MODULECACHE_PATH: "/tmp/vibe-pocket-swift-module-cache",
    },
  });
});

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

test("only explicit foreground-dependent actions may activate ChatGPT", async () => {
  const source = await readFile(helperUrl, "utf8");
  const activations = source.match(/desktop\(activateDesktop: true\)/g) ?? [];

  assert.equal(activations.length, 3);
  assert.match(source, /case "attach":[\s\S]*?desktop\(activateDesktop: true\)/);
  assert.match(source, /private func launchWorkflow[\s\S]*?desktop\(activateDesktop: true\)/);
  assert.match(source, /case "navigate":[\s\S]*?desktop\(activateDesktop: true\)/);
  assert.doesNotMatch(source, /activateIgnoringOtherApps/);
  assert.match(source, /waitForStableForeground\(application, window: window\)/);
  assert.match(source, /consecutiveObservations >= 3/);
  assert.match(source, /attempt == 6 \|\| attempt == 12/);
  assert.match(source, /kAXFrontmostAttribute/);
  assert.match(source, /kAXMainAttribute/);
  assert.match(source, /kAXFocusedAttribute/);
  assert.match(source, /AXUIElementPerformAction\(window, kAXRaiseAction/);
});

test("finds only the official ChatGPT bundle at its expected installed path", async () => {
  const source = await readFile(helperUrl, "utf8");
  const lookup = source.slice(
    source.indexOf("private func findChatGPT"),
    source.indexOf("private func codexArea", source.indexOf("private func findChatGPT")),
  );

  assert.match(source, /chatGPTBundleIdentifier = "com\.openai\.codex"/);
  assert.match(source, /chatGPTApplicationPath = "\/Applications\/ChatGPT\.app"/);
  assert.match(lookup, /NSRunningApplication\.runningApplications\(withBundleIdentifier: chatGPTBundleIdentifier\)/);
  assert.match(lookup, /bundleURL\.standardizedFileURL\.path == chatGPTApplicationPath/);
  assert.doesNotMatch(lookup, /localizedName|localizedCaseInsensitiveContains/);
});

test("background keys are PID-scoped and hardware keys are foreground-revalidated", async () => {
  const source = await readFile(helperUrl, "utf8");
  const calls = [...callsNamed(source, "postKey"), ...callsNamed(source, "postChord")];
  const approval = source.slice(source.indexOf("private func press"), source.indexOf("private func postKey"));
  const availability = source.slice(
    source.indexOf("private func controlAvailability"),
    source.indexOf("private func accessModeLabel", source.indexOf("private func controlAvailability")),
  );
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
  const hardware = source.slice(
    source.indexOf("private func postHardwareKey"),
    source.indexOf("private let selectableRoles"),
  );
  assert.match(hardware, /postHardwareKey[\s\S]*?\.post\(tap: \.cghidEventTap\)/);
  assert.match(hardware, /postHardwareChord[\s\S]*?\.post\(tap: \.cghidEventTap\)/);
  assert.match(source, /openComposerSubmenuFromKeyboard[\s\S]*?if requireForeground[\s\S]*?postHardwareChord[\s\S]*?try revalidate\(\)[\s\S]*?postHardwareKey/);
  assert.match(targetValidation, /CFEqual\(current\.window, expected\.window\)/);
  assert.match(targetValidation, /expected\.mutationToken[\s\S]*?current\.mutationToken != expectedToken/);
  assert.match(approval, /controlButton\(control, in: target\.area\)[\s\S]*?revalidateFocusedDesktopTarget[\s\S]*?performPress\(button\)/);
  assert.doesNotMatch(approval, /hasDraft|focusPrompt|postKey/);
  assert.match(availability, /"approve": direct\["approve"\] == true/);
  assert.match(workflow, /revalidateFocusedDesktopTarget\(nextTarget[\s\S]*?postKey\(36, to: application\.processIdentifier\)/);
  assert.match(navigation, /revalidateFocusedDesktopTarget[\s\S]*?postKey\(try keyCode\(for: direction\), to: application\.processIdentifier\)/);
  assert.match(deletion, /focusPrompt[\s\S]*?revalidateFocusedDesktopTarget[\s\S]*?postKey\(51, to: application\.processIdentifier\)/);
});

test("synthetic keys isolate and explicitly set exact modifier flags", async () => {
  const source = await readFile(helperUrl, "utf8");
  const plainKey = source.slice(source.indexOf("private func postKey"), source.indexOf("private func postChord"));
  const chord = source.slice(source.indexOf("private func postChord"), source.indexOf("private func focus("));
  const eventSources = source.match(/CGEventSource\(stateID:/g) ?? [];
  const privateSources = source.match(/CGEventSource\(stateID: \.privateState\)/g) ?? [];

  assert.equal(eventSources.length, 4);
  assert.equal(privateSources.length, eventSources.length);
  assert.doesNotMatch(source, /hidSystemState/);
  assert.match(plainKey, /down\.flags = \[\][\s\S]*?up\.flags = \[\]/);
  assert.match(chord, /down\.flags = flags[\s\S]*?up\.flags = flags/);
});

test("mode switching targets ChatGPT without taking the foreground", async () => {
  const source = await readFile(helperUrl, "utf8");
  const modeCase = source.slice(
    source.indexOf('case "plan-mode"'),
    source.indexOf('case "model-picker"'),
  );

  assert.match(source, /postToPid\(processIdentifier\)/);
  assert.match(modeCase, /expectedMutationToken\(arguments, at: 1\)/);
  assert.match(modeCase, /withInteractiveMutationDesktop\(expectedToken: expectedToken\)/);
  assert.match(modeCase, /togglePlanMode\(in: snapshot\.application\)/);
  assert.match(modeCase, /planModeIsActive[\s\S]*?confirmed/);
  assert.match(modeCase, /"settings": \["mode": \["available": true, "label": confirmed\]\]/);
  assert.doesNotMatch(modeCase, /verifyForeground|activate\(/);
  assert.match(source, /withInteractiveMutationDesktop[\s\S]*?previousApplication[\s\S]*?kCFBooleanFalse[\s\S]*?defer[\s\S]*?kCFBooleanTrue[\s\S]*?requestForeground\(previousApplication\)/);
  assert.match(source, /try activate\(initial\.application, window: ready\.window\)/);
});

test("composer controls and Agent focus identity use the focused Codex window", async () => {
  const source = await readFile(helperUrl, "utf8");
  const statusReply = source.slice(
    source.indexOf("private func statusReply"),
    source.indexOf("private func press", source.indexOf("private func statusReply")),
  );

  assert.match(source, /kAXFocusedWindowAttribute/);
  assert.match(source, /codexArea\(in: focusedWindow\(for: application\)\)/);
  assert.match(statusReply, /let scope = try focusedWindow\(for: application\)/);
  assert.match(statusReply, /focusedAgentTarget\(in: scope/);
  assert.doesNotMatch(statusReply, /agentTargets\(/);
  assert.doesNotMatch(statusReply, /AXUIElementCreateApplication/);
});

test("Codex window selection accepts the primary dialog, excludes Pet overlays, and fails closed on ambiguity", async () => {
  const source = await readFile(helperUrl, "utf8");
  const webAreas = source.slice(
    source.indexOf("private func codexWebAreas"),
    source.indexOf("private func requestAccessibilityPermission"),
  );
  const selection = source.slice(
    source.indexOf("private func isCodexContentWindow"),
    source.indexOf("private func codexArea(in:"),
  );
  const identity = selection.slice(0, selection.indexOf("private func matchingWindow"));

  assert.match(selection, /\[kAXStandardWindowSubrole, kAXDialogSubrole\][\s\S]*?contains\(subrole\)/);
  assert.match(selection, /title\.hasPrefix\("Codex Pet"\)/);
  assert.doesNotMatch(identity, /AreaIndex\(area, using: children\)/);
  assert.doesNotMatch(identity, /prompt\(in: index\)|reasoningControl\(in: index\)|controlButton\(/);
  assert.match(selection, /kAXMinimizedAttribute/);
  assert.match(selection, /codexWebAreas\(of: window\)\.first/);
  assert.match(webAreas, /childElements\(candidate\)/);
  assert.match(webAreas, /= visibleChildren/);
  assert.match(selection, /kAXFocusedWindowAttribute[\s\S]*?kAXMainWindowAttribute/);
  assert.match(selection, /if candidates\.count == 1/);
  assert.match(selection, /multiple visible Codex windows but none is focused/);
  assert.doesNotMatch(selection, /title == "ChatGPT"/);
  assert.match(source, /using childElements: \(AXUIElement\) -> \[AXUIElement\] = children/);
  assert.match(source, /let leafRoles:[\s\S]*?"AXList"/);
});

test("task identity ignores row action text that precedes the actual title", async () => {
  const source = await readFile(helperUrl, "utf8");
  const labels = source.slice(
    source.indexOf("private let taskRowActionLabels"),
    source.indexOf("private func taskIsFocused"),
  );

  assert.match(labels, /"置顶任务"/);
  assert.match(labels, /"归档任务"/);
  assert.match(labels, /"pin task"/);
  assert.match(labels, /"archive task"/);
  assert.match(labels, /labels\.first\(where: isTaskTitleLabel\)/);
  assert.match(labels, /!taskRowActionLabels\.contains\(normalized\)/);
});

test("production Swift selector semantics parse and confirm exact transitions deterministically", async (context) => {
  if (process.platform !== "darwin") {
    context.skip("The macOS Accessibility helper is compiled only on macOS.");
    return;
  }
  const source = await readFile(helperUrl, "utf8");
  await execFileAsync("/usr/bin/swiftc", ["-frontend", "-parse", fileURLToPath(helperUrl)]);
  assert.match(source, /case xhigh[\s\S]*?case max[\s\S]*?case ultra/);
  assert.match(source, /ambiguous: level == \.xhigh/);
  assert.match(source, /Set\(options\)\.count == options\.count/);
  assert.match(source, /observed\.level == requested/);
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

test("desktop helper exposes no model or reasoning mutation handlers", async () => {
  const source = await readFile(helperUrl, "utf8");

  assert.doesNotMatch(source, /case "select-model":/);
  assert.doesNotMatch(source, /case "select-reasoning":/);
  assert.doesNotMatch(source, /case "reasoning":/);
  assert.match(source, /case "model-picker":[\s\S]*?openModelSubmenu\(interaction\)/);
  assert.match(source, /case "delete-backward":[\s\S]*?focusPrompt[\s\S]*?postKey\(51, to: application\.processIdentifier\)/);
});

test("one-time Micro setup stabilizes the settings window before each semantic press", async () => {
  const source = await readFile(helperUrl, "utf8");
  const setup = source.slice(
    source.indexOf("private func configureMicroReasoningKnob"),
    source.indexOf("private func focus", source.indexOf("private func configureMicroReasoningKnob")),
  );

  assert.equal((setup.match(/try activate\(application, window: settings\.window\)/g) ?? []).length, 2);
  assert.doesNotMatch(setup, /requestForeground\(application, window: settings\.window\)/);
  assert.match(setup, /requestMicroConnection\(for: application, in: settings\.window\)[\s\S]*?currentLabel/);
  assert.match(setup, /if currentMode == nil[\s\S]*?focus\(settings\.element\)[\s\S]*?postKey\(36, to: application\.processIdentifier\)[\s\S]*?findCurrentMode/);
  assert.match(setup, /MenuInteraction\(application: application\)[\s\S]*?openOptions\(currentMode\)[\s\S]*?Reasoning only[\s\S]*?chooseMenuItem\(reasoning\)/);
  assert.match(setup, /if reasoning == nil[\s\S]*?openOptionsFromKeyboard\(currentMode\)[\s\S]*?findReasoning/);
  assert.match(setup, /observeReasoningMode[\s\S]*?AXMenuItem[\s\S]*?reasoning only[\s\S]*?if confirmed == nil[\s\S]*?chooseMenuItemFromKeyboard\(keyboardReasoning\)[\s\S]*?observeReasoningMode/);
});

test("one-time Micro setup requests a visible device connection before returning", async () => {
  const source = await readFile(helperUrl, "utf8");
  const connection = source.slice(
    source.indexOf("private func requestMicroConnection"),
    source.indexOf("private func settingsMenuItem", source.indexOf("private func requestMicroConnection")),
  );

  assert.match(connection, /exactControl\(in: window, labels: \["Connect", "连接"\]\)/);
  assert.match(connection, /activate\(application, window: window\)[\s\S]*?performPress\(connect\)/);
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
  const start = source.slice(
    source.indexOf("func start() throws"),
    source.indexOf("func registerBridgeProcess"),
  );
  const accepting = source.slice(source.indexOf("func startAccepting"), source.indexOf("func stop()"));

  await execFileAsync("/usr/bin/swiftc", ["-frontend", "-parse", fileURLToPath(hostUrl)]);
  assert.match(start, /defer \{[\s\S]*?Darwin\.close\(descriptor\)[\s\S]*?if bound \{ unlinkOwnedSocket\(\) \}/);
  assert.match(start, /bound = true[\s\S]*?started = true/);
  assert.doesNotMatch(start, /Darwin\.listen/);
  assert.match(accepting, /hasRegisteredProcess[\s\S]*?Darwin\.listen[\s\S]*?acceptQueue\.async/);
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
