import { RefreshSequence } from "./refresh-sequence.mjs";

const ACTION_DELAYS_MS = Object.freeze([32, 64, 128]);
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
  #actionDelays;
  #confirmation = null;
  #confirmationGeneration = 0;
  #successful = false;
  #failures = 0;
  #sequence = new RefreshSequence();

  constructor({ desktop, state, blocked, intervalMs, actionDelaysMs = ACTION_DELAYS_MS }) {
    if (!Array.isArray(actionDelaysMs) || actionDelaysMs.length === 0
      || actionDelaysMs.some((value) => !Number.isFinite(value) || value < 0)) {
      throw new TypeError("Action confirmation delays must be a non-empty list of non-negative numbers.");
    }
    this.#desktop = desktop;
    this.#state = state;
    this.#blocked = blocked;
    this.#intervalMs = intervalMs;
    this.#actionDelays = [...actionDelaysMs];
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
    this.#confirmation = null;
    this.#confirmationGeneration += 1;
  }

  async now({ publishIfChanged = false } = {}) {
    return this.#sequence.run(
      () => this.#desktop.status(),
      ({ value, error }) => {
        const before = this.#state.fingerprint();
        if (error) {
          this.#failures += 1;
          if (this.#successful && this.#failures < FAILURE_THRESHOLD) {
            this.#state.retain();
          } else {
            this.#state.degrade(error);
          }
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
    const confirmation = {
      generation: ++this.#confirmationGeneration,
      fingerprint: this.#state.fingerprint(),
      step: 0,
    };
    this.#confirmation = confirmation;
    this.#scheduleAction(confirmation);
  }

  #scheduleAction(confirmation) {
    if (this.#sequence.stopped || this.#confirmation !== confirmation) return;
    if (this.#actionTimer) clearTimeout(this.#actionTimer);
    this.#actionTimer = setTimeout(() => {
      this.#actionTimer = null;
      if (this.#confirmation !== confirmation) return;
      if (this.#action) {
        this.#scheduleAction(confirmation);
        return;
      }
      this.#action = this.now({ publishIfChanged: true })
        .finally(() => {
          this.#action = null;
          if (this.#confirmation !== confirmation) return;
          if (this.#state.fingerprint() !== confirmation.fingerprint) {
            this.#confirmation = null;
            return;
          }
          confirmation.step += 1;
          if (confirmation.step >= this.#actionDelays.length) {
            this.#confirmation = null;
            return;
          }
          this.#scheduleAction(confirmation);
        });
    }, this.#actionDelays[confirmation.step]);
    this.#actionTimer.unref?.();
  }

  #schedulePoll() {
    if (this.#sequence.stopped || this.#poll || this.#blocked() || this.#action
      || this.#actionTimer || this.#confirmation) return;
    this.#poll = this.now({ publishIfChanged: true })
      .finally(() => { this.#poll = null; });
  }
}
