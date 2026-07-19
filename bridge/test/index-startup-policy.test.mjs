import assert from "node:assert/strict";
import { mkdtemp, lstat, rm } from "node:fs/promises";
import { createConnection, createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  createRuntimeReadiness,
  listen,
  listenOnOwnedUnixSocket,
  startService,
} from "../src/index.mjs";
import { Readiness } from "../src/server/readiness.mjs";

test("awaits public and pairing binds before starting Accessibility discovery", async () => {
  const source = await readFile(new URL("../src/index.mjs", import.meta.url), "utf8");
  const publicBind = source.indexOf("await listen(server, config.port, config.host);");
  const pairingBind = source.indexOf("await listenOnOwnedUnixSocket(admin, config.pairingSocketPath);");
  const startup = source.indexOf("await service.start();");

  assert.ok(publicBind >= 0);
  assert.ok(pairingBind > publicBind);
  assert.ok(startup > pairingBind);
});

test("does not become ready until service startup completes", async () => {
  let completeStartup;
  const startup = new Promise((resolve) => { completeStartup = resolve; });
  const service = { start: () => startup };
  const readiness = new Readiness({
    runtimeIdentity: `sha256:${"b".repeat(64)}`,
    protocolVersion: 9,
  });

  const starting = startService(service, readiness);
  assert.equal(readiness.response().status, 503);

  completeStartup();
  await starting;
  assert.equal(readiness.response().status, 200);
});

test("creates deterministic readiness for an unmanifested source checkout", async () => {
  const sourceRoot = fileURLToPath(new URL("../", import.meta.url));

  const first = await createRuntimeReadiness(sourceRoot);
  const second = await createRuntimeReadiness(sourceRoot);
  first.markReady();
  second.markReady();

  assert.match(first.response().body.runtimeIdentity, /^sha256:[0-9a-f]{64}$/);
  assert.equal(second.response().body.runtimeIdentity, first.response().body.runtimeIdentity);
  assert.equal(first.response().body.protocolVersion, 9);
});

test("delegates signals to the ordered shutdown coordinator", async () => {
  const source = await readFile(new URL("../src/index.mjs", import.meta.url), "utf8");

  assert.match(source, /new Shutdown\(\{/);
  assert.match(source, /servers: \[server, admin\]/);
  assert.match(source, /shutdown\.close\(\)/);
});

test("reports a duplicate public bind before startup can continue", async (t) => {
  const running = createServer();
  const duplicate = createServer();
  t.after(async () => {
    await close(duplicate);
    await close(running);
  });
  await listen(running, 0, "127.0.0.1");
  const { port } = running.address();

  await assert.rejects(
    () => listen(duplicate, port, "127.0.0.1"),
    (error) => error?.code === "EADDRINUSE",
  );
});

test("does not unlink a healthy pairing socket", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-pairing-live-"));
  const socketPath = join(root, "pairing.sock");
  const running = createServer((socket) => socket.end());
  const duplicate = createServer();
  t.after(async () => {
    await close(duplicate);
    await close(running);
    await rm(root, { recursive: true, force: true });
  });
  await listen(running, socketPath);
  const before = await lstat(socketPath);

  await assert.rejects(
    () => listenOnOwnedUnixSocket(duplicate, socketPath),
    (error) => error?.code === "EADDRINUSE",
  );

  const after = await lstat(socketPath);
  assert.equal(after.dev, before.dev);
  assert.equal(after.ino, before.ino);
  await connect(socketPath);
});

test("an old pairing lease cannot unlink a replacement socket", async (t) => {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-pairing-owner-"));
  const socketPath = join(root, "pairing.sock");
  const original = createServer();
  const replacement = createServer((socket) => socket.end());
  t.after(async () => {
    await close(original);
    await close(replacement);
    await rm(root, { recursive: true, force: true });
  });
  const lease = await listenOnOwnedUnixSocket(original, socketPath);
  await close(original);
  await rm(socketPath, { force: true });
  await listen(replacement, socketPath);
  const before = await lstat(socketPath);

  lease.release();

  const after = await lstat(socketPath);
  assert.equal(after.dev, before.dev);
  assert.equal(after.ino, before.ino);
  await connect(socketPath);
});

function connect(socketPath) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ path: socketPath });
    socket.once("connect", () => {
      socket.destroy();
      resolve();
    });
    socket.once("error", reject);
  });
}

function close(server) {
  if (!server.listening) return Promise.resolve();
  return new Promise((resolve, reject) => {
    server.close((error) => {
      if (error) reject(error);
      else resolve();
    });
  });
}
