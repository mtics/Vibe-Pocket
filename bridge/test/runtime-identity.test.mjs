import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { readyPayload, readyPayloadMatches } from "../src/runtime/identity.mjs";

const identityTool = new URL("../src/runtime/identity.mjs", import.meta.url);
const installer = new URL("../bin/install-launch-agent.sh", import.meta.url);
const launcher = new URL("../bin/run-launchd.sh", import.meta.url);
const RUNTIME_IDENTITY = `sha256:${"c".repeat(64)}`;

test("readiness matching requires the exact bounded identity and protocol payload", () => {
  const expected = readyPayload(RUNTIME_IDENTITY, 9);
  assert.equal(readyPayloadMatches(expected, { ...expected }), true);
  assert.equal(readyPayloadMatches(expected, {
    ...expected,
    runtimeIdentity: `sha256:${"d".repeat(64)}`,
  }), false);
  assert.equal(readyPayloadMatches(expected, { ...expected, protocolVersion: 8 }), false);
  assert.equal(readyPayloadMatches(expected, { ...expected, controller: {} }), false);
});

test("readiness matcher CLI fails closed on identity mismatch", () => {
  const expected = readyPayload(RUNTIME_IDENTITY, 9);
  const mismatch = { ...expected, runtimeIdentity: `sha256:${"e".repeat(64)}` };
  const result = spawnSync(process.execPath, [identityTool.pathname, "matches", JSON.stringify(expected)], {
    input: JSON.stringify(mismatch),
    encoding: "utf8",
  });

  assert.equal(result.status, 1, result.stderr);
});

test("installer waits for an exact /readyz response from the staged runtime", async () => {
  const source = await readFile(installer, "utf8");
  assert.match(source, /IDENTITY_TOOL=.*runtime\/identity\.mjs/);
  assert.match(source, /IDENTITY_TOOL\" expected \"\$RUNTIME_DIR/);
  assert.match(source, /\/readyz/);
  assert.match(source, /IDENTITY_TOOL\" matches \"\$EXPECTED_READY/);
  const readinessLoop = source.slice(source.indexOf("READY=0"));
  assert.doesNotMatch(readinessLoop, /\/healthz/);
});

test("installer preserves an explicit multi-workspace allowlist", async () => {
  const source = await readFile(installer, "utf8");
  const launchSource = await readFile(launcher, "utf8");

  assert.match(source, /WORKSPACES=\$\{VIBE_POCKET_WORKSPACES:-\}/);
  assert.match(source, /VIBE_POCKET_WORKSPACES:-.*CONFIG_FILE/s);
  assert.match(source, /if \[\[ -n "\$WORKSPACES" \]\][\s\S]*?VIBE_POCKET_WORKSPACES=%q/);
  assert.match(source, /else[\s\S]*?VIBE_POCKET_WORKSPACE=%q/);
  assert.match(launchSource, /VIBE_POCKET_WORKSPACE="\$\{VIBE_POCKET_WORKSPACE:-\}"/);
  assert.match(launchSource, /VIBE_POCKET_WORKSPACES="\$\{VIBE_POCKET_WORKSPACES:-\}"/);
});

test("launcher tolerates host capabilities omitted by an older wrapper", async () => {
  const launchSource = await readFile(launcher, "utf8");

  assert.match(launchSource, /VIBE_POCKET_HOST_SOCKET="\$\{VIBE_POCKET_HOST_SOCKET:-\}"/);
});

test("installer restores the previous deployment until exact readiness commits cutover", async () => {
  const source = await readFile(installer, "utf8");
  const bootouts = [...source.matchAll(/launchctl bootout/g)];
  const cutover = source.indexOf("CUTOVER_PENDING=1");
  const bootout = source.indexOf('launchctl bootout "gui/$UID/$LABEL"', cutover);
  const bootstrap = source.indexOf('launchctl bootstrap "gui/$UID" "$PLIST"', cutover);
  const committed = source.indexOf("CUTOVER_PENDING=0", bootstrap);
  const readinessFailure = source.indexOf("if (( ! READY ))", bootstrap);

  assert.equal(bootouts.length, 2);
  assert.match(source, /trap restore_interrupted_cutover EXIT/);
  assert.doesNotMatch(source, /local status=/);
  assert.match(source, /if \(\( CUTOVER_PENDING \)\)[\s\S]*?rm -rf "\$RUNTIME_DIR" "\$HOST_APP" "\$PAIR_APP"/);
  assert.match(source, /HAD_RUNTIME[\s\S]*?mv "\$ROLLBACK_RUNTIME" "\$RUNTIME_DIR"/);
  assert.match(source, /HAD_HOST[\s\S]*?mv "\$ROLLBACK_HOST" "\$HOST_APP"/);
  assert.match(source, /HAD_CONFIG[\s\S]*?mv "\$ROLLBACK_CONFIG" "\$CONFIG_FILE"/);
  assert.match(source, /HAD_PLIST[\s\S]*?mv "\$ROLLBACK_PLIST" "\$PLIST"/);
  assert.ok(cutover >= 0 && cutover < bootout);
  assert.ok(bootstrap > bootout);
  assert.ok(readinessFailure > bootstrap);
  assert.ok(committed > readinessFailure);
});

test("installer restores the previous runtime and host when host compilation fails", async (t) => {
  const home = await mkdtemp(join(tmpdir(), "vibe-pocket-install-rollback-"));
  t.after(() => rm(home, { recursive: true, force: true }));
  const config = join(home, "Library", "Application Support", "Vibe Pocket");
  const runtime = join(config, "runtime");
  const host = join(config, "Vibe Pocket Bridge Host.app", "Contents", "MacOS");
  const launchAgents = join(home, "Library", "LaunchAgents");
  const applications = join(home, "Applications", "Pair Vibe Pocket.app");
  const fakeBin = join(home, "bin");
  const launchctlLog = join(home, "launchctl.log");
  await Promise.all([
    mkdir(runtime, { recursive: true }),
    mkdir(host, { recursive: true }),
    mkdir(launchAgents, { recursive: true }),
    mkdir(applications, { recursive: true }),
    mkdir(fakeBin, { recursive: true }),
  ]);
  await Promise.all([
    writeFile(join(runtime, "previous-runtime"), "healthy\n"),
    writeFile(join(host, "previous-host"), "healthy\n"),
    writeFile(join(config, "bridge.env"), "previous-config\n"),
    writeFile(join(config, "bridge-host.sha256"), "previous-hash\n"),
    writeFile(join(applications, "previous-pair-app"), "healthy\n"),
    writeFile(join(launchAgents, "au.edu.uts.vibepocket.bridge.plist"), "previous-plist\n"),
    writeFile(join(fakeBin, "launchctl"), "#!/bin/zsh\nprint -r -- \"$*\" >> \"$LAUNCHCTL_LOG\"\n"),
    writeFile(join(fakeBin, "codex"), "#!/bin/zsh\nexit 0\n"),
    writeFile(join(fakeBin, "ps"), "#!/bin/zsh\nexit 0\n"),
    writeFile(join(fakeBin, "swiftc-fail"), "#!/bin/zsh\nexit 73\n"),
  ]);
  await Promise.all([
    chmod(join(fakeBin, "launchctl"), 0o755),
    chmod(join(fakeBin, "codex"), 0o755),
    chmod(join(fakeBin, "ps"), 0o755),
    chmod(join(fakeBin, "swiftc-fail"), 0o755),
  ]);

  const result = spawnSync("/bin/zsh", [installer.pathname], {
    encoding: "utf8",
    timeout: 30_000,
    env: {
      ...process.env,
      HOME: home,
      PATH: `${fakeBin}:${process.env.PATH}`,
      LAUNCHCTL_LOG: launchctlLog,
      VIBE_POCKET_NODE: process.execPath,
      VIBE_POCKET_SWIFTC: join(fakeBin, "swiftc-fail"),
      VIBE_POCKET_TOKEN: "a".repeat(32),
      VIBE_POCKET_PORT: "49191",
    },
  });

  assert.equal(result.status, 73, result.stderr);
  assert.equal(await readFile(join(runtime, "previous-runtime"), "utf8"), "healthy\n");
  assert.equal(await readFile(join(host, "previous-host"), "utf8"), "healthy\n");
  assert.equal(await readFile(join(config, "bridge.env"), "utf8"), "previous-config\n");
  assert.equal(await readFile(join(config, "bridge-host.sha256"), "utf8"), "previous-hash\n");
  assert.equal(await readFile(join(applications, "previous-pair-app"), "utf8"), "healthy\n");
  assert.equal(
    await readFile(join(launchAgents, "au.edu.uts.vibepocket.bridge.plist"), "utf8"),
    "previous-plist\n",
  );
  assert.match(await readFile(launchctlLog, "utf8"), /bootout[\s\S]*bootstrap[\s\S]*kickstart/);
});
