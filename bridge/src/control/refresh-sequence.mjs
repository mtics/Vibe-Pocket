export class RefreshSequence {
  #generation = 0;
  #active = new Set();
  #stopped = false;

  get stopped() {
    return this.#stopped;
  }

  invalidate() {
    if (!this.#stopped) this.#generation += 1;
  }

  run(load, commit) {
    if (this.#stopped) return Promise.resolve(false);
    const generation = ++this.#generation;
    const execution = (async () => {
      let outcome;
      try {
        outcome = { value: await load(), error: null };
      } catch (error) {
        outcome = { value: null, error };
      }
      if (this.#stopped || generation !== this.#generation) return false;
      commit(outcome);
      return true;
    })();
    this.#active.add(execution);
    execution.then(
      () => this.#active.delete(execution),
      () => this.#active.delete(execution),
    );
    return execution;
  }

  async stop() {
    if (!this.#stopped) {
      this.#stopped = true;
      this.#generation += 1;
    }
    await Promise.allSettled([...this.#active]);
  }
}
