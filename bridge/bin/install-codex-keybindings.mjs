#!/usr/bin/env node

import { installVibePocketCodexKeybindings } from "../src/codex-keybindings.mjs";

try {
  const result = await installVibePocketCodexKeybindings(process.argv[2]);
  process.stdout.write(`installed ${result.installed} Vibe Pocket Codex shortcuts in ${result.filePath}\n`);
} catch (error) {
  process.stderr.write(`${error?.message ?? error}\n`);
  process.exitCode = 1;
}
