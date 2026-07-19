const MODES = [
  { label: "Read only", permissions: ":read-only", approvalPolicy: "on-request" },
  { label: "Workspace", permissions: ":workspace", approvalPolicy: "on-request" },
  { label: "Full access", permissions: ":danger-full-access", approvalPolicy: "never" },
];
const FALLBACK_MODES = [
  { name: "Default", mode: "default", model: null, reasoning_effort: null },
  { name: "Plan", mode: "plan", model: null, reasoning_effort: "medium" },
];
const FALLBACK_EFFORTS = ["low", "medium", "high", "xhigh", "max"];

export class Settings {
  #rpc;
  #threads = new Map();
  #modes = structuredClone(FALLBACK_MODES);
  #models = new Map();
  #model = null;
  #access = 1;
  #mode = 0;
  #effort = "medium";

  constructor(rpc) {
    this.#rpc = rpc;
  }

  async start() {
    await Promise.all([this.#loadModels(), this.#loadPermissions(), this.#loadModes()]);
  }

  projection(threadId) {
    const current = this.for(threadId);
    return {
      mode: {
        available: this.#modes.length > 0,
        label: this.#modes[current.mode]?.name ?? "Default",
      },
      access: { available: true, label: MODES[current.access].label },
      reasoning: { available: true, label: current.effort },
    };
  }

  creation(cwd) {
    const current = this.for(null);
    const access = MODES[this.#access];
    return {
      request: {
        cwd,
        approvalPolicy: access.approvalPolicy,
        permissions: access.permissions,
        collaborationMode: this.#payload(this.#mode, current),
        threadSource: "vibePocket",
      },
      fallback: {
        access: this.#access,
        mode: this.#mode,
        effort: this.#effort,
        model: this.#model,
      },
    };
  }

  created(threadId, result, fallback) {
    this.capture(threadId, result, {
      ...fallback,
      effort: result.reasoningEffort ?? fallback.effort,
      model: result.model ?? fallback.model,
    });
  }

  resumed(threadId, result) {
    this.capture(threadId, result, {
      effort: result.reasoningEffort,
      model: result.model,
    });
  }

  capture(threadId, value, fallback = {}) {
    if (!threadId) return;
    const existing = this.#threads.get(threadId) ?? {};
    const permissionId = value.activePermissionProfile?.id;
    const access = MODES.findIndex(({ permissions }) => permissions === permissionId);
    const collaborationKind = value.collaborationMode?.mode ?? fallback.collaborationMode;
    const mode = this.#modes.findIndex((candidate) => candidate.mode === collaborationKind);
    const model = value.model ?? fallback.model ?? existing.model ?? this.#model;
    const effort = value.effort ?? value.reasoningEffort ?? fallback.effort ?? existing.effort
      ?? this.#models.get(model)?.defaultEffort ?? this.#effort;
    this.#threads.set(threadId, {
      model,
      effort,
      access: access >= 0 ? access : fallback.access ?? existing.access ?? this.#access,
      mode: mode >= 0 ? mode : fallback.mode ?? existing.mode ?? this.#mode,
    });
  }

  for(threadId) {
    return this.#threads.get(threadId) ?? {
      model: this.#model,
      effort: this.#effort,
      access: this.#access,
      mode: this.#mode,
    };
  }

  async cycleMode(threadId) {
    const current = this.for(threadId);
    const mode = (current.mode + 1) % this.#modes.length;
    const collaborationMode = this.#payload(mode, current);
    this.#mode = mode;
    if (threadId) {
      const result = await this.#rpc.request("thread/settings/update", { threadId, collaborationMode });
      this.capture(threadId, result.threadSettings ?? result, { mode });
    }
    return `Selected ${this.#modes[mode].name} collaboration mode.`;
  }

  async cycleAccess(threadId) {
    const current = this.for(threadId);
    const access = (current.access + 1) % MODES.length;
    this.#access = access;
    if (threadId) {
      const selected = MODES[access];
      const result = await this.#rpc.request("thread/settings/update", {
        threadId,
        permissions: selected.permissions,
        approvalPolicy: selected.approvalPolicy,
      });
      this.capture(threadId, result.threadSettings ?? result, { access });
    }
    return `Selected ${MODES[access].label} access for subsequent Codex turns.`;
  }

  async adjust(threadId, delta) {
    const current = this.for(threadId);
    const efforts = this.#models.get(current.model)?.efforts ?? FALLBACK_EFFORTS;
    const index = Math.max(0, efforts.indexOf(current.effort));
    const effort = efforts[Math.max(0, Math.min(efforts.length - 1, index + delta))];
    this.#effort = effort;
    if (threadId) {
      const result = await this.#rpc.request("thread/settings/update", { threadId, effort });
      this.capture(threadId, result.threadSettings ?? result, { effort });
    }
    return `Selected ${effort} reasoning for subsequent Codex turns.`;
  }

  #payload(index, settings) {
    const mode = this.#modes[index];
    const model = mode?.model ?? settings.model ?? this.#model;
    if (!mode || !model) throw new Error("Codex did not advertise a usable collaboration mode.");
    return {
      mode: mode.mode,
      settings: {
        model,
        reasoning_effort: settings.effort ?? mode.reasoning_effort ?? this.#effort,
        developer_instructions: null,
      },
    };
  }

  async #loadModels() {
    try {
      const result = await this.#rpc.request("model/list", { limit: 50 });
      for (const model of result.data ?? []) {
        const efforts = model.supportedReasoningEfforts
          ?.map((entry) => entry.reasoningEffort)
          .filter((value) => typeof value === "string" && value.length > 0);
        if (model.id && efforts?.length) {
          this.#models.set(model.id, {
            efforts: [...new Set(efforts)],
            defaultEffort: model.defaultReasoningEffort,
          });
        }
      }
      const model = result.data?.find((candidate) => candidate.isDefault) ?? result.data?.[0];
      if (model?.id) this.#model = model.id;
      if (model?.defaultReasoningEffort) this.#effort = model.defaultReasoningEffort;
    } catch {
      this.#models.clear();
    }
  }

  async #loadPermissions() {
    try {
      const result = await this.#rpc.request("permissionProfile/list", { limit: 50 });
      const allowed = new Set((result.data ?? []).filter(({ allowed }) => allowed).map(({ id }) => id));
      for (const mode of MODES) {
        if (!allowed.has(mode.permissions)) {
          throw new Error(`Codex permission profile ${mode.permissions} is unavailable.`);
        }
      }
    } catch (error) {
      if (error.message?.includes("unavailable")) throw error;
    }
  }

  async #loadModes() {
    try {
      const result = await this.#rpc.request("collaborationMode/list", {});
      const modes = (result.data ?? []).filter((mode) => (
        mode
        && typeof mode.name === "string"
        && (mode.mode === "default" || mode.mode === "plan")
      )).map((mode) => ({
        name: mode.name.slice(0, 64),
        mode: mode.mode,
        model: typeof mode.model === "string" ? mode.model : null,
        reasoning_effort: typeof mode.reasoning_effort === "string" ? mode.reasoning_effort : null,
      }));
      if (modes.length > 0) this.#modes = modes;
      this.#mode = Math.max(0, this.#modes.findIndex(({ mode }) => mode === "default"));
    } catch {
      this.#modes = structuredClone(FALLBACK_MODES);
    }
  }
}
