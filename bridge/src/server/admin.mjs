import { createServer } from "node:http";

import { Failure } from "./failure.mjs";
import { readJson } from "./json.mjs";
import { manage, normalizeDeadlines, RequestTracker } from "./request-tracker.mjs";

export function create({ invitations, deadlines: deadlineOverrides = {} }) {
  const deadlines = normalizeDeadlines(deadlineOverrides);
  const requests = new RequestTracker();
  const server = createServer((request, response) => {
    void requests.run(() => handle(request, response)).catch((error) => sendError(request, response, error));
  });

  async function handle(request, response) {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "POST" && url.pathname === "/v1/pairing/invitations") {
        const { origin } = await readJson(request, {
          maxBytes: 4 * 1024,
          timeoutMs: deadlines.bodyMs,
          invalidMessage: "Pairing request must be valid JSON.",
          tooLargeMessage: "Pairing request is too large.",
        });
        send(request, response, 201, invitations.create(origin));
        return;
      }
      send(request, response, 404, { error: { code: "not_found", message: "Vibe Pocket admin endpoint not found." } });
    } catch (error) {
      sendError(request, response, error);
    }
  }
  return manage(server, requests, { deadlines });
}

function sendError(request, response, error) {
  const status = error instanceof Failure ? error.status : 500;
  const code = error instanceof Failure ? error.code : "admin_error";
  const message = error instanceof Failure ? error.message : "The Vibe Pocket admin service could not finish this request.";
  send(request, response, status, { error: { code, message } }, {
    closeConnection: ["body_too_large", "request_timeout", "incomplete_request"].includes(code),
  });
}

function send(request, response, status, body, { closeConnection = false } = {}) {
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
