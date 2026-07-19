import { Failure } from "./failure.mjs";

export const DEFAULT_SERVER_DEADLINES = Object.freeze({
  headersMs: 5_000,
  requestMs: 15_000,
  bodyMs: 10_000,
  keepAliveMs: 2_000,
  shutdownMs: 5_000,
});

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

export function normalizeDeadlines(overrides = {}) {
  const deadlines = { ...DEFAULT_SERVER_DEADLINES, ...overrides };
  for (const [name, value] of Object.entries(deadlines)) {
    if (!Number.isSafeInteger(value) || value <= 0) {
      throw new TypeError(`Server deadline ${name} must be a positive integer.`);
    }
  }
  return Object.freeze(deadlines);
}

export function manage(server, tracker, { deadlines: overrides = {} } = {}) {
  const deadlines = normalizeDeadlines(overrides);
  const sockets = new Set();
  const headerTimers = new Map();
  let closing = null;

  server.headersTimeout = deadlines.headersMs;
  server.requestTimeout = deadlines.requestMs;
  server.keepAliveTimeout = deadlines.keepAliveMs;

  server.on("connection", (socket) => {
    sockets.add(socket);
    const timer = setTimeout(() => socket.destroy(), deadlines.headersMs);
    timer.unref();
    headerTimers.set(socket, timer);
    socket.once("close", () => {
      sockets.delete(socket);
      clearTimeout(headerTimers.get(socket));
      headerTimers.delete(socket);
    });
  });
  server.prependListener("request", (request) => {
    clearTimeout(headerTimers.get(request.socket));
    headerTimers.delete(request.socket);
  });

  server.destroyConnections = () => {
    for (const socket of [...sockets]) socket.destroy();
  };
  server.stopAccepting = () => {
    tracker.stop();
    if (!closing) {
      closing = new Promise((resolve, reject) => {
        const force = setTimeout(() => server.destroyConnections(), deadlines.shutdownMs);
        force.unref();
        server.close((error) => {
          clearTimeout(force);
          if (error && error.code !== "ERR_SERVER_NOT_RUNNING") reject(error);
          else resolve();
        });
        server.closeIdleConnections?.();
      });
    }
    return closing;
  };
  server.drain = () => tracker.drain();
  server.deadlines = deadlines;
  return server;
}
