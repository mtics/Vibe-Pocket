import { createServer } from "node:http";

import { Failure } from "./failure.mjs";

export function create({ invitations }) {
  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "POST" && url.pathname === "/v1/pairing/invitations") {
        const { origin } = await read(request);
        send(response, 201, invitations.create(origin));
        return;
      }
      send(response, 404, { error: { code: "not_found", message: "Vibe Pocket admin endpoint not found." } });
    } catch (error) {
      const status = error instanceof Failure ? error.status : 500;
      const code = error instanceof Failure ? error.code : "admin_error";
      const message = error instanceof Failure ? error.message : "The Vibe Pocket admin service could not finish this request.";
      send(response, status, { error: { code, message } });
    }
  });
}

function read(request) {
  return new Promise((resolve, reject) => {
    let body = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      body += chunk;
      if (body.length > 4 * 1024) {
        reject(new Failure(413, "body_too_large", "Pairing request is too large."));
        request.destroy();
      }
    });
    request.on("end", () => {
      try {
        resolve(JSON.parse(body || "{}"));
      } catch {
        reject(new Failure(400, "invalid_json", "Pairing request must be valid JSON."));
      }
    });
    request.on("error", reject);
  });
}

function send(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  response.end(JSON.stringify(body));
}
