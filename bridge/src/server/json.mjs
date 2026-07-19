import { Failure } from "./failure.mjs";

export function readJson(request, {
  maxBytes = 64 * 1024,
  timeoutMs = 10_000,
  invalidMessage = "Request body must be valid JSON.",
  tooLargeMessage = "Request payload is too large.",
} = {}) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    let settled = false;

    const timer = setTimeout(() => {
      request.pause();
      finish(() => reject(new Failure(408, "request_timeout", "Request body was not received before the deadline.")));
    }, timeoutMs);
    timer.unref();

    const finish = (operation) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      operation();
    };

    const declaredLength = Number.parseInt(request.headers["content-length"] ?? "0", 10);
    if (Number.isSafeInteger(declaredLength) && declaredLength > maxBytes) {
      request.pause();
      finish(() => reject(new Failure(413, "body_too_large", tooLargeMessage)));
      return;
    }

    request.on("data", (chunk) => {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      bytes += buffer.length;
      if (bytes > maxBytes) {
        chunks.length = 0;
        request.pause();
        finish(() => reject(new Failure(413, "body_too_large", tooLargeMessage)));
        return;
      }
      chunks.push(buffer);
    });
    request.on("end", () => finish(() => {
      try {
        const source = new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks, bytes));
        resolve(JSON.parse(source));
      } catch {
        reject(new Failure(400, "invalid_json", invalidMessage));
      }
    }));
    request.on("aborted", () => finish(() => {
      reject(new Failure(400, "incomplete_request", "Request body was truncated."));
    }));
    request.on("error", (error) => finish(() => reject(error)));
  });
}
