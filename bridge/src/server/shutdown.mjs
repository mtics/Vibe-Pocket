export class Shutdown {
  #servers;
  #service;
  #events;
  #cleanup;
  #deadlineMs;
  #closing = null;

  constructor({ servers, service, events, cleanup = () => {}, deadlineMs = 5_000 }) {
    if (!Number.isSafeInteger(deadlineMs) || deadlineMs <= 0) {
      throw new TypeError("Shutdown deadline must be a positive integer.");
    }
    this.#servers = servers;
    this.#service = service;
    this.#events = events;
    this.#cleanup = cleanup;
    this.#deadlineMs = deadlineMs;
  }

  close() {
    if (!this.#closing) this.#closing = this.#close();
    return this.#closing;
  }

  async #close() {
    let failure = null;
    const expiresAt = Date.now() + this.#deadlineMs;
    const attempt = async (operation) => {
      try {
        const result = operation();
        if (result && typeof result.then === "function") {
          const remainingMs = Math.max(0, expiresAt - Date.now());
          await within(result, remainingMs);
        }
      } catch (error) {
        failure ??= error;
      }
    };
    const closed = this.#servers.map((server) => server.stopAccepting());
    await attempt(() => Promise.all(this.#servers.map((server) => server.drain())));
    await attempt(() => this.#service.stop());
    await attempt(() => this.#events.close());
    if (Date.now() >= expiresAt) {
      for (const server of this.#servers) server.destroyConnections?.();
    }
    await attempt(() => Promise.all(closed));
    await attempt(() => this.#cleanup());
    await attempt(() => this.#service.dispose());
    if (failure) throw failure;
  }
}

function within(result, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(
      () => reject(new Error("Vibe Pocket bridge shutdown exceeded its deadline.")),
      timeoutMs,
    );
    Promise.resolve(result).then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error) => {
        clearTimeout(timer);
        reject(error);
      },
    );
  });
}
