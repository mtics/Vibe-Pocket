import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import test from "node:test";

import { create, PROTOCOL_VERSION } from "../../src/server/http.mjs";
import { Events } from "../../src/server/events.mjs";
import { Invitations } from "../../src/pairing/invitations.mjs";

const TOKEN = "test-token-with-at-least-24-characters";
const DEVICE_TOKEN = "vp1.testdevice.abcdefghijklmnopqrstuvwxyzABCDEFG";

async function withServer(run, { eventHub = null } = {}) {
  const calls = [];
  const attachedThreads = [];
  const hooks = [];
  const service = {
    async snapshot() { return { revision: "r_7", controller: { taskState: "idle" } }; },
    async bindDesktopThread(threadId) {
      attachedThreads.push(threadId);
      return { attached: true, revision: "r_8" };
    },
    async codexHook(event, payload) {
      hooks.push({ event, payload });
      return { hookSpecificOutput: { hookEventName: event } };
    },
    async command(command, idempotencyKey) {
      calls.push({ command, idempotencyKey });
      return { accepted: true, commandId: "cmd_test", revision: "r_8" };
    },
  };
  const events = eventHub ?? { connect() { throw new Error("SSE is not used in this test."); } };
  const invitations = new Invitations({ issue: () => DEVICE_TOKEN });
  let deviceActive = true;
  const credentials = {
    accepts: (candidate) => candidate === DEVICE_TOKEN && deviceActive,
    resolve: (candidate) => {
      if (candidate === DEVICE_TOKEN && deviceActive) {
        return { id: "device:test", revocable: true, valid: () => deviceActive };
      }
      if (candidate === TOKEN) return { id: "root:test", revocable: false, valid: () => true };
      return null;
    },
    revoke: (candidate) => {
      if (candidate !== DEVICE_TOKEN || !deviceActive) return false;
      deviceActive = false;
      return true;
    },
  };
  const server = create({ service, events, token: TOKEN, credentials, invitations });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    await run({ baseUrl: `http://127.0.0.1:${port}`, calls, attachedThreads, hooks, invitations, server });
  } finally {
    await server.stopAccepting();
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
      protocolVersion: PROTOCOL_VERSION,
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

test("pairing claims are public, phone-bound, and invitation creation is not exposed", async () => {
  await withServer(async ({ baseUrl, invitations }) => {
    const invitation = invitations.create("https://m5.example.ts.net");
    const code = new URL(invitation.pairingUrl).searchParams.get("code");
    const nonce = "n".repeat(43);

    const claim = await fetch(`${baseUrl}/v1/pairing/claim`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, nonce }),
    });
    assert.equal(claim.status, 200);
    assert.deepEqual(await claim.json(), {
      baseUrl: "https://m5.example.ts.net",
      token: DEVICE_TOKEN,
      protocolVersion: 6,
      capabilities: ["device_credentials", "events", "virtual_hardware"],
    });

    const replay = await fetch(`${baseUrl}/v1/pairing/claim`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, nonce: "o".repeat(43) }),
    });
    assert.equal(replay.status, 410);

    const deviceSnapshot = await fetch(`${baseUrl}/v1/pocket/snapshot`, {
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(deviceSnapshot.status, 200);

    const revoked = await fetch(`${baseUrl}/v1/pocket/devices/current`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(revoked.status, 200);
    const rejectedAfterRevoke = await fetch(`${baseUrl}/v1/pocket/snapshot`, {
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(rejectedAfterRevoke.status, 401);

    const remoteCreation = await fetch(`${baseUrl}/v1/pairing/invitations`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ origin: "https://m5.example.ts.net" }),
    });
    assert.equal(remoteCreation.status, 404);
  });
});

test("desktop task attachment is authenticated and forwards only the task ID", async () => {
  await withServer(async ({ baseUrl, attachedThreads }) => {
    const body = JSON.stringify({ threadId: "019f2ce2-e042-7ab0-a73d-9fa41d58e210" });
    const unauthorized = await fetch(`${baseUrl}/v1/pocket/desktop/attach`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });
    assert.equal(unauthorized.status, 401);

    const response = await fetch(`${baseUrl}/v1/pocket/desktop/attach`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
      },
      body,
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { attached: true, revision: "r_8" });
    assert.deepEqual(attachedThreads, ["019f2ce2-e042-7ab0-a73d-9fa41d58e210"]);
  });
});

test("Codex lifecycle hooks are authenticated and forward bounded JSON", async () => {
  await withServer(async ({ baseUrl, hooks }) => {
    const payload = {
      hook_event_name: "PreToolUse",
      session_id: "019f2ce2-e042-7ab0-a73d-9fa41d58e210",
      turn_id: "turn-7",
      cwd: "/Users/lizhw/Project",
      tool_use_id: "tool-2",
    };
    const unauthorized = await fetch(`${baseUrl}/v1/pocket/codex-hooks/PreToolUse`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    assert.equal(unauthorized.status, 401);

    const response = await fetch(`${baseUrl}/v1/pocket/codex-hooks/PreToolUse`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { hookSpecificOutput: { hookEventName: "PreToolUse" } });
    assert.deepEqual(hooks, [{ event: "PreToolUse", payload }]);
  });
});

test("rejects an unfinished oversized body promptly and does not block shutdown", async () => {
  await withServer(async ({ baseUrl, calls, server }) => {
    const body = Buffer.alloc(64 * 1024 + 1, 0x61);
    const { request, response } = unfinishedRequest(`${baseUrl}/v1/pocket/commands`, {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Length": body.length + 1,
      "Content-Type": "application/json",
      "Idempotency-Key": "unfinished-oversized-body",
    });
    request.write(body);

    const oversized = await within(response, 250, "oversized body was not rejected promptly");
    assert.equal(oversized.status, 413);
    assert.equal(oversized.headers.connection, "close");
    assert.deepEqual(JSON.parse(oversized.body), {
      error: { code: "body_too_large", message: "Request payload is too large." },
    });
    await within(server.drain(), 250, "oversized body blocked request drain");
    await within(server.stopAccepting(), 250, "oversized body blocked server shutdown");
    assert.deepEqual(calls, []);
  });
});

test("rejects an empty JSON body instead of treating it as an empty object", async () => {
  await withServer(async ({ baseUrl, calls }) => {
    const response = await fetch(`${baseUrl}/v1/pocket/commands`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Type": "application/json",
        "Idempotency-Key": "empty-body",
      },
    });

    assert.equal(response.status, 400);
    assert.equal((await response.json()).error.code, "invalid_json");
    assert.deepEqual(calls, []);
  });
});

test("rejects malformed UTF-8 and truncated JSON with one structured 400", async () => {
  await withServer(async ({ baseUrl, calls }) => {
    const malformed = Buffer.from([0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xc3, 0x22, 0x7d]);
    const headers = {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Type": "application/json",
      "Idempotency-Key": "invalid-body",
    };
    const invalidUtf8 = await rawRequest(`${baseUrl}/v1/pocket/commands`, {
      method: "POST",
      headers: { ...headers, "Content-Length": malformed.length },
      chunks: [malformed],
    });
    assert.equal(invalidUtf8.status, 400);
    assert.equal(JSON.parse(invalidUtf8.body).error.code, "invalid_json");

    const truncated = Buffer.from('{"kind":"stop"');
    const invalidJson = await rawRequest(`${baseUrl}/v1/pocket/commands`, {
      method: "POST",
      headers: {
        ...headers,
        "Idempotency-Key": "truncated-body",
        "Content-Length": truncated.length,
      },
      chunks: [truncated],
    });
    assert.equal(invalidJson.status, 400);
    assert.equal(JSON.parse(invalidJson.body).error.code, "invalid_json");
    assert.deepEqual(calls, []);
  });
});

test("accepts a valid JSON body delivered in slow raw-byte chunks", async () => {
  await withServer(async ({ baseUrl, calls }) => {
    const body = Buffer.from('{"kind":"stop"}');
    const response = await rawRequest(`${baseUrl}/v1/pocket/commands`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${TOKEN}`,
        "Content-Length": body.length,
        "Content-Type": "application/json",
        "Idempotency-Key": "slow-body",
      },
      chunks: [body.subarray(0, 5), body.subarray(5)],
      delayMs: 15,
    });

    assert.equal(response.status, 202);
    assert.deepEqual(calls, [{ command: { kind: "stop" }, idempotencyKey: "slow-body" }]);
  });
});

test("successful device revocation closes its live SSE stream", async () => {
  const events = new Events({ heartbeatMs: 60_000 });
  await withServer(async ({ baseUrl }) => {
    const stream = await fetch(`${baseUrl}/v1/pocket/events`, {
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(stream.status, 200);
    const reader = stream.body.getReader();
    const connected = await reader.read();
    assert.match(Buffer.from(connected.value).toString("utf8"), /connected/);

    const revoked = await fetch(`${baseUrl}/v1/pocket/devices/current`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(revoked.status, 200);
    const closed = await Promise.race([
      readUntilClosed(reader),
      new Promise((_, reject) => setTimeout(() => reject(new Error("revoked SSE remained open")), 250)),
    ]);
    assert.equal(closed, true);
  }, { eventHub: events });
});

function rawRequest(url, {
  agent,
  method = "GET",
  headers = {},
  chunks = [],
  delayMs = 0,
} = {}) {
  return new Promise((resolve, reject) => {
    let socket;
    const request = httpRequest(url, { agent, method, headers }, (response) => {
      const responseChunks = [];
      response.on("data", (chunk) => responseChunks.push(chunk));
      response.on("end", () => resolve({
        status: response.statusCode,
        body: Buffer.concat(responseChunks).toString("utf8"),
        socket,
      }));
    });
    request.on("socket", (value) => { socket = value; });
    request.on("error", reject);
    void (async () => {
      for (const [index, chunk] of chunks.entries()) {
        request.write(chunk);
        if (delayMs > 0 && index < chunks.length - 1) {
          await new Promise((resolveDelay) => setTimeout(resolveDelay, delayMs));
        }
      }
      request.end();
    })().catch(reject);
  });
}

async function readUntilClosed(reader) {
  while (true) {
    const { done } = await reader.read();
    if (done) return true;
  }
}

function unfinishedRequest(url, headers) {
  let resolveResponse;
  let rejectResponse;
  const response = new Promise((resolve, reject) => {
    resolveResponse = resolve;
    rejectResponse = reject;
  });
  const request = httpRequest(url, { method: "POST", headers }, (incoming) => {
    const chunks = [];
    incoming.on("data", (chunk) => chunks.push(chunk));
    incoming.on("end", () => resolveResponse({
      status: incoming.statusCode,
      headers: incoming.headers,
      body: Buffer.concat(chunks).toString("utf8"),
    }));
  });
  request.on("error", rejectResponse);
  return { request, response };
}

function within(promise, timeoutMs, message) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(message)), timeoutMs)),
  ]);
}
