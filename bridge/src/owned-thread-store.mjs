import { chmod, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, isAbsolute } from "node:path";

const THREAD_ID_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9-]{7,127}$/;
const MAX_OWNED_THREADS = 24;

export class OwnedThreadStore {
  #path;

  constructor({ path }) {
    if (!isAbsolute(path)) throw new Error("Owned thread registry path must be absolute.");
    this.#path = path;
  }

  async load() {
    try {
      const value = JSON.parse(await readFile(this.#path, "utf8"));
      return normalizeIds(value.threadIds);
    } catch {
      return [];
    }
  }

  async add(threadId) {
    const current = await this.load();
    const threadIds = normalizeIds([threadId, ...current]);
    await this.#save(threadIds);
    return threadIds;
  }

  async replace(threadIds) {
    const normalized = normalizeIds(threadIds);
    await this.#save(normalized);
    return normalized;
  }

  async #save(threadIds) {
    await mkdir(dirname(this.#path), { recursive: true, mode: 0o700 });
    const temporaryPath = `${this.#path}.${process.pid}.tmp`;
    await writeFile(temporaryPath, `${JSON.stringify({ version: 1, threadIds }, null, 2)}\n`, {
      mode: 0o600,
    });
    await rename(temporaryPath, this.#path);
    await chmod(this.#path, 0o600);
  }
}

function normalizeIds(value) {
  if (!Array.isArray(value)) return [];
  return [...new Set(value.filter((id) => typeof id === "string" && THREAD_ID_PATTERN.test(id)))]
    .slice(0, MAX_OWNED_THREADS);
}
