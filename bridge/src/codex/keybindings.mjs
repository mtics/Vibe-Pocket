import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";

export const KEYBINDINGS = Object.freeze([
  Object.freeze({ command: "composer.openModelPicker", key: "Ctrl+Shift+M" }),
  Object.freeze({ command: "composer.togglePlanMode", key: "Ctrl+Alt+Shift+P" }),
  Object.freeze({ command: "composer.increaseReasoningEffort", key: "Ctrl+Alt+Shift+U" }),
  Object.freeze({ command: "composer.decreaseReasoningEffort", key: "Ctrl+Alt+Shift+J" }),
]);

export function defaultPath() {
  return join(homedir(), ".codex", "keybindings.json");
}

export async function install(
  filePath = defaultPath(),
) {
  const existing = await readKeybindings(filePath);
  assertNoConflicts(existing);

  const managedCommands = new Set(KEYBINDINGS.map(({ command }) => command));
  const bindings = existing.filter(({ command, key }) => !managedCommands.has(command) || key !== null);
  for (const binding of KEYBINDINGS) {
    if (!bindings.some(({ command, key }) => command === binding.command && key === binding.key)) {
      bindings.push({ ...binding });
    }
  }
  bindings.sort((left, right) => left.command.localeCompare(right.command) || `${left.key}`.localeCompare(`${right.key}`));

  await mkdir(dirname(filePath), { recursive: true, mode: 0o700 });
  const temporaryPath = `${filePath}.${process.pid}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(bindings, null, 2)}\n`, { mode: 0o600 });
  await rename(temporaryPath, filePath);
  return {
    filePath,
    installed: KEYBINDINGS.length,
    bindings,
  };
}

async function readKeybindings(filePath) {
  let source;
  try {
    source = await readFile(filePath, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }

  let value;
  try {
    value = JSON.parse(source);
  } catch (error) {
    throw new Error(`Codex keybindings are not valid JSON: ${filePath}`, { cause: error });
  }
  if (!Array.isArray(value) || value.some((entry) => !validBinding(entry))) {
    throw new Error(`Codex keybindings must be an array of command/key objects: ${filePath}`);
  }
  return value.map(({ command, key }) => ({ command, key }));
}

function validBinding(value) {
  return value
    && typeof value === "object"
    && !Array.isArray(value)
    && Object.keys(value).length === 2
    && typeof value.command === "string"
    && value.command.length > 0
    && (typeof value.key === "string" || value.key === null);
}

function assertNoConflicts(existing) {
  for (const binding of KEYBINDINGS) {
    const conflict = existing.find(({ command, key }) => command !== binding.command && key === binding.key);
    if (conflict) {
      throw new Error(
        `${binding.key} is already assigned to ${conflict.command}; choose a different Vibe Pocket shortcut.`,
      );
    }
  }
}
