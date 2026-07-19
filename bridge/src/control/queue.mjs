import { Failure } from "../server/failure.mjs";

const LIMIT = 256;

export class Queue {
  #tail = Promise.resolve();
  #entries = new Map();
  #pending = 0;

  get busy() {
    return this.#pending > 0;
  }

  run(operation) {
    const execution = this.#tail.then(operation);
    this.#tail = execution.catch(() => {});
    return execution;
  }

  once(key, value, operation) {
    if (!key || key.length > 160) {
      throw new Failure(400, "idempotency_key_required", "Every controller action needs an Idempotency-Key header.");
    }

    const fingerprint = JSON.stringify(value);
    const existing = this.#entries.get(key);
    if (existing) {
      if (existing.fingerprint !== fingerprint) {
        throw new Failure(409, "idempotency_key_reused", "This Idempotency-Key was already used for another action.");
      }
      return existing.execution;
    }

    this.#pending += 1;
    const execution = this.run(operation)
      .catch((error) => {
        this.#entries.delete(key);
        throw error;
      })
      .finally(() => { this.#pending -= 1; });
    this.#entries.set(key, { fingerprint, execution });
    while (this.#entries.size > LIMIT) {
      this.#entries.delete(this.#entries.keys().next().value);
    }
    return execution;
  }
}
