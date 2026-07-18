import { execFile } from "node:child_process";

const CODEX_BUNDLE_ID = "com.openai.codex";
const DEFAULT_ACTIVATION_DELAY_SECONDS = 0.15;

const KEY_EVENT_SCRIPT = String.raw`
ObjC.import("CoreGraphics");
ObjC.import("Foundation");

const SHIFT = 1 << 17;
const CONTROL = 1 << 18;
const COMMAND = 1 << 20;
const ACTIONS = Object.freeze({
  approve: [[36, 0]],
  reject: [[53, 0]],
  stop: [[53, 0]],
  voice: [[2, CONTROL | SHIFT]],
  mode: [[48, SHIFT]],
  clear: [[0, COMMAND], [51, 0]],
  up: [[126, 0]],
  down: [[125, 0]],
  left: [[123, 0]],
  right: [[124, 0]],
});

function postKey(source, keyCode, flags) {
  const down = $.CGEventCreateKeyboardEvent(source, keyCode, true);
  const up = $.CGEventCreateKeyboardEvent(source, keyCode, false);
  $.CGEventSetFlags(down, flags);
  $.CGEventSetFlags(up, flags);
  $.CGEventPost($.kCGHIDEventTap, down);
  $.NSThread.sleepForTimeInterval(0.024);
  $.CGEventPost($.kCGHIDEventTap, up);
  $.NSThread.sleepForTimeInterval(0.012);
}

function run(argv) {
  const action = argv[0];
  const activationDelay = Number(argv[1]);
  const chords = ACTIONS[action];
  if (!chords) throw new Error("Unsupported Vibe Pocket key action.");
  $.NSThread.sleepForTimeInterval(activationDelay);
  const source = $.CGEventSourceCreate($.kCGEventSourceStateHIDSystemState);
  for (const [keyCode, flags] of chords) postKey(source, keyCode, flags);
}`;

const PASTE_SCRIPT = String.raw`
ObjC.import("AppKit");
ObjC.import("CoreGraphics");
ObjC.import("Foundation");

function postPaste() {
  const source = $.CGEventSourceCreate($.kCGEventSourceStateHIDSystemState);
  const down = $.CGEventCreateKeyboardEvent(source, 9, true);
  const up = $.CGEventCreateKeyboardEvent(source, 9, false);
  $.CGEventSetFlags(down, 1 << 20);
  $.CGEventSetFlags(up, 1 << 20);
  $.CGEventPost($.kCGHIDEventTap, down);
  $.NSThread.sleepForTimeInterval(0.024);
  $.CGEventPost($.kCGHIDEventTap, up);
}

function run(argv) {
  const incomingText = argv[0];
  const activationDelay = Number(argv[1]);
  const pasteboard = $.NSPasteboard.generalPasteboard;
  const previousText = pasteboard.stringForType($.NSPasteboardTypeString);
  pasteboard.clearContents;
  pasteboard.setStringForType($(incomingText), $.NSPasteboardTypeString);
  $.NSThread.sleepForTimeInterval(activationDelay);
  postPaste();
  $.NSThread.sleepForTimeInterval(0.12);
  pasteboard.clearContents;
  if (previousText) pasteboard.setStringForType(previousText, $.NSPasteboardTypeString);
}`;

export class CodexAccessibilityController {
  #runCommand;
  #activationDelaySeconds;
  #voiceActive = false;

  constructor({
    runCommand = runCommandFile,
    activationDelaySeconds = DEFAULT_ACTIVATION_DELAY_SECONDS,
  } = {}) {
    if (typeof runCommand !== "function") throw new TypeError("runCommand must be a function.");
    if (!Number.isFinite(activationDelaySeconds) || activationDelaySeconds < 0 || activationDelaySeconds > 2) {
      throw new TypeError("activationDelaySeconds must be between 0 and 2.");
    }
    this.#runCommand = runCommand;
    this.#activationDelaySeconds = activationDelaySeconds;
  }

  get voiceActive() {
    return this.#voiceActive;
  }

  async activate() {
    await this.#activate();
    return { message: "Focused the visible Codex window." };
  }

  async press(control) {
    if (control === "new-task") {
      await this.#run("open", ["-b", CODEX_BUNDLE_ID, "codex://new"]);
      return { message: "Opened a new visible Codex task." };
    }
    if (!["approve", "reject", "stop"].includes(control)) {
      throw new Error(`Unsupported visible Codex control: ${control}.`);
    }
    await this.#runKeyAction(control);
    return { message: `${control === "approve" ? "Sent Return to" : "Sent Escape to"} the visible Codex window.` };
  }

  async setVoice(active) {
    if (typeof active !== "boolean") throw new TypeError("Voice state must be boolean.");
    if (this.#voiceActive === active) {
      return { message: active ? "Visible Codex dictation is already active." : "Visible Codex dictation is already inactive." };
    }
    await this.#runKeyAction("voice");
    this.#voiceActive = active;
    return { message: active ? "Started visible Codex dictation." : "Stopped visible Codex dictation." };
  }

  async setDictationDraft(text) {
    if (!validDictation(text)) {
      throw new Error("Phone dictation must contain printable non-empty text up to 12,000 characters.");
    }
    await this.#activate();
    await this.#run("osascript", [
      "-l", "JavaScript",
      "-e", PASTE_SCRIPT,
      "--", text.trim(), `${this.#activationDelaySeconds}`,
    ]);
    this.#voiceActive = false;
    return { message: "Pasted phone dictation into the visible Codex composer." };
  }

  async navigate(direction) {
    if (!["up", "down", "left", "right"].includes(direction)) {
      throw new Error("Navigation direction must be up, down, left, or right.");
    }
    await this.#runKeyAction(direction);
    return { message: `Sent ${direction} to the visible Codex window.` };
  }

  async cycleMode() {
    await this.#runKeyAction("mode");
    return { message: "Sent Shift+Tab to the visible Codex composer." };
  }

  async clearInput() {
    await this.#runKeyAction("clear");
    return { message: "Cleared the visible Codex composer." };
  }

  async #activate() {
    await this.#run("open", ["-b", CODEX_BUNDLE_ID]);
  }

  async #runKeyAction(action) {
    await this.#activate();
    await this.#run("osascript", [
      "-l", "JavaScript",
      "-e", KEY_EVENT_SCRIPT,
      "--", action, `${this.#activationDelaySeconds}`,
    ]);
  }

  async #run(command, args) {
    try {
      await this.#runCommand(command, args);
    } catch (error) {
      const detail = error?.stderr?.trim?.() || error?.message || `${command} failed.`;
      if (/not authorized|not permitted|assistive|accessibility|automation|1743|1002/i.test(detail)) {
        throw new Error(
          "Vibe Pocket Bridge Host needs macOS Accessibility access to control the visible Codex window.",
          { cause: error },
        );
      }
      throw new Error(`Visible Codex control failed: ${detail}`, { cause: error });
    }
  }
}

function runCommandFile(command, args) {
  return new Promise((resolve, reject) => {
    execFile(command, args, { timeout: 5_000, maxBuffer: 256 * 1024 }, (error, stdout, stderr) => {
      if (error) {
        error.stdout = stdout;
        error.stderr = stderr;
        reject(error);
      } else {
        resolve({ stdout, stderr });
      }
    });
  });
}

function validDictation(text) {
  return typeof text === "string"
    && text.trim().length > 0
    && text.length <= 12_000
    && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(text);
}
