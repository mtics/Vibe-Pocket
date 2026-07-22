import { Failure } from "../server/failure.mjs";

export class EffectBoundary {
  #crossed = false;
  #valid;

  constructor(valid = () => true) {
    this.#valid = valid;
  }

  get crossed() {
    return this.#crossed;
  }

  async commit(operation) {
    if (this.#crossed) throw new Error("A controller command may cross its effect boundary only once.");
    if (!this.#valid()) {
      throw new Failure(401, "credential_revoked", "This paired device credential has been revoked.");
    }
    this.#crossed = true;
    return operation();
  }
}

export class Authority {
  #dispatch;
  #effects;

  constructor(dispatch, effects) {
    this.#dispatch = dispatch;
    this.#effects = effects;
  }

  immediate(operation) {
    return this.#dispatch(() => this.#effects.commit(operation));
  }

  async effect(operation) {
    const result = await this.#dispatch(() => operation(this.#effects));
    if (!this.#effects.crossed) {
      throw new Error("The controller operation completed without crossing its effect boundary.");
    }
    return result;
  }

  async deferred(operation) {
    const result = await this.#dispatch(() => operation(this.#effects));
    if (!this.#effects.crossed) {
      throw new Error("The controller mutation completed without crossing its effect boundary.");
    }
    return result;
  }
}
