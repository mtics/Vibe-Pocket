import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  DEFAULT_MAX_OPERATIONS,
  DEFAULT_TERMINAL_RETENTION_MS,
  Operations,
  commandFingerprint,
  persistOperations,
} from "../../src/control/operations.mjs";

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

test("fails closed at capacity and never evicts a recent replay barrier", () => {
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

test("uses bounded defaults sized for a seven-day controller recovery window", () => {
  assert.equal(DEFAULT_TERMINAL_RETENTION_MS, 7 * 24 * 60 * 60 * 1_000);
  assert.equal(DEFAULT_MAX_OPERATIONS, 16_384);
});

test("retains every recent terminal state and prunes each at the expiry boundary", () => {
  let now = 0;
  const operations = new Operations({
    maxEntries: 3,
    terminalRetentionMs: 1_000,
    now: () => now,
  });

  operations.create("succeeded", { kind: "approve" }, "device:first");
  operations.markRunning("succeeded", "device:first");
  operations.markSucceeded("succeeded", "device:first", { accepted: true });
  operations.create("failed", { kind: "reject" }, "device:first");
  operations.markFailed("failed", "device:first", { status: 500, code: "test_failure" });
  operations.create("unknown", { kind: "stop" }, "device:first");
  operations.markRunning("unknown", "device:first");
  operations.markUnknown("unknown", "device:first");

  now = 999;
  assert.equal(operations.get("succeeded", "device:first").status, "succeeded");
  assert.equal(operations.get("failed", "device:first").status, "failed");
  assert.equal(operations.get("unknown", "device:first").status, "unknown");
  assert.equal(
    operations.match("succeeded", { kind: "approve" }, "device:first").status,
    "succeeded",
  );
  assert.throws(
    () => operations.create("blocked", { kind: "approve" }, "device:first"),
    (error) => error.code === "operation_capacity_reached",
  );

  now = 1_000;
  assert.equal(
    operations.create("replacement", { kind: "approve" }, "device:first").created,
    true,
  );
  for (const operationId of ["succeeded", "failed", "unknown"]) {
    assert.throws(
      () => operations.get(operationId, "device:first"),
      (error) => error.code === "operation_not_found",
    );
  }
});

test("never prunes accepted or running operations to make capacity", () => {
  let now = 0;
  const operations = new Operations({
    maxEntries: 2,
    terminalRetentionMs: 1_000,
    now: () => now,
  });
  operations.create("accepted", { kind: "approve" }, "device:first");
  operations.create("running", { kind: "stop" }, "device:first");
  operations.markRunning("running", "device:first");

  now = 10_000;
  assert.throws(
    () => operations.create("blocked", { kind: "reject" }, "device:first"),
    (error) => error.code === "operation_capacity_reached",
  );
  assert.equal(operations.get("accepted", "device:first").status, "accepted");
  assert.equal(operations.get("running", "device:first").status, "running");
});

test("recovers unresolved operations before startup pruning and later expires them", async (t) => {
  const path = await temporaryLog(t);
  let now = 0;
  const first = new Operations({
    path,
    maxEntries: 3,
    terminalRetentionMs: 100,
    now: () => now,
  });
  first.create("expired-terminal", { kind: "reject" }, "device:first");
  first.markFailed("expired-terminal", "device:first", { status: 500, code: "test_failure" });
  first.create("accepted-before-restart", { kind: "approve" }, "device:first");
  first.create("running-before-restart", { kind: "stop" }, "device:first");
  first.markRunning("running-before-restart", "device:first");

  now = 100;
  const recovered = new Operations({
    path,
    maxEntries: 3,
    terminalRetentionMs: 100,
    now: () => now,
  });
  assert.throws(
    () => recovered.get("expired-terminal", "device:first"),
    (error) => error.code === "operation_not_found",
  );
  assert.equal(recovered.get("accepted-before-restart", "device:first").status, "failed");
  assert.equal(recovered.get("running-before-restart", "device:first").status, "unknown");
  assert.equal(
    recovered.get("running-before-restart", "device:first").updatedAt,
    "1970-01-01T00:00:00.100Z",
  );

  now = 199;
  const recent = new Operations({
    path,
    maxEntries: 3,
    terminalRetentionMs: 100,
    now: () => now,
  });
  assert.equal(recent.get("running-before-restart", "device:first").status, "unknown");

  now = 200;
  const expired = new Operations({
    path,
    maxEntries: 3,
    terminalRetentionMs: 100,
    now: () => now,
  });
  assert.throws(
    () => expired.get("accepted-before-restart", "device:first"),
    (error) => error.code === "operation_not_found",
  );
  assert.throws(
    () => expired.get("running-before-restart", "device:first"),
    (error) => error.code === "operation_not_found",
  );
});

test("keeps memory and disk unchanged when admission pruning cannot persist", async (t) => {
  const path = await temporaryLog(t);
  let now = 0;
  let failWrites = false;
  const operations = new Operations({
    path,
    maxEntries: 1,
    terminalRetentionMs: 100,
    now: () => now,
    commit: (destination, entries) => {
      if (failWrites) throw new Error("disk unavailable");
      persistOperations(destination, entries);
    },
  });
  operations.create("expired", { kind: "approve" }, "device:first");
  operations.markRunning("expired", "device:first");
  operations.markSucceeded("expired", "device:first", { accepted: true });

  now = 100;
  failWrites = true;
  assert.throws(
    () => operations.create("not-admitted", { kind: "reject" }, "device:first"),
    (error) => error.code === "operation_persistence_failed",
  );
  assert.equal(operations.get("expired", "device:first").status, "succeeded");
  assert.throws(
    () => operations.get("not-admitted", "device:first"),
    (error) => error.code === "operation_not_found",
  );
  const persisted = JSON.parse(await readFile(path, "utf8"));
  assert.deepEqual(persisted.operations.map((entry) => entry.operationId), ["expired"]);

  assert.throws(
    () => new Operations({
      path,
      maxEntries: 1,
      terminalRetentionMs: 100,
      now: () => now,
      commit: () => { throw new Error("disk unavailable"); },
    }),
    (error) => error.code === "operation_persistence_failed",
  );
  assert.deepEqual(
    JSON.parse(await readFile(path, "utf8")).operations.map((entry) => entry.operationId),
    ["expired"],
  );
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
