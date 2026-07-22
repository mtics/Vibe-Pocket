import {
  ValidationError,
  validateAction,
  workflowPrompt,
} from "../profile/model.mjs";
import { Failure } from "../server/failure.mjs";
import { resolve } from "./command.mjs";

export class Execution {
  #desktop;
  #state;
  #refresh;
  #profile;
  #activeLayerId;
  #selectLayer;
  #replaceProfile;

  constructor({
    desktop,
    state,
    refresh,
    profile,
    activeLayerId,
    selectLayer,
    replaceProfile,
  }) {
    this.#desktop = desktop;
    this.#state = state;
    this.#refresh = refresh;
    this.#profile = profile;
    this.#activeLayerId = activeLayerId;
    this.#selectLayer = selectLayer;
    this.#replaceProfile = replaceProfile;
  }

  resolve(command) {
    try {
      const intent = resolve(command, {
        profile: this.#profile(),
        layerId: this.#activeLayerId(),
      });
      return intent.kind === "action"
        ? { ...intent, value: validateAction(intent.value) }
        : intent;
    } catch (error) {
      if (error instanceof Failure) throw error;
      if (error instanceof ValidationError) {
        throw new Failure(400, "invalid_controller_configuration", error.message);
      }
      throw error;
    }
  }

  async execute(command, authority) {
    try {
      const intent = this.resolve(command);
      if (intent.kind === "voice") return await this.#setVoice(intent.active, authority);
      if (intent.kind === "agent") return await this.#focusAgent(intent.id, authority);
      if (intent.kind === "target") return await this.#selectAgent(intent.id, authority);
      if (intent.kind === "model") return await this.#selectModel(intent.id, intent.target, authority);
      if (intent.kind === "reasoning") return await this.#selectReasoning(intent.level, intent.target, authority);
      if (intent.kind === "reasoning_delta") {
        return await this.#adjustReasoning(intent.delta, intent.target, authority);
      }
      if (intent.kind === "action") return await this.#executeAction(intent.value, authority);
      if (intent.kind === "layer") return this.#selectLayer(intent.id);
      if (intent.kind === "profile") {
        return await this.#replaceProfile(intent.value, intent.message, {
          resetLayer: intent.resetLayer,
          authority,
        });
      }
    } catch (error) {
      if (error instanceof Failure) throw error;
      if (error instanceof ValidationError) {
        throw new Failure(400, "invalid_controller_configuration", error.message);
      }
      const message = error.message || "Codex did not accept this controller action.";
      this.#state.reject(message);
      this.#refresh.afterAction();
      throw new Failure(409, "desktop_action_failed", message);
    }
  }

  async perform(operation, fallbackMessage, authority) {
    const result = await this.#performEffect(operation, authority);
    this.#complete(result, fallbackMessage);
    return result;
  }

  async #performDeferred(operation, fallbackMessage, authority) {
    const result = await authority.deferred(operation);
    this.#complete(result, fallbackMessage, { confirmedSettings: true });
  }

  #complete(result, fallbackMessage, { confirmedSettings = false } = {}) {
    const before = this.#state.fingerprint();
    if (confirmedSettings) this.#state.setSettings(result?.settings);
    this.#state.record(result?.message ?? fallbackMessage, { publish: false });
    if (before !== this.#state.fingerprint()) this.#state.publish("snapshot_changed");
    this.#refresh.afterAction();
  }

  async #executeAction(action, authority) {
    action = validateAction(action);
    switch (action.type) {
      case "attach":
        await this.perform(
          (effects) => this.#desktop.attach(effects),
          "Resumed the focused Vibe Pocket Codex task.",
          authority,
        );
        return;
      case "voice":
        await this.#setVoice(!this.#state.voice.active, authority);
        return;
      case "stop":
        await this.#press("stop", "Stopped the focused Codex turn.", authority);
        return;
      case "approve":
        await this.#press("approve", "Approved the focused Codex request.", authority);
        return;
      case "reject":
        await this.#press("reject", "Rejected the focused Codex request.", authority);
        return;
      case "new_task":
        await this.#press("new-task", "Created a new Vibe Pocket Codex task.", authority);
        return;
      case "navigate":
        if (!["up", "down", "left", "right"].includes(action.direction)) {
          throw new Failure(400, "invalid_direction", "Navigation direction must be up, down, left, or right.");
        }
        await this.perform(
          (effects) => this.#desktop.navigate(action.direction, effects),
          `Moved ${action.direction} in Codex.`,
          authority,
        );
        return;
      case "mode_cycle":
        throw new Failure(409, "mode_selection_disabled", "Codex mode selection is disabled by protocol v12.");
      case "model_picker":
        await this.perform(
          (effects) => this.#desktop.openModel(effects),
          "Opened the Codex model picker.",
          authority,
        );
        return;
      case "access_cycle":
        await this.perform(
          (effects) => this.#desktop.cycleAccess(effects),
          "Selected the next Codex access level.",
          authority,
        );
        return;
      case "delete_backward":
        await this.perform(
          (effects) => this.#desktop.deleteBackward(effects),
          "Deleted one character from the visible Codex input.",
          authority,
        );
        return;
      case "clear_input":
        await this.perform(
          (effects) => this.#desktop.clearInput(effects),
          "Cleared the visible Codex input.",
          authority,
        );
        return;
      case "focus_next":
        await this.#focusNext(authority);
        return;
      case "focus_agent":
        await this.#focusSlot(action.index, authority);
        return;
      case "select_layer":
        this.#selectLayer(action.layerId);
        return;
      case "reasoning_depth":
        throw new Failure(
          409,
          "reasoning_target_required",
          "Refresh Vibe Pocket before adjusting reasoning for the bound Codex task.",
        );
      case "workflow":
        await this.perform(
          (effects) => this.#desktop.workflow(workflowPrompt(this.#profile(), action.workflowId), effects),
          "Started the selected workflow in a new Codex task.",
          authority,
        );
        return;
      default:
        throw new Failure(400, "unsupported_command", "This Vibe Pocket controller action is not supported.");
    }
  }

  async #focusNext(authority) {
    const agents = this.#state.agents;
    if (agents.length === 0) {
      await this.perform(
        (effects) => this.#desktop.attach(effects),
        "Resumed the focused Vibe Pocket Codex task.",
        authority,
      );
      return;
    }
    const nextIndex = (this.#state.focusedAgentIndex + 1) % agents.length;
    const agent = agents[nextIndex];
    await this.perform(
      (effects) => this.#desktop.focusAgent(agent.id, effects),
      `Focused ${agent.label}.`,
      authority,
    );
    this.#state.focus(agent.id);
  }

  async #focusSlot(index, authority) {
    const agent = this.#state.agents[index];
    if (!agent) {
      throw new Failure(409, "agent_slot_unavailable", "That Codex agent slot is not currently available.");
    }
    await this.perform(
      (effects) => this.#desktop.focusAgent(agent.id, effects),
      `Focused ${agent.label}.`,
      authority,
    );
    this.#state.focus(agent.id);
  }

  async #press(control, fallbackMessage, authority) {
    await this.perform((effects) => this.#desktop.press(control, effects), fallbackMessage, authority);
  }

  async #setVoice(active, authority) {
    if (active && !this.#state.voice.available) {
      throw new Failure(409, "voice_unavailable", "The visible ChatGPT Codex dictation control is unavailable.");
    }
    const result = await this.#performEffect(
      (effects) => this.#desktop.setVoice(active, effects),
      authority,
    );
    this.#state.setVoice(active);
    this.#state.record(
      result?.message ?? (active ? "Started ChatGPT Codex dictation." : "Stopped ChatGPT Codex dictation."),
      { publish: false },
    );
    this.#refresh.afterAction();
  }

  async #focusAgent(agentId, authority) {
    const agent = this.#state.agents.find((candidate) => candidate.id === agentId);
    if (!agent) {
      throw new Failure(409, "agent_unavailable", "That Codex agent is no longer available.");
    }
    await this.perform(
      (effects) => this.#desktop.focusAgent(agent.id, effects),
      `Focused ${agent.label}.`,
      authority,
    );
    this.#state.focus(agent.id);
  }

  async #selectAgent(agentId, authority) {
    const agent = this.#state.agents.find((candidate) => candidate.id === agentId);
    if (!agent || agent.actionable === false) {
      throw new Failure(409, "agent_unavailable", "That Codex agent is no longer available.");
    }
    await this.perform(
      (effects) => this.#desktop.selectAgent(agent.id, effects),
      `Selected ${agent.label} for Vibe Pocket controls.`,
      authority,
    );
    this.#refresh.invalidate();
    await this.#refresh.now({ publishIfChanged: true });
    if (this.#state.target?.agentId !== agent.id) {
      throw new Error("Codex did not confirm the selected Vibe Pocket control target.");
    }
    this.#state.focus(agent.id);
  }

  async #selectModel(modelId, target, authority) {
    await this.#performDeferred(
      (effects) => this.#desktop.selectModel(modelId, target, effects),
      "Selected the Codex model.",
      authority,
    );
  }

  async #selectReasoning(level, target, authority) {
    await this.#performDeferred(
      (effects) => this.#desktop.selectReasoning(level, target, effects),
      "Selected the Codex reasoning level.",
      authority,
    );
  }

  async #adjustReasoning(delta, target, authority) {
    await this.#performDeferred(
      (effects) => this.#desktop.adjustReasoning(delta, target, effects),
      "Adjusted Codex reasoning depth.",
      authority,
    );
  }

  #performEffect(operation, authority) {
    return authority.effect((effects) => (
      this.#desktop.effectAware === true
        ? operation(effects)
        : effects.commit(() => operation(null))
    ));
  }
}
