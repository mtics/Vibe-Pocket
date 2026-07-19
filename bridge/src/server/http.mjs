import { createHash, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { Failure } from "./failure.mjs";
import { Invitations } from "../pairing/invitations.mjs";
import { readJson } from "./json.mjs";
import { manage, RequestTracker } from "./request-tracker.mjs";

export const PROTOCOL_VERSION = 6;

export function create({ service, events, token, credentials, invitations = new Invitations({ issue: () => token }) }) {
  const requests = new RequestTracker();
  const server = createServer((request, response) => {
    void requests.run(() => handle(request, response)).catch((error) => sendError(response, error));
  });

  async function handle(request, response) {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "GET" && url.pathname === "/healthz") {
        sendJson(response, 200, {
          ok: true,
          service: "vibe-pocket-bridge",
          protocolVersion: PROTOCOL_VERSION,
        });
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pairing/claim") {
        const { code, nonce } = await readJson(request, { maxBytes: 4 * 1024 });
        sendJson(response, 200, invitations.claim(code, nonce));
        return;
      }
      const principal = authenticate(request, token, credentials);
      if (!principal) {
        sendJson(response, 401, { error: { code: "unauthorized", message: "Pair Vibe Pocket before connecting." } });
        return;
      }
      if (request.method === "DELETE" && url.pathname === "/v1/pocket/devices/current") {
        if (!principal.revocable || !credentials?.revoke(bearer(request))) {
          throw new Failure(400, "device_credential_required", "Only a paired device can revoke its credential.");
        }
        events.closeIdentity?.(principal.id);
        sendJson(response, 200, { revoked: true });
        return;
      }
      if (request.method === "GET" && url.pathname === "/v1/pocket/snapshot") {
        sendJson(response, 200, await service.snapshot());
        return;
      }
      if (request.method === "GET" && url.pathname === "/v1/pocket/events") {
        events.connect(request, response, principal.id);
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pocket/desktop/attach") {
        const { threadId } = await readJson(request);
        const responseBody = await service.bindDesktopThread(threadId);
        sendJson(response, 200, responseBody);
        return;
      }
      const hookMatch = url.pathname.match(/^\/v1\/pocket\/codex-hooks\/([A-Za-z]+)$/);
      if (request.method === "POST" && hookMatch) {
        const responseBody = await service.codexHook(hookMatch[1], await readJson(request, { maxBytes: 2 * 1024 * 1024 }));
        sendJson(response, 200, responseBody);
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pocket/commands") {
        const command = await readJson(request);
        const responseBody = await service.command(command, request.headers["idempotency-key"], principal);
        sendJson(response, 202, responseBody);
        return;
      }
      sendJson(response, 404, { error: { code: "not_found", message: "Vibe Pocket endpoint not found." } });
    } catch (error) {
      sendError(response, error);
    }
  }

  return manage(server, requests);
}

function authenticate(request, token, credentials) {
  const raw = bearer(request);
  if (raw == null) return null;
  const resolved = credentials?.resolve?.(raw);
  if (resolved) return resolved;
  if (credentials?.accepts(raw)) {
    return {
      id: identity(raw),
      revocable: raw !== token,
      valid: () => credentials.accepts(raw),
    };
  }
  const candidate = Buffer.from(raw);
  const expected = Buffer.from(token);
  if (candidate.length !== expected.length || !timingSafeEqual(candidate, expected)) return null;
  return { id: identity(raw), revocable: false, valid: () => true };
}

function bearer(request) {
  const value = request.headers.authorization;
  return value?.startsWith("Bearer ") ? value.slice("Bearer ".length) : null;
}

function identity(raw) {
  return `credential:${createHash("sha256").update(raw).digest("base64url")}`;
}

function sendError(response, error) {
  const status = error instanceof Failure ? error.status : 500;
  const code = error instanceof Failure ? error.code : "bridge_error";
  const message = error instanceof Failure ? error.message : "The Vibe Pocket bridge could not finish this request.";
  sendJson(response, status, { error: { code, message } }, { closeConnection: code === "body_too_large" });
}

function sendJson(response, status, body, { closeConnection = false } = {}) {
  if (response.headersSent || response.writableEnded || response.destroyed) return;
  const socket = response.socket;
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    ...(closeConnection ? { Connection: "close" } : {}),
  });
  response.end(JSON.stringify(body), () => {
    if (closeConnection) socket?.destroy();
  });
}
