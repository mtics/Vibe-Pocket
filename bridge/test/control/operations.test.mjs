import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { Operations, commandFingerprint } from "../../src/control/operations.mjs";

async function temporaryLog(t) {
  const root = await mkdtemp(join(tmpdir(), "vibe-pocket-operations-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  return join(root, "operations.json");
}

test("persists only bounded operation metadata with an atomic private file", async (t) => {
  const path = await temporaryLog(t);
  const operations = new Operations({ path, now: () => 1_000 });
  const command = { kind: "binding", inputId: "key_voice", secret: "not-persisted" };

  operations.create("operation-1", command, "credential:principal-digest");
  operations.markRunning("operation-1", "credential:principal-digest");
  const result = operations.markSucceeded("operation-1", "credential:principal-digest", {
    accepted: true,
    revision: "r_8",
  });

  assert.equal(result.status, "succeeded");
  assert.deepEqual(result.result, { accepted: true, revision: "r_8" });
  const serialized = await readFile(path, "utf8");
  assert.doesNotMatch(serialized, /not-persisted|key_voice/);
  assert.match(serialized, /credential:principal-digest/);
  assert.match(serialized, new RegExp(commandFingerprint(command)));
  assert.equal((await stat(path)).mode & 0o777, 0o600);
});

test("recovers accepted as definitely failed and running as unknown without replay", async (t) => {
  const path = await temporaryLog(t);
  const first = new Operations({ path, now: () => 1_000 });
  first.create("accepted-before-crash", { kind: "approve" }, "device:first");
  first.create("running-before-crash", { kind: "stop" }, "device:first");
  first.markRunning("running-before-crash", "device:first");

  const recovered = new Operations({ path, now: () => 2_000 });
  assert.deepEqual(recovered.get("accepted-before-crash", "device:first"), {
    operationId: "accepted-before-crash",
    status: "failed",
    error: {
      status: 503,
      code: "command_not_executed",
      message: "The Bridge restarted before this command began; it was not executed.",
    },
    createdAt: "1970-01-01T00:00:01.000Z",
    updatedAt: "1970-01-01T00:00:02.000Z",
  });
  assert.equal(recovered.get("running-before-crash", "device:first").status, "unknown");
  assert.equal(
    recovered.get("running-before-crash", "device:first").error.code,
    "command_outcome_indeterminate",
  );

  const restartedAgain = new Operations({ path, now: () => 3_000 });
  assert.equal(restartedAgain.get("accepted-before-crash", "device:first").status, "failed");
  assert.equal(restartedAgain.get("running-before-crash", "device:first").status, "unknown");
});

test("binds an operation globally to its principal and canonical command fingerprint", () => {
  const operations = new Operations();
  operations.create("shared-operation", { nested: { b: 2, a: 1 } }, "device:first");
  operations.markRunning("shared-operation", "device:first");
  operations.markSucceeded("shared-operation", "device:first", { accepted: true, revision: "r_1" });

  assert.equal(
    operations.match("shared-operation", { nested: { a: 1, b: 2 } }, "device:first").status,
    "succeeded",
  );
  assert.throws(
    () => operations.match("shared-operation", { nested: { a: 2, b: 1 } }, "device:first"),
    (error) => error.code === "idempotency_key_reused",
  );
  assert.throws(
    () => operations.match("shared-operation", { nested: { a: 1, b: 2 } }, "device:second"),
    (error) => error.code === "operation_id_unavailable" && !/succeeded/.test(error.message),
  );
  assert.throws(
    () => operations.get("shared-operation", "device:second"),
    (error) => error.status === 404 && error.code === "operation_not_found",
  );
});

test("fails closed at capacity and never evicts an old replay barrier", () => {
  const operations = new Operations({ maxEntries: 1 });
  operations.create("old-operation", { kind: "approve" }, "device:first");
  operations.markRunning("old-operation", "device:first");
  operations.markSucceeded("old-operation", "device:first", { accepted: true });

  assert.throws(
    () => operations.create("new-operation", { kind: "reject" }, "device:first"),
    (error) => error.status === 503 && error.code === "operation_capacity_reached",
  );
  assert.equal(operations.match("old-operation", { kind: "approve" }, "device:first").status, "succeeded");
});

test("does not expose an uncommitted accepted record after persistence failure", async (t) => {
  const path = await temporaryLog(t);
  const operations = new Operations({
    path,
    commit: () => { throw new Error("disk unavailable"); },
  });

  assert.throws(
    () => operations.create("not-committed", { kind: "approve" }, "device:first"),
    (error) => error.code === "operation_persistence_failed",
  );
  assert.throws(
    () => operations.get("not-committed", "device:first"),
    (error) => error.code === "operation_not_found",
  );
});

test("best-effort principal cleanup frees every record owned by the credential", () => {
  const operations = new Operations({ maxEntries: 2 });
  operations.create("settled", { kind: "approve" }, "device:first");
  operations.markRunning("settled", "device:first");
  operations.markSucceeded("settled", "device:first", { accepted: true });
  operations.create("pending", { kind: "stop" }, "device:first");

  assert.equal(operations.removePrincipal("device:first"), true);
  assert.throws(() => operations.get("settled", "device:first"), (error) => error.status === 404);
  assert.throws(() => operations.get("pending", "device:first"), (error) => error.status === 404);
});
