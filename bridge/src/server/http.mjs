import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { Failure } from "./failure.mjs";
import { Invitations } from "../pairing/invitations.mjs";

export const PROTOCOL_VERSION = 6;

export function create({ service, events, token, credentials, invitations = new Invitations({ issue: () => token }) }) {
  return createServer(async (request, response) => {
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
        const { code, nonce } = await readJson(request, 4 * 1024);
        sendJson(response, 200, invitations.claim(code, nonce));
        return;
      }
      if (!authorized(request, token, credentials)) {
        sendJson(response, 401, { error: { code: "unauthorized", message: "Pair Vibe Pocket before connecting." } });
        return;
      }
      if (request.method === "DELETE" && url.pathname === "/v1/pocket/devices/current") {
        if (!credentials?.revoke(bearer(request))) {
          throw new Failure(400, "device_credential_required", "Only a paired device can revoke its credential.");
        }
        sendJson(response, 200, { revoked: true });
        return;
      }
      if (request.method === "GET" && url.pathname === "/v1/pocket/snapshot") {
        sendJson(response, 200, await service.snapshot());
        return;
      }
      if (request.method === "GET" && url.pathname === "/v1/pocket/events") {
        events.connect(request, response);
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
        const responseBody = await service.codexHook(hookMatch[1], await readJson(request, 2 * 1024 * 1024));
        sendJson(response, 200, responseBody);
        return;
      }
      if (request.method === "POST" && url.pathname === "/v1/pocket/commands") {
        const command = await readJson(request);
        const responseBody = await service.command(command, request.headers["idempotency-key"]);
        sendJson(response, 202, responseBody);
        return;
      }
      sendJson(response, 404, { error: { code: "not_found", message: "Vibe Pocket endpoint not found." } });
    } catch (error) {
      const status = error instanceof Failure ? error.status : 500;
      const code = error instanceof Failure ? error.code : "bridge_error";
      const message = error instanceof Failure ? error.message : "The Vibe Pocket bridge could not finish this request.";
      sendJson(response, status, { error: { code, message } });
    }
  });
}

function authorized(request, token, credentials) {
  const raw = bearer(request);
  if (raw == null) return false;
  if (credentials?.accepts(raw)) return true;
  const candidate = Buffer.from(raw);
  const expected = Buffer.from(token);
  return candidate.length === expected.length && timingSafeEqual(candidate, expected);
}

function bearer(request) {
  const value = request.headers.authorization;
  return value?.startsWith("Bearer ") ? value.slice("Bearer ".length) : null;
}

function readJson(request, maxLength = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > maxLength) {
        reject(new Failure(413, "body_too_large", "Request payload is too large."));
        request.destroy();
      }
    });
    request.on("end", () => {
      try {
        resolve(JSON.parse(body || "{}"));
      } catch {
        reject(new Failure(400, "invalid_json", "Command body must be valid JSON."));
      }
    });
    request.on("error", reject);
  });
}

function sendJson(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(JSON.stringify(body));
}
