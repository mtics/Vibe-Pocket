import { spawn } from "node:child_process";

const THREAD_ID_PATTERN = /^[A-Za-z0-9_-]{1,160}$/;

export async function openCodexThread(threadId, {
  platform = process.platform,
  spawnProcess = spawn,
} = {}) {
  if (typeof threadId !== "string" || !THREAD_ID_PATTERN.test(threadId)) {
    throw new Error("Codex returned an invalid thread ID for desktop navigation.");
  }

  const url = `codex://threads/${encodeURIComponent(threadId)}`;
  const [command, args] = platformCommand(platform, url);
  await waitForExit(spawnProcess(command, args, { stdio: "ignore" }), command);
  return { url };
}

function platformCommand(platform, url) {
  if (platform === "darwin") return ["open", ["-g", "-b", "com.openai.codex", url]];
  if (platform === "win32") return ["cmd.exe", ["/d", "/s", "/c", "start", "", url]];
  return ["xdg-open", [url]];
}

function waitForExit(child, command) {
  return new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("exit", (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`${command} could not open Codex (${signal ?? `code ${code ?? "unknown"}`}).`));
    });
  });
}
