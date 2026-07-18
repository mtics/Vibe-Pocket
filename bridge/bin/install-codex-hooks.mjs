#!/usr/bin/env node

import { installCodexHooks, removeCodexHooks } from "../src/codex-hooks-installer.mjs";

const args = process.argv.slice(2);

try {
  const result = args[0] === "--remove"
    ? await removeCodexHooks({ hooksPath: args[1] })
    : await installCodexHooks({ hooksPath: args[0], reporterPath: args[1] });
  process.stdout.write(result);
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
}
