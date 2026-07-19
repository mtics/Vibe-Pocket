import { open, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, relative, resolve } from "node:path";

const CHUNK_SIZE = 1024 * 1024;
const LOOKBACK_BYTES = 256 * 1024 * 1024;
const OVERLAP_BYTES = 256 * 1024;

export class Context {
  #sessionsRoot;
  #cache = new Map();

  constructor({ sessionsRoot = resolve(homedir(), ".codex/sessions") } = {}) {
    this.#sessionsRoot = resolve(sessionsRoot);
  }

  async read(thread) {
    const path = this.#safePath(thread?.path);
    if (!path) return null;
    try {
      const fileStat = await stat(path);
      const cached = this.#cache.get(path);
      if (cached?.size === fileStat.size) return cached.value;

      let value;
      if (cached && fileStat.size > cached.size) {
        value = await this.#readForward(
          path,
          Math.max(0, cached.size - OVERLAP_BYTES),
          fileStat.size,
        ) ?? cached.value;
      } else {
        value = await this.#readBackward(path, fileStat.size);
      }
      this.#cache.set(path, { size: fileStat.size, value });
      return value;
    } catch {
      this.#cache.delete(path);
      return null;
    }
  }

  #safePath(value) {
    if (typeof value !== "string" || !isAbsolute(value) || !value.endsWith(".jsonl")) return null;
    const path = resolve(value);
    const child = relative(this.#sessionsRoot, path);
    return child && !child.startsWith("..") && !isAbsolute(child) ? path : null;
  }

  async #readForward(path, start, end) {
    const file = await open(path, "r");
    try {
      let cursor = start;
      let carry = "";
      let latest = null;
      while (cursor < end) {
        const length = Math.min(CHUNK_SIZE, end - cursor);
        const buffer = Buffer.allocUnsafe(length);
        const { bytesRead } = await file.read(buffer, 0, length, cursor);
        if (bytesRead === 0) break;
        const text = carry + buffer.subarray(0, bytesRead).toString("utf8");
        const lineEnd = text.lastIndexOf("\n");
        if (lineEnd >= 0) {
          latest = lastSettings(text.slice(0, lineEnd + 1)) ?? latest;
          carry = text.slice(lineEnd + 1);
        } else {
          carry = text;
        }
        cursor += bytesRead;
      }
      return lastSettings(carry) ?? latest;
    } finally {
      await file.close();
    }
  }

  async #readBackward(path, size) {
    const file = await open(path, "r");
    try {
      const lowerBound = Math.max(0, size - LOOKBACK_BYTES);
      let end = size;
      while (end > lowerBound) {
        const start = Math.max(lowerBound, end - CHUNK_SIZE);
        const buffer = Buffer.allocUnsafe(end - start);
        const { bytesRead } = await file.read(buffer, 0, buffer.length, start);
        const settings = lastSettings(buffer.subarray(0, bytesRead).toString("utf8"));
        if (settings) return settings;
        if (start === lowerBound) break;
        end = start + OVERLAP_BYTES;
      }
      return null;
    } finally {
      await file.close();
    }
  }
}

function lastSettings(text) {
  const lines = text.split("\n");
  for (let index = lines.length - 1; index >= 0; index -= 1) {
    const line = lines[index];
    if (!line.includes('"type":"thread_settings_applied"')) continue;
    try {
      const event = JSON.parse(line);
      const value = event?.payload?.thread_settings;
      if (!value || typeof value !== "object" || Array.isArray(value)) continue;
      return {
        model: typeof value.model === "string" ? value.model : null,
        reasoningEffort: typeof value.reasoning_effort === "string" ? value.reasoning_effort : null,
      };
    } catch {
      continue;
    }
  }
  return null;
}
