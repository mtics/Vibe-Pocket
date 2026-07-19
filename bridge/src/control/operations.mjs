import { createHash, randomBytes } from "node:crypto";
import {
  closeSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname, isAbsolute } from "node:path";

import { Failure } from "../server/failure.mjs";

// Seven days covers delayed controller retries and reconnects; 16K entries
// supports sustained daily use while keeping the persisted log bounded.
export const DEFAULT_TERMINAL_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000;
export const DEFAULT_MAX_OPERATIONS = 16_384;

const VERSION = 1;
const VALID_STATUSES = new Set(["accepted", "running", "succeeded", "failed", "unknown"]);
const TERMINAL_STATUSES = new Set(["succeeded", "failed", "unknown"]);

export class Operations {
  #entries = new Map();
  #commit;
  #now;

  constructor({
    path = null,
    maxEntries = DEFAULT_MAX_OPERATIONS,
    terminalRetentionMs = DEFAULT_TERMINAL_RETENTION_MS,
    now = Date.now,
    commit = persistOperations,
  } = {}) {
    if (path != null && (typeof path !== "string" || !isAbsolute(path))) {
      throw new TypeError("The operation log path must be absolute.");
    }
    if (!Number.isSafeInteger(maxEntries) || maxEntries <= 0) {
      throw new TypeError("The operation log capacity must be a positive integer.");
    }
    if (!Number.isSafeInteger(terminalRetentionMs) || terminalRetentionMs <= 0) {
      throw new TypeError("The terminal operation retention window must be a positive integer.");
    }
    if (typeof now !== "function" || typeof commit !== "function") {
      throw new TypeError("The operation log requires clock and commit functions.");
    }
    this.path = path;
    this.maxEntries = maxEntries;
    this.terminalRetentionMs = terminalRetentionMs;
    this.#now = now;
    this.#commit = commit;
    this.#entries = this.#load();
    const nowMs = this.#now();
    this.#recover(nowMs);
    this.#pruneExpiredTerminals(nowMs);
  }

  match(operationId, command, principalId) {
    operationId = validateOperationId(operationId);
    principalId = validatePrincipalId(principalId);
    const fingerprint = commandFingerprint(command);
    this.#pruneExpiredTerminals(this.#now());
    const existing = this.#entries.get(operationId);
    if (!existing) return null;
    assertOwner(existing, principalId);
    if (existing.fingerprint !== fingerprint) {
      throw new Failure(409, "idempotency_key_reused", "This Idempotency-Key was already used for another action.");
    }
    return publicOperation(existing);
  }

  create(operationId, command, principalId) {
    operationId = validateOperationId(operationId);
    principalId = validatePrincipalId(principalId);
    const existing = this.match(operationId, command, principalId);
    if (existing) return { created: false, operation: existing };
    if (this.#entries.size >= this.maxEntries) {
      throw new Failure(
        503,
        "operation_capacity_reached",
        "The command operation log is full; no action was executed.",
      );
    }

    const timestamp = new Date(this.#now()).toISOString();
    const entry = {
      principalId,
      operationId,
      fingerprint: commandFingerprint(command),
      status: "accepted",
      createdAt: timestamp,
      updatedAt: timestamp,
    };
    const next = new Map(this.#entries);
    next.set(operationId, entry);
    this.#replace(next);
    return { created: true, operation: publicOperation(entry) };
  }

  get(operationId, principalId) {
    operationId = validateOperationId(operationId);
    principalId = validatePrincipalId(principalId);
    const entry = this.#entries.get(operationId);
    if (!entry || entry.principalId !== principalId) {
      throw new Failure(404, "operation_not_found", "Command operation not found.");
    }
    return publicOperation(entry);
  }

  markRunning(operationId, principalId) {
    return this.#transition(operationId, principalId, ["accepted"], { status: "running" });
  }

  markSucceeded(operationId, principalId, result) {
    return this.#transition(operationId, principalId, ["running"], {
      status: "succeeded",
      result: normalizeResult(result),
    });
  }

  markFailed(operationId, principalId, error) {
    return this.#transition(operationId, principalId, ["accepted", "running"], {
      status: "failed",
      error: normalizeError(error),
    });
  }

  markUnknown(operationId, principalId) {
    const current = this.#owned(operationId, principalId);
    if (current.status === "unknown") return publicOperation(current);
    if (current.status !== "running") return publicOperation(current);
    const nextEntry = {
      ...current,
      status: "unknown",
      error: indeterminateError(),
      updatedAt: new Date(this.#now()).toISOString(),
    };
    delete nextEntry.result;
    const next = new Map(this.#entries);
    next.set(operationId, nextEntry);
    try {
      this.#replace(next);
    } catch {
      // The persisted running state is itself a durable replay barrier. Keep
      // unknown in memory; startup recovery will derive the same state.
      this.#entries = next;
    }
    return publicOperation(nextEntry);
  }

  removePrincipal(principalId) {
    principalId = validatePrincipalId(principalId);
    const next = new Map(
      [...this.#entries].filter(([, entry]) => entry.principalId !== principalId),
    );
    if (next.size === this.#entries.size) return false;
    this.#replace(next);
    return true;
  }

  #transition(operationId, principalId, fromStatuses, changes) {
    const current = this.#owned(operationId, principalId);
    if (!fromStatuses.includes(current.status)) {
      if (current.status === changes.status) return publicOperation(current);
      throw new Failure(409, "invalid_operation_state", "The command operation cannot make that state transition.");
    }
    const nextEntry = {
      ...current,
      ...changes,
      updatedAt: new Date(this.#now()).toISOString(),
    };
    if (changes.status !== "succeeded") delete nextEntry.result;
    if (changes.status !== "failed" && changes.status !== "unknown") delete nextEntry.error;
    const next = new Map(this.#entries);
    next.set(operationId, nextEntry);
    this.#replace(next);
    return publicOperation(nextEntry);
  }

  #owned(operationId, principalId) {
    operationId = validateOperationId(operationId);
    principalId = validatePrincipalId(principalId);
    const entry = this.#entries.get(operationId);
    if (!entry || entry.principalId !== principalId) {
      throw new Failure(404, "operation_not_found", "Command operation not found.");
    }
    return entry;
  }

  #replace(next) {
    try {
      if (this.path) this.#commit(this.path, next);
    } catch (error) {
      throw new Failure(
        503,
        "operation_persistence_failed",
        "The command operation log could not be saved; no safe retry is available until its status is checked.",
      );
    }
    this.#entries = next;
  }

  #load() {
    if (!this.path || !existsSync(this.path)) return new Map();
    let parsed;
    try {
      parsed = JSON.parse(readFileSync(this.path, "utf8"));
    } catch (error) {
      throw new Error("The command operation log is unreadable.", { cause: error });
    }
    if (parsed?.version !== VERSION || !Array.isArray(parsed.operations)) {
      throw new Error("The command operation log has an unsupported format.");
    }
    if (parsed.operations.length > this.maxEntries) {
      throw new Error("The command operation log exceeds its configured capacity.");
    }
    const entries = new Map();
    for (const value of parsed.operations) {
      const entry = normalizeStoredOperation(value);
      if (!entry || entries.has(entry.operationId)) {
        throw new Error("The command operation log contains an invalid entry.");
      }
      entries.set(entry.operationId, entry);
    }
    return entries;
  }

  #recover(nowMs) {
    let changed = false;
    const timestamp = new Date(nowMs).toISOString();
    const next = new Map();
    for (const [operationId, entry] of this.#entries) {
      if (entry.status === "accepted") {
        changed = true;
        next.set(operationId, {
          ...entry,
          status: "failed",
          error: {
            status: 503,
            code: "command_not_executed",
            message: "The Bridge restarted before this command began; it was not executed.",
          },
          updatedAt: timestamp,
        });
      } else if (entry.status === "running") {
        changed = true;
        next.set(operationId, {
          ...entry,
          status: "unknown",
          error: indeterminateError(),
          updatedAt: timestamp,
        });
      } else {
        next.set(operationId, entry);
      }
    }
    if (changed) this.#replace(next);
  }

  #pruneExpiredTerminals(nowMs) {
    const cutoff = nowMs - this.terminalRetentionMs;
    const next = new Map(this.#entries);
    let changed = false;
    for (const [operationId, entry] of this.#entries) {
      if (TERMINAL_STATUSES.has(entry.status) && Date.parse(entry.updatedAt) <= cutoff) {
        next.delete(operationId);
        changed = true;
      }
    }
    if (changed) this.#replace(next);
  }
}

export function commandFingerprint(value) {
  const canonical = JSON.stringify(canonicalize(value));
  return createHash("sha256").update(canonical).digest("base64url");
}

export function persistOperations(path, entries, { sync = fsyncSync } = {}) {
  const directory = dirname(path);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const temporary = `${path}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  const operations = [...entries.values()];
  let descriptor = null;
  let directoryDescriptor = null;
  let renamed = false;
  try {
    descriptor = openSync(temporary, "wx", 0o600);
    writeFileSync(descriptor, `${JSON.stringify({ version: VERSION, operations }, null, 2)}\n`, "utf8");
    sync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, path);
    renamed = true;
    try {
      directoryDescriptor = openSync(directory, "r");
      sync(directoryDescriptor);
    } catch {
      // Rename is the commit point. A directory sync failure must not make the
      // caller repeat an operation whose log replacement is already visible.
    }
  } finally {
    if (descriptor != null) closeSync(descriptor);
    if (directoryDescriptor != null) {
      try {
        closeSync(directoryDescriptor);
      } catch {
        // The replacement is already committed.
      }
    }
    if (!renamed) rmSync(temporary, { force: true });
  }
}

function normalizeStoredOperation(value) {
  if (
    !value
    || typeof value !== "object"
    || Array.isArray(value)
    || !VALID_STATUSES.has(value.status)
    || typeof value.operationId !== "string"
    || typeof value.principalId !== "string"
    || typeof value.fingerprint !== "string"
    || !/^[A-Za-z0-9_-]{43}$/.test(value.fingerprint)
    || !validTimestamp(value.createdAt)
    || !validTimestamp(value.updatedAt)
  ) return null;
  try {
    validateOperationId(value.operationId);
    validatePrincipalId(value.principalId);
  } catch {
    return null;
  }
  const entry = {
    principalId: value.principalId,
    operationId: value.operationId,
    fingerprint: value.fingerprint,
    status: value.status,
    createdAt: value.createdAt,
    updatedAt: value.updatedAt,
  };
  if (value.status === "succeeded") {
    try {
      entry.result = normalizeResult(value.result);
    } catch {
      return null;
    }
  }
  if (value.status === "failed" || value.status === "unknown") {
    try {
      entry.error = normalizeError(value.error);
    } catch {
      return null;
    }
  }
  return entry;
}

function normalizeResult(result) {
  if (
    !result
    || typeof result !== "object"
    || Array.isArray(result)
    || result.accepted !== true
    || (result.revision != null && (typeof result.revision !== "string" || result.revision.length > 160))
  ) {
    throw new TypeError("Command operation results must use the bounded result schema.");
  }
  return {
    accepted: true,
    ...(result.revision == null ? {} : { revision: result.revision }),
  };
}

function normalizeError(error) {
  const status = Number.isInteger(error?.status) && error.status >= 400 && error.status <= 599
    ? error.status
    : 500;
  const code = typeof error?.code === "string" && /^[a-z0-9_]{1,64}$/.test(error.code)
    ? error.code
    : "bridge_error";
  const rawMessage = typeof error?.message === "string" && error.message.length > 0
    ? error.message
    : "The Vibe Pocket bridge could not finish this command.";
  return { status, code, message: rawMessage.slice(0, 512) };
}

function publicOperation(entry) {
  return structuredClone({
    operationId: entry.operationId,
    status: entry.status,
    ...(entry.result ? { result: entry.result } : {}),
    ...(entry.error ? { error: entry.error } : {}),
    createdAt: entry.createdAt,
    updatedAt: entry.updatedAt,
  });
}

function validateOperationId(value) {
  if (
    typeof value !== "string"
    || value.length === 0
    || value.length > 160
    || /[\u0000-\u001f\u007f]/.test(value)
  ) {
    throw new Failure(400, "idempotency_key_required", "Every controller action needs a valid Idempotency-Key header.");
  }
  return value;
}

function validatePrincipalId(value) {
  if (typeof value !== "string" || value.length === 0 || value.length > 256) {
    throw new TypeError("Command operations require a bounded principal ID.");
  }
  return value;
}

function assertOwner(entry, principalId) {
  if (entry.principalId !== principalId) {
    throw new Failure(409, "operation_id_unavailable", "This operation ID is unavailable for this credential.");
  }
}

function canonicalize(value) {
  if (Array.isArray(value)) return value.map(canonicalize);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.keys(value)
      .sort()
      .filter((key) => value[key] !== undefined)
      .map((key) => [key, canonicalize(value[key])]),
  );
}

function validTimestamp(value) {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function indeterminateError() {
  return {
    status: 409,
    code: "command_outcome_indeterminate",
    message: "The controller action may have completed; this operation will never be replayed automatically.",
  };
}
