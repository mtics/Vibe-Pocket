const EXECUTION_TYPES = new Set([
  "commandExecution",
  "fileChange",
  "mcpToolCall",
  "dynamicToolCall",
  "collabAgentToolCall",
]);
const FINISHED_LIMIT = 256;

export class Turns {
  #active = new Map();
  #finished = new Set();
  #outcomes = new Map();
  #items = new Map();
  #unread = new Set();

  active(threadId) {
    return this.#active.get(threadId) ?? null;
  }

  acceptStart(threadId, turnId) {
    if (this.#finished.has(turnId)) return false;
    this.#active.set(threadId, turnId);
    this.activity(threadId, true);
    return true;
  }

  started(threadId, turnId) {
    this.#finished.delete(turnId);
    this.#active.set(threadId, turnId);
    this.activity(threadId, true);
  }

  completed(threadId, turnId, status, focused) {
    if (turnId) this.#markFinished(turnId);
    this.#active.delete(threadId);
    this.#items.delete(threadId);
    if (status === "failed") {
      this.#outcomes.set(threadId, "error");
    } else if (status === "completed") {
      this.#outcomes.set(threadId, "complete");
      if (!focused) this.#unread.add(threadId);
    } else {
      this.#outcomes.delete(threadId);
    }
  }

  interrupted(threadId, turnId) {
    this.#active.delete(threadId);
    this.#markFinished(turnId);
  }

  item(threadId, item, active) {
    if (!item?.id || !EXECUTION_TYPES.has(item.type)) return;
    const items = this.#items.get(threadId) ?? new Set();
    if (active) items.add(item.id);
    else items.delete(item.id);
    if (items.size > 0) this.#items.set(threadId, items);
    else this.#items.delete(threadId);
  }

  activity(threadId, focused) {
    this.#outcomes.delete(threadId);
    if (focused) this.#unread.delete(threadId);
  }

  stopped(threadId, focused) {
    this.#items.delete(threadId);
    this.#outcomes.set(threadId, "complete");
    if (!focused) this.#unread.add(threadId);
  }

  read(threadId) {
    this.#unread.delete(threadId);
  }

  state(threadId, { status, waiting, lifecycle }) {
    if (waiting) return "waiting";
    if (status?.type === "systemError" || this.#outcomes.get(threadId) === "error") return "error";
    if ((this.#items.get(threadId)?.size ?? 0) > 0) return "executing";
    if (["waiting", "executing", "thinking"].includes(lifecycle)) return lifecycle;
    if (this.#active.has(threadId) || status?.type === "active") return "thinking";
    if (this.#unread.has(threadId)) return "unread";
    if (lifecycle === "complete" || this.#outcomes.get(threadId) === "complete") return "complete";
    return "idle";
  }

  #markFinished(turnId) {
    this.#finished.add(turnId);
    while (this.#finished.size > FINISHED_LIMIT) {
      this.#finished.delete(this.#finished.values().next().value);
    }
  }
}
