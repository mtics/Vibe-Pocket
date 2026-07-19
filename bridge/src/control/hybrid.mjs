export class Hybrid {
  #task;
  #visible;

  constructor({ taskController, accessibilityController }) {
    if (!taskController || !accessibilityController) {
      throw new TypeError("Hybrid requires task and accessibility controllers.");
    }
    this.#task = taskController;
    this.#visible = accessibilityController;
  }

  async status() {
    const [status, visible] = await Promise.all([
      this.#task.status(),
      this.#visible.status(),
    ]);
    const structuredInput = Boolean(status.userInput);
    return {
      ...status,
      taskState: visible.taskState ?? status.taskState,
      message: visible.message ?? "Ready to control the visible Codex window with macOS Accessibility.",
      controls: {
        ...status.controls,
        voice: true,
        stop: visible.controls.stop === true,
        "new-task": visible.controls["new-task"] === true,
        approve: structuredInput ? status.controls.approve === true : visible.controls.approve === true,
        reject: structuredInput ? status.controls.reject === true : visible.controls.reject === true,
        "clear-input": structuredInput
          ? status.controls["clear-input"] === true
          : visible.controls["clear-input"] === true,
        "mode-cycle": visible.controls["plan-mode"] === true,
        "access-cycle": visible.controls["mode-cycle"] === true,
        navigate: structuredInput ? status.controls.navigate === true : visible.controls.navigate === true,
        reasoning: visible.controls.reasoning === true,
      },
      mode: { available: true, label: "Codex" },
      access: visible.mode ?? status.access,
      reasoning: visible.reasoning ?? status.reasoning,
      voice: { available: true, active: this.#visible.voiceActive },
    };
  }

  async attach() {
    const result = await this.#task.attach();
    await this.#visible.activate();
    return result;
  }

  async bindThread(threadId) {
    const result = await this.#task.bindThread(threadId);
    await this.#visible.activate();
    return result;
  }

  applyLifecycleHook(event, payload) {
    return this.#task.applyLifecycleHook(event, payload);
  }

  async press(control) {
    if (control === "new-task") {
      return this.#visible.press(control);
    }

    if (["approve", "reject"].includes(control)) {
      const status = await this.#task.status();
      if (status.taskState === "waiting" || status.userInput) return this.#task.press(control);
      return this.#visible.press(control);
    }

    if (control === "stop") {
      return this.#visible.press(control);
    }

    throw new Error(`Unsupported hybrid Codex control: ${control}.`);
  }

  async setVoice(active) {
    const result = await this.#visible.setVoice(active);
    await this.#task.setVoice(active);
    return result;
  }

  async setDictationDraft(text) {
    const result = await this.#visible.setDictationDraft(text);
    await this.#task.setVoice(false);
    return result;
  }

  async navigate(direction) {
    const status = await this.#task.status();
    return status.userInput
      ? this.#task.navigate(direction)
      : this.#visible.navigate(direction);
  }

  async cycleMode() {
    return this.#visible.cycleMode();
  }

  async cycleAccess() {
    return this.#visible.cycleAccess();
  }

  async clearInput() {
    const status = await this.#task.status();
    return status.userInput && status.controls["clear-input"]
      ? this.#task.clearInput()
      : this.#visible.clearInput();
  }

  focusAgent(agentId) {
    return this.#task.focusAgent(agentId);
  }

  async adjustReasoning(delta) {
    return this.#visible.adjustReasoning(delta);
  }

  workflow(prompt) {
    return this.#visible.workflow(prompt);
  }

  async dispose() {
    await this.#task.dispose?.();
  }
}
