import { createHash, randomBytes } from "node:crypto";

const TARGET_KEYS = Object.freeze([
  "agentId",
  "appServerGeneration",
  "bindingEpoch",
  "bridgeInstanceId",
  "canonicalWorkspaceId",
  "threadId",
]);

export class TargetBinding {
  #appServerGeneration = 0;
  #bindingEpoch = 0;
  #target = null;

  constructor({ bridgeInstanceId = `bridge-${randomBytes(16).toString("hex")}` } = {}) {
    if (typeof bridgeInstanceId !== "string" || !/^bridge-[a-zA-Z0-9_-]{16,128}$/.test(bridgeInstanceId)) {
      throw new TypeError("Bridge instance identity must be a bounded opaque ID.");
    }
    this.bridgeInstanceId = bridgeInstanceId;
  }

  get current() {
    return this.#target;
  }

  get appServerGeneration() {
    return this.#appServerGeneration;
  }

  bind({ threadId, agentId, canonicalWorkspaceId }) {
    assertTargetText(threadId, "threadId", 256);
    if (typeof agentId !== "string" || !/^agent-[a-f0-9]{24}$/.test(agentId)) {
      throw new TypeError("Target agentId must be a stable Codex agent ID.");
    }
    assertTargetText(canonicalWorkspaceId, "canonicalWorkspaceId", 160);
    this.#bindingEpoch += 1;
    this.#target = Object.freeze({
      threadId,
      agentId,
      bindingEpoch: this.#bindingEpoch,
      bridgeInstanceId: this.bridgeInstanceId,
      appServerGeneration: this.#appServerGeneration,
      canonicalWorkspaceId,
    });
    return this.#target;
  }

  transportReset() {
    this.#appServerGeneration += 1;
    this.#bindingEpoch += 1;
    this.#target = null;
  }

  require(candidate) {
    validateTargetRef(candidate);
    if (!this.#target) {
      throw new Error("No Codex task target is bound for this settings mutation.");
    }
    for (const key of TARGET_KEYS) {
      if (candidate[key] !== this.#target[key]) {
        throw new Error(`The bound Codex target changed at ${key} before its settings mutation.`);
      }
    }
    return this.#target;
  }
}

export function validateTargetRef(value) {
  if (!isPlainObject(value)) throw new TypeError("A complete Codex TargetRef is required.");
  const keys = Object.keys(value).sort();
  if (keys.length !== TARGET_KEYS.length || keys.some((key, index) => key !== TARGET_KEYS[index])) {
    throw new TypeError("Codex TargetRef contains missing or unsupported fields.");
  }
  assertTargetText(value.threadId, "threadId", 256);
  if (typeof value.agentId !== "string" || !/^agent-[a-f0-9]{24}$/.test(value.agentId)) {
    throw new TypeError("Codex TargetRef agentId is invalid.");
  }
  if (!Number.isSafeInteger(value.bindingEpoch) || value.bindingEpoch < 1) {
    throw new TypeError("Codex TargetRef bindingEpoch is invalid.");
  }
  if (typeof value.bridgeInstanceId !== "string" || !/^bridge-[a-zA-Z0-9_-]{16,128}$/.test(value.bridgeInstanceId)) {
    throw new TypeError("Codex TargetRef bridgeInstanceId is invalid.");
  }
  if (!Number.isSafeInteger(value.appServerGeneration) || value.appServerGeneration < 0) {
    throw new TypeError("Codex TargetRef appServerGeneration is invalid.");
  }
  assertTargetText(value.canonicalWorkspaceId, "canonicalWorkspaceId", 160);
  return Object.freeze({ ...value });
}

export function canonicalWorkspaceId({ canonicalPath, dev, ino }) {
  const material = `${canonicalPath}\0${dev}\0${ino}`;
  return `workspace-${createHash("sha256").update(material).digest("hex").slice(0, 32)}`;
}

function assertTargetText(value, name, maximum) {
  if (
    typeof value !== "string"
    || value.length === 0
    || value.length > maximum
    || /[\u0000-\u001f\u007f]/.test(value)
  ) {
    throw new TypeError(`Codex TargetRef ${name} is invalid.`);
  }
}

function isPlainObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}
