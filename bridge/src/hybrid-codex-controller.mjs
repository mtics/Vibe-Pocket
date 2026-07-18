export class HybridCodexController {
  #task;
  #visible;

  constructor({ taskController, accessibilityController }) {
    if (!taskController || !accessibilityController) {
      throw new TypeError("HybridCodexController requires task and accessibility controllers.");
    }
    this.#task = taskController;
    this.#visible = accessibilityController;
  }

  async status() {
    const status = await this.#task.status();
    return {
      ...status,
      message: "Ready to control the visible Codex window with macOS Accessibility.",
      controls: {
        ...status.controls,
        voice: true,
        stop: true,
        "new-task": true,
        approve: true,
        reject: true,
        "clear-input": true,
        "mode-cycle": true,
        navigate: true,
      },
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
      const result = await this.#task.press(control);
      await this.#visible.activate();
      return result;
    }

    if (["approve", "reject"].includes(control)) {
      const status = await this.#task.status();
      if (status.taskState === "waiting" || status.userInput) return this.#task.press(control);
      return this.#visible.press(control);
    }

    if (control === "stop") {
      const status = await this.#task.status();
      return status.controls.stop ? this.#task.press(control) : this.#visible.press(control);
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

  navigate(direction) {
    return this.#visible.navigate(direction);
  }

  cycleMode() {
    return this.#visible.cycleMode();
  }

  async cycleAccess() {
    const result = await this.#task.cycleAccess();
    await this.#task.attach();
    return result;
  }

  clearInput() {
    return this.#visible.clearInput();
  }

  focusAgent(agentId) {
    return this.#task.focusAgent(agentId);
  }

  async adjustReasoning(delta) {
    const result = await this.#task.adjustReasoning(delta);
    await this.#task.attach();
    return result;
  }

  workflow(prompt) {
    return this.#task.workflow(prompt);
  }

  async dispose() {
    await this.#task.dispose?.();
  }
}
