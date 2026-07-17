export class SseHub {
  #clients = new Set();
  #history = [];
  #nextId = 1;
  #heartbeat = null;

  constructor({ historyLimit = 64, heartbeatMs = 20_000 } = {}) {
    this.historyLimit = historyLimit;
    this.heartbeatMs = heartbeatMs;
  }

  connect(request, response) {
    response.writeHead(200, {
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "Content-Type": "text/event-stream; charset=utf-8",
      "X-Accel-Buffering": "no",
    });
    response.write(": connected\n\n");
    this.#clients.add(response);
    this.#ensureHeartbeat();

    const lastId = Number.parseInt(request.headers["last-event-id"] ?? "0", 10);
    for (const event of this.#history) {
      if (!Number.isNaN(lastId) && event.id > lastId) {
        response.write(formatEvent(event));
      }
    }

    request.on("close", () => {
      this.#clients.delete(response);
      if (this.#clients.size === 0) {
        this.#clearHeartbeat();
      }
    });
  }

  publish(type, data) {
    const event = { id: this.#nextId++, type, data };
    this.#history.push(event);
    if (this.#history.length > this.historyLimit) {
      this.#history.shift();
    }
    const serialized = formatEvent(event);
    for (const client of this.#clients) {
      client.write(serialized);
    }
    return event;
  }

  close() {
    this.#clearHeartbeat();
    for (const client of this.#clients) {
      client.end();
    }
    this.#clients.clear();
  }

  #ensureHeartbeat() {
    if (!this.#heartbeat) {
      this.#heartbeat = setInterval(() => {
        for (const client of this.#clients) {
          client.write(": keepalive\n\n");
        }
      }, this.heartbeatMs);
      this.#heartbeat.unref();
    }
  }

  #clearHeartbeat() {
    if (this.#heartbeat) {
      clearInterval(this.#heartbeat);
      this.#heartbeat = null;
    }
  }
}

function formatEvent(event) {
  return `id: ${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`;
}
