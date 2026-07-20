import { randomBytes } from "node:crypto";

export class Events {
  #clients = new Set();
  #history = [];
  #nextId = 1;
  #heartbeat = null;
  #streamId;

  constructor({ historyLimit = 64, heartbeatMs = 20_000, streamId = randomBytes(8).toString("hex") } = {}) {
    if (typeof streamId !== "string" || !/^[a-f0-9]{16}$/.test(streamId)) {
      throw new TypeError("The SSE stream ID must be 16 lowercase hexadecimal characters.");
    }
    this.historyLimit = historyLimit;
    this.heartbeatMs = heartbeatMs;
    this.#streamId = streamId;
  }

  connect(request, response, principal = null) {
    principal = normalizePrincipal(principal);
    if (!principal.valid()) {
      response.writeHead(401, {
        "Cache-Control": "no-store",
        Connection: "close",
        "Content-Type": "application/json; charset=utf-8",
      });
      response.end(JSON.stringify({
        error: { code: "credential_revoked", message: "This paired device credential has been revoked." },
      }));
      return false;
    }
    response.writeHead(200, {
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "Content-Type": "text/event-stream; charset=utf-8",
      "X-Accel-Buffering": "no",
    });
    const client = {
      identity: principal.id,
      principal,
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

    const rawCursor = request.headers["last-event-id"];
    const cursor = parseCursor(rawCursor);
    const latestId = this.#history.at(-1)?.id ?? 0;
    const earliestId = this.#history[0]?.id ?? this.#nextId;
    const cannotReplay = rawCursor != null && (
      !cursor
      || cursor.streamId !== this.#streamId
      || cursor.sequence > latestId
      || cursor.sequence < earliestId - 1
    );
    if (cannotReplay) {
      const reset = { id: this.#nextId++, type: "snapshot_changed", data: { reason: "history_reset" } };
      this.#history.push(reset);
      if (this.#history.length > this.historyLimit) this.#history.shift();
      this.#write(client, formatEvent(reset, this.#streamId));
      return true;
    }
    for (const event of this.#history) {
      if (event.id > (cursor?.sequence ?? 0)) {
        if (!this.#write(client, formatEvent(event, this.#streamId))) return;
      }
    }
    return true;
  }

  publish(type, data) {
    const event = { id: this.#nextId++, type, data };
    this.#history.push(event);
    if (this.#history.length > this.historyLimit) {
      this.#history.shift();
    }
    const serialized = formatEvent(event, this.#streamId);
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
    if (!client.principal.valid()) {
      this.#disconnect(client, { end: true });
      return false;
    }
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

function formatEvent(event, streamId) {
  return `id: ${streamId}:${event.id}\nevent: ${event.type}\ndata: ${JSON.stringify(event.data)}\n\n`;
}

function parseCursor(value) {
  if (typeof value !== "string") return null;
  const match = value.match(/^([a-f0-9]{16}):([1-9][0-9]*)$/);
  if (!match) return null;
  const sequence = Number(match[2]);
  return Number.isSafeInteger(sequence) ? { streamId: match[1], sequence } : null;
}

function normalizePrincipal(principal) {
  if (typeof principal === "string") {
    return { id: principal, valid: () => true };
  }
  if (principal && typeof principal.valid === "function") return principal;
  return { id: principal?.id ?? null, valid: () => true };
}
