import { Failure } from "../server/failure.mjs";

const SETTLED_LIMIT = 256;

export class Idempotency {
  #entries = new Map();
  #settled = new Map();

  once(key, value, operation) {
    if (typeof key !== "string" || key.length === 0 || key.length > 160) {
      throw new Failure(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }

    const fingerprint = canonicalFingerprint(value);
    const existing = this.#entries.get(key);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new Failure(409, "idempotency_key_reused", "This Idempotency-Key was already used for another action.");
      }
      return existing.execution;
    }

    const entry = { fingerprint, dispatched: false, execution: null };
    this.#entries.set(key, entry);
    entry.execution = Promise.resolve().then(async () => {
      try {
        const outcome = await operation({
          dispatch: (authority) => {
            entry.dispatched = true;
            return authority();
          },
        });
        this.#settle(key, entry);
        return outcome;
      } catch (error) {
        if (!entry.dispatched) {
          if (this.#entries.get(key) === entry) this.#entries.delete(key);
          throw error;
        }
        const failure = new Failure(
          409,
          "command_outcome_indeterminate",
          "The controller action may have completed, so this Idempotency-Key cannot be retried safely.",
        );
        this.#settle(key, entry);
        throw failure;
      }
    });
    return entry.execution;
  }

  #settle(key, entry) {
    if (this.#entries.get(key) !== entry) return;
    this.#settled.delete(key);
    this.#settled.set(key, entry);
    while (this.#settled.size > SETTLED_LIMIT) {
      const oldest = this.#settled.keys().next().value;
      this.#settled.delete(oldest);
      if (this.#entries.get(oldest)) this.#entries.delete(oldest);
    }
  }
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
