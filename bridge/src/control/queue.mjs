import { Failure } from "../server/failure.mjs";

export const DEFAULT_MAX_PENDING_COMMANDS = 64;
export const DEFAULT_MAX_PENDING_COMMANDS_PER_PRINCIPAL = 8;

export class Queue {
  #tail = Promise.resolve();
  #pending = 0;
  #pendingByPrincipal = new Map();
  #accepting = true;

  constructor({
    maxPending = DEFAULT_MAX_PENDING_COMMANDS,
    maxPendingPerPrincipal = DEFAULT_MAX_PENDING_COMMANDS_PER_PRINCIPAL,
  } = {}) {
    if (!Number.isSafeInteger(maxPending) || maxPending <= 0) {
      throw new TypeError("Global pending command limit must be a positive integer.");
    }
    if (!Number.isSafeInteger(maxPendingPerPrincipal) || maxPendingPerPrincipal <= 0) {
      throw new TypeError("Per-principal pending command limit must be a positive integer.");
    }
    if (maxPendingPerPrincipal > maxPending) {
      throw new TypeError("Per-principal pending command limit cannot exceed the global limit.");
    }
    this.maxPending = maxPending;
    this.maxPendingPerPrincipal = maxPendingPerPrincipal;
  }

  get busy() {
    return this.#pending > 0;
  }

  reserve({ principal = null } = {}) {
    if (!this.#accepting) {
      throw new Failure(503, "bridge_stopping", "The Vibe Pocket bridge is shutting down.");
    }
    const principalId = identity(principal);
    const principalPending = this.#pendingByPrincipal.get(principalId) ?? 0;
    if (principalPending >= this.maxPendingPerPrincipal) {
      throw new Failure(
        429,
        "principal_command_queue_full",
        "This credential already has the maximum number of pending controller actions.",
      );
    }
    if (this.#pending >= this.maxPending) {
      throw new Failure(429, "command_queue_full", "The controller command queue is full.");
    }

    this.#pending += 1;
    this.#pendingByPrincipal.set(principalId, principalPending + 1);
    return new Admission(principalId, () => {
      this.#pending -= 1;
      const remaining = (this.#pendingByPrincipal.get(principalId) ?? 1) - 1;
      if (remaining === 0) this.#pendingByPrincipal.delete(principalId);
      else this.#pendingByPrincipal.set(principalId, remaining);
    });
  }

  run(operation, { principal = null, admission = null } = {}) {
    try {
      admission ??= this.reserve({ principal });
      admission.consume(identity(principal));
      if (!this.#accepting) {
        throw new Failure(503, "bridge_stopping", "The Vibe Pocket bridge is shutting down.");
      }
    } catch (error) {
      admission?.release?.();
      return Promise.reject(error);
    }

    const execution = this.#tail.then(() => {
      if (principal && (typeof principal.valid !== "function" || !principal.valid())) {
        throw new Failure(401, "credential_revoked", "This paired device credential has been revoked.");
      }
      return operation();
    });
    const settled = execution.finally(() => admission.release());
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

class Admission {
  #consumed = false;
  #released = false;
  #release;

  constructor(principalId, release) {
    this.principalId = principalId;
    this.#release = release;
  }

  consume(principalId) {
    if (this.#consumed || this.#released || principalId !== this.principalId) {
      throw new TypeError("Invalid command queue admission reservation.");
    }
    this.#consumed = true;
  }

  release() {
    if (this.#released) return;
    this.#released = true;
    this.#release();
  }
}

function identity(principal) {
  return principal?.id ?? "principal:local-root";
}
