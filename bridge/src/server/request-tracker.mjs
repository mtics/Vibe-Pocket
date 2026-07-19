import { Failure } from "./failure.mjs";

export class RequestTracker {
  #accepting = true;
  #active = 0;
  #waiters = new Set();

  async run(operation) {
    if (!this.#accepting) {
      throw new Failure(503, "bridge_stopping", "The Vibe Pocket bridge is shutting down.");
    }
    this.#active += 1;
    try {
      return await operation();
    } finally {
      this.#active -= 1;
      if (this.#active === 0) {
        for (const resolve of this.#waiters) resolve();
        this.#waiters.clear();
      }
    }
  }

  stop() {
    this.#accepting = false;
  }

  drain() {
    if (this.#active === 0) return Promise.resolve();
    return new Promise((resolve) => this.#waiters.add(resolve));
  }
}

export function manage(server, tracker) {
  let closing = null;
  server.stopAccepting = () => {
    tracker.stop();
    if (!closing) {
      closing = new Promise((resolve, reject) => {
        server.close((error) => {
          if (error && error.code !== "ERR_SERVER_NOT_RUNNING") reject(error);
          else resolve();
        });
      });
    }
    return closing;
  };
  server.drain = () => tracker.drain();
  return server;
}
