import { open, stat } from "node:fs/promises";
import { homedir } from "node:os";
import { isAbsolute, relative, resolve } from "node:path";

const DEFAULT_CHUNK_SIZE = 256 * 1024;
const DEFAULT_INITIAL_LOOKBACK_BYTES = 256 * 1024 * 1024;
const MARKER_OVERLAP_BYTES = 96;
const LIFECYCLE_MARKERS = ["task_started", "task_complete", "turn_aborted"];

export class Activity {
  #sessionsRoot;
  #chunkSize;
  #initialLookbackBytes;
  #cache = new Map();

  constructor({
    sessionsRoot = resolve(homedir(), ".codex/sessions"),
    chunkSize = DEFAULT_CHUNK_SIZE,
    initialLookbackBytes = DEFAULT_INITIAL_LOOKBACK_BYTES,
  } = {}) {
    this.#sessionsRoot = resolve(sessionsRoot);
    this.#chunkSize = chunkSize;
    this.#initialLookbackBytes = initialLookbackBytes;
  }

  async statesFor(threads) {
    if (!Array.isArray(threads)) return new Map();
    const seenPaths = new Set();
    const entries = await Promise.all(threads.map(async (thread) => {
      const path = this.#safeRolloutPath(thread?.path);
      if (!path || typeof thread?.id !== "string") return null;
      seenPaths.add(path);
      const marker = await this.#markerFor(path);
      return marker === "task_started" ? [thread.id, "thinking"] : null;
    }));
    for (const path of this.#cache.keys()) {
      if (!seenPaths.has(path)) this.#cache.delete(path);
    }
    return new Map(entries.filter(Boolean));
  }

  #safeRolloutPath(value) {
    if (typeof value !== "string" || !isAbsolute(value) || !value.endsWith(".jsonl")) return null;
    const path = resolve(value);
    const childPath = relative(this.#sessionsRoot, path);
    if (!childPath || childPath.startsWith("..") || isAbsolute(childPath)) return null;
    return path;
  }

  async #markerFor(path) {
    try {
      const fileStat = await stat(path);
      const cached = this.#cache.get(path);
      let marker;
      if (cached && fileStat.size >= cached.size) {
        marker = cached.marker;
        if (fileStat.size > cached.size) {
          marker = await this.#lastMarkerForward(
            path,
            Math.max(0, cached.size - MARKER_OVERLAP_BYTES),
            fileStat.size,
          ) ?? marker;
        }
      } else {
        marker = await this.#lastMarkerBackward(path, fileStat.size);
      }
      this.#cache.set(path, { size: fileStat.size, marker });
      return marker;
    } catch {
      this.#cache.delete(path);
      return null;
    }
  }

  async #lastMarkerForward(path, start, end) {
    const file = await open(path, "r");
    try {
      let cursor = start;
      let carry = "";
      let marker = null;
      while (cursor < end) {
        const length = Math.min(this.#chunkSize, end - cursor);
        const buffer = Buffer.allocUnsafe(length);
        const { bytesRead } = await file.read(buffer, 0, length, cursor);
        if (bytesRead === 0) break;
        const text = carry + buffer.subarray(0, bytesRead).toString("utf8");
        marker = lastLifecycleMarker(text)?.type ?? marker;
        carry = text.slice(-MARKER_OVERLAP_BYTES);
        cursor += bytesRead;
      }
      return marker;
    } finally {
      await file.close();
    }
  }

  async #lastMarkerBackward(path, size) {
    const file = await open(path, "r");
    try {
      const lowerBound = Math.max(0, size - this.#initialLookbackBytes);
      let end = size;
      while (end > lowerBound) {
        const start = Math.max(lowerBound, end - this.#chunkSize);
        const length = end - start;
        const buffer = Buffer.allocUnsafe(length);
        const { bytesRead } = await file.read(buffer, 0, length, start);
        const marker = lastLifecycleMarker(buffer.subarray(0, bytesRead).toString("utf8"));
        if (marker) return marker.type;
        if (start === lowerBound) break;
        end = start + MARKER_OVERLAP_BYTES;
      }
      return null;
    } finally {
      await file.close();
    }
  }
}

function lastLifecycleMarker(text) {
  let latest = null;
  for (const type of LIFECYCLE_MARKERS) {
    const index = text.lastIndexOf(`"type":"${type}"`);
    if (index >= 0 && (!latest || index > latest.index)) latest = { type, index };
  }
  return latest;
}
