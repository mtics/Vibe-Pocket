import assert from "node:assert/strict";
import { chmod, cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { PROTOCOL_VERSION } from "../src/protocol.mjs";
import { readyPayload, writeRuntimeIdentity } from "../src/runtime/identity.mjs";

const BRIDGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ANDROID_MANIFEST = resolve(BRIDGE_ROOT, "../android/app/src/main/AndroidManifest.xml");
const CLAIM_CODE = "abcdefghijklmnopqrstuvwxyzABCDEF";
const PAIRING_URL = `vibepocket://pair?origin=https%3A%2F%2Fbridge.test&code=${CLAIM_CODE}`;
const VALID_RESPONSE = JSON.stringify({
  pairingUrl: PAIRING_URL,
  expiresAt: "2099-01-01T00:05:00.000Z",
});

function expectedAdbCommand(deepLink) {
  return [
    "if pm path au.edu.uts.vibepocket >/dev/null 2>&1; then",
    `  exec am start -W -n au.edu.uts.vibepocket/.MainActivity -a android.intent.action.VIEW -d ${deepLink}`,
    "fi",
    "if pm path au.edu.uts.vibepocket.research >/dev/null 2>&1; then",
    `  exec am start -W -n au.edu.uts.vibepocket.research/au.edu.uts.vibepocket.MainActivity -a android.intent.action.VIEW -d ${deepLink}`,
    "fi",
    "echo 'Vibe Pocket is not installed.' >&2",
    "exit 1",
    "",
  ].join("\n");
}

test("delegates a source checkout to the exact installed pairing launcher", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-pair-delegation-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const home = join(root, "home");
  const installedLauncher = join(
    home,
    "Library",
    "Application Support",
    "Vibe Pocket",
    "runtime",
    "bin",
    "pair-phone.sh",
  );
  await mkdir(dirname(installedLauncher), { recursive: true });
  await executable(installedLauncher, "#!/bin/zsh\nprint -r -- delegated\n");

  const result = await runLauncher(join(BRIDGE_ROOT, "bin", "pair-phone.sh"), {
    ...process.env,
    HOME: home,
  });

  assert.equal(result.code, 0, result.stderr);
  assert.equal(result.stdout, "delegated\n");
});

async function fixture(t, {
  response = VALID_RESPONSE,
  device = true,
  openExit = 0,
  localProtocol = PROTOCOL_VERSION,
  remoteProtocol = PROTOCOL_VERSION,
  localIdentity = null,
  remoteIdentity = null,
} = {}) {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-pair-launcher-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const home = join(root, "home");
  const configDirectory = join(home, "Library", "Application Support", "Vibe Pocket");
  const bin = join(root, "bin");
  await mkdir(bin, { recursive: true });
  await mkdir(configDirectory, { recursive: true });
  await writeFile(join(configDirectory, "bridge.env"), "VIBE_POCKET_PORT=4320\n");
  const runtime = join(configDirectory, "runtime");
  await cp(BRIDGE_ROOT, runtime, {
    recursive: true,
    filter: (source) => source !== join(BRIDGE_ROOT, "node_modules")
      && !source.startsWith(`${join(BRIDGE_ROOT, "node_modules")}/`),
  });
  const installedIdentity = await writeRuntimeIdentity(runtime);
  const localReady = readyPayload(localIdentity ?? installedIdentity, localProtocol);
  const remoteReady = readyPayload(remoteIdentity ?? installedIdentity, remoteProtocol);

  const paths = {
    nodeLog: join(root, "node-argv.log"),
    curlLog: join(root, "curl-argv.log"),
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
print -r -- "$*" >> "$CURL_ARGV_LOG"
for argument in "$@"; do
  if [[ "$argument" == http://127.0.0.1:*/readyz ]]; then
    print -r -- "$LOCAL_READY"
    exit 0
  fi
  if [[ "$argument" == */readyz ]]; then
    print -r -- "$REMOTE_READY"
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
    launcher: join(runtime, "bin", "pair-phone.sh"),
    configDirectory,
    paths,
    environment: {
      ...process.env,
      HOME: home,
      REAL_NODE: process.execPath,
      NODE_ARGV_LOG: paths.nodeLog,
      CURL_ARGV_LOG: paths.curlLog,
      ADB_ARGV_LOG: paths.adbLog,
      ADB_STDIN_LOG: paths.adbStdin,
      OPEN_LOG: paths.openLog,
      LOCAL_READY: JSON.stringify(localReady),
      REMOTE_READY: JSON.stringify(remoteReady),
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
  const result = await runLauncher(setup.launcher, setup.environment);

  assert.equal(result.code, 0, result.stderr);
  assert.match(result.stdout, /Pairing invitation sent to phone-123/);
  const nodeArgv = await readFile(setup.paths.nodeLog, "utf8");
  const adbArgv = await readFile(setup.paths.adbLog, "utf8");
  assert.equal(nodeArgv.includes(PAIRING_URL), false);
  assert.equal(nodeArgv.includes(CLAIM_CODE), false);
  assert.equal(adbArgv.includes(PAIRING_URL), false);
  assert.equal(adbArgv.includes(CLAIM_CODE), false);
  assert.match(adbArgv, /-s phone-123 shell sh/);
  const adbStdin = await readFile(setup.paths.adbStdin, "utf8");
  assert.match(adbStdin, new RegExp(CLAIM_CODE));
  assert.match(
    adbStdin,
    /^if pm path au\.edu\.uts\.vibepocket >\/dev\/null 2>&1; then\n  exec am start -W -n au\.edu\.uts\.vibepocket\/\.MainActivity -a android\.intent\.action\.VIEW -d /,
  );
  assert.match(
    adbStdin,
    /au\.edu\.uts\.vibepocket\.research\/au\.edu\.uts\.vibepocket\.MainActivity/,
  );
  assert.doesNotMatch(adbStdin, /(?:^|\s)-p(?:\s|$)/);
});

test("shell-quotes the deep link passed through adb stdin", async (t) => {
  const quotedUrl = `${PAIRING_URL}&note=it's;$(touch%20/tmp/pwned)`;
  const response = JSON.stringify({
    pairingUrl: quotedUrl,
    expiresAt: "2099-01-01T00:05:00.000Z",
  });
  const result = await runPairingResponse(response);

  assert.equal(result.code, 0, result.stderr);
  const shellQuotedUrl = `'${quotedUrl.replaceAll("'", "'\\''")}'`;
  assert.equal(
    result.stdout,
    expectedAdbCommand(shellQuotedUrl),
  );
  assert.equal(result.stdout.includes("note=it's"), false);
});

test("keeps vibepocket pairing private while preserving the launcher", async () => {
  const manifest = await readFile(ANDROID_MANIFEST, "utf8");
  const intentFilters = manifest.match(/<intent-filter\b[\s\S]*?<\/intent-filter>/g) ?? [];

  assert.equal(
    intentFilters.some((filter) => /android:scheme\s*=\s*"vibepocket"/.test(filter)),
    false,
  );
  assert.equal(
    intentFilters.some((filter) => (
      filter.includes("android.intent.action.MAIN")
      && filter.includes("android.intent.category.LAUNCHER")
    )),
    true,
  );
});

test("requires exact local and remote readiness before creating an invitation", async (t) => {
  for (const mismatch of [
    { localProtocol: PROTOCOL_VERSION - 1, remoteProtocol: PROTOCOL_VERSION },
    { localProtocol: PROTOCOL_VERSION, remoteProtocol: PROTOCOL_VERSION - 1 },
    { localIdentity: `sha256:${"1".repeat(64)}` },
    { remoteIdentity: `sha256:${"2".repeat(64)}` },
  ]) {
    await t.test(JSON.stringify(mismatch), async (t) => {
      const setup = await fixture(t, mismatch);
      const result = await runLauncher(setup.launcher, setup.environment);

      assert.notEqual(result.code, 0);
      assert.match(result.stderr, new RegExp(`runtime identity and pairing protocol ${PROTOCOL_VERSION}`));
      assert.doesNotMatch(await readFile(setup.paths.curlLog, "utf8"), /\/v1\/pairing\/invitations/);
      assert.equal(await readOptional(setup.paths.adbLog), "");
    });
  }
});

test("bounds the local pairing invitation response", async (t) => {
  const setup = await fixture(t);
  const result = await runLauncher(setup.launcher, setup.environment);

  assert.equal(result.code, 0, result.stderr);
  const invitationCall = (await readFile(setup.paths.curlLog, "utf8"))
    .split("\n")
    .find((line) => line.includes("/v1/pairing/invitations"));
  assert.ok(invitationCall);
  assert.match(invitationCall, /--connect-timeout 1/);
  assert.match(invitationCall, /--max-time 3/);
  assert.match(invitationCall, /--max-filesize 16384/);
});

test("rejects malformed admin responses before invoking adb", async (t) => {
  for (const response of ["not json", "{}", '{"pairingUrl":42}']) {
    await t.test(response, async (t) => {
      const setup = await fixture(t, { response });
      const result = await runLauncher(setup.launcher, setup.environment);
      assert.notEqual(result.code, 0);
      assert.match(result.stderr, /invalid pairing invitation/);
      assert.equal(await readOptional(setup.paths.adbLog), "");
    });
  }
});

test("creates the fallback invitation with mode 0600 and removes it when open fails", async (t) => {
  const setup = await fixture(t, { device: false, openExit: 1 });
  await mkdir(join(setup.configDirectory, "Vibe Pocket Bridge Host.app"));
  const result = await runLauncher(setup.launcher, setup.environment);

  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /pairing window could not be opened/);
  assert.match(await readFile(setup.paths.openLog, "utf8"), /^600 /);
  assert.deepEqual(await readdir(join(setup.configDirectory, "invitations")), []);
});

async function executable(path, contents) {
  await writeFile(path, contents);
  await chmod(path, 0o700);
}

async function runLauncher(launcher, environment) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn("/bin/zsh", [launcher], { env: environment });
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

async function runPairingResponse(response) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(process.execPath, [resolve(BRIDGE_ROOT, "bin/pairing-response.mjs")]);
    let stdout = "";
    let stderr = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", reject);
    child.once("close", (code, signal) => resolvePromise({ code, signal, stdout, stderr }));
    child.stdin.end(response);
  });
}

async function readOptional(path) {
  return readFile(path, "utf8").catch((error) => {
    if (error.code === "ENOENT") return "";
    throw error;
  });
}
