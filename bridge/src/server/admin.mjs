import { createServer } from "node:http";

import { Failure } from "./failure.mjs";
import { readJson } from "./json.mjs";
import { manage, RequestTracker } from "./request-tracker.mjs";

export function create({ invitations }) {
  const requests = new RequestTracker();
  const server = createServer((request, response) => {
    void requests.run(() => handle(request, response)).catch((error) => sendError(response, error));
  });

  async function handle(request, response) {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "POST" && url.pathname === "/v1/pairing/invitations") {
        const { origin } = await readJson(request, {
          maxBytes: 4 * 1024,
          invalidMessage: "Pairing request must be valid JSON.",
          tooLargeMessage: "Pairing request is too large.",
        });
        send(response, 201, invitations.create(origin));
        return;
      }
      send(response, 404, { error: { code: "not_found", message: "Vibe Pocket admin endpoint not found." } });
    } catch (error) {
      sendError(response, error);
    }
  }
  return manage(server, requests);
}

function sendError(response, error) {
  const status = error instanceof Failure ? error.status : 500;
  const code = error instanceof Failure ? error.code : "admin_error";
  const message = error instanceof Failure ? error.message : "The Vibe Pocket admin service could not finish this request.";
  send(response, status, { error: { code, message } });
}

function send(response, status, body) {
  if (response.headersSent || response.writableEnded || response.destroyed) return;
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(JSON.stringify(body));
}
