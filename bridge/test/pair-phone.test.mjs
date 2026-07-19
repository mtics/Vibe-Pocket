import assert from "node:assert/strict";
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const BRIDGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const LAUNCHER = join(BRIDGE_ROOT, "bin", "pair-phone.sh");
const CLAIM_CODE = "abcdefghijklmnopqrstuvwxyzABCDEF";
const PAIRING_URL = `vibepocket://pair?origin=https%3A%2F%2Fbridge.test&code=${CLAIM_CODE}`;
const VALID_RESPONSE = JSON.stringify({
  pairingUrl: PAIRING_URL,
  expiresAt: "2099-01-01T00:05:00.000Z",
});

async function fixture(t, { response = VALID_RESPONSE, device = true, openExit = 0 } = {}) {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-pair-launcher-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const home = join(root, "home");
  const configDirectory = join(home, "Library", "Application Support", "Vibe Pocket");
  const bin = join(root, "bin");
  await mkdir(bin, { recursive: true });
  await mkdir(configDirectory, { recursive: true });
  await writeFile(join(configDirectory, "bridge.env"), "VIBE_POCKET_PORT=4320\n");

  const paths = {
    nodeLog: join(root, "node-argv.log"),
    adbLog: join(root, "adb-argv.log"),
    adbStdin: join(root, "adb-stdin.log"),
    openLog: join(root, "open.log"),
  };

  await executable(join(bin, "node"), `#!/bin/zsh
{
  print -r -- CALL
  for argument in "$@"; do print -r -- "$argument"; done
} >> "$NODE_ARGV_LOG"
exec "$REAL_NODE" "$@"
`);
  await executable(join(bin, "curl"), `#!/bin/zsh
for argument in "$@"; do
  if [[ "$argument" == */healthz ]]; then
    print -r -- '{"protocolVersion":6}'
    exit 0
  fi
  if [[ "$argument" == */v1/pairing/invitations ]]; then
    print -r -- "$PAIRING_RESPONSE"
    exit 0
  fi
done
exit 2
`);
  await executable(join(bin, "adb"), `#!/bin/zsh
print -r -- "$*" >> "$ADB_ARGV_LOG"
if [[ "$1" == devices ]]; then
  print -r -- 'List of devices attached'
  ${device ? "print -r -- 'phone-123 device'" : "true"}
  exit 0
fi
/bin/cat > "$ADB_STDIN_LOG"
exit 0
`);
  await executable(join(bin, "open"), `#!/bin/zsh
pairing_file="$@[-1]"
mode=$(/usr/bin/stat -f '%Lp' "$pairing_file" 2>/dev/null || /usr/bin/stat -c '%a' "$pairing_file")
print -r -- "$mode $pairing_file" > "$OPEN_LOG"
exit ${openExit}
`);

  return {
    root,
    configDirectory,
    paths,
    environment: {
      ...process.env,
      HOME: home,
      REAL_NODE: process.execPath,
      NODE_ARGV_LOG: paths.nodeLog,
      ADB_ARGV_LOG: paths.adbLog,
      ADB_STDIN_LOG: paths.adbStdin,
      OPEN_LOG: paths.openLog,
      PAIRING_RESPONSE: response,
      VIBE_POCKET_NODE: join(bin, "node"),
      VIBE_POCKET_CURL: join(bin, "curl"),
      VIBE_POCKET_ADB: join(bin, "adb"),
      VIBE_POCKET_OPEN: join(bin, "open"),
      VIBE_POCKET_PUBLIC_URL: "https://bridge.test",
    },
  };
}

test("keeps the pairing URL and claim code out of Node and adb argv", async (t) => {
  const setup = await fixture(t);
  const result = await runLauncher(setup.environment);

  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /Pairing invitation sent to phone-123/);
  const nodeArgv = await readFile(setup.paths.nodeLog, "utf8");
  const adbArgv = await readFile(setup.paths.adbLog, "utf8");
  assert.equal(nodeArgv.includes(PAIRING_URL), false);
  assert.equal(nodeArgv.includes(CLAIM_CODE), false);
  assert.equal(adbArgv.includes(PAIRING_URL), false);
  assert.equal(adbArgv.includes(CLAIM_CODE), false);
  assert.match(adbArgv, /-s phone-123 shell sh/);
  assert.match(await readFile(setup.paths.adbStdin, "utf8"), new RegExp(CLAIM_CODE));
});

test("shell-quotes the deep link passed through adb stdin", async (t) => {
  const quotedUrl = `${PAIRING_URL}&note=it's`;
  const setup = await fixture(t, {
    response: JSON.stringify({ pairingUrl: quotedUrl, expiresAt: "2099-01-01T00:05:00.000Z" }),
  });
  const result = await runLauncher(setup.environment);

  assert.equal(result.code, 0, result.stderr);
  const command = await readFile(setup.paths.adbStdin, "utf8");
  assert.equal(command.includes("note=it'\\''s"), true);
  assert.equal(command.includes("note=it's"), false);
});

test("rejects malformed admin responses before invoking adb", async (t) => {
  for (const response of ["not json", "{}", '{"pairingUrl":42}']) {
    await t.test(response, async (t) => {
      const setup = await fixture(t, { response });
      const result = await runLauncher(setup.environment);
      assert.notEqual(result.code, 0);
      assert.match(result.stderr, /invalid pairing invitation/);
      assert.equal(await readOptional(setup.paths.adbLog), "");
    });
  }
});

test("creates the fallback invitation with mode 0600 and removes it when open fails", async (t) => {
  const setup = await fixture(t, { device: false, openExit: 1 });
  await mkdir(join(setup.configDirectory, "Vibe Pocket Bridge Host.app"));
  const result = await runLauncher(setup.environment);

  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /pairing window could not be opened/);
  assert.match(await readFile(setup.paths.openLog, "utf8"), /^600 /);
  assert.deepEqual(await readdir(join(setup.configDirectory, "invitations")), []);
});

async function executable(path, contents) {
  await writeFile(path, contents);
  await chmod(path, 0o700);
}

async function runLauncher(environment) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn("/bin/zsh", [LAUNCHER], { env: environment });
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code, signal) => resolvePromise({ code, signal, stdout, stderr }));
  });
}

async function readOptional(path) {
  return readFile(path, "utf8").catch((error) => {
    if (error.code === "ENOENT") return "";
    throw error;
  });
}
