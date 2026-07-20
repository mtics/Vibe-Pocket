import { PROTOCOL_VERSION } from "../protocol.mjs";

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
  #observedAt = null;
  #observationFresh = false;
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
      protocolVersion: PROTOCOL_VERSION,
      revision: this.revision,
      observation: {
        fresh: this.#observationFresh,
        observedAt: this.#observedAt,
      },
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
    this.#observedAt = Date.now();
    this.#observationFresh = true;
    const desktop = normalizeDesktop(result);
    const controls = normalizeControls(result.controls);
    controls["focus-agent"] = controls["focus-agent"]
      && desktop.tasks.availability === "fresh"
      && desktop.agents.some((agent) => agent.actionable);
    this.#status = {
      state: "ready",
      message: result.message ?? null,
      controls,
    };
    this.#desktop = desktop;
    this.#task.state = this.#desktop.taskState === "error" ? "error" : "active";
    this.#task.canInterrupt = this.#status.controls.stop;
    this.#task.terminalTail = result.message ?? "Ready to control Vibe Pocket Codex tasks.";
    this.#task.updatedAt = new Date().toISOString();
  }

  retain() {
    this.#observationFresh = false;
  }

  degrade(error) {
    this.#observationFresh = false;
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

  setSettings(value) {
    if (value?.mode) this.#desktop.mode = normalizeSelector(value.mode);
    if (value?.model) this.#desktop.model = normalizeModel(value.model);
    if (value?.reasoning) this.#desktop.reasoning = normalizeReasoning(value.reasoning);
    this.#status = {
      ...this.#status,
      controls: {
        ...this.#status.controls,
        "mode-cycle": this.#desktop.mode.available,
        model: this.#desktop.model.available,
        reasoning: this.#desktop.reasoning.available,
      },
    };
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
      this.#observationFresh,
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
    "model-picker": false,
    model: false,
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
    tasks: { availability: "unavailable", message: null },
    focusedAgentIndex: -1,
    focusedAgentId: null,
    voice: { available: false, active: false },
    mode: { available: false, id: null, label: "", options: [] },
    access: { available: false, label: "" },
    model: { available: false, id: null, label: "", options: [] },
    reasoning: {
      available: false,
      label: "",
      level: null,
      canIncrease: false,
      canDecrease: false,
      increaseTo: null,
      decreaseTo: null,
      options: [],
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
        freshness: agent.freshness === "stale" ? "stale" : "fresh",
        actionable: agent.actionable !== false && agent.freshness !== "stale",
      }))
    : [];
  const focusedAgentId = agents.find((agent) => agent.focused)?.id ?? null;
  const focusedAgents = agents.map((agent) => ({ ...agent, focused: agent.id === focusedAgentId }));
  return {
    foreground: result.foreground === true,
    taskState: TASK_STATES.has(result.taskState) ? result.taskState : "idle",
    agents: focusedAgents,
    tasks: normalizeTasks(result.tasks, focusedAgents),
    focusedAgentIndex: focusedAgentId ? focusedAgents.findIndex((agent) => agent.id === focusedAgentId) : -1,
    focusedAgentId,
    voice: normalizeVoice(result.voice),
    mode: normalizeSelector(result.mode),
    access: normalizeSelector(result.access),
    model: normalizeModel(result.model, result.reasoning?.modelLabel),
    reasoning: normalizeReasoning(result.reasoning),
    userInput: normalizeUserInput(result.userInput),
  };
}

function normalizeTasks(value, agents) {
  const availability = value?.availability === "fresh"
    ? "fresh"
    : value?.availability === "stale" || agents.some((agent) => agent.freshness === "stale")
      ? "stale"
      : "unavailable";
  return {
    availability,
    message: typeof value?.message === "string" ? value.message.slice(0, 500) : null,
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
  const options = Array.isArray(value?.options)
    ? value.options
      .filter((option) => option && (option.id === "default" || option.id === "plan"))
      .slice(0, 8)
      .map((option) => ({
        id: option.id,
        label: typeof option.label === "string" ? option.label.slice(0, 80) : option.id,
        selected: option.selected === true,
      }))
    : [];
  const id = value?.id === "default" || value?.id === "plan"
    ? value.id
    : options.find(({ selected }) => selected)?.id ?? null;
  return {
    available: value?.available === true,
    label: typeof value?.label === "string" ? value.label.slice(0, 80) : "",
    id,
    options: options.map((option) => ({ ...option, selected: option.id === id })),
  };
}

function normalizeReasoning(value) {
  const available = value?.available === true;
  const levels = ["minimal", "low", "medium", "high", "xhigh", "max", "ultra"];
  const level = levels.includes(value?.level)
    ? value.level
    : null;
  const canIncrease = available && value?.canIncrease !== false;
  const canDecrease = available && value?.canDecrease !== false;
  return {
    available,
    label: typeof value?.label === "string" ? value.label.slice(0, 80) : "",
    level,
    canIncrease,
    canDecrease,
    increaseTo: canIncrease && levels.includes(value?.increaseTo) ? value.increaseTo : null,
    decreaseTo: canDecrease && levels.includes(value?.decreaseTo) ? value.decreaseTo : null,
    options: Array.isArray(value?.options)
      ? value.options.filter((option) => levels.includes(option)).slice(0, levels.length)
      : [],
  };
}

function normalizeModel(value, fallbackLabel = "") {
  const options = Array.isArray(value?.options)
    ? value.options
      .filter((option) => option && typeof option.id === "string" && /^[a-zA-Z0-9._-]{1,128}$/.test(option.id))
      .slice(0, 20)
      .map((option) => ({
        id: option.id,
        label: typeof option.label === "string" ? option.label.slice(0, 80) : option.id,
        selected: option.selected === true,
      }))
    : [];
  const id = typeof value?.id === "string" && options.some((option) => option.id === value.id)
    ? value.id
    : options.find((option) => option.selected)?.id ?? null;
  return {
    available: value?.available === true && options.length > 0,
    id,
    label: typeof value?.label === "string" && value.label
      ? value.label.slice(0, 80)
      : typeof fallbackLabel === "string" ? fallbackLabel.slice(0, 80) : "",
    options: options.map((option) => ({ ...option, selected: option.id === id })),
  };
}
