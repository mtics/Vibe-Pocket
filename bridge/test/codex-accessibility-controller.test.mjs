import assert from "node:assert/strict";
import test from "node:test";

import { CodexAccessibilityController } from "../src/codex-accessibility-controller.mjs";

function makeController() {
  const calls = [];
  return {
    calls,
    controller: new CodexAccessibilityController({
      runCommand: async (command, args) => { calls.push([command, args]); },
      activationDelaySeconds: 0,
    }),
  };
}

test("sends fixed Accessibility keystrokes to the Codex bundle", async () => {
  const { controller, calls } = makeController();

  await controller.press("approve");
  await controller.navigate("left");
  await controller.cycleMode();
  await controller.clearInput();

  assert.equal(calls.length, 4);
  for (const [command, args] of calls) {
    assert.equal(command, "osascript");
    assert.ok(args.includes('tell application id "com.openai.codex" to activate'));
  }
  assert.ok(calls[0][1].includes('tell application "System Events" to key code 36'));
  assert.ok(calls[1][1].includes('tell application "System Events" to key code 123'));
  assert.ok(calls[2][1].includes('tell application "System Events" to key code 48 using shift down'));
  assert.ok(calls[3][1].includes('tell application "System Events" to keystroke "a" using command down'));
  assert.ok(calls[3][1].includes('tell application "System Events" to key code 51'));
});

test("passes phone dictation only as an osascript argument", async () => {
  const { controller, calls } = makeController();
  const hostileText = '测试" & do shell script "touch /tmp/not-allowed"';

  await controller.setDictationDraft(hostileText);

  assert.equal(calls[0][0], "osascript");
  assert.equal(calls[0][1].at(-1), hostileText);
  assert.ok(!calls[0][1].slice(0, -1).join("\n").includes(hostileText));
});

test("toggles visible dictation only when the requested state changes", async () => {
  const { controller, calls } = makeController();

  await controller.setVoice(true);
  await controller.setVoice(true);
  await controller.setVoice(false);

  assert.equal(calls.length, 2);
  assert.equal(controller.voiceActive, false);
  assert.ok(calls[0][1].includes('tell application "System Events" to keystroke "d" using {control down, shift down}'));
});

test("reports the macOS permission boundary clearly", async () => {
  const controller = new CodexAccessibilityController({
    runCommand: async () => {
      const error = new Error("osascript is not authorized to send Apple events");
      error.stderr = "not authorized";
      throw error;
    },
  });

  await assert.rejects(
    () => controller.press("approve"),
    /Accessibility and Automation access/,
  );
});
