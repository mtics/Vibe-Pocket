import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { install, KEYBINDINGS } from "../../src/codex/keybindings.mjs";

test("installs fixed desktop semantic shortcuts without replacing user bindings", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-keybindings-"));
  const filePath = join(directory, "keybindings.json");
  const existing = [{ command: "toggleSidebar", key: "CmdOrCtrl+B" }];
  await writeFile(filePath, JSON.stringify(existing));

  await install(filePath);
  await install(filePath);

  const installed = JSON.parse(await readFile(filePath, "utf8"));
  assert.ok(installed.some(({ command, key }) => command === existing[0].command && key === existing[0].key));
  for (const binding of KEYBINDINGS) {
    assert.equal(
      installed.filter(({ command, key }) => command === binding.command && key === binding.key).length,
      1,
    );
  }
  assert.ok(installed.some(({ command, key }) => command === "composer.openModelPicker" && key === "Ctrl+Shift+M"));
});

test("re-enables a managed command that was explicitly cleared", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-keybindings-"));
  const filePath = join(directory, "keybindings.json");
  await writeFile(filePath, JSON.stringify([
    { command: "composer.togglePlanMode", key: null },
  ]));

  await install(filePath);

  const installed = JSON.parse(await readFile(filePath, "utf8"));
  assert.equal(installed.some(({ command, key }) => command === "composer.togglePlanMode" && key === null), false);
  assert.equal(installed.some(({ command, key }) => command === "composer.togglePlanMode" && key != null), true);
});

test("refuses to silently steal a shortcut from another command", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vibe-pocket-keybindings-"));
  const filePath = join(directory, "keybindings.json");
  await writeFile(filePath, JSON.stringify([
    { command: "some.user.command", key: KEYBINDINGS[0].key },
  ]));

  await assert.rejects(
    () => install(filePath),
    /already assigned/,
  );
});
