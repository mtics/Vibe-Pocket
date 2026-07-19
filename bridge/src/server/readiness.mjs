import { readyPayload, SERVICE_NAME } from "../runtime/identity.mjs";

const NOT_READY_BODY = Object.freeze({
  ok: false,
  service: SERVICE_NAME,
});

export class Readiness {
  #body;
  #ready = false;

  constructor({ runtimeIdentity, protocolVersion }) {
    this.#body = Object.freeze(readyPayload(runtimeIdentity, protocolVersion));
  }

  markReady() {
    this.#ready = true;
  }

  response() {
    return this.#ready
      ? { status: 200, body: this.#body }
      : { status: 503, body: NOT_READY_BODY };
  }
}
