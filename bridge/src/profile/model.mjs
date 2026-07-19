export const VERSION = 4;

const WORKFLOW_PROMPTS = Object.freeze({
  "review-pr": "Review the current change for correctness, security, regressions, and missing tests. Report concrete findings first.",
  debug: "Investigate the current failure from evidence, identify the root cause, implement the smallest robust fix, and verify it.",
  refactor: "Refactor the current code to improve clarity and maintainability without changing behavior. Keep the change scoped and verify it.",
  test: "Run the most relevant tests for the current change. Diagnose failures, fix real defects, and report the verified result.",
});

const INPUTS = [
  { id: "key_accept", kind: "key", label: "Accept", icon: "check" },
  { id: "key_reject", kind: "key", label: "Reject", icon: "close" },
  { id: "key_voice", kind: "key", label: "Voice", icon: "mic" },
  { id: "key_new_task", kind: "key", label: "New task", icon: "add" },
  { id: "key_stop", kind: "key", label: "Stop", icon: "stop" },
  { id: "key_mode", kind: "key", label: "Mode", icon: "cycle" },
  { id: "key_clear", kind: "key", label: "Delete", icon: "clear" },
  { id: "key_focus", kind: "key", label: "Next agent", icon: "agent" },
  { id: "key_up", kind: "key", label: "Up", icon: "up" },
  { id: "key_down", kind: "key", label: "Down", icon: "down" },
  { id: "key_left", kind: "key", label: "Left", icon: "left" },
  { id: "key_right", kind: "key", label: "Right", icon: "right" },
  { id: "key_attach", kind: "key", label: "Focus Codex", icon: "focus" },
  { id: "touch", kind: "touch", label: "Next agent", icon: "touch" },
  { id: "joystick_up", kind: "joystick", label: "Review PR", icon: "review" },
  { id: "joystick_down", kind: "joystick", label: "Debug", icon: "debug" },
  { id: "joystick_left", kind: "joystick", label: "Refactor", icon: "refactor" },
  { id: "joystick_right", kind: "joystick", label: "Tests", icon: "test" },
  { id: "dial_cw", kind: "dial", label: "More reasoning", icon: "dial" },
  { id: "dial_ccw", kind: "dial", label: "Less reasoning", icon: "dial" },
];

const WORKFLOWS = [
  { id: "review-pr", label: "Review PR", prompt: WORKFLOW_PROMPTS["review-pr"] },
  { id: "debug", label: "Debug", prompt: WORKFLOW_PROMPTS.debug },
  { id: "refactor", label: "Refactor", prompt: WORKFLOW_PROMPTS.refactor },
  { id: "test", label: "Tests", prompt: WORKFLOW_PROMPTS.test },
];

export const GESTURES = deepFreeze([
  { id: "tap", label: "Tap" },
  { id: "double_tap", label: "Double tap" },
  { id: "hold", label: "Hold" },
]);

const NO_ARGUMENT_ACTIONS = new Map([
  ["approve", "Approve"],
  ["reject", "Reject"],
  ["voice", "Voice"],
  ["new_task", "New task"],
  ["stop", "Stop"],
  ["mode_cycle", "Next mode"],
  ["model_picker", "Choose model"],
  ["access_cycle", "Next access level"],
  ["delete_backward", "Delete backward"],
  ["clear_input", "Clear input"],
  ["focus_next", "Next agent"],
  ["attach", "Focus Codex"],
]);

const NAVIGATION_DIRECTIONS = ["up", "down", "left", "right"];
const LAYER_COLORS = ["#F4F4F2", "#A020F0", "#25D9E8", "#FF8C24", "#FF4F9A", "#FFE04A"];
const INPUT_IDS = new Set(INPUTS.map(({ id }) => id));
const GESTURE_IDS = new Set(GESTURES.map(({ id }) => id));
const WORKFLOW_IDS = new Set(WORKFLOWS.map(({ id }) => id));
const LAYER_IDS = LAYER_COLORS.map((_, index) => `layer-${index + 1}`);
const LAYER_ID_SET = new Set(LAYER_IDS);

const DEFAULT_TAP_ACTIONS = {
  key_accept: { type: "approve" },
  key_reject: { type: "reject" },
  key_voice: { type: "voice" },
  key_new_task: { type: "new_task" },
  key_stop: { type: "stop" },
  key_mode: { type: "mode_cycle" },
  key_clear: { type: "delete_backward" },
  key_focus: { type: "focus_next" },
  key_up: { type: "navigate", direction: "up" },
  key_down: { type: "navigate", direction: "down" },
  key_left: { type: "navigate", direction: "left" },
  key_right: { type: "navigate", direction: "right" },
  key_attach: { type: "attach" },
  touch: { type: "focus_next" },
  joystick_up: { type: "workflow", workflowId: "review-pr" },
  joystick_down: { type: "workflow", workflowId: "debug" },
  joystick_left: { type: "workflow", workflowId: "refactor" },
  joystick_right: { type: "workflow", workflowId: "test" },
  dial_cw: { type: "reasoning_depth", delta: 1 },
  dial_ccw: { type: "reasoning_depth", delta: -1 },
};

export const ACTIONS = deepFreeze([
  ...[...NO_ARGUMENT_ACTIONS].map(([type, label]) => ({ id: type, label, action: { type } })),
  ...NAVIGATION_DIRECTIONS.map((direction) => ({
    id: `navigate_${direction}`,
    label: `Navigate ${direction}`,
    action: { type: "navigate", direction },
  })),
  { id: "reasoning_increase", label: "Increase reasoning", action: { type: "reasoning_depth", delta: 1 } },
  { id: "reasoning_decrease", label: "Decrease reasoning", action: { type: "reasoning_depth", delta: -1 } },
  ...Array.from({ length: 6 }, (_, index) => ({
    id: `focus_agent_${index + 1}`,
    label: `Focus agent ${index + 1}`,
    action: { type: "focus_agent", index },
  })),
  ...LAYER_IDS.map((layerId, index) => ({
    id: `select_layer_${index + 1}`,
    label: `Select layer ${index + 1}`,
    action: { type: "select_layer", layerId },
  })),
  ...WORKFLOWS.map(({ id, label }) => ({
    id: `workflow_${id}`,
    label,
    action: { type: "workflow", workflowId: id },
  })),
]);

export const DEFAULT = deepFreeze(buildDefaultProfile());

export class ValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ValidationError";
  }
}

export function createDefault() {
  return structuredClone(DEFAULT);
}

export function normalize(candidate) {
  requireRecord(candidate, "Controller profile");
  if (![1, 2, 3, VERSION].includes(candidate.version)) {
    throw new ValidationError("Controller profile version is not supported.");
  }
  if (!Array.isArray(candidate.layers) || candidate.layers.length !== LAYER_IDS.length) {
    throw new ValidationError("Controller profile must contain exactly six layers.");
  }

  const candidatesById = new Map();
  for (const layer of candidate.layers) {
    requireRecord(layer, "Controller layer");
    if (!LAYER_ID_SET.has(layer.id) || candidatesById.has(layer.id)) {
      throw new ValidationError("Controller profile contains an unknown or duplicate layer.");
    }
    candidatesById.set(layer.id, layer);
  }

  return {
    version: VERSION,
    inputs: structuredClone(INPUTS),
    workflows: candidate.version >= 3 ? normalizeWorkflows(candidate.workflows) : structuredClone(WORKFLOWS),
    layers: LAYER_IDS.map((id, index) => {
      const layer = candidatesById.get(id);
      if (!layer) throw new ValidationError("Controller profile is missing a fixed layer.");
      return {
        id,
        name: validateLayerName(layer.name),
        color: candidate.version >= 3 ? validateLayerColor(layer.color) : LAYER_COLORS[index],
        bindings: migrateBindings(normalizeBindings(layer.bindings), candidate.version),
      };
    }),
  };
}

export function bindingFor(profile, layerId, inputId, gesture = "tap") {
  if (!GESTURE_IDS.has(gesture)) return null;
  const layer = profile.layers.find((candidate) => candidate.id === layerId);
  const binding = layer?.bindings?.[inputId];
  if (!binding) return null;
  return Object.hasOwn(binding, "type")
    ? (gesture === "tap" ? binding : null)
    : binding[gesture] ?? null;
}

export function updateBinding(profile, { layerId, inputId, gesture = "tap", action }) {
  validateLayerId(layerId);
  validateInputId(inputId);
  validateGesture(gesture);
  const next = normalize(profile);
  const layer = next.layers.find((candidate) => candidate.id === layerId);
  layer.bindings[inputId] ??= {};
  layer.bindings[inputId][gesture] = validateAction(action);
  return normalize(next);
}

export function clearBinding(profile, { layerId, inputId, gesture = "tap" }) {
  validateLayerId(layerId);
  validateInputId(inputId);
  validateGesture(gesture);
  const next = normalize(profile);
  const layer = next.layers.find((candidate) => candidate.id === layerId);
  if (!layer.bindings[inputId]) return next;
  delete layer.bindings[inputId][gesture];
  if (Object.keys(layer.bindings[inputId]).length === 0) delete layer.bindings[inputId];
  return next;
}

export function renameLayer(profile, { layerId, name }) {
  validateLayerId(layerId);
  const next = normalize(profile);
  next.layers.find((candidate) => candidate.id === layerId).name = validateLayerName(name);
  return next;
}

export function updateLayerColor(profile, { layerId, color }) {
  validateLayerId(layerId);
  const next = normalize(profile);
  next.layers.find((candidate) => candidate.id === layerId).color = validateLayerColor(color);
  return normalize(next);
}

export function updateWorkflow(profile, { workflowId, prompt }) {
  if (!WORKFLOW_IDS.has(workflowId)) {
    throw new ValidationError("Workflow does not exist.");
  }
  const next = normalize(profile);
  next.workflows.find((workflow) => workflow.id === workflowId).prompt = validateWorkflowPrompt(prompt);
  return normalize(next);
}

export function validateAction(action) {
  requireRecord(action, "Controller action");
  if (NO_ARGUMENT_ACTIONS.has(action.type)) {
    requireExactKeys(action, ["type"], "Controller action");
    return { type: action.type };
  }
  if (action.type === "navigate") {
    requireExactKeys(action, ["type", "direction"], "Navigation action");
    if (!NAVIGATION_DIRECTIONS.includes(action.direction)) {
      throw new ValidationError("Navigation direction must be up, down, left, or right.");
    }
    return { type: "navigate", direction: action.direction };
  }
  if (action.type === "reasoning_depth") {
    requireExactKeys(action, ["type", "delta"], "Reasoning action");
    if (action.delta !== 1 && action.delta !== -1) {
      throw new ValidationError("Reasoning adjustment must be exactly 1 or -1.");
    }
    return { type: "reasoning_depth", delta: action.delta };
  }
  if (action.type === "focus_agent") {
    requireExactKeys(action, ["type", "index"], "Focus agent action");
    if (!Number.isInteger(action.index) || action.index < 0 || action.index > 5) {
      throw new ValidationError("Agent index must be an integer from 0 to 5.");
    }
    return { type: "focus_agent", index: action.index };
  }
  if (action.type === "select_layer") {
    requireExactKeys(action, ["type", "layerId"], "Layer action");
    return { type: "select_layer", layerId: validateLayerId(action.layerId) };
  }
  if (action.type === "workflow") {
    requireExactKeys(action, ["type", "workflowId"], "Workflow action");
    if (!WORKFLOW_IDS.has(action.workflowId)) {
      throw new ValidationError("Workflow action must reference a known workflow ID.");
    }
    return { type: "workflow", workflowId: action.workflowId };
  }
  throw new ValidationError("Controller action type is not supported.");
}

export function validateLayerId(layerId) {
  if (!LAYER_ID_SET.has(layerId)) {
    throw new ValidationError("Controller layer does not exist.");
  }
  return layerId;
}

export function validateInputId(inputId) {
  if (!INPUT_IDS.has(inputId)) {
    throw new ValidationError("Controller input does not exist.");
  }
  return inputId;
}

export function validateGesture(gesture) {
  if (!GESTURE_IDS.has(gesture)) {
    throw new ValidationError("Gesture must be tap, double_tap, or hold.");
  }
  return gesture;
}

export function workflowPrompt(profile, workflowId) {
  const prompt = normalize(profile).workflows.find(({ id }) => id === workflowId)?.prompt;
  if (!prompt) throw new ValidationError("Unknown Vibe Pocket workflow.");
  return prompt;
}

function normalizeWorkflows(workflows) {
  if (!Array.isArray(workflows) || workflows.length !== WORKFLOWS.length) {
    throw new ValidationError("Controller profile must contain exactly four workflows.");
  }
  const candidates = new Map();
  for (const workflow of workflows) {
    requireRecord(workflow, "Workflow");
    requireExactKeys(workflow, ["id", "label", "prompt"], "Workflow");
    if (!WORKFLOW_IDS.has(workflow.id) || candidates.has(workflow.id)) {
      throw new ValidationError("Controller profile contains an unknown or duplicate workflow.");
    }
    const canonical = WORKFLOWS.find(({ id }) => id === workflow.id);
    if (workflow.label !== canonical.label) {
      throw new ValidationError("Workflow labels cannot alter the fixed workflow topology.");
    }
    candidates.set(workflow.id, { ...canonical, prompt: validateWorkflowPrompt(workflow.prompt) });
  }
  return WORKFLOWS.map(({ id }) => candidates.get(id));
}

function buildDefaultProfile() {
  return {
    version: VERSION,
    inputs: structuredClone(INPUTS),
    workflows: structuredClone(WORKFLOWS),
    layers: LAYER_COLORS.map((color, index) => ({
      id: LAYER_IDS[index],
      name: index === 0 ? "Default" : `Layer ${index + 1}`,
      color,
      bindings: index === 0 ? defaultBindings() : {},
    })),
  };
}

function defaultBindings() {
  const bindings = Object.fromEntries(
    Object.entries(DEFAULT_TAP_ACTIONS).map(([inputId, action]) => [inputId, { tap: action }]),
  );
  bindings.key_clear.hold = { type: "clear_input" };
  return bindings;
}

function migrateBindings(bindings, version) {
  const clear = bindings.key_clear;
  if (
    version < 4 &&
    clear?.tap?.type === "clear_input" &&
    Object.keys(clear).length === 1
  ) {
    return {
      ...bindings,
      key_clear: {
        tap: { type: "delete_backward" },
        hold: { type: "clear_input" },
      },
    };
  }
  return bindings;
}

function normalizeBindings(bindings) {
  requireRecord(bindings, "Layer bindings");
  const normalized = {};
  for (const [inputId, binding] of Object.entries(bindings)) {
    validateInputId(inputId);
    requireRecord(binding, "Input binding");
    if (Object.hasOwn(binding, "type")) {
      normalized[inputId] = { tap: validateAction(binding) };
      continue;
    }

    const gestures = {};
    for (const [gesture, action] of Object.entries(binding)) {
      validateGesture(gesture);
      gestures[gesture] = validateAction(action);
    }
    validateVoiceGestureBinding(gestures);
    if (Object.keys(gestures).length > 0) normalized[inputId] = gestures;
  }
  return normalized;
}

function validateVoiceGestureBinding(gestures) {
  const voiceGestures = Object.entries(gestures)
    .filter(([, action]) => action.type === "voice")
    .map(([gesture]) => gesture);
  if (voiceGestures.length === 0) return;
  if (voiceGestures.length !== 1 || voiceGestures[0] !== "tap" || Object.keys(gestures).length !== 1) {
    throw new ValidationError(
      "Push-to-talk must be the only gesture on its input and must use tap.",
    );
  }
}

function validateLayerName(name) {
  if (typeof name !== "string") {
    throw new ValidationError("Layer name must be text.");
  }
  const trimmed = name.trim();
  if (trimmed.length === 0 || trimmed.length > 40 || /[\u0000-\u001f\u007f]/.test(trimmed)) {
    throw new ValidationError("Layer name must be 1 to 40 printable characters.");
  }
  return trimmed;
}

function validateLayerColor(color) {
  if (typeof color !== "string" || !/^#[0-9a-fA-F]{6}$/.test(color)) {
    throw new ValidationError("Layer color must be a six-digit hexadecimal color.");
  }
  return color.toUpperCase();
}

function validateWorkflowPrompt(prompt) {
  if (typeof prompt !== "string") {
    throw new ValidationError("Workflow prompt must be text.");
  }
  const trimmed = prompt.trim();
  if (trimmed.length === 0 || trimmed.length > 4_000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(trimmed)) {
    throw new ValidationError("Workflow prompt must be 1 to 4,000 printable characters.");
  }
  return trimmed;
}

function requireRecord(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.getPrototypeOf(value) !== Object.prototype) {
    throw new ValidationError(`${label} must be a JSON object.`);
  }
}

function requireExactKeys(value, expected, label) {
  const actual = Object.keys(value).sort();
  const required = [...expected].sort();
  if (actual.length !== required.length || actual.some((key, index) => key !== required[index])) {
    throw new ValidationError(`${label} contains unsupported arguments.`);
  }
}

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const child of Object.values(value)) deepFreeze(child);
  return value;
}
