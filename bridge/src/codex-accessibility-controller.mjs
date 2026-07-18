import { execFile } from "node:child_process";

const CODEX_BUNDLE_ID = "com.openai.codex";
const DEFAULT_ACTIVATION_DELAY_SECONDS = 0.15;

const KEY_STEPS = Object.freeze({
  approve: ["key code 36"],
  reject: ["key code 53"],
  stop: ["key code 53"],
  voice: ["keystroke \"d\" using {control down, shift down}"],
  mode: ["key code 48 using shift down"],
  clear: ["keystroke \"a\" using command down", "key code 51"],
  up: ["key code 126"],
  down: ["key code 125"],
  left: ["key code 123"],
  right: ["key code 124"],
});

const PASTE_SCRIPT = `on run argv
  set incomingText to item 1 of argv
  set hadClipboard to true
  try
    set previousClipboard to the clipboard
  on error
    set hadClipboard to false
  end try
  set the clipboard to incomingText
  tell application id "${CODEX_BUNDLE_ID}" to activate
  delay ${DEFAULT_ACTIVATION_DELAY_SECONDS}
  tell application "System Events" to keystroke "v" using command down
  delay 0.1
  if hadClipboard then set the clipboard to previousClipboard
end run`;

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
    await this.#runAppleScript([]);
    return { message: "Focused the visible Codex window." };
  }

  async press(control) {
    if (control === "new-task") {
      await this.#run("open", ["-b", CODEX_BUNDLE_ID, "codex://new"]);
      return { message: "Opened a new visible Codex task." };
    }
    if (!Object.hasOwn(KEY_STEPS, control) || !["approve", "reject", "stop"].includes(control)) {
      throw new Error(`Unsupported visible Codex control: ${control}.`);
    }
    await this.#runAppleScript(KEY_STEPS[control]);
    return { message: `${control === "approve" ? "Sent Return to" : "Sent Escape to"} the visible Codex window.` };
  }

  async setVoice(active) {
    if (typeof active !== "boolean") throw new TypeError("Voice state must be boolean.");
    if (this.#voiceActive === active) {
      return { message: active ? "Visible Codex dictation is already active." : "Visible Codex dictation is already inactive." };
    }
    await this.#runAppleScript(KEY_STEPS.voice);
    this.#voiceActive = active;
    return { message: active ? "Started visible Codex dictation." : "Stopped visible Codex dictation." };
  }

  async setDictationDraft(text) {
    if (!validDictation(text)) {
      throw new Error("Phone dictation must contain printable non-empty text up to 12,000 characters.");
    }
    await this.#run("osascript", ["-e", PASTE_SCRIPT, "--", text.trim()]);
    this.#voiceActive = false;
    return { message: "Pasted phone dictation into the visible Codex composer." };
  }

  async navigate(direction) {
    if (!Object.hasOwn(KEY_STEPS, direction) || !["up", "down", "left", "right"].includes(direction)) {
      throw new Error("Navigation direction must be up, down, left, or right.");
    }
    await this.#runAppleScript(KEY_STEPS[direction]);
    return { message: `Sent ${direction} to the visible Codex window.` };
  }

  async cycleMode() {
    await this.#runAppleScript(KEY_STEPS.mode);
    return { message: "Sent Shift+Tab to the visible Codex composer." };
  }

  async clearInput() {
    await this.#runAppleScript(KEY_STEPS.clear);
    return { message: "Cleared the visible Codex composer." };
  }

  async #runAppleScript(steps) {
    const args = [
      "-e", `tell application id "${CODEX_BUNDLE_ID}" to activate`,
      "-e", `delay ${this.#activationDelaySeconds}`,
    ];
    for (const step of steps) {
      args.push("-e", `tell application "System Events" to ${step}`);
    }
    await this.#run("osascript", args);
  }

  async #run(command, args) {
    try {
      await this.#runCommand(command, args);
    } catch (error) {
      const detail = error?.stderr?.trim?.() || error?.message || `${command} failed.`;
      if (/not authorized|not permitted|assistive|accessibility|automation|1743|1002/i.test(detail)) {
        throw new Error(
          "Vibe Pocket needs macOS Accessibility and Automation access to control the visible Codex window.",
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
