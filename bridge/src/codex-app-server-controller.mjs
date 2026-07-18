import { createHash } from "node:crypto";
import { isAbsolute, relative } from "node:path";

const OWNER_THREAD_SOURCE = "vibePocket";
const AGENT_THREAD_SOURCES = [
  "subAgent",
  "subAgentReview",
  "subAgentCompact",
  "subAgentThreadSpawn",
  "subAgentOther",
];
const MAX_AGENTS = 6;
const MAX_FINISHED_TURNS = 256;
const APPROVAL_METHODS = new Set([
  "item/commandExecution/requestApproval",
  "item/fileChange/requestApproval",
  "item/permissions/requestApproval",
]);
const USER_INPUT_METHOD = "item/tool/requestUserInput";
const EXECUTION_ITEM_TYPES = new Set([
  "commandExecution",
  "fileChange",
  "mcpToolCall",
  "dynamicToolCall",
  "collabAgentToolCall",
]);
const MODES = [
  { label: "Read only", permissions: ":read-only", approvalPolicy: "on-request" },
  { label: "Workspace", permissions: ":workspace", approvalPolicy: "on-request" },
  { label: "Full access", permissions: ":danger-full-access", approvalPolicy: "never" },
];
const FALLBACK_COLLABORATION_MODES = [
  { name: "Default", mode: "default", model: null, reasoning_effort: null },
  { name: "Plan", mode: "plan", model: null, reasoning_effort: "medium" },
];
const FALLBACK_EFFORTS = ["low", "medium", "high", "xhigh", "max"];

export class CodexAppServerController {
  #appServer;
  #workspaces;
  #ownershipStore;
  #started = false;
  #threads = [];
  #rootThreads = new Map();
  #ownedRootIds = new Set();
  #focusThreadId = null;
  #ownedSessionIds = new Set();
  #loadedThreads = new Set();
  #activeTurns = new Map();
  #finishedTurns = new Set();
  #threadStatuses = new Map();
  #threadOutcomes = new Map();
  #executionItems = new Map();
  #approvals = new Map();
  #userInputs = new Map();
  #agentThreads = new Map();
  #unreadThreads = new Set();
  #threadSettings = new Map();
  #allowedPermissions = new Set(MODES.map(({ permissions }) => permissions));
  #collaborationModes = structuredClone(FALLBACK_COLLABORATION_MODES);
  #models = new Map();
  #defaultModel = null;
  #defaultModeIndex = 1;
  #defaultCollaborationModeIndex = 0;
  #defaultEffort = "medium";
  #voiceActive = false;
  #draft = null;

  constructor({ appServer, workspaces, ownershipStore = null }) {
    this.#appServer = appServer;
    this.#workspaces = workspaces;
    this.#ownershipStore = ownershipStore;
    this.#appServer.on("notification", (message) => this.#onNotification(message));
    this.#appServer.on("serverRequest", (message) => this.#onServerRequest(message));
  }

  async status() {
    await this.#ensureStarted();
    await this.#refreshThreads();
    if (this.#focusThreadId) await this.#ensureLoaded(this.#focusThreadId);
    const agents = this.#agents();
    const approval = this.#focusedApproval();
    const userInput = this.#focusedUserInput();
    const settings = this.#settingsFor(this.#focusThreadId);
    const hasDraft = Boolean(this.#draft?.text);
    const hasUserInputDraft = Boolean(userInput?.customAnswers.has(this.#activeUserInputQuestion(userInput)?.id));
    return {
      available: true,
      message: this.#focusThreadId
        ? "Ready to control the focused Vibe Pocket Codex task."
        : "Create a Codex task from Vibe Pocket.",
      taskState: this.#focusThreadId ? this.#threadState(this.#focusThreadId) : "idle",
      controls: {
        voice: true,
        stop: this.#activeTurns.has(this.#focusThreadId),
        "new-task": true,
        approve: Boolean(approval) || Boolean(userInput) || hasDraft,
        reject: Boolean(approval) || Boolean(userInput) || hasDraft,
        "clear-input": hasDraft || hasUserInputDraft,
        "focus-agent": agents.length > 0,
        "mode-cycle": true,
        "access-cycle": true,
        navigate: Boolean(userInput) || agents.length > 0,
        reasoning: true,
        workflow: true,
      },
      agents,
      voice: { available: true, active: this.#voiceActive },
      mode: {
        available: this.#collaborationModes.length > 0,
        label: this.#collaborationModes[settings.collaborationModeIndex]?.name ?? "Default",
      },
      access: { available: true, label: MODES[settings.modeIndex].label },
      reasoning: { available: true, label: settings.effort },
      userInput: this.#publicUserInput(userInput),
    };
  }

  async attach() {
    await this.#ensureStarted();
    await this.#refreshThreads();
    const threadId = await this.#ensureFocusedThread();
    await this.#ensureLoaded(threadId);
    return { message: "Focused the selected Vibe Pocket Codex task." };
  }

  async press(control) {
    await this.#ensureStarted();
    switch (control) {
      case "new-task":
        await this.#createThread();
        return { message: "Created a new Vibe Pocket Codex task." };
      case "stop":
        await this.#interruptFocusedTurn();
        return { message: "Interrupted the focused Codex turn." };
      case "approve":
        return this.#acceptFocusedIntent();
      case "reject":
        return this.#rejectFocusedIntent();
      default:
        throw new Error(`Unsupported Codex app-server control: ${control}.`);
    }
  }

  async setVoice(active) {
    await this.#ensureStarted();
    this.#voiceActive = active;
    return { message: active ? "Started phone dictation." : "Stopped phone dictation." };
  }

  async setDictationDraft(text) {
    await this.#ensureStarted();
    if (!validDictation(text)) {
      throw new Error("Phone dictation must contain printable non-empty text up to 12,000 characters.");
    }
    const threadId = await this.#ensureFocusedThread();
    const userInput = this.#focusedUserInput();
    if (userInput) {
      const question = this.#activeUserInputQuestion(userInput);
      userInput.customAnswers.set(question.id, text.trim());
      this.#voiceActive = false;
      return { message: `Stored a spoken answer for ${question.header}. Press Accept to send it.` };
    }
    this.#draft = { text: text.trim(), threadId };
    this.#voiceActive = false;
    return { message: "Stored phone dictation for the focused task. Press Accept to send it." };
  }

  async navigate(direction) {
    await this.#ensureStarted();
    await this.#refreshThreads();
    const userInput = this.#focusedUserInput();
    if (userInput) return this.#navigateUserInput(userInput, direction);
    const agents = this.#agents();
    if (agents.length === 0) throw new Error("There are no Vibe Pocket Codex tasks to navigate.");
    const current = agents.findIndex((agent) => agent.focused);
    const delta = direction === "up" || direction === "left" ? -1 : 1;
    const next = (Math.max(current, 0) + delta + agents.length) % agents.length;
    await this.#selectThread(this.#agentThreads.get(agents[next].id));
    return { message: `Focused Codex task ${agents[next].label}.` };
  }

  async cycleMode() {
    await this.#ensureStarted();
    if (this.#focusThreadId) await this.#ensureLoaded(this.#focusThreadId);
    const current = this.#settingsFor(this.#focusThreadId);
    const collaborationModeIndex = (current.collaborationModeIndex + 1) % this.#collaborationModes.length;
    const collaborationMode = this.#collaborationModePayload(collaborationModeIndex, current);
    this.#defaultCollaborationModeIndex = collaborationModeIndex;
    if (this.#focusThreadId) {
      const result = await this.#appServer.request("thread/settings/update", {
        threadId: this.#focusThreadId,
        collaborationMode,
      });
      this.#captureSettings(this.#focusThreadId, result.threadSettings ?? result, { collaborationModeIndex });
    }
    return { message: `Selected ${this.#collaborationModes[collaborationModeIndex].name} collaboration mode.` };
  }

  async cycleAccess() {
    await this.#ensureStarted();
    if (this.#focusThreadId) await this.#ensureLoaded(this.#focusThreadId);
    const current = this.#settingsFor(this.#focusThreadId);
    const modeIndex = (current.modeIndex + 1) % MODES.length;
    this.#defaultModeIndex = modeIndex;
    if (this.#focusThreadId) {
      const mode = MODES[modeIndex];
      const result = await this.#appServer.request("thread/settings/update", {
        threadId: this.#focusThreadId,
        permissions: mode.permissions,
        approvalPolicy: mode.approvalPolicy,
      });
      this.#captureSettings(this.#focusThreadId, result.threadSettings ?? result, { modeIndex });
    }
    return { message: `Selected ${MODES[modeIndex].label} access for subsequent Codex turns.` };
  }

  async clearInput() {
    await this.#ensureStarted();
    const userInput = this.#focusedUserInput();
    const question = this.#activeUserInputQuestion(userInput);
    if (question && userInput.customAnswers.delete(question.id)) {
      return { message: `Cleared the spoken answer for ${question.header}.` };
    }
    this.#draft = null;
    return { message: "Cleared the pending phone dictation." };
  }

  async focusAgent(agentId) {
    await this.#ensureStarted();
    await this.#refreshThreads();
    this.#agents();
    const threadId = this.#agentThreads.get(agentId);
    if (!threadId) throw new Error("That Codex task is no longer available.");
    await this.#selectThread(threadId);
    const thread = this.#threads.find((candidate) => candidate.id === threadId);
    return { message: `Focused Codex task ${threadLabel(thread)}.` };
  }

  async adjustReasoning(delta) {
    await this.#ensureStarted();
    if (this.#focusThreadId) await this.#ensureLoaded(this.#focusThreadId);
    const current = this.#settingsFor(this.#focusThreadId);
    const efforts = this.#effortsFor(current.model);
    const currentIndex = Math.max(0, efforts.indexOf(current.effort));
    const effort = efforts[Math.max(0, Math.min(efforts.length - 1, currentIndex + delta))];
    this.#defaultEffort = effort;
    if (this.#focusThreadId) {
      const result = await this.#appServer.request("thread/settings/update", {
        threadId: this.#focusThreadId,
        effort,
      });
      this.#captureSettings(this.#focusThreadId, result.threadSettings ?? result, { effort });
    }
    return { message: `Selected ${effort} reasoning for subsequent Codex turns.` };
  }

  async workflow(prompt) {
    await this.#ensureStarted();
    const threadId = await this.#createThread();
    await this.#startTurn(threadId, prompt);
    return { message: "Started the workflow in a new Vibe Pocket Codex task." };
  }

  async dispose() {
    if (!this.#started) return;
    this.#started = false;
    await this.#appServer.stop();
  }

  async #ensureStarted() {
    if (this.#started) return;
    await this.#appServer.start();
    this.#started = true;
    await Promise.all([this.#loadModels(), this.#loadPermissionProfiles(), this.#loadCollaborationModes()]);
    for (const threadId of await this.#ownershipStore?.load?.() ?? []) {
      this.#ownedRootIds.add(threadId);
    }
    await this.#hydrateOwnedRoots();
  }

  async #loadModels() {
    try {
      const result = await this.#appServer.request("model/list", { limit: 50 });
      for (const model of result.data ?? []) {
        const efforts = model.supportedReasoningEfforts
          ?.map((entry) => entry.reasoningEffort)
          .filter((value) => typeof value === "string" && value.length > 0);
        if (model.id && efforts?.length) {
          this.#models.set(model.id, {
            efforts: [...new Set(efforts)],
            defaultEffort: model.defaultReasoningEffort,
          });
        }
      }
      const model = result.data?.find((candidate) => candidate.isDefault) ?? result.data?.[0];
      if (model?.id) this.#defaultModel = model.id;
      if (model?.defaultReasoningEffort) this.#defaultEffort = model.defaultReasoningEffort;
    } catch {
      this.#models.clear();
    }
  }

  async #loadPermissionProfiles() {
    try {
      const result = await this.#appServer.request("permissionProfile/list", { limit: 50 });
      this.#allowedPermissions = new Set(
        (result.data ?? []).filter(({ allowed }) => allowed).map(({ id }) => id),
      );
      for (const mode of MODES) {
        if (!this.#allowedPermissions.has(mode.permissions)) {
          throw new Error(`Codex permission profile ${mode.permissions} is unavailable.`);
        }
      }
    } catch (error) {
      this.#allowedPermissions = new Set(MODES.map(({ permissions }) => permissions));
      if (error.message?.includes("unavailable")) throw error;
    }
  }

  async #loadCollaborationModes() {
    try {
      const result = await this.#appServer.request("collaborationMode/list", {});
      const modes = (result.data ?? []).filter((mode) => (
        mode
        && typeof mode.name === "string"
        && (mode.mode === "default" || mode.mode === "plan")
      )).map((mode) => ({
        name: mode.name.slice(0, 64),
        mode: mode.mode,
        model: typeof mode.model === "string" ? mode.model : null,
        reasoning_effort: typeof mode.reasoning_effort === "string" ? mode.reasoning_effort : null,
      }));
      if (modes.length > 0) this.#collaborationModes = modes;
      const defaultIndex = this.#collaborationModes.findIndex(({ mode }) => mode === "default");
      this.#defaultCollaborationModeIndex = Math.max(0, defaultIndex);
    } catch {
      this.#collaborationModes = structuredClone(FALLBACK_COLLABORATION_MODES);
    }
  }

  async #refreshThreads() {
    await this.#hydrateOwnedRoots();
    const roots = [...this.#rootThreads.values()]
      .filter((thread) => this.#workspaceAllows(thread.cwd))
      .sort((left, right) => (right.recencyAt ?? right.updatedAt ?? 0) - (left.recencyAt ?? left.updatedAt ?? 0))
      .slice(0, MAX_AGENTS);
    this.#ownedSessionIds = new Set(roots.map((thread) => thread.sessionId).filter(Boolean));

    let descendants = [];
    const agentResults = await Promise.all(roots.map((thread) => this.#appServer.request("thread/list", {
      ancestorThreadId: thread.id,
      limit: 48,
      sortDirection: "desc",
      sortKey: "recency_at",
      sourceKinds: AGENT_THREAD_SOURCES,
      useStateDbOnly: true,
    })));
    descendants = agentResults.flatMap((result) => result.data ?? [])
      .filter((thread) => this.#ownedSessionIds.has(thread.sessionId) && this.#workspaceAllows(thread.cwd));

    const merged = [...roots, ...descendants]
      .sort((left, right) => (right.recencyAt ?? right.updatedAt ?? 0) - (left.recencyAt ?? left.updatedAt ?? 0));
    this.#threads = uniqueThreads(merged).slice(0, MAX_AGENTS);
    for (const thread of this.#threads) {
      if (!this.#threadStatuses.has(thread.id)) this.#threadStatuses.set(thread.id, thread.status);
    }
    if (!this.#threads.some((thread) => thread.id === this.#focusThreadId)) {
      this.#focusThreadId = this.#threads[0]?.id ?? null;
    }
  }

  async #hydrateOwnedRoots() {
    const missing = [...this.#ownedRootIds].filter((threadId) => !this.#rootThreads.has(threadId));
    if (missing.length === 0) return;
    const results = await Promise.all(missing.map(async (threadId) => {
      try {
        return await this.#appServer.request("thread/read", { threadId, includeTurns: false });
      } catch {
        return null;
      }
    }));
    const invalid = [];
    results.forEach((result, index) => {
      const threadId = missing[index];
      if (result?.thread && this.#workspaceAllows(result.thread.cwd)) {
        this.#rootThreads.set(threadId, result.thread);
        this.#registerOwnedThread(result.thread);
      } else {
        invalid.push(threadId);
        this.#ownedRootIds.delete(threadId);
      }
    });
    if (invalid.length > 0) await this.#ownershipStore?.replace?.([...this.#ownedRootIds]);
  }

  async #ensureFocusedThread() {
    if (this.#focusThreadId) return this.#focusThreadId;
    return this.#createThread();
  }

  async #createThread() {
    const mode = MODES[this.#defaultModeIndex];
    const cwd = Object.values(this.#workspaces)[0];
    const collaborationMode = this.#collaborationModePayload(
      this.#defaultCollaborationModeIndex,
      this.#settingsFor(null),
    );
    const result = await this.#appServer.request("thread/start", {
      cwd,
      approvalPolicy: mode.approvalPolicy,
      permissions: mode.permissions,
      collaborationMode,
      threadSource: OWNER_THREAD_SOURCE,
    });
    this.#ownedRootIds.add(result.thread.id);
    this.#rootThreads.set(result.thread.id, result.thread);
    await this.#ownershipStore?.add?.(result.thread.id);
    this.#registerOwnedThread(result.thread);
    this.#loadedThreads.add(result.thread.id);
    this.#captureSettings(result.thread.id, result, {
      modeIndex: this.#defaultModeIndex,
      collaborationModeIndex: this.#defaultCollaborationModeIndex,
      effort: result.reasoningEffort ?? this.#defaultEffort,
      model: result.model ?? this.#defaultModel,
    });
    await this.#selectThread(result.thread.id, { resume: false });
    return result.thread.id;
  }

  async #ensureLoaded(threadId) {
    if (this.#loadedThreads.has(threadId)) return;
    const result = await this.#appServer.request("thread/resume", { threadId });
    this.#loadedThreads.add(threadId);
    if (result.thread) this.#registerOwnedThread(result.thread);
    this.#captureSettings(threadId, result, {
      effort: result.reasoningEffort,
      model: result.model,
    });
  }

  async #selectThread(threadId, { resume = true } = {}) {
    if (!threadId) throw new Error("No Vibe Pocket Codex task is available.");
    if (resume) await this.#ensureLoaded(threadId);
    this.#focusThreadId = threadId;
    this.#unreadThreads.delete(threadId);
  }

  async #startTurn(threadId, text) {
    await this.#ensureLoaded(threadId);
    const settings = this.#settingsFor(threadId);
    const result = await this.#appServer.request("turn/start", {
      threadId,
      input: [{ type: "text", text }],
      effort: settings.effort,
    });
    if (!this.#finishedTurns.has(result.turn.id)) {
      this.#activeTurns.set(threadId, result.turn.id);
      this.#threadOutcomes.delete(threadId);
      this.#unreadThreads.delete(threadId);
    }
    this.#focusThreadId = threadId;
  }

  async #submitDraft() {
    if (!this.#draft?.text) throw new Error("There is no pending phone dictation to submit.");
    const { text, threadId } = this.#draft;
    await this.#selectThread(threadId);
    const activeTurnId = this.#activeTurns.get(threadId);
    if (activeTurnId) {
      await this.#appServer.request("turn/steer", {
        threadId,
        expectedTurnId: activeTurnId,
        input: [{ type: "text", text }],
      });
    } else {
      await this.#startTurn(threadId, text);
    }
    this.#draft = null;
  }

  async #interruptFocusedTurn() {
    const threadId = this.#focusThreadId;
    const turnId = this.#activeTurns.get(threadId);
    if (!threadId || !turnId) throw new Error("The focused Codex task has no interruptible turn.");
    try {
      await this.#appServer.request("turn/interrupt", { threadId, turnId });
    } catch (error) {
      if (!/no active turn to interrupt/i.test(error.message ?? "")) throw error;
      this.#activeTurns.delete(threadId);
      this.#markTurnFinished(turnId);
    }
  }

  async #acceptFocusedIntent() {
    const approval = this.#focusedApproval();
    if (approval) {
      this.#respondToApproval(approval, true);
      return { message: "Approved the focused Codex request." };
    }
    const userInput = this.#focusedUserInput();
    if (userInput) {
      this.#respondToUserInput(userInput, true);
      return { message: "Answered the focused Codex question." };
    }
    await this.#submitDraft();
    return { message: "Submitted the phone dictation to Codex." };
  }

  async #rejectFocusedIntent() {
    const approval = this.#focusedApproval();
    if (approval) {
      this.#respondToApproval(approval, false);
      return { message: "Rejected the focused Codex request." };
    }
    const userInput = this.#focusedUserInput();
    if (userInput) {
      this.#respondToUserInput(userInput, false);
      return { message: "Dismissed the focused Codex question." };
    }
    if (this.#draft) {
      this.#draft = null;
      return { message: "Discarded the pending phone dictation." };
    }
    throw new Error("There is no pending Codex request or dictation to reject.");
  }

  #respondToApproval(approval, accepted) {
    if (approval.method === "item/permissions/requestApproval") {
      this.#appServer.respond(approval.requestId, {
        permissions: accepted ? approval.params.permissions ?? {} : {},
        scope: "turn",
      });
    } else {
      const decision = accepted
        ? chooseDecision(approval.params.availableDecisions, ["accept", "acceptForSession"])
        : chooseDecision(approval.params.availableDecisions, ["decline", "cancel"]);
      if (!decision) throw new Error(`Codex did not offer a supported ${accepted ? "accept" : "reject"} decision.`);
      this.#appServer.respond(approval.requestId, { decision });
    }
    this.#approvals.delete(approval.requestId);
  }

  #navigateUserInput(userInput, direction) {
    if (direction === "left" || direction === "right") {
      const delta = direction === "left" ? -1 : 1;
      userInput.activeQuestionIndex = wrapIndex(
        userInput.activeQuestionIndex + delta,
        userInput.questions.length,
      );
      const question = this.#activeUserInputQuestion(userInput);
      return { message: `Selected question ${userInput.activeQuestionIndex + 1}: ${question.header}.` };
    }

    const question = this.#activeUserInputQuestion(userInput);
    if (question.options.length === 0) {
      throw new Error("This Codex question needs a spoken answer. Hold Voice, then press Accept.");
    }
    const delta = direction === "up" ? -1 : 1;
    const selected = userInput.selectedOptionIndexes.get(question.id) ?? 0;
    const next = wrapIndex(selected + delta, question.options.length);
    userInput.selectedOptionIndexes.set(question.id, next);
    userInput.customAnswers.delete(question.id);
    return { message: `Selected ${question.options[next].label} for ${question.header}.` };
  }

  #respondToUserInput(userInput, accepted) {
    const answers = Object.fromEntries(userInput.questions.map((question) => {
      if (!accepted) return [question.id, { answers: [] }];
      const customAnswer = userInput.customAnswers.get(question.id);
      if (customAnswer) return [question.id, { answers: [customAnswer] }];
      const selected = userInput.selectedOptionIndexes.get(question.id) ?? 0;
      const label = question.options[selected]?.label;
      return [question.id, { answers: label ? [label] : [] }];
    }));
    this.#appServer.respond(userInput.requestId, { answers });
    this.#userInputs.delete(userInput.requestId);
  }

  #registerOwnedThread(thread) {
    if (!thread || !this.#workspaceAllows(thread.cwd)) return;
    if (thread.sessionId) this.#ownedSessionIds.add(thread.sessionId);
    if (!thread.parentThreadId && this.#ownedRootIds.has(thread.id)) this.#rootThreads.set(thread.id, thread);
    this.#threads = uniqueThreads([thread, ...this.#threads]).slice(0, MAX_AGENTS);
    if (thread.status) this.#threadStatuses.set(thread.id, thread.status);
  }

  #captureSettings(threadId, value, fallback = {}) {
    if (!threadId) return;
    const existing = this.#threadSettings.get(threadId) ?? {};
    const permissionId = value.activePermissionProfile?.id;
    const modeIndex = MODES.findIndex(({ permissions }) => permissions === permissionId);
    const collaborationKind = value.collaborationMode?.mode ?? fallback.collaborationMode;
    const collaborationModeIndex = this.#collaborationModes.findIndex(({ mode }) => mode === collaborationKind);
    const model = value.model ?? fallback.model ?? existing.model ?? this.#defaultModel;
    const effort = value.effort ?? value.reasoningEffort ?? fallback.effort ?? existing.effort
      ?? this.#models.get(model)?.defaultEffort ?? this.#defaultEffort;
    this.#threadSettings.set(threadId, {
      model,
      effort,
      modeIndex: modeIndex >= 0 ? modeIndex : fallback.modeIndex ?? existing.modeIndex ?? this.#defaultModeIndex,
      collaborationModeIndex: collaborationModeIndex >= 0
        ? collaborationModeIndex
        : fallback.collaborationModeIndex ?? existing.collaborationModeIndex ?? this.#defaultCollaborationModeIndex,
    });
  }

  #settingsFor(threadId) {
    return this.#threadSettings.get(threadId) ?? {
      model: this.#defaultModel,
      effort: this.#defaultEffort,
      modeIndex: this.#defaultModeIndex,
      collaborationModeIndex: this.#defaultCollaborationModeIndex,
    };
  }

  #collaborationModePayload(index, settings) {
    const mode = this.#collaborationModes[index];
    const model = mode?.model ?? settings.model ?? this.#defaultModel;
    if (!mode || !model) throw new Error("Codex did not advertise a usable collaboration mode.");
    return {
      mode: mode.mode,
      settings: {
        model,
        reasoning_effort: mode.reasoning_effort ?? settings.effort ?? this.#defaultEffort,
        developer_instructions: null,
      },
    };
  }

  #effortsFor(model) {
    return this.#models.get(model)?.efforts ?? FALLBACK_EFFORTS;
  }

  #agents() {
    this.#agentThreads.clear();
    return this.#threads.map((thread) => {
      const id = agentIdForThread(thread.id);
      this.#agentThreads.set(id, thread.id);
      return {
        id,
        label: threadLabel(thread),
        state: this.#threadState(thread.id),
        focused: thread.id === this.#focusThreadId,
      };
    });
  }

  #threadState(threadId) {
    const status = this.#threadStatuses.get(threadId)
      ?? this.#threads.find((thread) => thread.id === threadId)?.status;
    if (this.#hasWaitingState(threadId, status)) return "waiting";
    if (status?.type === "systemError" || this.#threadOutcomes.get(threadId) === "error") return "error";
    if ((this.#executionItems.get(threadId)?.size ?? 0) > 0) return "executing";
    if (this.#activeTurns.has(threadId) || status?.type === "active") return "thinking";
    if (this.#unreadThreads.has(threadId)) return "unread";
    if (this.#threadOutcomes.get(threadId) === "complete") return "complete";
    return "idle";
  }

  #hasWaitingState(threadId, status) {
    if ([...this.#approvals.values()].some((approval) => approval.threadId === threadId)) return true;
    if ([...this.#userInputs.values()].some((input) => input.threadId === threadId)) return true;
    return status?.type === "active"
      && status.activeFlags?.some((flag) => flag === "waitingOnApproval" || flag === "waitingOnUserInput");
  }

  #focusedApproval() {
    return [...this.#approvals.values()].find((approval) => approval.threadId === this.#focusThreadId) ?? null;
  }

  #focusedUserInput() {
    return [...this.#userInputs.values()].find((input) => input.threadId === this.#focusThreadId) ?? null;
  }

  #activeUserInputQuestion(userInput) {
    return userInput?.questions[userInput.activeQuestionIndex] ?? null;
  }

  #publicUserInput(userInput) {
    if (!userInput) return null;
    const question = this.#activeUserInputQuestion(userInput);
    const selectedOptionIndex = userInput.selectedOptionIndexes.get(question.id) ?? 0;
    return {
      questionIndex: userInput.activeQuestionIndex,
      questionCount: userInput.questions.length,
      header: question.header,
      question: question.question,
      options: question.options,
      selectedOptionIndex: question.options.length > 0 ? selectedOptionIndex : -1,
      hasSpokenAnswer: userInput.customAnswers.has(question.id),
      isSecret: question.isSecret,
    };
  }

  #workspaceAllows(cwd) {
    if (typeof cwd !== "string" || cwd.length === 0) return false;
    return Object.values(this.#workspaces).some((workspace) => {
      const path = relative(workspace, cwd);
      return path === "" || (!path.startsWith("..") && !isAbsolute(path));
    });
  }

  #onServerRequest(message) {
    if (message.method === USER_INPUT_METHOD) {
      const userInput = normalizeUserInputRequest(message);
      if (!userInput) {
        this.#appServer.respondWithError(message.id, -32602, "Invalid Codex user-input request.");
        return;
      }
      this.#userInputs.set(message.id, userInput);
      return;
    }
    if (!APPROVAL_METHODS.has(message.method)) {
      this.#appServer.respondWithError(message.id, -32601, "Unsupported Vibe Pocket server request.");
      return;
    }
    this.#approvals.set(message.id, {
      requestId: message.id,
      threadId: message.params?.threadId ?? "unknown",
      turnId: message.params?.turnId ?? "unknown",
      method: message.method,
      params: message.params ?? {},
    });
  }

  #onNotification(message) {
    const threadId = message.params?.threadId ?? message.params?.thread?.id;
    const turnId = message.params?.turnId ?? message.params?.turn?.id;
    if (message.method === "serverRequest/resolved") {
      this.#approvals.delete(message.params?.requestId);
      this.#userInputs.delete(message.params?.requestId);
      return;
    }
    if (message.method === "thread/started" && message.params?.thread) {
      const thread = message.params.thread;
      if (this.#ownedRootIds.has(thread.id) || this.#ownedSessionIds.has(thread.sessionId)) {
        this.#registerOwnedThread(thread);
      }
    }
    if (!threadId) return;
    if (message.method === "thread/status/changed") {
      this.#threadStatuses.set(threadId, message.params.status);
    }
    if (message.method === "thread/settings/updated") {
      this.#captureSettings(threadId, message.params.threadSettings ?? {});
    }
    if (message.method === "turn/started" && turnId) {
      this.#finishedTurns.delete(turnId);
      this.#activeTurns.set(threadId, turnId);
      this.#threadOutcomes.delete(threadId);
      this.#unreadThreads.delete(threadId);
    }
    if (message.method === "item/started") this.#updateExecutionItem(threadId, message.params?.item, true);
    if (message.method === "item/completed") this.#updateExecutionItem(threadId, message.params?.item, false);
    if (message.method === "turn/completed") {
      if (turnId) this.#markTurnFinished(turnId);
      this.#activeTurns.delete(threadId);
      this.#executionItems.delete(threadId);
      const status = message.params?.turn?.status;
      if (status === "failed") {
        this.#threadOutcomes.set(threadId, "error");
      } else if (status === "completed") {
        this.#threadOutcomes.set(threadId, "complete");
        if (threadId !== this.#focusThreadId) this.#unreadThreads.add(threadId);
      } else {
        this.#threadOutcomes.delete(threadId);
      }
    }
  }

  #updateExecutionItem(threadId, item, active) {
    if (!item?.id || !EXECUTION_ITEM_TYPES.has(item.type)) return;
    const items = this.#executionItems.get(threadId) ?? new Set();
    if (active) items.add(item.id);
    else items.delete(item.id);
    if (items.size > 0) this.#executionItems.set(threadId, items);
    else this.#executionItems.delete(threadId);
  }

  #markTurnFinished(turnId) {
    this.#finishedTurns.add(turnId);
    while (this.#finishedTurns.size > MAX_FINISHED_TURNS) {
      this.#finishedTurns.delete(this.#finishedTurns.values().next().value);
    }
  }
}

function chooseDecision(available, preferred) {
  if (!Array.isArray(available) || available.length === 0) return preferred[0];
  const offered = new Set(available.filter((value) => typeof value === "string"));
  return preferred.find((value) => offered.has(value)) ?? null;
}

function normalizeUserInputRequest(message) {
  const params = message.params;
  if (
    (typeof message.id !== "string" && typeof message.id !== "number")
    || !params
    || typeof params.threadId !== "string"
    || typeof params.turnId !== "string"
    || !Array.isArray(params.questions)
    || params.questions.length === 0
    || params.questions.length > 3
  ) {
    return null;
  }

  const questions = params.questions.map((question) => {
    if (!question || typeof question !== "object" || Array.isArray(question)) return null;
    const id = printableText(question.id, 64);
    const header = printableText(question.header, 64);
    const prompt = printableText(question.question, 2_000);
    if (!id || !header || !prompt) return null;
    if (question.options !== null && question.options !== undefined && !Array.isArray(question.options)) return null;
    const options = (question.options ?? []).map((option) => {
      if (!option || typeof option !== "object" || Array.isArray(option)) return null;
      const label = printableText(option.label, 120);
      const description = printableText(option.description, 500, { allowEmpty: true });
      return label && description !== null ? { label, description } : null;
    });
    if (options.some((option) => option === null) || options.length > 8) return null;
    return {
      id,
      header,
      question: prompt,
      options,
      isSecret: question.isSecret === true,
    };
  });
  if (questions.some((question) => question === null) || new Set(questions.map(({ id }) => id)).size !== questions.length) {
    return null;
  }

  return {
    requestId: message.id,
    threadId: params.threadId,
    turnId: params.turnId,
    questions,
    activeQuestionIndex: 0,
    selectedOptionIndexes: new Map(questions.map(({ id }) => [id, 0])),
    customAnswers: new Map(),
  };
}

function printableText(value, maxLength, { allowEmpty = false } = {}) {
  if (typeof value !== "string" || value.length > maxLength || /[\u0000-\u001f\u007f]/.test(value)) return null;
  const trimmed = value.trim();
  return trimmed.length > 0 || allowEmpty ? trimmed : null;
}

function wrapIndex(index, length) {
  return ((index % length) + length) % length;
}

function validDictation(text) {
  return typeof text === "string"
    && text.trim().length > 0
    && text.length <= 12_000
    && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(text);
}

function uniqueThreads(threads) {
  return [...new Map(threads.map((thread) => [thread.id, thread])).values()];
}

function agentIdForThread(threadId) {
  return `agent-${createHash("sha256").update(threadId).digest("hex").slice(0, 24)}`;
}

function threadLabel(thread) {
  const value = String(thread?.agentNickname || thread?.name || thread?.preview || "Untitled Codex task")
    .replaceAll(/\s+/g, " ")
    .trim();
  return value.slice(0, 64) || "Untitled Codex task";
}
