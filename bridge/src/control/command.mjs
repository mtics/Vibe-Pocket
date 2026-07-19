import {
  ValidationError,
  bindingFor,
  clearBinding,
  createDefault,
  renameLayer,
  updateBinding,
  updateLayerColor,
  updateWorkflow,
  validateGesture,
  validateAction,
  validateInputId,
  validateLayerId,
} from "../profile/model.mjs";
import { Failure } from "../server/failure.mjs";

export function resolve(command, { profile, layerId }) {
  if (!command || typeof command !== "object" || Array.isArray(command)) {
    throw new Failure(400, "invalid_command", "Controller action must be a JSON object.");
  }

  if (command.kind === "voice_start" || command.kind === "voice_stop") {
    requireKeys(command, ["kind"]);
    return { kind: "voice", active: command.kind === "voice_start" };
  }

  if (command.kind === "focus_agent" && Object.hasOwn(command, "agentId")) {
    requireKeys(command, ["kind", "agentId"]);
    if (typeof command.agentId !== "string" || !/^agent-[a-f0-9]{24}$/.test(command.agentId)) {
      throw new ValidationError("A stable Codex agent ID is required.");
    }
    return { kind: "agent", id: command.agentId };
  }

  if (command.kind === "select_model") {
    requireKeys(command, ["kind", "modelId"]);
    if (typeof command.modelId !== "string" || !/^[a-zA-Z0-9._-]{1,128}$/.test(command.modelId)) {
      throw new ValidationError("A stable Codex model ID is required.");
    }
    return { kind: "model", id: command.modelId };
  }

  if (command.kind === "binding") {
    requireKeys(command, ["kind", "inputId", "gesture", "layerId", "action"]);
    validateInputId(command.inputId);
    const gesture = validateGesture(command.gesture);
    const expectedLayerId = validateLayerId(command.layerId);
    const expectedAction = validateAction(command.action);
    if (expectedLayerId !== layerId) throw staleBinding();
    const action = bindingFor(profile, layerId, command.inputId, gesture);
    if (!action) {
      throw new Failure(409, "unmapped_input", "This controller gesture has no action on the active layer.");
    }
    if (JSON.stringify(action) !== JSON.stringify(expectedAction)) throw staleBinding();
    return { kind: "action", value: action };
  }

  if (command.kind === "select_layer") {
    requireKeys(command, ["kind", "layerId"]);
    return { kind: "layer", id: validateLayerId(command.layerId) };
  }

  if (command.kind === "update_binding") {
    requireKeys(command, ["kind", "layerId", "inputId", "action"], ["gesture"]);
    return {
      kind: "profile",
      value: updateBinding(profile, {
        layerId: command.layerId,
        inputId: command.inputId,
        gesture: command.gesture ?? "tap",
        action: command.action,
      }),
      message: "Updated controller binding.",
    };
  }

  if (command.kind === "clear_binding") {
    requireKeys(command, ["kind", "layerId", "inputId"], ["gesture"]);
    return {
      kind: "profile",
      value: clearBinding(profile, {
        layerId: command.layerId,
        inputId: command.inputId,
        gesture: command.gesture ?? "tap",
      }),
      message: "Cleared controller binding.",
    };
  }

  if (command.kind === "rename_layer") {
    requireKeys(command, ["kind", "layerId", "name"]);
    return {
      kind: "profile",
      value: renameLayer(profile, { layerId: command.layerId, name: command.name }),
      message: `Renamed ${command.layerId}.`,
    };
  }

  if (command.kind === "update_layer_color") {
    requireKeys(command, ["kind", "layerId", "color"]);
    return {
      kind: "profile",
      value: updateLayerColor(profile, { layerId: command.layerId, color: command.color }),
      message: `Updated ${command.layerId} color.`,
    };
  }

  if (command.kind === "update_workflow") {
    requireKeys(command, ["kind", "workflowId", "prompt"]);
    return {
      kind: "profile",
      value: updateWorkflow(profile, { workflowId: command.workflowId, prompt: command.prompt }),
      message: `Updated ${command.workflowId} workflow.`,
    };
  }

  if (command.kind === "reset_profile") {
    requireKeys(command, ["kind"]);
    return {
      kind: "profile",
      value: createDefault(),
      message: "Reset all controller layers.",
      resetLayer: true,
    };
  }

  return { kind: "action", value: legacy(command) };
}

function staleBinding() {
  return new Failure(
    409,
    "stale_controller_context",
    "The controller layer or binding changed; refresh before retrying this action.",
  );
}

function legacy(command) {
  switch (command.kind) {
    case "start":
      requireKeys(command, ["kind"], ["workspaceId"]);
      if (command.workspaceId !== undefined && (typeof command.workspaceId !== "string" || command.workspaceId.length === 0)) {
        throw new ValidationError("workspaceId must be non-empty text when provided.");
      }
      return { type: "attach" };
    case "attach":
      requireKeys(command, ["kind"]);
      return { type: "attach" };
    case "focus":
      requireKeys(command, ["kind", "sessionId"]);
      if (command.sessionId !== "vibe-pocket-codex") {
        throw new Failure(404, "unknown_session", "Vibe Pocket is attached only to the current desktop Codex task.");
      }
      return { type: "attach" };
    case "voice":
      requireKeys(command, ["kind"]);
      return { type: "voice" };
    case "stop":
    case "interrupt":
      requireKeys(command, ["kind"]);
      return { type: "stop" };
    case "accept":
    case "approve":
      requireKeys(command, ["kind"]);
      return { type: "approve" };
    case "reject":
      requireKeys(command, ["kind"]);
      return { type: "reject" };
    case "new_task":
    case "new_chat":
      requireKeys(command, ["kind"]);
      return { type: "new_task" };
    case "focus_next":
      requireKeys(command, ["kind"]);
      return { type: "focus_next" };
    case "focus_agent":
      requireKeys(command, ["kind", "index"]);
      return { type: "focus_agent", index: command.index };
    case "navigate":
      requireKeys(command, ["kind", "direction"]);
      return { type: "navigate", direction: command.direction };
    case "reasoning_depth":
      requireKeys(command, ["kind", "delta"]);
      return { type: "reasoning_depth", delta: command.delta };
    case "model_picker":
      requireKeys(command, ["kind"]);
      return { type: "model_picker" };
    case "delete_backward":
      requireKeys(command, ["kind"]);
      return { type: "delete_backward" };
    case "workflow":
      requireKeys(command, ["kind", "workflowId"]);
      return { type: "workflow", workflowId: command.workflowId };
    default:
      throw new Failure(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
  }
}

function requireKeys(command, required, optional = []) {
  const allowed = new Set([...required, ...optional]);
  const actual = Object.keys(command);
  if (required.some((key) => !Object.hasOwn(command, key)) || actual.some((key) => !allowed.has(key))) {
    throw new ValidationError("Controller command contains missing or unsupported fields.");
  }
}
