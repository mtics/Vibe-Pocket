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

  assert.equal(calls.length, 8);
  for (const [command, args] of calls.filter(([command]) => command === "osascript")) {
    assert.equal(command, "osascript");
    assert.deepEqual(args.slice(0, 3), ["-l", "JavaScript", "-e"]);
    assert.ok(!args.join("\n").includes("System Events"));
  }
  assert.deepEqual(calls.filter(([command]) => command === "open").map(([, args]) => args), [
    ["-b", "com.openai.codex"],
    ["-b", "com.openai.codex"],
    ["-b", "com.openai.codex"],
    ["-b", "com.openai.codex"],
  ]);
  assert.equal(calls[1][1].at(-2), "approve");
  assert.equal(calls[3][1].at(-2), "left");
  assert.equal(calls[5][1].at(-2), "mode");
  assert.equal(calls[7][1].at(-2), "clear");
});

test("passes phone dictation only as a JXA argument", async () => {
  const { controller, calls } = makeController();
  const hostileText = '测试" & do shell script "touch /tmp/not-allowed"';

  await controller.setDictationDraft(hostileText);

  assert.equal(calls[0][0], "open");
  assert.equal(calls[1][0], "osascript");
  assert.equal(calls[1][1].at(-2), hostileText);
  assert.ok(!calls[1][1].slice(0, -2).join("\n").includes(hostileText));
});

test("toggles visible dictation only when the requested state changes", async () => {
  const { controller, calls } = makeController();

  await controller.setVoice(true);
  await controller.setVoice(true);
  await controller.setVoice(false);

  assert.equal(calls.length, 4);
  assert.equal(controller.voiceActive, false);
  assert.equal(calls[1][1].at(-2), "voice");
  assert.equal(calls[3][1].at(-2), "voice");
});

test("reports the macOS permission boundary clearly", async () => {
  const controller = new CodexAccessibilityController({
    runCommand: async () => {
      const error = new Error("CGEvent is not permitted to post keyboard input");
      error.stderr = "not permitted";
      throw error;
    },
  });

  await assert.rejects(
    () => controller.press("approve"),
    /Accessibility access/,
  );
});
