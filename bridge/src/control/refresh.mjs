const ACTION_DELAY_MS = 160;
const FAILURE_THRESHOLD = 3;

export class Refresh {
  #desktop;
  #state;
  #blocked;
  #intervalMs;
  #interval = null;
  #poll = null;
  #action = null;
  #actionTimer = null;
  #successful = false;
  #failures = 0;

  constructor({ desktop, state, blocked, intervalMs }) {
    this.#desktop = desktop;
    this.#state = state;
    this.#blocked = blocked;
    this.#intervalMs = intervalMs;
  }

  async start() {
    await this.now();
    if (this.#intervalMs <= 0) return;
    this.#interval = setInterval(() => this.#schedulePoll(), this.#intervalMs);
    this.#interval.unref?.();
  }

  async stop() {
    if (this.#interval) clearInterval(this.#interval);
    this.#interval = null;
    await this.#poll?.catch(() => {});
    this.#poll = null;
    await this.#action?.catch(() => {});
    this.#action = null;
    if (this.#actionTimer) clearTimeout(this.#actionTimer);
    this.#actionTimer = null;
  }

  async now({ publishIfChanged = false } = {}) {
    const before = this.#state.fingerprint();
    try {
      this.#state.apply(await this.#desktop.status());
      this.#successful = true;
      this.#failures = 0;
    } catch (error) {
      this.#failures += 1;
      if (this.#successful && this.#failures < FAILURE_THRESHOLD) return;
      this.#state.degrade(error);
    }
    if (publishIfChanged && before !== this.#state.fingerprint()) {
      this.#state.publish("snapshot_changed");
    }
  }

  afterAction() {
    if (this.#actionTimer) clearTimeout(this.#actionTimer);
    this.#actionTimer = setTimeout(() => {
      this.#actionTimer = null;
      if (this.#action) return;
      this.#action = this.now({ publishIfChanged: true })
        .finally(() => { this.#action = null; });
    }, ACTION_DELAY_MS);
    this.#actionTimer.unref?.();
  }

  #schedulePoll() {
    if (this.#poll || this.#blocked() || this.#action || this.#actionTimer) return;
    this.#poll = this.now({ publishIfChanged: true })
      .finally(() => { this.#poll = null; });
  }
}
