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

test("bounds settled idempotency outcomes", async () => {
  const ledger = new Idempotency();
  let firstCalls = 0;
  for (let index = 0; index < 257; index += 1) {
    await ledger.once(`settled-${index}`, { index }, async () => {
      if (index === 0) firstCalls += 1;
      return index;
    });
  }

  await ledger.once("settled-0", { index: 0 }, async () => {
    firstCalls += 1;
    return 0;
  });
  assert.equal(firstCalls, 2);
});
