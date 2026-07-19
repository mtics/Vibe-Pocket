import { printable } from "./text.mjs";

const EVENTS = new Set(["UserPromptSubmit", "PreToolUse", "PermissionRequest", "PostToolUse", "Stop"]);
const THREAD_ID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;

export class Lifecycle {
  #allows;
  #authorized;
  #focused;
  #activity;
  #stopped;
  #timeoutMs;
  #states = new Map();
  #approvals = new Map();
  #nextApproval = 1;

  constructor({ allows, authorized, focused, activity, stopped, timeoutMs }) {
    this.#allows = allows;
    this.#authorized = authorized;
    this.#focused = focused;
    this.#activity = activity;
    this.#stopped = stopped;
    this.#timeoutMs = timeoutMs;
  }

  apply(event, payload) {
    const threadId = payload.session_id;
    const turnId = printable(payload.turn_id, 200);
    if (
      !EVENTS.has(event)
      || payload.hook_event_name !== event
      || typeof threadId !== "string"
      || !THREAD_ID.test(threadId)
      || !turnId
      || !this.#allows(payload.cwd)
      || !this.#authorized(threadId)
    ) {
      return { accepted: false, response: Promise.resolve({}) };
    }

    const state = this.#for(threadId, turnId);
    if (event !== "Stop") this.#activity(threadId, threadId === this.#focused());

    if (event === "UserPromptSubmit") {
      this.release(threadId);
      state.activeTools.clear();
      state.value = "thinking";
    } else if (event === "PreToolUse") {
      const toolUseId = printable(payload.tool_use_id, 240);
      if (toolUseId) state.activeTools.add(toolUseId);
      state.value = "executing";
    } else if (event === "PostToolUse") {
      const toolUseId = printable(payload.tool_use_id, 240);
      if (toolUseId) state.activeTools.delete(toolUseId);
      state.value = state.activeTools.size > 0 ? "executing" : "thinking";
    } else if (event === "PermissionRequest") {
      state.value = "waiting";
      return {
        accepted: true,
        response: this.#wait({
          threadId,
          turnId,
          toolName: printable(payload.tool_name, 240) ?? "Codex tool",
        }),
      };
    } else if (event === "Stop") {
      this.release(threadId);
      state.activeTools.clear();
      state.value = "complete";
      this.#stopped(threadId, threadId === this.#focused());
    }

    return { accepted: true, response: Promise.resolve({}) };
  }

  state(threadId) {
    return this.#states.get(threadId)?.value ?? null;
  }

  waiting(threadId) {
    return [...this.#approvals.values()].some((approval) => approval.threadId === threadId);
  }

  approval(threadId) {
    return [...this.#approvals.values()].find((approval) => approval.threadId === threadId) ?? null;
  }

  resolve(approval, accepted) {
    if (!this.#approvals.delete(approval.id)) return;
    clearTimeout(approval.timeout);
    const state = this.#states.get(approval.threadId);
    if (state?.turnId === approval.turnId) {
      if (!accepted) state.activeTools.clear();
      state.value = accepted && state.activeTools.size > 0 ? "executing" : "thinking";
    }
    approval.done({
      hookSpecificOutput: {
        hookEventName: "PermissionRequest",
        decision: accepted
          ? { behavior: "allow" }
          : { behavior: "deny", message: "Rejected from Vibe Pocket." },
      },
    });
  }

  started(threadId, turnId) {
    this.#for(threadId, turnId).value = "thinking";
  }

  completed(threadId) {
    this.#states.delete(threadId);
    this.release(threadId);
  }

  release(threadId = null) {
    for (const approval of [...this.#approvals.values()]) {
      if (threadId && approval.threadId !== threadId) continue;
      this.#approvals.delete(approval.id);
      clearTimeout(approval.timeout);
      approval.done({});
    }
  }

  #for(threadId, turnId) {
    const current = this.#states.get(threadId);
    if (current?.turnId === turnId) return current;
    const state = { turnId, value: "thinking", activeTools: new Set() };
    this.#states.set(threadId, state);
    return state;
  }

  #wait({ threadId, turnId, toolName }) {
    const id = `hook-${this.#nextApproval++}`;
    return new Promise((done) => {
      const approval = { id, threadId, turnId, toolName, done, timeout: null };
      approval.timeout = setTimeout(() => {
        if (!this.#approvals.delete(id)) return;
        const state = this.#states.get(threadId);
        if (state?.turnId === turnId) {
          state.value = state.activeTools.size > 0 ? "executing" : "thinking";
        }
        done({});
      }, this.#timeoutMs);
      approval.timeout.unref?.();
      this.#approvals.set(id, approval);
    });
  }
}
