import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import { SseHub } from "../src/sse-hub.mjs";

class FakeResponse {
  headers = null;
  chunks = [];

  writeHead(_status, headers) { this.headers = headers; }
  write(chunk) { this.chunks.push(chunk); }
  end() {}
}

test("forces a snapshot refresh when a client resumes after the bridge sequence resets", () => {
  const hub = new SseHub({ heartbeatMs: 60_000 });
  hub.publish("snapshot_changed", { revision: "r_1" });
  const request = new EventEmitter();
  request.headers = { "last-event-id": "99" };
  const response = new FakeResponse();

  hub.connect(request, response);

  assert.equal(response.headers["Content-Type"], "text/event-stream; charset=utf-8");
  assert.match(response.chunks.join(""), /id: 2\nevent: snapshot_changed/);
  assert.match(response.chunks.join(""), /"reason":"history_reset"/);
  request.emit("close");
  hub.close();
});
