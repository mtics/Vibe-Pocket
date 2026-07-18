import { createServer } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { PocketError } from "./pocket-error.mjs";

export const POCKET_PROTOCOL_VERSION = 5;

export function createPocketHttpServer({ service, events, token }) {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "GET" && url.pathname === "/healthz") {
        sendJson(response, 200, {
          ok: true,
          service: "vibe-pocket-bridge",
          protocolVersion: POCKET_PROTOCOL_VERSION,
        });
        return;
      }
      if (!authorized(request, token)) {
        sendJson(response, 401, { error: { code: "unauthorized", message: "Pair Vibe Pocket before connecting." } });
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
      if (request.method === "POST" && url.pathname === "/v1/pocket/commands") {
        const command = await readJson(request);
        const responseBody = await service.command(command, request.headers["idempotency-key"]);
        sendJson(response, 202, responseBody);
        return;
      }
      sendJson(response, 404, { error: { code: "not_found", message: "Vibe Pocket endpoint not found." } });
    } catch (error) {
      const status = error instanceof PocketError ? error.status : 500;
      const code = error instanceof PocketError ? error.code : "bridge_error";
      const message = error instanceof PocketError ? error.message : "The Vibe Pocket bridge could not finish this request.";
      sendJson(response, status, { error: { code, message } });
    }
  });
}

function authorized(request, token) {
  const value = request.headers.authorization;
  if (!value?.startsWith("Bearer ")) return false;
  const candidate = Buffer.from(value.slice("Bearer ".length));
  const expected = Buffer.from(token);
  return candidate.length === expected.length && timingSafeEqual(candidate, expected);
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 64 * 1024) {
        reject(new PocketError(413, "body_too_large", "Command payload exceeds 64 KB."));
        request.destroy();
      }
    });
    request.on("end", () => {
      try {
        resolve(JSON.parse(body || "{}"));
      } catch {
        reject(new PocketError(400, "invalid_json", "Command body must be valid JSON."));
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
