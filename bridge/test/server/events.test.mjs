import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import { Events } from "../../src/server/events.mjs";

class FakeResponse {
  headers = null;
  chunks = [];
  ended = 0;
  writeResult = true;

  writeHead(_status, headers) { this.headers = headers; }
  write(chunk) { this.chunks.push(chunk); return this.writeResult; }
  end() { this.ended += 1; }
}

test("forces a snapshot refresh when a client resumes after the bridge sequence resets", () => {
  const hub = new Events({ heartbeatMs: 60_000, streamId: "aaaaaaaaaaaaaaaa" });
  hub.publish("snapshot_changed", { revision: "r_1" });
  const request = new EventEmitter();
  request.headers = { "last-event-id": "99" };
  const response = new FakeResponse();

  hub.connect(request, response);

  assert.equal(response.headers["Content-Type"], "text/event-stream; charset=utf-8");
  assert.match(response.chunks.join(""), /id: aaaaaaaaaaaaaaaa:2\nevent: snapshot_changed/);
  assert.match(response.chunks.join(""), /"reason":"history_reset"/);
  request.emit("close");
  const chunksAfterClose = response.chunks.length;
  hub.publish("snapshot_changed", { revision: "r_2" });
  assert.equal(response.chunks.length, chunksAfterClose);
  hub.close();
});

test("resets an equal sequence cursor from a different bridge instance", () => {
  const hub = new Events({ heartbeatMs: 60_000, streamId: "bbbbbbbbbbbbbbbb" });
  hub.publish("snapshot_changed", { revision: "r_new" });
  const request = new EventEmitter();
  request.headers = { "last-event-id": "aaaaaaaaaaaaaaaa:1" };
  const response = new FakeResponse();

  hub.connect(request, response);

  assert.match(response.chunks.join(""), /id: bbbbbbbbbbbbbbbb:2/);
  assert.match(response.chunks.join(""), /"reason":"history_reset"/);
  request.emit("close");
  hub.close();
});

test("does not invent a reset for the current stream at its latest event", () => {
  const hub = new Events({ heartbeatMs: 60_000, streamId: "cccccccccccccccc" });
  hub.publish("snapshot_changed", { revision: "r_1" });
  const request = new EventEmitter();
  request.headers = { "last-event-id": "cccccccccccccccc:1" };
  const response = new FakeResponse();

  hub.connect(request, response);

  assert.equal(response.chunks.join(""), ": connected\n\n");
  request.emit("close");
  hub.close();
});

test("disconnects a slow SSE client when write reports backpressure", () => {
  const hub = new Events({ heartbeatMs: 60_000 });
  const request = new EventEmitter();
  request.headers = {};
  const response = new FakeResponse();
  hub.connect(request, response, "device:slow");
  response.writeResult = false;

  hub.publish("snapshot_changed", { revision: "r_1" });
  const chunksAfterBackpressure = response.chunks.length;
  hub.publish("snapshot_changed", { revision: "r_2" });

  assert.equal(response.ended, 1);
  assert.equal(response.chunks.length, chunksAfterBackpressure);
  hub.close();
});

test("closes only SSE streams owned by a revoked credential identity", () => {
  const hub = new Events({ heartbeatMs: 60_000 });
  const firstRequest = new EventEmitter();
  firstRequest.headers = {};
  const firstResponse = new FakeResponse();
  const secondRequest = new EventEmitter();
  secondRequest.headers = {};
  const secondResponse = new FakeResponse();
  hub.connect(firstRequest, firstResponse, "device:first");
  hub.connect(secondRequest, secondResponse, "device:second");

  assert.equal(hub.closeIdentity("device:first"), 1);
  hub.publish("snapshot_changed", { revision: "r_1" });

  assert.equal(firstResponse.ended, 1);
  assert.doesNotMatch(firstResponse.chunks.join(""), /revision/);
  assert.match(secondResponse.chunks.join(""), /revision/);
  hub.close();
});

test("revalidates an SSE principal before publishing to an evicted credential", () => {
  const hub = new Events({ heartbeatMs: 60_000 });
  const request = new EventEmitter();
  request.headers = {};
  const response = new FakeResponse();
  let valid = true;
  hub.connect(request, response, { id: "device:evicted", valid: () => valid });
  const chunksBeforeEviction = response.chunks.length;

  valid = false;
  hub.publish("snapshot_changed", { revision: "r_secret" });

  assert.equal(response.ended, 1);
  assert.equal(response.chunks.length, chunksBeforeEviction);
  hub.close();
});

test("denies an SSE principal that is already invalid", () => {
  const hub = new Events({ heartbeatMs: 60_000 });
  const request = new EventEmitter();
  request.headers = {};
  const response = new FakeResponse();

  assert.equal(hub.connect(request, response, { id: "device:evicted", valid: () => false }), false);
  assert.equal(response.ended, 1);
  assert.equal(response.headers.Connection, "close");
  hub.close();
});
