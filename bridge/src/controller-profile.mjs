const WORKFLOW_PROMPTS = Object.freeze({
  "review-pr": "Review the current change for correctness, security, regressions, and missing tests. Report concrete findings first.",
  debug: "Investigate the current failure from evidence, identify the root cause, implement the smallest robust fix, and verify it.",
  refactor: "Refactor the current code to improve clarity and maintainability without changing behavior. Keep the change scoped and verify it.",
  test: "Run the most relevant tests for the current change. Diagnose failures, fix real defects, and report the verified result.",
});

const INPUTS = Object.freeze([
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
]);

const DEFAULT_BINDINGS = Object.freeze({
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
});

const LAYER_COLORS = ["#F4F4F2", "#A020F0", "#25D9E8", "#FF8C24", "#FF4F9A", "#FFE04A"];

export const DEFAULT_CONTROLLER_PROFILE = Object.freeze({
  version: 1,
  inputs: INPUTS,
  workflows: Object.freeze([
    { id: "review-pr", label: "Review PR" },
    { id: "debug", label: "Debug" },
    { id: "refactor", label: "Refactor" },
    { id: "test", label: "Tests" },
  ]),
  layers: Object.freeze(LAYER_COLORS.map((color, index) => Object.freeze({
    id: `layer-${index + 1}`,
    name: index === 0 ? "Default" : `Layer ${index + 1}`,
    color,
    bindings: index === 0 ? DEFAULT_BINDINGS : Object.freeze({}),
  }))),
});

export function bindingFor(profile, layerId, inputId) {
  const layer = profile.layers.find((candidate) => candidate.id === layerId);
  return layer?.bindings?.[inputId] ?? null;
}

export function workflowPrompt(workflowId) {
  const prompt = WORKFLOW_PROMPTS[workflowId];
  if (!prompt) throw new Error("Unknown Vibe Pocket workflow.");
  return prompt;
}
