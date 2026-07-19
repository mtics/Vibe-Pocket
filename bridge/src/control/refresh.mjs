import { RefreshSequence } from "./refresh-sequence.mjs";

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
  #sequence = new RefreshSequence();

  constructor({ desktop, state, blocked, intervalMs }) {
    this.#desktop = desktop;
    this.#state = state;
    this.#blocked = blocked;
    this.#intervalMs = intervalMs;
  }

  async start() {
    await this.now();
    if (this.#sequence.stopped) return false;
    if (this.#intervalMs <= 0) return true;
    this.#interval = setInterval(() => this.#schedulePoll(), this.#intervalMs);
    this.#interval.unref?.();
    return true;
  }

  async stop() {
    if (this.#interval) clearInterval(this.#interval);
    this.#interval = null;
    await this.#sequence.stop();
    await this.#poll?.catch(() => {});
    this.#poll = null;
    await this.#action?.catch(() => {});
    this.#action = null;
    if (this.#actionTimer) clearTimeout(this.#actionTimer);
    this.#actionTimer = null;
  }

  async now({ publishIfChanged = false } = {}) {
    return this.#sequence.run(
      () => this.#desktop.status(),
      ({ value, error }) => {
        const before = this.#state.fingerprint();
        if (error) {
          this.#failures += 1;
          if (this.#successful && this.#failures < FAILURE_THRESHOLD) return;
          this.#state.degrade(error);
        } else {
          this.#state.apply(value);
          this.#successful = true;
          this.#failures = 0;
        }
        if (publishIfChanged && before !== this.#state.fingerprint()) {
          this.#state.publish("snapshot_changed");
        }
      },
    );
  }

  invalidate() {
    this.#sequence.invalidate();
  }

  afterAction() {
    if (this.#sequence.stopped) return;
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
    if (this.#sequence.stopped || this.#poll || this.#blocked() || this.#action || this.#actionTimer) return;
    this.#poll = this.now({ publishIfChanged: true })
      .finally(() => { this.#poll = null; });
  }
}
