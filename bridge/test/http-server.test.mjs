import assert from "node:assert/strict";
import test from "node:test";

import { createPocketHttpServer, POCKET_PROTOCOL_VERSION } from "../src/http-server.mjs";

const TOKEN = "test-token-with-at-least-24-characters";

async function withServer(run) {
  const calls = [];
  const service = {
    async snapshot() { return { revision: "r_7", controller: { taskState: "idle" } }; },
    async command(command, idempotencyKey) {
      calls.push({ command, idempotencyKey });
      return { accepted: true, commandId: "cmd_test", revision: "r_8" };
    },
  };
  const events = { connect() { throw new Error("SSE is not used in this test."); } };
  const server = createPocketHttpServer({ service, events, token: TOKEN });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    await run({ baseUrl: `http://127.0.0.1:${port}`, calls });
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

test("health check distinguishes reachability without exposing controller state", async () => {
  await withServer(async ({ baseUrl }) => {
    const response = await fetch(`${baseUrl}/healthz`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get("x-content-type-options"), "nosniff");
    assert.deepEqual(await response.json(), {
      ok: true,
      service: "vibe-pocket-bridge",
      protocolVersion: POCKET_PROTOCOL_VERSION,
    });
  });
});

test("snapshot and commands remain authenticated", async () => {
  await withServer(async ({ baseUrl, calls }) => {
    const unauthorized = await fetch(`${baseUrl}/v1/pocket/snapshot`);
    assert.equal(unauthorized.status, 401);

    const snapshot = await fetch(`${baseUrl}/v1/pocket/snapshot`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
    });
    assert.equal(snapshot.status, 200);
    assert.equal((await snapshot.json()).revision, "r_7");

    const command = await fetch(`${baseUrl}/v1/pocket/commands`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
        "Idempotency-Key": "gesture-123",
      },
      body: JSON.stringify({ kind: "binding", inputId: "key_voice" }),
    });
    assert.equal(command.status, 202);
    assert.deepEqual(calls, [{
      command: { kind: "binding", inputId: "key_voice" },
      idempotencyKey: "gesture-123",
    }]);
  });
});
