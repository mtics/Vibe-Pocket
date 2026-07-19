import assert from "node:assert/strict";
import test from "node:test";

import { Idempotency } from "../../src/control/idempotency.mjs";

test("keeps 257 in-flight idempotency entries non-evictable", async () => {
  const ledger = new Idempotency();
  const gate = Promise.withResolvers();
  const calls = Array(257).fill(0);
  const executions = Array.from({ length: 257 }, (_, index) => ledger.once(
    `pending-${index}`,
    { kind: "action", index },
    async () => {
      calls[index] += 1;
      await gate.promise;
      return { index };
    },
  ));
  await new Promise(setImmediate);

  const replay = ledger.once("pending-0", { index: 0, kind: "action" }, async () => {
    calls[0] += 1;
    return { index: 0 };
  });
  assert.strictEqual(replay, executions[0]);
  assert.equal(calls[0], 1);

  gate.resolve();
  await Promise.all(executions);
  assert.ok(calls.every((count) => count === 1));
});

test("removes definite pre-dispatch failures so the key can be retried", async () => {
  const ledger = new Idempotency();
  let calls = 0;

  await assert.rejects(
    () => ledger.once("retryable", { nested: { b: 2, a: 1 } }, async () => {
      calls += 1;
      throw new Error("validation failed before dispatch");
    }),
    /validation failed/,
  );
  const result = await ledger.once("retryable", { nested: { a: 1, b: 2 } }, async () => {
    calls += 1;
    return "accepted";
  });

  assert.equal(result, "accepted");
  assert.equal(calls, 2);
});

test("ages settled outcomes into tombstones instead of redispatching", async () => {
  const ledger = new Idempotency();
  let firstCalls = 0;
  for (let index = 0; index < 257; index += 1) {
    await ledger.once(`settled-${index}`, { index }, async () => {
      if (index === 0) firstCalls += 1;
      return index;
    });
  }

  assert.throws(
    () => ledger.once("settled-0", { index: 0 }, async () => {
      firstCalls += 1;
      return 0;
    }),
    (error) => error.code === "idempotency_result_expired",
  );
  assert.equal(firstCalls, 1);
});

test("bounds tombstones and forgets their principal ownership together", async () => {
  const ledger = new Idempotency({ settledLimit: 1, tombstoneLimit: 1 });
  const principal = { id: "device:first" };
  let firstCalls = 0;
  await ledger.once("first", { index: 0 }, async () => { firstCalls += 1; }, { principal });
  await ledger.once("second", { index: 1 }, async () => {}, { principal });
  await ledger.once("third", { index: 2 }, async () => {}, { principal });

  await ledger.once("first", { index: 0 }, async () => { firstCalls += 1; }, { principal });
  assert.equal(firstCalls, 2);
});

test("rejects a raw key collision from another principal without sharing an outcome", async () => {
  const ledger = new Idempotency();
  const first = { id: "device:first" };
  const second = { id: "device:second" };
  const result = await ledger.once("shared-key", { kind: "stop" }, async () => "first-result", { principal: first });
  assert.equal(result, "first-result");

  assert.throws(
    () => ledger.once("shared-key", { kind: "stop" }, async () => "second-result", { principal: second }),
    (error) => error.code === "idempotency_key_principal_collision",
  );
});

test("runs admission before ledger insertion and leaves a rejected key reusable", async () => {
  const ledger = new Idempotency();
  let operations = 0;
  assert.throws(
    () => ledger.once("admission-key", { kind: "stop" }, async () => {
      operations += 1;
    }, {
      admit: () => { throw new Error("queue full"); },
    }),
    /queue full/,
  );

  await ledger.once("admission-key", { kind: "stop" }, async () => { operations += 1; });
  assert.equal(operations, 1);
});
