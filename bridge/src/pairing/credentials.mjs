import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

const MAXIMUM_DEVICES = 24;

export class Credentials {
  constructor({
    path,
    rootToken,
    now = Date.now,
    randomId = () => randomBytes(9).toString("base64url"),
    randomSecret = () => randomBytes(32).toString("base64url"),
  }) {
    this.path = path;
    this.rootToken = rootToken;
    this.now = now;
    this.randomId = randomId;
    this.randomSecret = randomSecret;
    this.devices = this.#load();
  }

  issue() {
    const id = this.randomId();
    const secret = this.randomSecret();
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(id) || !/^[A-Za-z0-9_-]{32,128}$/.test(secret)) {
      throw new Error("The device credential generator returned invalid material.");
    }
    const credential = `vp1.${id}.${secret}`;
    this.devices.set(id, {
      digest: digest(credential),
      createdAt: new Date(this.now()).toISOString(),
    });
    while (this.devices.size > MAXIMUM_DEVICES) this.devices.delete(this.devices.keys().next().value);
    this.#persist();
    return credential;
  }

  accepts(candidate) {
    if (typeof candidate !== "string") return false;
    if (equal(candidate, this.rootToken)) return true;
    const match = candidate.match(/^vp1\.([A-Za-z0-9_-]{8,64})\.([A-Za-z0-9_-]{32,128})$/);
    if (!match) return false;
    const device = this.devices.get(match[1]);
    return device != null && equal(digest(candidate), device.digest);
  }

  revoke(candidate) {
    if (typeof candidate !== "string") return false;
    const match = candidate.match(/^vp1\.([A-Za-z0-9_-]{8,64})\.([A-Za-z0-9_-]{32,128})$/);
    if (!match || !this.accepts(candidate) || !this.devices.delete(match[1])) return false;
    this.#persist();
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

  #persist() {
    mkdirSync(dirname(this.path), { recursive: true, mode: 0o700 });
    const temporary = `${this.path}.${process.pid}.tmp`;
    const devices = [...this.devices].map(([id, value]) => ({ id, ...value }));
    writeFileSync(temporary, `${JSON.stringify({ version: 1, devices }, null, 2)}\n`, { mode: 0o600 });
    renameSync(temporary, this.path);
    chmodSync(this.path, 0o600);
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
