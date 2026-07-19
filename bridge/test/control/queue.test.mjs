import assert from "node:assert/strict";
import test from "node:test";

import { Queue } from "../../src/control/queue.mjs";

test("enforces per-principal and global pending limits", async () => {
  const queue = new Queue({ maxPending: 2, maxPendingPerPrincipal: 1 });
  const gate = Promise.withResolvers();
  const first = { id: "device:first", valid: () => true };
  const second = { id: "device:second", valid: () => true };
  const third = { id: "device:third", valid: () => true };
  const active = queue.run(() => gate.promise, { principal: first });

  await assert.rejects(
    queue.run(async () => {}, { principal: first }),
    (error) => error.code === "principal_command_queue_full",
  );
  const queued = queue.run(async () => "second", { principal: second });
  await assert.rejects(
    queue.run(async () => {}, { principal: third }),
    (error) => error.code === "command_queue_full",
  );

  gate.resolve();
  await active;
  assert.equal(await queued, "second");
  assert.equal(await queue.run(async () => "third", { principal: third }), "third");
});

test("releases a pre-reserved admission when shutdown wins before enqueue", async () => {
  const queue = new Queue({ maxPending: 1, maxPendingPerPrincipal: 1 });
  const principal = { id: "device:first", valid: () => true };
  const admission = queue.reserve({ principal });
  await queue.stop();

  await assert.rejects(
    queue.run(async () => {}, { principal, admission }),
    (error) => error.code === "bridge_stopping",
  );
});
