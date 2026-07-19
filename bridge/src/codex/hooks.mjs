import { readFile, mkdir, rename, stat, unlink, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

export const EVENTS = [
  "UserPromptSubmit",
  "PreToolUse",
  "PermissionRequest",
  "PostToolUse",
  "Stop",
];

const COMMAND_MARKER = "VIBE_POCKET_CODEX_HOOK=1";

export async function install({ hooksPath, reporterPath }) {
  if (!hooksPath || !reporterPath) throw new TypeError("Codex hook paths are required.");
  return update({ hooksPath, reporterPath });
}

export async function remove({ hooksPath }) {
  if (!hooksPath) throw new TypeError("The Codex hooks path is required.");
  return update({ hooksPath, reporterPath: null });
}

async function update({ hooksPath, reporterPath }) {

  let settings = {};
  let existingMode = 0o600;
  try {
    const source = await readFile(hooksPath, "utf8");
    settings = JSON.parse(source);
    existingMode = (await stat(hooksPath)).mode & 0o777;
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw new Error(`Vibe Pocket left the existing Codex hooks untouched: ${error.message}`);
    }
  }

  if (!isRecord(settings)) throw new Error("Vibe Pocket left the existing Codex hooks untouched: hooks.json must contain an object.");
  if (settings.hooks !== undefined && !isRecord(settings.hooks)) {
    throw new Error("Vibe Pocket left the existing Codex hooks untouched: the hooks field must contain an object.");
  }
  settings.hooks ??= {};

  const before = JSON.stringify(settings);
  for (const [event, groups] of Object.entries(settings.hooks)) {
    if (!Array.isArray(groups)) {
      if (EVENTS.includes(event)) {
        throw new Error(`Vibe Pocket left the existing Codex hooks untouched: ${event} must contain an array.`);
      }
      continue;
    }
    const foreign = groups.filter((group) => !isManaged(group));
    if (foreign.length > 0) settings.hooks[event] = foreign;
    else delete settings.hooks[event];
  }

  if (reporterPath) {
    for (const event of EVENTS) {
      const groups = settings.hooks[event] ?? [];
      settings.hooks[event] = [...groups, desiredGroup(event, reporterPath)];
    }
  }

  if (JSON.stringify(settings) === before) return "unchanged";

  const temporaryPath = `${hooksPath}.${process.pid}.vibe-pocket-tmp`;
  await mkdir(dirname(hooksPath), { recursive: true });
  try {
    await writeFile(temporaryPath, `${JSON.stringify(settings, null, 2)}\n`, {
      encoding: "utf8",
      mode: existingMode,
      flag: "wx",
    });
    await rename(temporaryPath, hooksPath);
  } catch (error) {
    await unlink(temporaryPath).catch(() => {});
    throw error;
  }
  return "changed";
}

function desiredGroup(event, reporterPath) {
  return {
    hooks: [{
      type: "command",
      command: `${COMMAND_MARKER} /bin/zsh ${shellQuote(reporterPath)} ${shellQuote(event)}`,
      timeout: event === "PermissionRequest" ? 135 : 10,
      statusMessage: "Syncing Vibe Pocket controller state",
    }],
  };
}

function isManaged(group) {
  return isRecord(group)
    && Array.isArray(group.hooks)
    && group.hooks.some((hook) => isRecord(hook)
      && typeof hook.command === "string"
      && hook.command.includes(COMMAND_MARKER));
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`;
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
