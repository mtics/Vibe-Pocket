import { open } from "../task/open.mjs";
import { Intent } from "./intent.mjs";
import { Lifecycle } from "./lifecycle.mjs";
import { Settings } from "./settings.mjs";
import { Tasks } from "./tasks.mjs";
import { Turns } from "./turns.mjs";

const APPROVAL_TIMEOUT_MS = 120_000;

export class Session {
  #rpc;
  #settings;
  #tasks;
  #turns;
  #intent;
  #lifecycle;
  #started = false;

  constructor({
    appServer,
    workspaces,
    ownershipStore = null,
    openThread = open,
    hookApprovalTimeoutMs = APPROVAL_TIMEOUT_MS,
  }) {
    this.#rpc = appServer;
    this.#settings = new Settings(appServer);
    this.#turns = new Turns();
    this.#tasks = new Tasks({
      rpc: appServer,
      workspaces,
      ownership: ownershipStore,
      open: openThread,
      settings: this.#settings,
      read: (threadId) => this.#turns.read(threadId),
    });
    this.#intent = new Intent(appServer);
    this.#lifecycle = new Lifecycle({
      allows: (cwd) => this.#tasks.allows(cwd),
      authorized: (threadId) => this.#tasks.authorized(threadId),
      focused: () => this.#tasks.focus,
      activity: (threadId, focused) => this.#turns.activity(threadId, focused),
      stopped: (threadId, focused) => this.#turns.stopped(threadId, focused),
      timeoutMs: hookApprovalTimeoutMs,
    });
    appServer.on("notification", (message) => this.#onNotification(message));
    appServer.on("serverRequest", (message) => this.#intent.receive(message));
  }

  async status() {
    await this.#ensureStarted();
    await this.#tasks.refresh();
    const focus = this.#tasks.focus;
    if (focus) await this.#tasks.ensureLoaded(focus);
    const agents = this.#tasks.agents((threadId) => this.#threadState(threadId));
    const pending = this.#intent.status(focus);
    const hook = this.#lifecycle.approval(focus);
    const settings = this.#settings.projection(focus);
    return {
      available: true,
      message: focus
        ? "Ready to control the focused Vibe Pocket Codex task."
        : "Create a Codex task from Vibe Pocket.",
      taskState: focus ? this.#threadState(focus) : "idle",
      controls: {
        voice: true,
        stop: Boolean(this.#turns.active(focus)),
        "new-task": true,
        approve: Boolean(hook) || Boolean(pending.approval) || Boolean(pending.input) || pending.hasDraft,
        reject: Boolean(hook) || Boolean(pending.approval) || Boolean(pending.input) || pending.hasDraft,
        "clear-input": pending.hasDraft || pending.hasAnswer,
        "focus-agent": agents.length > 0,
        "mode-cycle": true,
        "access-cycle": true,
        navigate: Boolean(pending.input) || agents.length > 0,
        reasoning: true,
        workflow: true,
      },
      agents,
      voice: this.#intent.voice,
      mode: settings.mode,
      access: settings.access,
      reasoning: settings.reasoning,
      userInput: pending.userInput,
    };
  }

  async attach() {
    await this.#ensureStarted();
    return { message: await this.#tasks.attach() };
  }

  async bindThread(threadId) {
    await this.#ensureStarted();
    return { message: await this.#tasks.bind(threadId) };
  }

  async applyLifecycleHook(event, payload) {
    await this.#ensureStarted();
    return this.#lifecycle.apply(event, payload);
  }

  async press(control) {
    await this.#ensureStarted();
    switch (control) {
      case "new-task":
        await this.#tasks.show(await this.#tasks.create());
        return { message: "Created a new Vibe Pocket Codex task." };
      case "stop":
        await this.#interrupt();
        return { message: "Interrupted the focused Codex turn." };
      case "approve":
        return this.#accept();
      case "reject":
        return this.#reject();
      default:
        throw new Error(`Unsupported Codex app-server control: ${control}.`);
    }
  }

  async setVoice(active) {
    await this.#ensureStarted();
    return { message: this.#intent.setVoice(active) };
  }

  async setDictationDraft(text) {
    await this.#ensureStarted();
    const threadId = await this.#tasks.ensureFocus();
    return { message: this.#intent.dictate(text, threadId) };
  }

  async navigate(direction) {
    await this.#ensureStarted();
    await this.#tasks.refresh();
    const message = this.#intent.navigate(this.#tasks.focus, direction);
    if (message) return { message };
    return {
      message: await this.#tasks.navigate(direction, (threadId) => this.#threadState(threadId)),
    };
  }

  async cycleMode() {
    await this.#ensureStarted();
    const focus = this.#tasks.focus;
    if (focus) await this.#tasks.ensureLoaded(focus);
    return { message: await this.#settings.cycleMode(focus) };
  }

  async cycleAccess() {
    await this.#ensureStarted();
    const focus = this.#tasks.focus;
    if (focus) await this.#tasks.ensureLoaded(focus);
    return { message: await this.#settings.cycleAccess(focus) };
  }

  async clearInput() {
    await this.#ensureStarted();
    return { message: this.#intent.clear(this.#tasks.focus) };
  }

  async focusAgent(agentId) {
    await this.#ensureStarted();
    return {
      message: await this.#tasks.focusAgent(agentId, (threadId) => this.#threadState(threadId)),
    };
  }

  async adjustReasoning(delta) {
    await this.#ensureStarted();
    const focus = this.#tasks.focus;
    if (focus) await this.#tasks.ensureLoaded(focus);
    return { message: await this.#settings.adjust(focus, delta) };
  }

  async workflow(prompt) {
    await this.#ensureStarted();
    const threadId = await this.#tasks.create();
    await this.#startTurn(threadId, prompt);
    await this.#tasks.show(threadId);
    return { message: "Started the workflow in a new Vibe Pocket Codex task." };
  }

  async dispose() {
    if (!this.#started) return;
    this.#lifecycle.release();
    this.#started = false;
    await this.#rpc.stop();
  }

  async #ensureStarted() {
    if (this.#started) return;
    await this.#rpc.start();
    this.#started = true;
    await Promise.all([this.#settings.start(), this.#tasks.start()]);
  }

  async #startTurn(threadId, text) {
    await this.#tasks.ensureLoaded(threadId);
    const result = await this.#rpc.request("turn/start", {
      threadId,
      input: [{ type: "text", text }],
      effort: this.#settings.for(threadId).effort,
    });
    this.#turns.acceptStart(threadId, result.turn.id);
    await this.#tasks.select(threadId, { resume: false, read: false });
  }

  async #submitDraft() {
    const draft = this.#intent.draft;
    if (!draft?.text) throw new Error("There is no pending phone dictation to submit.");
    await this.#tasks.select(draft.threadId);
    const turnId = this.#turns.active(draft.threadId);
    if (turnId) {
      await this.#rpc.request("turn/steer", {
        threadId: draft.threadId,
        expectedTurnId: turnId,
        input: [{ type: "text", text: draft.text }],
      });
    } else {
      await this.#startTurn(draft.threadId, draft.text);
    }
    await this.#tasks.show(draft.threadId);
    this.#intent.submitted();
  }

  async #interrupt() {
    const threadId = this.#tasks.focus;
    const turnId = this.#turns.active(threadId);
    if (!threadId || !turnId) throw new Error("The focused Codex task has no interruptible turn.");
    try {
      await this.#rpc.request("turn/interrupt", { threadId, turnId });
    } catch (error) {
      if (!/no active turn to interrupt/i.test(error.message ?? "")) throw error;
      this.#turns.interrupted(threadId, turnId);
    }
  }

  async #accept() {
    const hook = this.#lifecycle.approval(this.#tasks.focus);
    if (hook) {
      this.#lifecycle.resolve(hook, true);
      return { message: `Approved ${hook.toolName} through the Codex permission hook.` };
    }
    const message = this.#intent.accept(this.#tasks.focus);
    if (message) return { message };
    await this.#submitDraft();
    return { message: "Submitted the phone dictation to Codex." };
  }

  async #reject() {
    const hook = this.#lifecycle.approval(this.#tasks.focus);
    if (hook) {
      this.#lifecycle.resolve(hook, false);
      return { message: `Rejected ${hook.toolName} through the Codex permission hook.` };
    }
    const message = this.#intent.reject(this.#tasks.focus);
    if (message) return { message };
    throw new Error("There is no pending Codex request or dictation to reject.");
  }

  #threadState(threadId) {
    const status = this.#tasks.status(threadId);
    const waiting = this.#lifecycle.waiting(threadId)
      || this.#intent.waiting(threadId)
      || (status?.type === "active" && status.activeFlags?.some((flag) => (
        flag === "waitingOnApproval" || flag === "waitingOnUserInput"
      )));
    return this.#turns.state(threadId, {
      status,
      waiting,
      lifecycle: this.#lifecycle.state(threadId),
    });
  }

  #onNotification(message) {
    const threadId = message.params?.threadId ?? message.params?.thread?.id;
    const turnId = message.params?.turnId ?? message.params?.turn?.id;
    if (message.method === "serverRequest/resolved") {
      this.#intent.resolved(message.params?.requestId);
      return;
    }
    if (message.method === "thread/started" && message.params?.thread) {
      this.#tasks.receiveStarted(message.params.thread);
    }
    if (!threadId) return;
    if (message.method === "thread/status/changed") {
      this.#tasks.updateStatus(threadId, message.params.status);
    }
    if (message.method === "thread/settings/updated") {
      this.#settings.capture(threadId, message.params.threadSettings ?? {});
    }
    if (message.method === "turn/started" && turnId) {
      this.#turns.started(threadId, turnId);
      this.#lifecycle.started(threadId, turnId);
    }
    if (message.method === "item/started") this.#turns.item(threadId, message.params?.item, true);
    if (message.method === "item/completed") this.#turns.item(threadId, message.params?.item, false);
    if (message.method === "turn/completed") {
      this.#turns.completed(
        threadId,
        turnId,
        message.params?.turn?.status,
        threadId === this.#tasks.focus,
      );
      this.#lifecycle.completed(threadId);
    }
  }
}
