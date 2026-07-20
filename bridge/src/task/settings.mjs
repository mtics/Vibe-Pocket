import { Context } from "./context.mjs";

export class Settings {
  #appServer;
  #context;
  #models = new Map();
  #started = false;
  #starting = null;
  #current = null;
  #threads = new Map();
  #loading = new Map();

  constructor({ appServer, context = new Context() }) {
    this.#appServer = appServer;
    this.#context = context;
    this.#appServer.on?.("notification", (message) => {
      if (message?.method !== "thread/settings/updated") return;
      this.#capture(message.params?.threadId, message.params?.threadSettings);
    });
    this.#appServer.on?.("transportReset", () => this.#invalidateThreadState());
  }

  async start({ refresh = false } = {}) {
    if (this.#started && !refresh) return;
    if (!this.#starting) {
      this.#starting = this.#loadModels()
        .finally(() => { this.#starting = null; });
    }
    await this.#starting;
  }

  async projection(thread, visible = {}) {
    const rollout = await this.#context.read(thread);
    let native = null;
    if (thread?.id) {
      try {
        native = await this.#resume(thread.id);
      } catch {
        native = null;
      }
    }
    const observed = {
      model: native?.model ?? rollout?.model ?? null,
      reasoningEffort: native?.reasoningEffort ?? rollout?.reasoningEffort ?? null,
    };
    const visibleMatches = this.#matches(visible.modelLabel);
    const visibleModel = visibleMatches.length === 1 ? visibleMatches[0] : null;
    const selected = this.#models.get(observed.model) ?? visibleModel;
    const efforts = selected?.efforts ?? [];
    const visibleLevel = visible.ambiguous === true
      ? null
      : typeof visible.level === "string" ? visible.level : null;
    const level = [observed.reasoningEffort, visibleLevel]
      .find((effort) => efforts.includes(effort)) ?? null;
    return this.#view(thread?.id, selected, level, {
      ...visible,
      modelAmbiguous: visibleMatches.length > 1,
    });
  }

  async selectModel(selection) {
    const { value: modelId, threadId, effects } = mutationSelection(selection, "id");
    const current = this.#requireCurrent(threadId);
    const selected = this.#models.get(modelId);
    if (!selected) throw new Error("The requested Codex model is unavailable.");
    if (!this.#modelIdentityIsUnique(selected)) {
      throw new Error("The requested Codex model is ambiguous in the desktop model menu.");
    }
    const level = selected.efforts.includes(current.level)
      ? current.level
      : selected.defaultEffort ?? selected.efforts[0] ?? null;
    const observed = await this.#update(threadId, {
      threadId: current.threadId,
      model: modelId,
      ...(level ? { effort: level } : {}),
    }, effects);
    this.#requireObserved(observed, { model: modelId, reasoningEffort: level });
    return this.#view(threadId, selected, observed.reasoningEffort);
  }

  async modelOption(selection, { refresh = false } = {}) {
    const { value: modelId, threadId, bound } = optionSelection(selection, "id");
    await this.start({ refresh });
    if (bound) this.#requireCurrent(threadId);
    const selected = this.#models.get(modelId);
    if (!selected) throw new Error("The requested Codex model is unavailable or stale.");
    if (!this.#modelIdentityIsUnique(selected)) {
      throw new Error("The requested Codex model is ambiguous in the desktop model menu.");
    }
    return {
      id: selected.id,
      label: selected.label,
      ...(bound ? { threadId } : {}),
    };
  }

  async adjustReasoning(delta, effects) {
    const current = this.#requireCurrent();
    if (delta !== -1 && delta !== 1) throw new Error("Reasoning adjustment must be one step.");
    const index = current.efforts.indexOf(current.level);
    if (index < 0) throw new Error("The current Codex reasoning level is unresolved.");
    const level = current.efforts[index + delta];
    if (!level) throw new Error("Codex reasoning cannot move farther in that direction.");
    return this.selectReasoning({ level, threadId: current.threadId, effects });
  }

  async selectReasoning(selection) {
    const { value: level, threadId, effects } = mutationSelection(selection, "level");
    const current = this.#requireCurrent(threadId);
    if (!current.efforts.includes(level)) {
      throw new Error("The requested Codex reasoning level is unavailable or stale.");
    }
    const observed = await this.#update(threadId, {
      threadId: current.threadId,
      effort: level,
    }, effects);
    this.#requireObserved(observed, { model: current.model, reasoningEffort: level });
    return this.#view(threadId, this.#models.get(observed.model), observed.reasoningEffort);
  }

  reasoningTarget(delta) {
    if ((delta !== -1 && delta !== 1) || !this.#current?.level) return null;
    const index = this.#current.efforts.indexOf(this.#current.level);
    if (index < 0) return null;
    return this.#current.efforts[index + delta] ?? null;
  }

  hasReasoningLevel(level) {
    if (level && typeof level === "object") level = level.level;
    return typeof level === "string" && this.#current?.efforts.includes(level) === true;
  }

  #view(threadId, selected, level, visible = {}) {
    const efforts = selected?.efforts ?? [];
    const modelAmbiguous = visible.modelAmbiguous === true
      || (selected ? !this.#modelIdentityIsUnique(selected) : false);
    this.#current = threadId && selected ? { threadId, model: selected.id, efforts, level } : null;
    return {
      binding: threadId ? { threadId } : null,
      model: {
        available: Boolean(threadId && selected && this.#models.size > 0 && !modelAmbiguous),
        id: selected?.id ?? null,
        label: selected?.label ?? visible.modelLabel ?? "",
        options: [...this.#models.values()].map((model) => ({
          id: model.id,
          label: model.label,
          selected: model.id === selected?.id,
        })),
      },
      reasoning: {
        available: Boolean(threadId && level),
        label: level ? reasoningLabel(level) : visible.label ?? "",
        level,
        canIncrease: level ? efforts.indexOf(level) < efforts.length - 1 : false,
        canDecrease: level ? efforts.indexOf(level) > 0 : false,
      },
    };
  }

  #requireCurrent(expectedThreadId = null) {
    if (!this.#current?.threadId) throw new Error("No focused Codex task is available for settings changes.");
    if (expectedThreadId && this.#current.threadId !== expectedThreadId) {
      throw new Error("The focused Codex task changed before its settings mutation.");
    }
    return this.#current;
  }

  async #resume(threadId) {
    if (this.#threads.has(threadId)) return this.#threads.get(threadId);
    let pending = this.#loading.get(threadId);
    if (!pending) {
      pending = this.#appServer.request("thread/resume", { threadId })
        .then((result) => this.#capture(threadId, result))
        .finally(() => {
          if (this.#loading.get(threadId) === pending) this.#loading.delete(threadId);
        });
      this.#loading.set(threadId, pending);
    }
    return pending;
  }

  async #update(threadId, params, effects) {
    try {
      await effects.commit(() => this.#appServer.request("thread/settings/update", params));
    } finally {
      this.#dropThread(threadId);
    }
    return this.#readFresh(threadId);
  }

  async #readFresh(threadId) {
    const pending = this.#appServer.request("thread/resume", { threadId });
    this.#loading.set(threadId, pending);
    try {
      const observed = observedSettings(await pending);
      this.#threads.set(threadId, observed);
      return observed;
    } finally {
      if (this.#loading.get(threadId) === pending) this.#loading.delete(threadId);
    }
  }

  #dropThread(threadId) {
    this.#threads.delete(threadId);
    this.#loading.delete(threadId);
    if (this.#current?.threadId === threadId) this.#current = null;
  }

  #requireObserved(actual, expected) {
    if (
      actual.model !== expected.model
      || (expected.reasoningEffort && actual.reasoningEffort !== expected.reasoningEffort)
    ) {
      throw new Error("Codex did not confirm the requested settings for the bound task.");
    }
  }

  #invalidateThreadState() {
    this.#current = null;
    this.#threads.clear();
    this.#loading.clear();
  }

  async #loadModels() {
    const result = await this.#appServer.request("model/list", { limit: 50 });
    const models = new Map();
    for (const entry of result.data ?? []) {
      if (!entry?.id || entry.hidden === true) continue;
      models.set(entry.id, {
        id: entry.id,
        label: displayName(entry),
        defaultEffort: entry.defaultReasoningEffort,
        efforts: entry.supportedReasoningEfforts
          ?.map(({ reasoningEffort }) => reasoningEffort)
          .filter((effort) => typeof effort === "string" && effort.length > 0) ?? [],
      });
    }
    this.#models = models;
    this.#started = true;
  }

  #capture(threadId, value) {
    if (typeof threadId !== "string" || !value) return null;
    const previous = this.#threads.get(threadId) ?? {};
    const next = {
      model: typeof value.model === "string" ? value.model : previous.model ?? null,
      reasoningEffort: typeof value.effort === "string"
        ? value.effort
        : typeof value.reasoningEffort === "string"
          ? value.reasoningEffort
          : previous.reasoningEffort ?? null,
    };
    this.#threads.set(threadId, next);
    return next;
  }

  #matches(label) {
    const candidate = canonical(label);
    if (!candidate) return [];
    return [...this.#models.values()].filter((model) => (
      canonical(model.id) === candidate || canonical(model.label) === candidate
    ));
  }

  #modelIdentityIsUnique(selected) {
    const candidates = new Set([canonical(selected.id), canonical(selected.label)].filter(Boolean));
    if (candidates.size === 0) return false;
    return [...this.#models.values()].filter((model) => (
      candidates.has(canonical(model.id)) || candidates.has(canonical(model.label))
    )).length === 1;
  }
}

function optionSelection(selection, property) {
  if (typeof selection === "string") {
    return { value: selection, threadId: null, bound: false };
  }
  const bound = boundSelection(selection, property);
  return { ...bound, bound: true };
}

function mutationSelection(selection, property) {
  const bound = boundSelection(selection, property);
  if (!selection.effects || typeof selection.effects.commit !== "function") {
    throw new Error("A settings effect boundary is required.");
  }
  return { ...bound, effects: selection.effects };
}

function boundSelection(selection, property) {
  const value = selection?.[property];
  const threadId = selection?.threadId;
  if (typeof value !== "string" || !value || typeof threadId !== "string" || !threadId) {
    throw new Error("A bound Codex settings mutation is required.");
  }
  return { value, threadId };
}

function observedSettings(value) {
  return {
    model: typeof value?.model === "string" ? value.model : null,
    reasoningEffort: typeof value?.effort === "string"
      ? value.effort
      : typeof value?.reasoningEffort === "string" ? value.reasoningEffort : null,
  };
}

function reasoningLabel(value) {
  return ({
    minimal: "Minimal",
    low: "Low",
    medium: "Medium",
    high: "High",
    xhigh: "Extra high",
    max: "Max",
    ultra: "Ultra",
  })[value] ?? value;
}

function displayName(entry) {
  const label = typeof entry.displayName === "string" && entry.displayName.trim()
    ? entry.displayName.trim()
    : entry.id;
  return label.replace(/^GPT-/i, "").replaceAll("-", " ").slice(0, 80);
}

function canonical(value) {
  return typeof value === "string"
    ? value.toLowerCase().replace(/^gpt/, "").replaceAll(/[^a-z0-9]/g, "")
    : "";
}
