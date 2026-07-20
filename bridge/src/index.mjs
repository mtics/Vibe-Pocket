import { chmodSync, lstatSync, rmSync } from "node:fs";
import { createConnection } from "node:net";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { load } from "./config.mjs";
import { Rpc } from "./codex/rpc.mjs";
import { Catalog } from "./task/catalog.mjs";
import { Store } from "./profile/store.mjs";
import { Session } from "./control/session.mjs";
import { Desktop } from "./macos/desktop.mjs";
import { create } from "./server/http.mjs";
import { create as createAdmin } from "./server/admin.mjs";
import { Events } from "./server/events.mjs";
import { Shutdown } from "./server/shutdown.mjs";
import { Invitations } from "./pairing/invitations.mjs";
import { Credentials } from "./pairing/credentials.mjs";
import { PROTOCOL_VERSION } from "./protocol.mjs";
import { resolveRuntimeIdentity } from "./runtime/identity.mjs";
import { Readiness } from "./server/readiness.mjs";

const PAIRING_PROBE_TIMEOUT_MS = 250;

export async function main() {
  const config = load();
  const runtimeRoot = fileURLToPath(new URL("../", import.meta.url));
  const readiness = await createRuntimeReadiness(runtimeRoot);
  const events = new Events();
  const profileStore = new Store({ profilePath: config.profilePath });
  const appServer = new Rpc({ command: config.codexCommand });
  appServer.on("serverRequest", (message) => {
    appServer.respondWithError(message.id, -32601, "Vibe Pocket's task catalog is read-only.");
  });
  const threadCatalog = new Catalog({ appServer, workspaces: config.workspaces });
  const desktop = new Desktop({ threadCatalog });
  const service = new Session({
    workspaces: config.workspaces,
    events,
    profileStore,
    operationPath: config.operationPath,
    desktop,
  });
  const credentials = new Credentials({ path: config.devicesPath, rootToken: config.token });
  const invitations = new Invitations({ issue: (expiresAt) => credentials.issue(expiresAt) });
  const server = create({ service, events, token: config.token, credentials, invitations, readiness });
  const admin = createAdmin({ invitations });

  await listen(server, config.port, config.host);
  let pairingLease;
  try {
    pairingLease = await listenOnOwnedUnixSocket(admin, config.pairingSocketPath);
  } catch (error) {
    await closeServer(server);
    throw error;
  }

  const shutdown = new Shutdown({
    servers: [server, admin],
    service,
    events,
    cleanup: () => pairingLease.release(),
  });

  function close() {
    void shutdown.close().catch((error) => {
      console.error("Vibe Pocket bridge shutdown failed:", error);
      process.exitCode = 1;
    });
  }

  process.once("SIGINT", close);
  process.once("SIGTERM", close);

  console.log(`Vibe Pocket bridge listening on http://${config.host}:${config.port}`);
  console.log(`Configured workspaces: ${Object.keys(config.workspaces).join(", ")}`);
  console.log(`Controller profile: ${config.profilePath}`);
  console.log("Codex control engine: Bluetooth HID, native task links, and scoped macOS Accessibility");

  try {
    await startService(service, readiness);
  } catch (error) {
    await shutdown.close();
    throw error;
  }
}

export async function createRuntimeReadiness(runtimeRoot) {
  const runtimeIdentity = await resolveRuntimeIdentity(runtimeRoot);
  return new Readiness({ runtimeIdentity, protocolVersion: PROTOCOL_VERSION });
}

export async function startService(service, readiness) {
  readiness.markNotReady();
  await service.start();
  readiness.markReady();
}

export function listen(server, ...args) {
  return new Promise((resolvePromise, reject) => {
    const onError = (error) => {
      server.off("listening", onListening);
      reject(error);
    };
    const onListening = () => {
      server.off("error", onError);
      resolvePromise();
    };
    server.once("error", onError);
    server.once("listening", onListening);
    server.listen(...args);
  });
}

export async function listenOnOwnedUnixSocket(server, socketPath, {
  probeTimeoutMs = PAIRING_PROBE_TIMEOUT_MS,
} = {}) {
  const observed = socketIdentity(socketPath);
  if (observed) {
    if (!observed.isSocket || await probeUnixSocket(socketPath, probeTimeoutMs)) {
      throw socketInUseError(socketPath);
    }
    const confirmed = socketIdentity(socketPath);
    if (!sameSocket(observed, confirmed)) throw socketInUseError(socketPath);
    rmSync(socketPath);
  }

  let owned = null;
  try {
    await listen(server, socketPath);
    owned = socketIdentity(socketPath);
    if (!owned?.isSocket) throw new Error(`Pairing socket was not created safely: ${socketPath}`);
    chmodSync(socketPath, 0o600);
    if (!sameSocket(owned, socketIdentity(socketPath))) {
      throw new Error(`Pairing socket ownership changed during startup: ${socketPath}`);
    }
  } catch (error) {
    await closeServer(server);
    removeOwnedSocket(socketPath, owned);
    throw error;
  }

  let released = false;
  return {
    release() {
      if (released) return;
      released = true;
      removeOwnedSocket(socketPath, owned);
    },
  };
}

async function probeUnixSocket(socketPath, timeoutMs) {
  return new Promise((resolvePromise, reject) => {
    const socket = createConnection({ path: socketPath });
    let settled = false;
    const finish = (value, error = null) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      socket.destroy();
      if (error) reject(error);
      else resolvePromise(value);
    };
    const timeout = setTimeout(() => finish(true), timeoutMs);
    socket.once("connect", () => finish(true));
    socket.once("error", (error) => {
      if (error?.code === "ENOENT" || error?.code === "ECONNREFUSED") finish(false);
      else finish(false, error);
    });
  });
}

function socketIdentity(socketPath) {
  try {
    const entry = lstatSync(socketPath);
    return { dev: entry.dev, ino: entry.ino, isSocket: entry.isSocket() };
  } catch (error) {
    if (error?.code === "ENOENT" || error?.code === "ENOTDIR") return null;
    throw error;
  }
}

function sameSocket(left, right) {
  return Boolean(left && right && left.isSocket && right.isSocket
    && left.dev === right.dev && left.ino === right.ino);
}

function removeOwnedSocket(socketPath, owned) {
  if (sameSocket(owned, socketIdentity(socketPath))) rmSync(socketPath);
}

function socketInUseError(socketPath) {
  const error = new Error(`Pairing socket is already owned by a running service: ${socketPath}`);
  error.code = "EADDRINUSE";
  return error;
}

function closeServer(server) {
  if (!server.listening) return Promise.resolve();
  return new Promise((resolvePromise, reject) => {
    server.close((error) => {
      if (error && error.code !== "ERR_SERVER_NOT_RUNNING") reject(error);
      else resolvePromise();
    });
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await main();
}
