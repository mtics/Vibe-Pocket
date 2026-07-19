import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { createConnection } from "node:net";
import { once } from "node:events";
import test from "node:test";

import { create, PROTOCOL_VERSION } from "../../src/server/http.mjs";
import { Events } from "../../src/server/events.mjs";
import { Invitations } from "../../src/pairing/invitations.mjs";

const TOKEN = "test-token-with-at-least-24-characters";
const DEVICE_TOKEN = "vp1.testdevice.abcdefghijklmnopqrstuvwxyzABCDEFG";

async function withServer(run, { eventHub = null, deadlines = {}, activationFailure = null } = {}) {
  const calls = [];
  const commandPrincipals = [];
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
    async command(command, idempotencyKey, principal) {
      calls.push({ command, idempotencyKey });
      commandPrincipals.push(principal);
      return { accepted: true, commandId: "cmd_test", revision: "r_8" };
    },
  };
  const events = eventHub ?? { connect() { throw new Error("SSE is not used in this test."); } };
  let deviceState = "active";
  const invitations = new Invitations({
    issue: () => {
      deviceState = "pending";
      return DEVICE_TOKEN;
    },
  });
  const credentials = {
    accepts: (candidate) => candidate === DEVICE_TOKEN && deviceState != null,
    resolve: (candidate) => {
      if (candidate === DEVICE_TOKEN && deviceState != null) {
        return {
          id: "device:test",
          role: "device",
          state: deviceState,
          revocable: true,
          valid: () => deviceState != null,
        };
      }
      if (candidate === TOKEN) {
        return { id: "root:test", role: "root", state: "active", revocable: false, valid: () => true };
      }
      return null;
    },
    activate: (candidate) => {
      if (candidate !== DEVICE_TOKEN || deviceState == null) return false;
      if (deviceState === "pending" && activationFailure) throw activationFailure;
      deviceState = "active";
      return true;
    },
    revoke: (candidate) => {
      if (candidate !== DEVICE_TOKEN || deviceState == null) return false;
      deviceState = null;
      return true;
    },
  };
  const server = create({ service, events, token: TOKEN, credentials, invitations, deadlines });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    await run({
      baseUrl: `http://127.0.0.1:${port}`,
      port,
      calls,
      commandPrincipals,
      attachedThreads,
      hooks,
      invitations,
      server,
    });
  } finally {
    await server.stopAccepting();
  }
}

test("health check distinguishes reachability without exposing controller state", async () => {
  assert.equal(PROTOCOL_VERSION, 7);
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
  await withServer(async ({ baseUrl, calls, commandPrincipals }) => {
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
    assert.equal(commandPrincipals[0].role, "root");
  });
});

test("pairing claims stay gated until a bodyless, repeatable commit", async () => {
  await withServer(async ({ baseUrl, invitations, calls, attachedThreads }) => {
    const invitation = invitations.create("https://m5.example.ts.net");
    const pairingUrl = new URL(invitation.pairingUrl);
    const code = pairingUrl.searchParams.get("code");
    const nonce = "n".repeat(43);
    assert.equal(pairingUrl.searchParams.get("expiresAt"), invitation.expiresAt);

    const claim = await fetch(`${baseUrl}/v1/pairing/claim`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, nonce }),
    });
    assert.equal(claim.status, 200);
    assert.deepEqual(await claim.json(), {
      baseUrl: "https://m5.example.ts.net",
      token: DEVICE_TOKEN,
      credentialState: "pending",
      credentialExpiresAt: invitation.expiresAt,
      protocolVersion: 7,
      capabilities: ["device_credentials", "events", "virtual_hardware", "pairing_commit"],
    });

    const replay = await fetch(`${baseUrl}/v1/pairing/claim`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, nonce: "o".repeat(43) }),
    });
    assert.equal(replay.status, 410);

    const pendingRequests = await Promise.all([
      fetch(`${baseUrl}/v1/pocket/snapshot`, {
        headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
      }),
      fetch(`${baseUrl}/v1/pocket/events`, {
        headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
      }),
      fetch(`${baseUrl}/v1/pocket/commands`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${DEVICE_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ kind: "stop" }),
      }),
      fetch(`${baseUrl}/v1/pocket/desktop/attach`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${DEVICE_TOKEN}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ threadId: "pending-thread" }),
      }),
    ]);
    for (const response of pendingRequests) {
      assert.equal(response.status, 403);
      assert.equal((await response.json()).error.code, "credential_not_active");
    }
    assert.deepEqual(calls, []);
    assert.deepEqual(attachedThreads, []);

    const bodyRejected = await fetch(`${baseUrl}/v1/pairing/commit`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${DEVICE_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: "{}",
    });
    assert.equal(bodyRejected.status, 400);
    assert.equal((await bodyRejected.json()).error.code, "unexpected_body");

    const commit = await fetch(`${baseUrl}/v1/pairing/commit`, {
      method: "POST",
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(commit.status, 200);
    assert.deepEqual(await commit.json(), { paired: true });
    const repeated = await fetch(`${baseUrl}/v1/pairing/commit`, {
      method: "POST",
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(repeated.status, 200);
    assert.deepEqual(await repeated.json(), { paired: true });

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

test("pending credentials can revoke and failed activation remains pending", async () => {
  await withServer(async ({ baseUrl, invitations }) => {
    const invitation = invitations.create("https://m5.example.ts.net");
    const code = new URL(invitation.pairingUrl).searchParams.get("code");
    await fetch(`${baseUrl}/v1/pairing/claim`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, nonce: "p".repeat(43) }),
    });

    const commit = await fetch(`${baseUrl}/v1/pairing/commit`, {
      method: "POST",
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(commit.status, 500);
    const gated = await fetch(`${baseUrl}/v1/pocket/snapshot`, {
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(gated.status, 403);
    assert.equal((await gated.json()).error.code, "credential_not_active");
    const revoked = await fetch(`${baseUrl}/v1/pocket/devices/current`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${DEVICE_TOKEN}` },
    });
    assert.equal(revoked.status, 200);
  }, { activationFailure: new Error("activation write failed") });
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

test("device credentials cannot call root-only desktop attachment or Codex hooks", async () => {
  await withServer(async ({ baseUrl, attachedThreads, hooks }) => {
    const attach = await fetch(`${baseUrl}/v1/pocket/desktop/attach`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${DEVICE_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ threadId: "019f2ce2-e042-7ab0-a73d-9fa41d58e210" }),
    });
    assert.equal(attach.status, 403);
    assert.equal((await attach.json()).error.code, "root_credential_required");

    const hook = await fetch(`${baseUrl}/v1/pocket/codex-hooks/PreToolUse`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${DEVICE_TOKEN}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ hook_event_name: "PreToolUse" }),
    });
    assert.equal(hook.status, 403);
    assert.equal((await hook.json()).error.code, "root_credential_required");
    assert.deepEqual(attachedThreads, []);
    assert.deepEqual(hooks, []);
  });
});

test("closes an unauthenticated early-response body without blocking drain", async () => {
  await withServer(async ({ baseUrl, server }) => {
    const { request, response } = unfinishedRequest(`${baseUrl}/v1/pocket/commands`, {
      "Content-Length": 100,
      "Content-Type": "application/json",
      "Idempotency-Key": "unauthenticated-slow-body",
    });
    request.write("{");

    const unauthorized = await within(response, 250, "unauthenticated body held the response open");
    assert.equal(unauthorized.status, 401);
    assert.equal(unauthorized.headers.connection, "close");
    await within(server.drain(), 250, "unauthenticated body blocked request drain");
  });
});

test("times out a slow authenticated body and closes its connection", async () => {
  await withServer(async ({ baseUrl, calls, server }) => {
    const { request, response } = unfinishedRequest(`${baseUrl}/v1/pocket/commands`, {
      Authorization: `Bearer ${TOKEN}`,
      "Content-Length": 20,
      "Content-Type": "application/json",
      "Idempotency-Key": "timed-out-body",
    });
    request.write('{"kind"');

    const timedOut = await within(response, 300, "slow body did not reach its deadline");
    assert.equal(timedOut.status, 408);
    assert.equal(timedOut.headers.connection, "close");
    assert.equal(JSON.parse(timedOut.body).error.code, "request_timeout");
    await within(server.drain(), 250, "timed-out body blocked request drain");
    assert.deepEqual(calls, []);
  }, { deadlines: { bodyMs: 40, shutdownMs: 80 } });
});

test("destroys a raw socket with incomplete headers at the shutdown deadline", async () => {
  await withServer(async ({ port, server }) => {
    const socket = createConnection({ host: "127.0.0.1", port });
    await once(socket, "connect");
    socket.write("POST /v1/pocket/commands HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer");
    const closed = once(socket, "close");

    await within(server.stopAccepting(), 300, "partial headers blocked server shutdown");
    await within(closed, 100, "partial-header socket remained open");
    assert.equal(socket.destroyed, true);
  }, { deadlines: { headersMs: 500, shutdownMs: 40 } });
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
