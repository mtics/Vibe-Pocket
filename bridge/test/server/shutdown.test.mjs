import assert from "node:assert/strict";
import test from "node:test";

import { Shutdown } from "../../src/server/shutdown.mjs";
import { RequestTracker } from "../../src/server/request-tracker.mjs";

test("request tracking rejects new work and drains existing handlers", async () => {
  const tracker = new RequestTracker();
  const gate = Promise.withResolvers();
  const active = tracker.run(() => gate.promise);
  tracker.stop();
  let drained = false;
  const draining = tracker.drain().then(() => { drained = true; });

  await assert.rejects(
    () => tracker.run(async () => {}),
    (error) => error.code === "bridge_stopping",
  );
  assert.equal(drained, false);
  gate.resolve();
  await active;
  await draining;
  assert.equal(drained, true);
});

test("shutdown is idempotent and waits for an active command before closing SSE and desktop", async () => {
  const order = [];
  const handlersDrained = Promise.withResolvers();
  const queueDrained = Promise.withResolvers();
  const publicClosed = Promise.withResolvers();
  const adminClosed = Promise.withResolvers();
  const server = fakeServer("public", handlersDrained.promise, publicClosed.promise, order);
  const admin = fakeServer("admin", Promise.resolve(), adminClosed.promise, order);
  const service = {
    async stop() {
      order.push("service:stop");
      await queueDrained.promise;
      order.push("queue:drained");
    },
    async dispose() { order.push("desktop:disposed"); },
  };
  const events = { close() { order.push("events:closed"); } };
  const shutdown = new Shutdown({
    servers: [server, admin],
    service,
    events,
    cleanup: () => order.push("cleanup"),
  });

  const first = shutdown.close();
  const replay = shutdown.close();
  assert.strictEqual(replay, first);
  assert.deepEqual(order, ["public:stop", "admin:stop"]);

  handlersDrained.resolve();
  await new Promise(setImmediate);
  assert.deepEqual(order, ["public:stop", "admin:stop", "service:stop"]);

  queueDrained.resolve();
  await new Promise(setImmediate);
  assert.deepEqual(order, [
    "public:stop",
    "admin:stop",
    "service:stop",
    "queue:drained",
    "events:closed",
  ]);

  publicClosed.resolve();
  adminClosed.resolve();
  await first;
  assert.deepEqual(order.slice(-2), ["cleanup", "desktop:disposed"]);
});

test("forces transports and finishes cleanup when graceful shutdown exceeds its deadline", async () => {
  const never = new Promise(() => {});
  const order = [];
  const server = {
    stopAccepting() { order.push("server:stop"); return never; },
    drain() { return never; },
    destroyConnections() { order.push("server:destroy"); },
  };
  const shutdown = new Shutdown({
    servers: [server],
    service: {
      stop() { order.push("service:stop"); return never; },
      dispose() { order.push("service:dispose"); },
    },
    events: { close() { order.push("events:close"); } },
    cleanup: () => order.push("cleanup"),
    deadlineMs: 25,
  });

  await assert.rejects(shutdown.close(), /shutdown exceeded its deadline/);
  assert.deepEqual(order, [
    "server:stop",
    "service:stop",
    "events:close",
    "server:destroy",
    "cleanup",
    "service:dispose",
  ]);
});

function fakeServer(name, drained, closed, order) {
  return {
    stopAccepting() {
      order.push(`${name}:stop`);
      return closed;
    },
    drain() { return drained; },
  };
}
