const MAX_AGENTS = 24;
const TASK_STATES = new Set([
  "idle",
  "unread",
  "thinking",
  "executing",
  "waiting",
  "complete",
  "error",
]);

export class State {
  #events;
  #workspaces;
  #revision = 0;
  #status = { state: "starting", message: null, controls: emptyControls() };
  #desktop = emptyDesktop();
  #task;

  constructor({ events, workspaces, taskId }) {
    this.#events = events;
    this.#workspaces = workspaces;
    this.#task = {
      id: taskId,
      workspaceId: "Vibe Pocket Codex",
      state: "starting",
      terminalTail: "Checking the visible Codex desktop controls...",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      canInterrupt: false,
    };
  }

  get revision() {
    return `r_${this.#revision}`;
  }

  get controls() {
    return this.#status.controls;
  }

  get agents() {
    return this.#desktop.agents;
  }

  get focusedAgentIndex() {
    return this.#desktop.focusedAgentIndex;
  }

  get voice() {
    return this.#desktop.voice;
  }

  snapshot({ profile, gestures, actions, activeLayerId, taskId }) {
    return {
      revision: this.revision,
      status: this.#status,
      focusSessionId: this.#task.state === "error" ? null : taskId,
      workspaces: Object.keys(this.#workspaces),
      controls: this.#status.controls,
      controller: {
        profile,
        gestures,
        actionCatalog: actions,
        activeLayerId,
        ...this.#desktop,
      },
      sessions: [{ ...this.#task }],
    };
  }

  apply(result) {
    this.#status = {
      state: "ready",
      message: result.message ?? null,
      controls: normalizeControls(result.controls),
    };
    this.#desktop = normalizeDesktop(result);
    this.#task.state = this.#desktop.taskState === "error" ? "error" : "active";
    this.#task.canInterrupt = this.#status.controls.stop;
    this.#task.terminalTail = result.message ?? "Ready to control Vibe Pocket Codex tasks.";
    this.#task.updatedAt = new Date().toISOString();
  }

  degrade(error) {
    this.#status = {
      state: "degraded",
      message: error.message || "Desktop Codex is unavailable.",
      controls: emptyControls(),
    };
    this.#desktop = { ...emptyDesktop(), taskState: "error" };
    this.#task.state = "error";
    this.#task.canInterrupt = false;
    this.#task.terminalTail = this.#status.message;
    this.#task.updatedAt = new Date().toISOString();
  }

  reject(message) {
    this.#status = { ...this.#status, message };
    this.#task.terminalTail = message;
    this.#task.updatedAt = new Date().toISOString();
    this.publish("snapshot_changed");
  }

  record(message, { publish = true } = {}) {
    this.#status = { ...this.#status, state: "ready", message };
    this.#task.state = this.#desktop.taskState === "error" ? "error" : "active";
    this.#task.canInterrupt = this.#status.controls.stop;
    this.#task.terminalTail = message;
    this.#task.updatedAt = new Date().toISOString();
    if (publish) this.publish("snapshot_changed");
  }

  setVoice(active) {
    this.#desktop.voice = { ...this.#desktop.voice, active };
  }

  focus(agentId) {
    this.#desktop.focusedAgentId = agentId;
    this.#desktop.focusedAgentIndex = this.#desktop.agents.findIndex((agent) => agent.id === agentId);
    this.#desktop.agents = this.#desktop.agents.map((agent) => ({
      ...agent,
      focused: agent.id === agentId,
    }));
  }

  fingerprint() {
    return JSON.stringify([
      { state: this.#status.state, controls: this.#status.controls },
      this.#desktop,
      this.#task.state,
      this.#task.canInterrupt,
    ]);
  }

  publish(type, data = {}) {
    this.#revision += 1;
    this.#events.publish(type, { revision: this.revision, ...data });
  }
}

function emptyControls() {
  return {
    voice: false,
    stop: false,
    "new-task": false,
    approve: false,
    reject: false,
    "clear-input": false,
    "focus-agent": false,
    "mode-cycle": false,
    "access-cycle": false,
    navigate: false,
    reasoning: false,
    workflow: false,
  };
}

function normalizeControls(value) {
  const defaults = emptyControls();
  if (!value || typeof value !== "object" || Array.isArray(value)) return defaults;
  return Object.fromEntries(Object.keys(defaults).map((key) => [key, value[key] === true]));
}

function emptyDesktop() {
  return {
    foreground: false,
    taskState: "idle",
    agents: [],
    focusedAgentIndex: -1,
    focusedAgentId: null,
    voice: { available: false, active: false },
    mode: { available: false, label: "" },
    access: { available: false, label: "" },
    reasoning: {
      available: false,
      label: "",
      level: null,
      canIncrease: false,
      canDecrease: false,
    },
    userInput: null,
  };
}

function normalizeDesktop(result) {
  const agents = Array.isArray(result.agents)
    ? result.agents
      .filter((agent) => agent && typeof agent.id === "string" && /^agent-[a-f0-9]{24}$/.test(agent.id)
        && typeof agent.label === "string" && typeof agent.state === "string")
      .slice(0, MAX_AGENTS)
      .map((agent) => ({
        id: agent.id,
        label: agent.label.slice(0, 64),
        state: TASK_STATES.has(agent.state) ? agent.state : "idle",
        focused: agent.focused === true,
      }))
    : [];
  const focusedAgentId = agents.find((agent) => agent.focused)?.id ?? null;
  const focusedAgents = agents.map((agent) => ({ ...agent, focused: agent.id === focusedAgentId }));
  return {
    foreground: result.foreground === true,
    taskState: TASK_STATES.has(result.taskState) ? result.taskState : "idle",
    agents: focusedAgents,
    focusedAgentIndex: focusedAgentId ? focusedAgents.findIndex((agent) => agent.id === focusedAgentId) : -1,
    focusedAgentId,
    voice: normalizeVoice(result.voice),
    mode: normalizeSelector(result.mode),
    access: normalizeSelector(result.access),
    reasoning: normalizeReasoning(result.reasoning),
    userInput: normalizeUserInput(result.userInput),
  };
}

function normalizeUserInput(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const questionCount = Number.isInteger(value.questionCount) ? value.questionCount : 0;
  const questionIndex = Number.isInteger(value.questionIndex) ? value.questionIndex : -1;
  if (questionCount < 1 || questionCount > 3 || questionIndex < 0 || questionIndex >= questionCount) return null;
  if (typeof value.header !== "string" || typeof value.question !== "string" || !Array.isArray(value.options)) return null;
  const options = value.options.slice(0, 8).map((option) => ({
    label: typeof option?.label === "string" ? option.label.slice(0, 120) : "",
    description: typeof option?.description === "string" ? option.description.slice(0, 500) : "",
  })).filter(({ label }) => label.length > 0);
  const selectedOptionIndex = options.length > 0 && Number.isInteger(value.selectedOptionIndex)
    ? Math.max(0, Math.min(options.length - 1, value.selectedOptionIndex))
    : -1;
  return {
    questionIndex,
    questionCount,
    header: value.header.slice(0, 64),
    question: value.question.slice(0, 2_000),
    options,
    selectedOptionIndex,
    hasSpokenAnswer: value.hasSpokenAnswer === true,
    isSecret: value.isSecret === true,
  };
}

function normalizeVoice(value) {
  return {
    available: value?.available === true,
    active: value?.active === true,
  };
}

function normalizeSelector(value) {
  return {
    available: value?.available === true,
    label: typeof value?.label === "string" ? value.label.slice(0, 80) : "",
  };
}

function normalizeReasoning(value) {
  const available = value?.available === true;
  const level = ["minimal", "low", "medium", "high", "xhigh"].includes(value?.level)
    ? value.level
    : null;
  return {
    available,
    label: typeof value?.label === "string" ? value.label.slice(0, 80) : "",
    modelLabel: typeof value?.modelLabel === "string" ? value.modelLabel.slice(0, 80) : "",
    level,
    canIncrease: available && value?.canIncrease !== false,
    canDecrease: available && value?.canDecrease !== false,
  };
}
