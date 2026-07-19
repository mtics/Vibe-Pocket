import { Failure } from "../server/failure.mjs";

export class Queue {
  #tail = Promise.resolve();
  #pending = 0;
  #accepting = true;

  get busy() {
    return this.#pending > 0;
  }

  run(operation, { principal = null } = {}) {
    if (!this.#accepting) {
      return Promise.reject(new Failure(503, "bridge_stopping", "The Vibe Pocket bridge is shutting down."));
    }

    this.#pending += 1;
    const execution = this.#tail.then(() => {
      if (principal && (typeof principal.valid !== "function" || !principal.valid())) {
        throw new Failure(401, "credential_revoked", "This paired device credential has been revoked.");
      }
      return operation();
    });
    const settled = execution.finally(() => { this.#pending -= 1; });
    this.#tail = settled.catch(() => {});
    return execution;
  }

  stop() {
    this.#accepting = false;
    return this.drain();
  }

  drain() {
    return this.#tail;
  }
}
