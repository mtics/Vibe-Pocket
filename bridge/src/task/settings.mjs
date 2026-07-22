import { Context } from "./context.mjs";
import { validateTargetRef } from "./target.mjs";

export class Settings {
  #appServer;
  #context;
  #models = new Map();
  #generation = 0;
  #started = false;
  #starting = null;
  #current = null;
  #threads = new Map();
  #confirmed = new Map();
  #observationSequence = 0;
  #now;
  #settingsFreshnessMs;
  #observationGraceMs;

  constructor({
    appServer,
    context = new Context(),
    now = Date.now,
    settingsFreshnessMs = 5_000,
    observationGraceMs = 3_000,
  }) {
    this.#appServer = appServer;
    this.#context = context;
    this.#now = now;
    this.#settingsFreshnessMs = settingsFreshnessMs;
    this.#observationGraceMs = observationGraceMs;
    this.#appServer.on?.("notification", (message) => {
      if (message?.method !== "thread/settings/updated") return;
      const threadId = message.params?.threadId;
      if (typeof threadId !== "string" || !threadId) return;
      this.#capture(threadId, message.params?.threadSettings);
    });
    this.#appServer.on?.("transportReset", () => this.#invalidateState());
  }

  async start({ refresh = false } = {}) {
    while (true) {
      if (this.#started && !refresh) return;
      const generation = this.#generation;
      let pending = this.#starting;
      if (!pending) {
        pending = this.#loadCatalogs(generation)
          .finally(() => {
            if (this.#starting === pending) this.#starting = null;
          });
        this.#starting = pending;
      }
      try {
        await pending;
      } catch (error) {
        if (generation === this.#generation) throw error;
      }
      if (generation === this.#generation) return;
      refresh = false;
    }
  }

  async projection(thread, visible = {}, target = null) {
    while (true) {
      await this.start();
      const generation = this.#generation;
      const rollout = await this.#context.read(thread);
      if (generation !== this.#generation) continue;
      const observation = await this.#observation(thread?.id, rollout);
      if (generation !== this.#generation) continue;
      const observed = observation.value;
      const visibleMatches = this.#matches(visible.modelLabel);
      const visibleModel = visibleMatches.length === 1 ? visibleMatches[0] : null;
      const selected = this.#models.get(observed?.model) ?? visibleModel;
      const efforts = selected?.efforts ?? [];
      const visibleLevel = visible.ambiguous === true
        ? null
        : typeof visible.level === "string" ? visible.level : null;
      const level = [observed?.reasoningEffort, visibleLevel]
        .find((effort) => efforts.includes(effort)) ?? null;
      return this.#view(target, selected, level, observed?.mode ?? modeFromLabel(visible.modeLabel), {
        ...visible,
        fresh: Boolean(target && thread?.id === target.threadId && observation.fresh),
        modelAmbiguous: visibleMatches.length > 1,
      });
    }
  }

  async selectModel(selection) {
    const { value: modelId, target, effects } = mutationSelection(selection, "id");
    const current = this.#requireCurrent(target);
    const selected = this.#models.get(modelId);
    if (!selected) throw new Error("The requested Codex model is unavailable.");
    if (!this.#modelIdentityIsUnique(selected)) {
      throw new Error("The requested Codex model is ambiguous in the desktop model menu.");
    }
    const level = selected.efforts.includes(current.level)
      ? null
      : selected.defaultEffort ?? selected.efforts[0] ?? null;
    const observed = await this.#update(target, {
      threadId: target.threadId,
      model: modelId,
      ...(level ? { effort: level } : {}),
    }, effects, current.generation, {
      model: modelId,
      ...(level ? { reasoningEffort: level } : {}),
    });
    return this.#view(target, selected, observed.reasoningEffort, observed.mode ?? current.mode);
  }

  async modelOption(selection, { refresh = false } = {}) {
    const { value: modelId, target, bound } = optionSelection(selection, "id");
    await this.start({ refresh });
    if (bound) this.#requireCurrent(target);
    const selected = this.#models.get(modelId);
    if (!selected) throw new Error("The requested Codex model is unavailable or stale.");
    if (!this.#modelIdentityIsUnique(selected)) {
      throw new Error("The requested Codex model is ambiguous in the desktop model menu.");
    }
    return {
      id: selected.id,
      label: selected.label,
      ...(bound ? { target } : {}),
    };
  }

  async adjustReasoning(delta, target, effects) {
    const current = this.#requireCurrent(target);
    if (delta !== -1 && delta !== 1) throw new Error("Reasoning adjustment must be one step.");
    const index = current.efforts.indexOf(current.level);
    if (index < 0) throw new Error("The current Codex reasoning level is unresolved.");
    const level = current.efforts[index + delta];
    if (!level) throw new Error("Codex reasoning cannot move farther in that direction.");
    return this.selectReasoning({ level, target, effects });
  }

  async selectReasoning(selection) {
    const { value: level, target, effects } = mutationSelection(selection, "level");
    const current = this.#requireCurrent(target);
    if (!current.efforts.includes(level)) {
      throw new Error("The requested Codex reasoning level is unavailable or stale.");
    }
    const observed = await this.#update(target, {
      threadId: target.threadId,
      effort: level,
    }, effects, current.generation, {
      model: current.model,
      reasoningEffort: level,
    });
    return this.#view(
      target,
      this.#models.get(observed.model),
      observed.reasoningEffort,
      observed.mode ?? current.mode,
    );
  }

  async selectMode() {
    throw new Error("Codex mode selection is disabled by the protocol v12 safety policy.");
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

  #view(target, selected, level, modeId, visible = {}) {
    const efforts = selected?.efforts ?? [];
    const fresh = visible.fresh !== false;
    const mutable = Boolean(fresh && target);
    const modelAmbiguous = visible.modelAmbiguous === true
      || (selected ? !this.#modelIdentityIsUnique(selected) : false);
    this.#current = mutable && selected
      ? { generation: this.#generation, target, model: selected.id, efforts, level, mode: modeId }
      : null;
    return {
      target,
      fresh,
      mode: {
        available: false,
        id: modeId,
        label: modeLabel(modeId) || visible.modeLabel || "",
        options: [],
      },
      model: {
        available: Boolean(mutable && selected && this.#models.size > 0 && !modelAmbiguous),
        id: selected?.id ?? null,
        label: selected?.label ?? visible.modelLabel ?? "",
        options: [...this.#models.values()].map((model) => ({
          id: model.id,
          label: model.label,
          selected: model.id === selected?.id,
        })),
      },
      reasoning: {
        available: Boolean(mutable && level),
        label: level ? reasoningLabel(level) : visible.label ?? "",
        level,
        canIncrease: level ? efforts.indexOf(level) < efforts.length - 1 : false,
        canDecrease: level ? efforts.indexOf(level) > 0 : false,
        increaseTo: level ? efforts[efforts.indexOf(level) + 1] ?? null : null,
        decreaseTo: level ? efforts[efforts.indexOf(level) - 1] ?? null : null,
        options: efforts,
      },
    };
  }

  #requireCurrent(expectedTarget, expectedGeneration = this.#generation) {
    const target = validateTargetRef(expectedTarget);
    if (expectedGeneration !== this.#generation) {
      throw new Error("The app-server restarted before its settings mutation was applied.");
    }
    if (!this.#current?.target || this.#current.generation !== this.#generation) {
      throw new Error("No focused Codex task is available for settings changes.");
    }
    for (const [key, value] of Object.entries(target)) {
      if (this.#current.target[key] !== value) {
        throw new Error(`The bound Codex target changed at ${key} before its settings mutation.`);
      }
    }
    return this.#current;
  }

  async #update(target, params, effects, generation, expected) {
    const threadId = target.threadId;
    const confirmationSequence = await effects.commit(async () => {
      const update = async () => {
        this.#requireCurrent(target, generation);
        await this.#appServer.request("thread/settings/update", params);
        return this.#observationSequence;
      };
      try {
        return await update();
      } catch (error) {
        if (!threadNotLoaded(error)) throw error;
        this.#capture(threadId, await this.#appServer.request("thread/resume", { threadId }));
        return update();
      }
    });
    if (generation !== this.#generation) {
      throw new Error("The app-server restarted before its settings mutation was confirmed.");
    }

    let observation = this.#threads.get(threadId);
    if (!(observation?.sequence > confirmationSequence && settingsMatch(observation.value, expected))) {
      const resumeSequence = observation?.sequence ?? 0;
      const resumed = await this.#appServer.request("thread/resume", { threadId });
      if (generation !== this.#generation) {
        throw new Error("The app-server restarted before its settings mutation was confirmed.");
      }
      const latest = this.#threads.get(threadId);
      if (!latest || latest.sequence === resumeSequence) this.#capture(threadId, resumed);
      observation = this.#threads.get(threadId);
    }
    if (!(observation?.sequence > confirmationSequence && settingsMatch(observation.value, expected))) {
      throw new Error("Codex did not confirm the requested settings for the bound task.");
    }

    const observed = {
      ...observation.value,
      mode: observation.value.mode ?? this.#current?.mode ?? null,
    };
    this.#confirmed.set(threadId, {
      value: observed,
      expiresAt: this.#now() + this.#observationGraceMs,
    });
    return observed;
  }

  #invalidateState() {
    this.#generation += 1;
    this.#current = null;
    this.#threads.clear();
    this.#confirmed.clear();
    this.#observationSequence = 0;
    this.#models.clear();
    this.#started = false;
    this.#starting = null;
  }

  async #loadCatalogs(generation) {
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
    if (generation !== this.#generation) return;
    this.#models = models;
    this.#started = true;
  }

  #capture(threadId, value, generation = this.#generation) {
    if (generation !== this.#generation || typeof threadId !== "string" || !value) return null;
    const previous = this.#threads.get(threadId)?.value ?? {};
    const next = {
      model: typeof value.model === "string" ? value.model : previous.model ?? null,
      reasoningEffort: typeof value.effort === "string"
        ? value.effort
        : typeof value.reasoningEffort === "string"
          ? value.reasoningEffort
          : previous.reasoningEffort ?? null,
      mode: modeId(value) ?? previous.mode ?? null,
    };
    this.#observationSequence += 1;
    this.#threads.set(threadId, {
      value: next,
      sequence: this.#observationSequence,
      expiresAt: this.#now() + this.#settingsFreshnessMs,
    });
    return next;
  }

  async #observation(threadId, rollout) {
    if (typeof threadId !== "string" || !threadId) {
      return { value: rollout, fresh: Boolean(rollout) };
    }
    const confirmed = this.#confirmed.get(threadId);
    if (confirmed) {
      if (settingsMatch(rollout, confirmed.value)) {
        this.#confirmed.delete(threadId);
        return { value: rollout, fresh: true };
      }
      if (this.#now() < confirmed.expiresAt) return { value: confirmed.value, fresh: true };
      this.#confirmed.delete(threadId);
    }
    if (rollout) return { value: rollout, fresh: true };

    const cached = this.#threads.get(threadId);
    if (cached && this.#now() < cached.expiresAt) {
      return { value: cached.value, fresh: true };
    }
    this.#threads.delete(threadId);
    return { value: null, fresh: false };
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
    return { value: selection, target: null, bound: false };
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
  if (typeof value !== "string" || !value) {
    throw new Error("A bound Codex settings mutation is required.");
  }
  return { value, target: validateTargetRef(selection?.target) };
}

function modeId(value) {
  const mode = value?.collaborationMode?.mode ?? value?.collaboration_mode?.mode;
  return mode === "default" || mode === "plan" ? mode : null;
}

function modeLabel(value) {
  return value === "plan" ? "Plan" : value === "default" ? "Default" : "";
}

function modeFromLabel(value) {
  const label = typeof value === "string" ? value.trim().toLowerCase() : "";
  return label === "plan" ? "plan" : label === "default" ? "default" : null;
}

function settingsMatch(actual, expected) {
  return Object.entries(expected).every(([key, value]) => value == null || actual?.[key] === value);
}

function threadNotLoaded(error) {
  return /thread not found/i.test(error?.message ?? "");
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
