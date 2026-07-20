import { readyPayload, SERVICE_NAME } from "../runtime/identity.mjs";
import { Failure } from "./failure.mjs";

export const READY = "READY";
export const NOT_READY = "NOT_READY";

const NOT_READY_BODY = Object.freeze({
  ok: false,
  service: SERVICE_NAME,
});

export class Readiness {
  #body;
  #state = NOT_READY;

  constructor({ runtimeIdentity, protocolVersion }) {
    this.#body = Object.freeze(readyPayload(runtimeIdentity, protocolVersion));
  }

  markReady() {
    this.#state = READY;
  }

  markNotReady() {
    this.#state = NOT_READY;
  }

  get state() {
    return this.#state;
  }

  requireReady() {
    if (this.#state !== READY) {
      throw new Failure(503, "bridge_not_ready", "The Vibe Pocket bridge is not ready.");
    }
  }

  response() {
    return this.#state === READY
      ? { status: 200, body: this.#body }
      : { status: 503, body: NOT_READY_BODY };
  }
}
