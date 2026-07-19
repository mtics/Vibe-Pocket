export class Events {
  #clients = new Set();
  #history = [];
  #nextId = 1;
  #heartbeat = null;

  constructor({ historyLimit = 64, heartbeatMs = 20_000 } = {}) {
    this.historyLimit = historyLimit;
    this.heartbeatMs = heartbeatMs;
  }

  connect(request, response, identity = null) {
    response.writeHead(200, {
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "Content-Type": "text/event-stream; charset=utf-8",
      "X-Accel-Buffering": "no",
    });
    const client = {
      identity,
      request,
      response,
      closed: false,
      onClose: null,
    };
    client.onClose = () => this.#disconnect(client, { end: false });
    request.on("close", client.onClose);
    this.#clients.add(client);
    this.#ensureHeartbeat();
    if (!this.#write(client, ": connected\n\n")) return;

    const lastId = Number.parseInt(request.headers["last-event-id"] ?? "0", 10);
    const latestId = this.#history.at(-1)?.id ?? 0;
    if (!Number.isNaN(lastId) && lastId > latestId) {
      // A bridge restart resets its in-memory sequence. The caller's cursor
      // cannot be replayed, so force exactly one fresh controller snapshot.
      const reset = { id: this.#nextId++, type: "snapshot_changed", data: { reason: "history_reset" } };
      this.#history.push(reset);
      if (this.#history.length > this.historyLimit) this.#history.shift();
      this.#write(client, formatEvent(reset));
      return;
    }
    for (const event of this.#history) {
      if (!Number.isNaN(lastId) && event.id > lastId) {
        if (!this.#write(client, formatEvent(event))) return;
      }
    }
  }

  publish(type, data) {
    const event = { id: this.#nextId++, type, data };
    this.#history.push(event);
    if (this.#history.length > this.historyLimit) {
      this.#history.shift();
    }
    const serialized = formatEvent(event);
    for (const client of [...this.#clients]) this.#write(client, serialized);
    return event;
  }

  closeIdentity(identity) {
    let closed = 0;
    for (const client of [...this.#clients]) {
      if (client.identity === identity) {
        this.#disconnect(client, { end: true });
        closed += 1;
      }
    }
    return closed;
  }

  close() {
    this.#clearHeartbeat();
    for (const client of [...this.#clients]) this.#disconnect(client, { end: true });
  }

  #ensureHeartbeat() {
    if (!this.#heartbeat) {
      this.#heartbeat = setInterval(() => {
        for (const client of [...this.#clients]) this.#write(client, ": keepalive\n\n");
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

  #write(client, chunk) {
    if (client.closed) return false;
    let accepted;
    try {
      accepted = client.response.write(chunk);
    } catch {
      this.#disconnect(client, { end: false });
      return false;
    }
    if (accepted === false) {
      this.#disconnect(client, { end: true });
      return false;
    }
    return true;
  }

  #disconnect(client, { end }) {
    if (client.closed) return;
    client.closed = true;
    this.#clients.delete(client);
    client.request.off("close", client.onClose);
    if (end) {
      try {
        client.response.end();
      } catch {
        // The peer may already have torn down the transport.
      }
    }
    if (this.#clients.size === 0) this.#clearHeartbeat();
  }
}

function formatEvent(event) {
  return `id: ${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`;
}
