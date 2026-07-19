import { Context } from "./context.mjs";

export class Settings {
  #appServer;
  #context;
  #models = new Map();
  #started = false;
  #starting = null;
  #current = null;
  #confirmed = new Map();
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
    const confirmed = thread?.id ? this.#confirmed.get(thread.id) : null;
    this.#settle(thread?.id, confirmed, observed);
    const visibleModel = this.#match(visible.modelLabel);
    const selected = visibleModel
      ?? this.#models.get(confirmed?.model ?? observed?.model);
    const efforts = selected?.efforts ?? [];
    const visibleLevel = visible.ambiguous === true
      ? null
      : typeof visible.level === "string" ? visible.level : null;
    const level = [visibleLevel, confirmed?.reasoningEffort, observed?.reasoningEffort]
      .find((effort) => efforts.includes(effort)) ?? null;
    return this.#view(thread?.id, selected, level, visible);
  }

  async selectModel(modelId) {
    const current = this.#requireCurrent();
    await this.#resume(current.threadId);
    const selected = this.#models.get(modelId);
    if (!selected) throw new Error("The requested Codex model is unavailable.");
    const level = selected.efforts.includes(current.level)
      ? current.level
      : selected.defaultEffort ?? selected.efforts[0] ?? null;
    await this.#appServer.request("thread/settings/update", {
      threadId: current.threadId,
      model: modelId,
      ...(level ? { effort: level } : {}),
    });
    this.#capture(current.threadId, { model: modelId, effort: level });
    this.#confirmed.set(current.threadId, { model: modelId, reasoningEffort: level });
    return this.#view(current.threadId, selected, level);
  }

  async modelOption(modelId, { refresh = false } = {}) {
    await this.start({ refresh });
    const selected = this.#models.get(modelId);
    if (!selected) throw new Error("The requested Codex model is unavailable or stale.");
    return { id: selected.id, label: selected.label };
  }

  async adjustReasoning(delta) {
    const current = this.#requireCurrent();
    if (delta !== -1 && delta !== 1) throw new Error("Reasoning adjustment must be one step.");
    const index = current.efforts.indexOf(current.level);
    if (index < 0) throw new Error("The current Codex reasoning level is unresolved.");
    const level = current.efforts[index + delta];
    if (!level) throw new Error("Codex reasoning cannot move farther in that direction.");
    return this.selectReasoning(level);
  }

  async selectReasoning(level) {
    const current = this.#requireCurrent();
    await this.#resume(current.threadId);
    if (!current.efforts.includes(level)) {
      throw new Error("The requested Codex reasoning level is unavailable or stale.");
    }
    await this.#appServer.request("thread/settings/update", {
      threadId: current.threadId,
      effort: level,
    });
    this.#capture(current.threadId, { model: current.model, effort: level });
    this.#confirmed.set(current.threadId, {
      ...this.#confirmed.get(current.threadId),
      reasoningEffort: level,
    });
    return this.#view(current.threadId, this.#models.get(current.model), level);
  }

  reasoningTarget(delta) {
    if ((delta !== -1 && delta !== 1) || !this.#current?.level) return null;
    const index = this.#current.efforts.indexOf(this.#current.level);
    if (index < 0) return null;
    return this.#current.efforts[index + delta] ?? null;
  }

  hasReasoningLevel(level) {
    return typeof level === "string" && this.#current?.efforts.includes(level) === true;
  }

  #view(threadId, selected, level, visible = {}) {
    const efforts = selected?.efforts ?? [];
    this.#current = threadId && selected ? { threadId, model: selected.id, efforts, level } : null;
    return {
      model: {
        available: Boolean(threadId && selected && this.#models.size > 0),
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

  #requireCurrent() {
    if (!this.#current?.threadId) throw new Error("No focused Codex task is available for settings changes.");
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

  #invalidateThreadState() {
    this.#current = null;
    this.#confirmed.clear();
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

  #settle(threadId, confirmed, observed) {
    if (!threadId || !confirmed || !observed) return;
    if (confirmed.model === observed.model) delete confirmed.model;
    if (confirmed.reasoningEffort === observed.reasoningEffort) delete confirmed.reasoningEffort;
    if (!confirmed.model && !confirmed.reasoningEffort) this.#confirmed.delete(threadId);
  }

  #match(label) {
    const candidate = canonical(label);
    if (!candidate) return null;
    return [...this.#models.values()].find((model) => (
      canonical(model.id) === candidate || canonical(model.label) === candidate
    )) ?? null;
  }
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
