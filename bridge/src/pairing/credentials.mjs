import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import {
  closeSync,
  existsSync,
  fsyncSync,
  mkdirSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { dirname } from "node:path";

import { Failure } from "../server/failure.mjs";

const MAXIMUM_DEVICES = 24;

export class Credentials {
  #devices;
  #commit;

  constructor({
    path,
    rootToken,
    now = Date.now,
    randomId = () => randomBytes(9).toString("base64url"),
    randomSecret = () => randomBytes(32).toString("base64url"),
    commit = persistCredentials,
  }) {
    this.path = path;
    this.rootToken = rootToken;
    this.now = now;
    this.randomId = randomId;
    this.randomSecret = randomSecret;
    this.#commit = commit;
    this.#devices = this.#load();
  }

  issue(expiresAt) {
    const now = this.now();
    const normalizedExpiresAt = normalizeExpiry(expiresAt);
    if (normalizedExpiresAt == null || normalizedExpiresAt <= now) {
      throw new Failure(410, "pairing_unavailable", "This pairing invitation has expired or is invalid.");
    }
    const next = new Map([...this.#devices].filter(([, device]) => isValid(device, now)));
    if (next.size >= MAXIMUM_DEVICES) {
      if (next.size !== this.#devices.size) {
        this.#commit(this.path, next);
        this.#devices = next;
      }
      throw capacityFailure();
    }
    const id = this.randomId();
    const secret = this.randomSecret();
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id) || !/^[A-Za-z0-9_-]{32,128}$/.test(secret)) {
      throw new Error("The device credential generator returned invalid material.");
    }
    if (next.has(id)) {
      throw new Failure(503, "credential_id_unavailable", "The Bridge could not allocate a device credential.");
    }
    const credential = `vp1.${id}.${secret}`;
    next.set(id, {
      digest: digest(credential),
      createdAt: new Date(now).toISOString(),
      state: "pending",
      expiresAt: new Date(normalizedExpiresAt).toISOString(),
    });
    this.#commit(this.path, next);
    this.#devices = next;
    return credential;
  }

  accepts(candidate) {
    return this.resolve(candidate)?.valid() === true;
  }

  resolve(candidate) {
    if (typeof candidate !== "string") return null;
    if (equal(candidate, this.rootToken)) {
      return Object.freeze({
        id: `credential:${digest(candidate)}`,
        role: "root",
        state: "active",
        revocable: false,
        valid: () => true,
      });
    }
    const match = candidate.match(/^vp1\.([A-Za-z0-9_-]{8,64})\.([A-Za-z0-9_-]{32,128})$/);
    if (!match) return null;
    const candidateDigest = digest(candidate);
    const device = this.#devices.get(match[1]);
    if (device == null || !equal(candidateDigest, device.digest)) return null;
    const deviceId = match[1];
    return Object.freeze({
      id: `credential:${candidateDigest}`,
      role: "device",
      state: device.state,
      expiresAt: device.state === "pending" ? device.expiresAt : null,
      revocable: true,
      valid: () => {
        const current = this.#devices.get(deviceId);
        return current != null && equal(candidateDigest, current.digest) && isValid(current, this.now());
      },
    });
  }

  activate(candidate) {
    const resolved = this.#device(candidate);
    if (!resolved) return false;
    const { id, device } = resolved;
    if (device.state === "active") return true;
    if (!isValid(device, this.now())) return false;
    const next = new Map(this.#devices);
    next.set(id, { digest: device.digest, createdAt: device.createdAt, state: "active" });
    this.#commit(this.path, next);
    this.#devices = next;
    return true;
  }

  revoke(candidate) {
    if (typeof candidate !== "string") return false;
    const resolved = this.#device(candidate);
    if (!resolved) return false;
    const next = new Map(this.#devices);
    next.delete(resolved.id);
    this.#commit(this.path, next);
    this.#devices = next;
    return true;
  }

  #load() {
    if (!existsSync(this.path)) return new Map();
    let parsed;
    try {
      parsed = JSON.parse(readFileSync(this.path, "utf8"));
    } catch {
      return new Map();
    }
    if (![1, 2].includes(parsed.version) || !Array.isArray(parsed.devices)) return new Map();
    const now = this.now();
    let changed = parsed.version === 1;
    const entries = new Map();
    for (const device of parsed.devices) {
      const normalized = normalizeDevice(device, parsed.version);
      if (!normalized || !isValid(normalized.value, now)) {
        changed = true;
        continue;
      }
      if (entries.has(normalized.id)) changed = true;
      entries.set(normalized.id, normalized.value);
    }
    if (entries.size > MAXIMUM_DEVICES) throw capacityFailure();
    if (changed) this.#commit(this.path, entries);
    return entries;
  }

  #device(candidate) {
    if (typeof candidate !== "string") return null;
    const match = candidate.match(/^vp1\.([A-Za-z0-9_-]{8,64})\.([A-Za-z0-9_-]{32,128})$/);
    if (!match) return null;
    const device = this.#devices.get(match[1]);
    if (!device || !equal(digest(candidate), device.digest)) return null;
    return { id: match[1], device };
  }
}

export function persistCredentials(path, entries, { sync = fsyncSync } = {}) {
  const directory = dirname(path);
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const temporary = `${path}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  const devices = [...entries].map(([id, value]) => ({ id, ...value }));
  let descriptor = null;
  let directoryDescriptor = null;
  let renamed = false;
  try {
    descriptor = openSync(temporary, "wx", 0o600);
    writeFileSync(descriptor, `${JSON.stringify({ version: 2, devices }, null, 2)}\n`, "utf8");
    sync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, path);
    renamed = true;
    try {
      directoryDescriptor = openSync(directory, "r");
      sync(directoryDescriptor);
    } catch {
      // The rename is the commit point. A directory sync failure must not
      // leave runtime state behind the replacement already visible on disk.
    }
  } finally {
    if (descriptor != null) closeSync(descriptor);
    if (directoryDescriptor != null) {
      try {
        closeSync(directoryDescriptor);
      } catch {
        if (!renamed) throw new Error("Could not close the credential directory.");
      }
    }
    if (!renamed) rmSync(temporary, { force: true });
  }
}

function digest(value) {
  return createHash("sha256").update(value).digest("base64url");
}

function equal(candidate, expected) {
  const left = Buffer.from(candidate);
  const right = Buffer.from(expected);
  return left.length === right.length && timingSafeEqual(left, right);
}

function normalizeDevice(device, version) {
  if (
    !device
    || typeof device.id !== "string"
    || !/^[A-Za-z0-9_-]{8,64}$/.test(device.id)
    || typeof device.digest !== "string"
    || !/^[A-Za-z0-9_-]{43}$/.test(device.digest)
    || typeof device.createdAt !== "string"
    || !Number.isFinite(Date.parse(device.createdAt))
  ) return null;
  const state = version === 1 ? "active" : device.state;
  if (state === "active") {
    return { id: device.id, value: { digest: device.digest, createdAt: device.createdAt, state } };
  }
  const expiresAt = normalizeExpiry(device.expiresAt);
  if (state !== "pending" || expiresAt == null) return null;
  return {
    id: device.id,
    value: {
      digest: device.digest,
      createdAt: device.createdAt,
      state,
      expiresAt: new Date(expiresAt).toISOString(),
    },
  };
}

function normalizeExpiry(value) {
  const timestamp = typeof value === "number" ? value : Date.parse(value);
  return Number.isFinite(timestamp) ? timestamp : null;
}

function isValid(device, now) {
  return device.state === "active" || (device.state === "pending" && Date.parse(device.expiresAt) > now);
}

function capacityFailure() {
  return new Failure(409, "device_capacity_reached", "The Bridge has reached its paired device capacity.");
}
