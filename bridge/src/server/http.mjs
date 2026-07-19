import { createHash, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { Failure } from "./failure.mjs";
import { Invitations } from "../pairing/invitations.mjs";
import { readJson } from "./json.mjs";
import { manage, normalizeDeadlines, RequestTracker } from "./request-tracker.mjs";

export const PROTOCOL_VERSION = 7;

export function create({
  service,
  events,
  token,
  credentials,
  invitations = new Invitations({
    issue: (expiresAt) => {
      if (typeof credentials?.issue !== "function") {
        throw new Failure(503, "pairing_unavailable", "Device credential issuance is unavailable.");
      }
      return credentials.issue(expiresAt);
    },
  }),
  deadlines: deadlineOverrides = {},
}) {
  const deadlines = normalizeDeadlines(deadlineOverrides);
  const requests = new RequestTracker();
  const server = createServer((request, response) => {
    void requests.run(() => handle(request, response)).catch((error) => sendError(request, response, error));
  });

  async function handle(request, response) {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "GET" && url.pathname === "/healthz") {
        requireBodyless(request);
        sendJson(request, response, 200, {
          ok: true,
          service: "vibe-pocket-bridge",
          protocolVersion: PROTOCOL_VERSION,
        });
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pairing/claim") {
        const { code, nonce } = await readJson(request, { maxBytes: 4 * 1024, timeoutMs: deadlines.bodyMs });
        sendJson(request, response, 200, invitations.claim(code, nonce));
        return;
      }
      const principal = authenticate(request, token, credentials);
      if (!principal) {
        sendJson(request, response, 401, { error: { code: "unauthorized", message: "Pair Vibe Pocket before connecting." } });
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pairing/commit") {
        requireBodyless(request);
        if (!principal.revocable || !credentials?.activate(bearer(request))) {
          throw new Failure(400, "device_credential_required", "Only a paired device can commit pairing.");
        }
        sendJson(request, response, 200, { paired: true });
        return;
      }
      if (request.method === "DELETE" && url.pathname === "/v1/pocket/devices/current") {
        requireBodyless(request);
        if (!principal.revocable || !credentials?.revoke(bearer(request))) {
          throw new Failure(400, "device_credential_required", "Only a paired device can revoke its credential.");
        }
        events.closeIdentity?.(principal.id);
        sendJson(request, response, 200, { revoked: true });
        return;
      }
      requireActive(principal);
      if (request.method === "GET" && url.pathname === "/v1/pocket/snapshot") {
        requireBodyless(request);
        sendJson(request, response, 200, await service.snapshot());
        return;
      }
      if (request.method === "GET" && url.pathname === "/v1/pocket/events") {
        requireBodyless(request);
        ensureValid(principal);
        events.connect(request, response, principal);
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pocket/desktop/attach") {
        requireRoot(principal);
        const { threadId } = await readJson(request, { timeoutMs: deadlines.bodyMs });
        const responseBody = await service.bindDesktopThread(threadId);
        sendJson(request, response, 200, responseBody);
        return;
      }
      const hookMatch = url.pathname.match(/^\/v1\/pocket\/codex-hooks\/([A-Za-z]+)$/);
      if (request.method === "POST" && hookMatch) {
        requireRoot(principal);
        const responseBody = await service.codexHook(hookMatch[1], await readJson(request, {
          maxBytes: 2 * 1024 * 1024,
          timeoutMs: deadlines.bodyMs,
        }));
        sendJson(request, response, 200, responseBody);
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pocket/commands") {
        const command = await readJson(request, { timeoutMs: deadlines.bodyMs });
        const responseBody = await service.command(command, request.headers["idempotency-key"], principal);
        sendJson(request, response, 202, responseBody);
        return;
      }
      sendJson(request, response, 404, { error: { code: "not_found", message: "Vibe Pocket endpoint not found." } });
    } catch (error) {
      sendError(request, response, error);
    }
  }

  return manage(server, requests, { deadlines });
}

function authenticate(request, token, credentials) {
  const raw = bearer(request);
  if (raw == null) return null;
  const resolved = credentials?.resolve?.(raw);
  if (resolved) {
    const principal = Object.freeze({
      ...resolved,
      role: resolved.role ?? (resolved.revocable ? "device" : "root"),
      state: resolved.state ?? "active",
    });
    return principal.valid?.() === false ? null : principal;
  }
  if (credentials?.accepts(raw)) {
    return {
      id: identity(raw),
      role: equal(raw, token) ? "root" : "device",
      state: "active",
      revocable: raw !== token,
      valid: () => credentials.accepts(raw),
    };
  }
  if (!equal(raw, token)) return null;
  return { id: identity(raw), role: "root", state: "active", revocable: false, valid: () => true };
}

function bearer(request) {
  const value = request.headers.authorization;
  return value?.startsWith("Bearer ") ? value.slice("Bearer ".length) : null;
}

function identity(raw) {
  return `credential:${createHash("sha256").update(raw).digest("base64url")}`;
}

function equal(candidate, expected) {
  const left = Buffer.from(candidate);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

function requireRoot(principal) {
  if (principal.role !== "root") {
    throw new Failure(403, "root_credential_required", "This endpoint requires the local bridge root credential.");
  }
}

function requireActive(principal) {
  if (principal.state !== "active") {
    throw new Failure(403, "credential_not_active", "Complete pairing before using this credential.");
  }
}

function ensureValid(principal) {
  if (typeof principal.valid !== "function" || !principal.valid()) {
    throw new Failure(401, "credential_revoked", "This paired device credential has been revoked.");
  }
}

function requireBodyless(request) {
  const contentLength = Number.parseInt(request.headers["content-length"] ?? "0", 10);
  if (request.headers["transfer-encoding"] != null || contentLength > 0) {
    throw new Failure(400, "unexpected_body", "This endpoint does not accept a request body.");
  }
}

function sendError(request, response, error) {
  const status = error instanceof Failure ? error.status : 500;
  const code = error instanceof Failure ? error.code : "bridge_error";
  const message = error instanceof Failure ? error.message : "The Vibe Pocket bridge could not finish this request.";
  sendJson(request, response, status, { error: { code, message } }, {
    closeConnection: ["body_too_large", "request_timeout", "incomplete_request", "unexpected_body"].includes(code),
  });
}

function sendJson(request, response, status, body, { closeConnection = false } = {}) {
  if (response.headersSent || response.writableEnded || response.destroyed) return;
  closeConnection ||= !request.complete;
  const socket = response.socket;
  if (closeConnection) response.shouldKeepAlive = false;
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
