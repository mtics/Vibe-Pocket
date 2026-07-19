import { Failure } from "../server/failure.mjs";

const SETTLED_LIMIT = 256;
const TOMBSTONE_LIMIT = 1_024;

export class Idempotency {
  #entries = new Map();
  #settled = new Map();
  #tombstones = new Map();
  #owners = new Map();

  constructor({ settledLimit = SETTLED_LIMIT, tombstoneLimit = TOMBSTONE_LIMIT } = {}) {
    if (!Number.isSafeInteger(settledLimit) || settledLimit <= 0) {
      throw new TypeError("Settled idempotency limit must be a positive integer.");
    }
    if (!Number.isSafeInteger(tombstoneLimit) || tombstoneLimit <= 0) {
      throw new TypeError("Idempotency tombstone limit must be a positive integer.");
    }
    this.settledLimit = settledLimit;
    this.tombstoneLimit = tombstoneLimit;
  }

  once(key, value, operation, { principal = null, admit = null } = {}) {
    if (typeof key !== "string" || key.length === 0 || key.length > 160) {
      throw new Failure(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }

    const principalId = identity(principal);
    const owner = this.#owners.get(key);
    if (owner != null && owner !== principalId) {
      throw new Failure(
        409,
        "idempotency_key_principal_collision",
        "This Idempotency-Key is already bound to another credential.",
      );
    }

    const fingerprint = canonicalFingerprint(value);
    const ledgerKey = composite(principalId, key);
    const existing = this.#entries.get(ledgerKey);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new Failure(409, "idempotency_key_reused", "This Idempotency-Key was already used for another action.");
      }
      return existing.execution;
    }

    const tombstone = this.#tombstones.get(ledgerKey);
    if (tombstone) {
      if (tombstone.fingerprint !== fingerprint) {
        throw new Failure(409, "idempotency_key_reused", "This Idempotency-Key was already used for another action.");
      }
      throw new Failure(
        409,
        "idempotency_result_expired",
        "This controller action already completed, but its cached result has expired.",
      );
    }

    const admission = admit?.();
    const entry = { fingerprint, dispatched: false, execution: null, key, principalId };
    this.#owners.set(key, principalId);
    this.#entries.set(ledgerKey, entry);
    entry.execution = Promise.resolve().then(async () => {
      try {
        const outcome = await operation({
          admission,
          dispatch: (authority) => {
            entry.dispatched = true;
            return authority();
          },
        });
        this.#settle(ledgerKey, entry);
        return outcome;
      } catch (error) {
        if (!entry.dispatched) {
          admission?.release?.();
          if (this.#entries.get(ledgerKey) === entry) {
            this.#entries.delete(ledgerKey);
            if (this.#owners.get(key) === principalId) this.#owners.delete(key);
          }
          throw error;
        }
        const failure = new Failure(
          409,
          "command_outcome_indeterminate",
          "The controller action may have completed, so this Idempotency-Key cannot be retried safely.",
        );
        this.#settle(ledgerKey, entry);
        throw failure;
      }
    });
    return entry.execution;
  }

  #settle(ledgerKey, entry) {
    if (this.#entries.get(ledgerKey) !== entry) return;
    this.#settled.delete(ledgerKey);
    this.#settled.set(ledgerKey, entry);
    while (this.#settled.size > this.settledLimit) {
      const oldest = this.#settled.keys().next().value;
      const evicted = this.#entries.get(oldest);
      this.#settled.delete(oldest);
      if (evicted) {
        this.#entries.delete(oldest);
        this.#tombstones.delete(oldest);
        this.#tombstones.set(oldest, {
          fingerprint: evicted.fingerprint,
          key: evicted.key,
          principalId: evicted.principalId,
        });
      }
    }
    while (this.#tombstones.size > this.tombstoneLimit) {
      const oldest = this.#tombstones.keys().next().value;
      const evicted = this.#tombstones.get(oldest);
      this.#tombstones.delete(oldest);
      if (evicted && this.#owners.get(evicted.key) === evicted.principalId) {
        this.#owners.delete(evicted.key);
      }
    }
  }
}

function identity(principal) {
  return principal?.id ?? "principal:local-root";
}

function composite(principalId, key) {
  return JSON.stringify([principalId, key]);
}

export function canonicalFingerprint(value) {
  return JSON.stringify(canonicalize(value));
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
