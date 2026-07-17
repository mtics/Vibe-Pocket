export const CONTROLLER_PROFILE_VERSION = 2;

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
  { id: "key_clear", kind: "key", label: "Clear", icon: "clear" },
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
  { id: "review-pr", label: "Review PR" },
  { id: "debug", label: "Debug" },
  { id: "refactor", label: "Refactor" },
  { id: "test", label: "Tests" },
];

export const CONTROLLER_GESTURES = deepFreeze([
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
  ["clear_input", "Clear input"],
  ["focus_next", "Next agent"],
  ["attach", "Focus Codex"],
]);

const NAVIGATION_DIRECTIONS = ["up", "down", "left", "right"];
const LAYER_COLORS = ["#F4F4F2", "#A020F0", "#25D9E8", "#FF8C24", "#FF4F9A", "#FFE04A"];
const INPUT_IDS = new Set(INPUTS.map(({ id }) => id));
const GESTURE_IDS = new Set(CONTROLLER_GESTURES.map(({ id }) => id));
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
  key_clear: { type: "clear_input" },
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

export const CONTROLLER_ACTION_CATALOG = deepFreeze([
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
  ...WORKFLOWS.map(({ id, label }) => ({
    id: `workflow_${id}`,
    label,
    action: { type: "workflow", workflowId: id },
  })),
]);

export const DEFAULT_CONTROLLER_PROFILE = deepFreeze(buildDefaultProfile());

export class ControllerProfileValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ControllerProfileValidationError";
  }
}

export function createDefaultControllerProfile() {
  return structuredClone(DEFAULT_CONTROLLER_PROFILE);
}

export function normalizeControllerProfile(candidate) {
  requireRecord(candidate, "Controller profile");
  if (candidate.version !== 1 && candidate.version !== CONTROLLER_PROFILE_VERSION) {
    throw new ControllerProfileValidationError("Controller profile version is not supported.");
  }
  if (!Array.isArray(candidate.layers) || candidate.layers.length !== LAYER_IDS.length) {
    throw new ControllerProfileValidationError("Controller profile must contain exactly six layers.");
  }

  const candidatesById = new Map();
  for (const layer of candidate.layers) {
    requireRecord(layer, "Controller layer");
    if (!LAYER_ID_SET.has(layer.id) || candidatesById.has(layer.id)) {
      throw new ControllerProfileValidationError("Controller profile contains an unknown or duplicate layer.");
    }
    candidatesById.set(layer.id, layer);
  }

  return {
    version: CONTROLLER_PROFILE_VERSION,
    inputs: structuredClone(INPUTS),
    workflows: structuredClone(WORKFLOWS),
    layers: LAYER_IDS.map((id, index) => {
      const layer = candidatesById.get(id);
      if (!layer) throw new ControllerProfileValidationError("Controller profile is missing a fixed layer.");
      return {
        id,
        name: validateLayerName(layer.name),
        color: LAYER_COLORS[index],
        bindings: normalizeBindings(layer.bindings),
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

export function updateControllerBinding(profile, { layerId, inputId, gesture = "tap", action }) {
  validateLayerId(layerId);
  validateInputId(inputId);
  validateGesture(gesture);
  const next = normalizeControllerProfile(profile);
  const layer = next.layers.find((candidate) => candidate.id === layerId);
  layer.bindings[inputId] ??= {};
  layer.bindings[inputId][gesture] = validateControllerAction(action);
  return normalizeControllerProfile(next);
}

export function clearControllerBinding(profile, { layerId, inputId, gesture = "tap" }) {
  validateLayerId(layerId);
  validateInputId(inputId);
  validateGesture(gesture);
  const next = normalizeControllerProfile(profile);
  const layer = next.layers.find((candidate) => candidate.id === layerId);
  if (!layer.bindings[inputId]) return next;
  delete layer.bindings[inputId][gesture];
  if (Object.keys(layer.bindings[inputId]).length === 0) delete layer.bindings[inputId];
  return next;
}

export function renameControllerLayer(profile, { layerId, name }) {
  validateLayerId(layerId);
  const next = normalizeControllerProfile(profile);
  next.layers.find((candidate) => candidate.id === layerId).name = validateLayerName(name);
  return next;
}

export function validateControllerAction(action) {
  requireRecord(action, "Controller action");
  if (NO_ARGUMENT_ACTIONS.has(action.type)) {
    requireExactKeys(action, ["type"], "Controller action");
    return { type: action.type };
  }
  if (action.type === "navigate") {
    requireExactKeys(action, ["type", "direction"], "Navigation action");
    if (!NAVIGATION_DIRECTIONS.includes(action.direction)) {
      throw new ControllerProfileValidationError("Navigation direction must be up, down, left, or right.");
    }
    return { type: "navigate", direction: action.direction };
  }
  if (action.type === "reasoning_depth") {
    requireExactKeys(action, ["type", "delta"], "Reasoning action");
    if (action.delta !== 1 && action.delta !== -1) {
      throw new ControllerProfileValidationError("Reasoning adjustment must be exactly 1 or -1.");
    }
    return { type: "reasoning_depth", delta: action.delta };
  }
  if (action.type === "focus_agent") {
    requireExactKeys(action, ["type", "index"], "Focus agent action");
    if (!Number.isInteger(action.index) || action.index < 0 || action.index > 5) {
      throw new ControllerProfileValidationError("Agent index must be an integer from 0 to 5.");
    }
    return { type: "focus_agent", index: action.index };
  }
  if (action.type === "workflow") {
    requireExactKeys(action, ["type", "workflowId"], "Workflow action");
    if (!WORKFLOW_IDS.has(action.workflowId)) {
      throw new ControllerProfileValidationError("Workflow action must reference a known workflow ID.");
    }
    return { type: "workflow", workflowId: action.workflowId };
  }
  throw new ControllerProfileValidationError("Controller action type is not supported.");
}

export function validateLayerId(layerId) {
  if (!LAYER_ID_SET.has(layerId)) {
    throw new ControllerProfileValidationError("Controller layer does not exist.");
  }
  return layerId;
}

export function validateInputId(inputId) {
  if (!INPUT_IDS.has(inputId)) {
    throw new ControllerProfileValidationError("Controller input does not exist.");
  }
  return inputId;
}

export function validateGesture(gesture) {
  if (!GESTURE_IDS.has(gesture)) {
    throw new ControllerProfileValidationError("Gesture must be tap, double_tap, or hold.");
  }
  return gesture;
}

export function workflowPrompt(workflowId) {
  const prompt = WORKFLOW_PROMPTS[workflowId];
  if (!prompt) throw new ControllerProfileValidationError("Unknown Vibe Pocket workflow.");
  return prompt;
}

function buildDefaultProfile() {
  return {
    version: CONTROLLER_PROFILE_VERSION,
    inputs: structuredClone(INPUTS),
    workflows: structuredClone(WORKFLOWS),
    layers: LAYER_COLORS.map((color, index) => ({
      id: LAYER_IDS[index],
      name: index === 0 ? "Default" : `Layer ${index + 1}`,
      color,
      bindings: index === 0
        ? Object.fromEntries(Object.entries(DEFAULT_TAP_ACTIONS).map(([inputId, action]) => [inputId, { tap: action }]))
        : {},
    })),
  };
}

function normalizeBindings(bindings) {
  requireRecord(bindings, "Layer bindings");
  const normalized = {};
  for (const [inputId, binding] of Object.entries(bindings)) {
    validateInputId(inputId);
    requireRecord(binding, "Input binding");
    if (Object.hasOwn(binding, "type")) {
      normalized[inputId] = { tap: validateControllerAction(binding) };
      continue;
    }

    const gestures = {};
    for (const [gesture, action] of Object.entries(binding)) {
      validateGesture(gesture);
      gestures[gesture] = validateControllerAction(action);
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
    throw new ControllerProfileValidationError(
      "Push-to-talk must be the only gesture on its input and must use tap.",
    );
  }
}

function validateLayerName(name) {
  if (typeof name !== "string") {
    throw new ControllerProfileValidationError("Layer name must be text.");
  }
  const trimmed = name.trim();
  if (trimmed.length === 0 || trimmed.length > 40 || /[\u0000-\u001f\u007f]/.test(trimmed)) {
    throw new ControllerProfileValidationError("Layer name must be 1 to 40 printable characters.");
  }
  return trimmed;
}

function requireRecord(value, label) {
  if (!value || typeof value !== "object" || Array.isArray(value) || Object.getPrototypeOf(value) !== Object.prototype) {
    throw new ControllerProfileValidationError(`${label} must be a JSON object.`);
  }
}

function requireExactKeys(value, expected, label) {
  const actual = Object.keys(value).sort();
  const required = [...expected].sort();
  if (actual.length !== required.length || actual.some((key, index) => key !== required[index])) {
    throw new ControllerProfileValidationError(`${label} contains unsupported arguments.`);
  }
}

function deepFreeze(value) {
  if (!value || typeof value !== "object" || Object.isFrozen(value)) return value;
  Object.freeze(value);
  for (const child of Object.values(value)) deepFreeze(child);
  return value;
}
