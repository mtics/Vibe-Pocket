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

  issue() {
    const id = this.randomId();
    const secret = this.randomSecret();
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id) || !/^[A-Za-z0-9_-]{32,128}$/.test(secret)) {
      throw new Error("The device credential generator returned invalid material.");
    }
    const credential = `vp1.${id}.${secret}`;
    const next = new Map(this.#devices);
    next.set(id, {
      digest: digest(credential),
      createdAt: new Date(this.now()).toISOString(),
    });
    while (next.size > MAXIMUM_DEVICES) next.delete(next.keys().next().value);
    this.#commit(this.path, next);
    this.#devices = next;
    return credential;
  }

  accepts(candidate) {
    return this.resolve(candidate) != null;
  }

  resolve(candidate) {
    if (typeof candidate !== "string") return null;
    if (equal(candidate, this.rootToken)) {
      return Object.freeze({
        id: `credential:${digest(candidate)}`,
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
      revocable: true,
      valid: () => {
        const current = this.#devices.get(deviceId);
        return current != null && equal(candidateDigest, current.digest);
      },
    });
  }

  revoke(candidate) {
    if (typeof candidate !== "string") return false;
    const match = candidate.match(/^vp1\.([A-Za-z0-9_-]{8,64})\.([A-Za-z0-9_-]{32,128})$/);
    if (!match || !this.accepts(candidate) || !this.#devices.has(match[1])) return false;
    const next = new Map(this.#devices);
    next.delete(match[1]);
    this.#commit(this.path, next);
    this.#devices = next;
    return true;
  }

  #load() {
    if (!existsSync(this.path)) return new Map();
    try {
      const parsed = JSON.parse(readFileSync(this.path, "utf8"));
      if (parsed.version !== 1 || !Array.isArray(parsed.devices)) return new Map();
      const entries = parsed.devices.filter((device) => (
        device
        && typeof device.id === "string"
        && /^[A-Za-z0-9_-]{8,64}$/.test(device.id)
        && typeof device.digest === "string"
        && /^[A-Za-z0-9_-]{43}$/.test(device.digest)
        && typeof device.createdAt === "string"
      ));
      return new Map(entries.slice(-MAXIMUM_DEVICES).map(({ id, digest, createdAt }) => [id, { digest, createdAt }]));
    } catch {
      return new Map();
    }
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
    writeFileSync(descriptor, `${JSON.stringify({ version: 1, devices }, null, 2)}\n`, "utf8");
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
