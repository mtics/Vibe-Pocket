#!/usr/bin/env node

import { installCodexHooks } from "../src/codex-hooks-installer.mjs";

const [hooksPath, reporterPath] = process.argv.slice(2);

try {
  const result = await installCodexHooks({ hooksPath, reporterPath });
  process.stdout.write(result);
} catch (error) {
  process.stderr.write(`${error.message}\n`);
  process.exitCode = 1;
}
