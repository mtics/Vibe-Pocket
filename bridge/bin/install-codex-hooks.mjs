#!/usr/bin/env node

import { install, remove } from "../src/codex/hooks.mjs";

const args = process.argv.slice(2);

try {
  const result = args[0] === "--remove"
    ? await remove({ hooksPath: args[1] })
    : await install({ hooksPath: args[0], reporterPath: args[1] });
  process.stdout.write(result);
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
}
