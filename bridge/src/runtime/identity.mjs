import { createHash } from "node:crypto";
import { realpathSync } from "node:fs";
import { lstat, readFile, readdir, rename, rm, writeFile } from "node:fs/promises";
import { join, relative, resolve, sep } from "node:path";
import { isDeepStrictEqual } from "node:util";
import { fileURLToPath } from "node:url";

const HASH_DOMAIN = Buffer.from("vibe-pocket-runtime-identity-v1\0");
const IDENTITY_PATTERN = /^sha256:[0-9a-f]{64}$/;
const MAX_MANIFEST_BYTES = 512;
const MAX_READY_BYTES = 4 * 1024;

export const RUNTIME_IDENTITY_FILE = "runtime-identity.json";
export const RUNTIME_IDENTITY_SCHEMA_VERSION = 1;
export const SERVICE_NAME = "vibe-pocket-bridge";

export async function computeRuntimeIdentity(root) {
  const runtimeRoot = resolve(root);
  const files = await collectFiles(runtimeRoot);
  const hash = createHash("sha256");
  hash.update(HASH_DOMAIN);
  for (const { path, absolutePath } of files) {
    updateFrame(hash, Buffer.from(path, "utf8"));
    updateFrame(hash, await readFile(absolutePath));
  }
  return `sha256:${hash.digest("hex")}`;
}

export async function writeRuntimeIdentity(root) {
  const runtimeRoot = resolve(root);
  const manifestPath = join(runtimeRoot, RUNTIME_IDENTITY_FILE);
  await removeExistingManifest(manifestPath);
  const runtimeIdentity = await computeRuntimeIdentity(runtimeRoot);
  const manifest = {
    schemaVersion: RUNTIME_IDENTITY_SCHEMA_VERSION,
    runtimeIdentity,
  };
  const temporaryPath = `${manifestPath}.${process.pid}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(manifest)}\n`, { flag: "wx" });
  try {
    await rename(temporaryPath, manifestPath);
  } catch (error) {
    await rm(temporaryPath, { force: true });
    throw error;
  }
  return runtimeIdentity;
}

export async function readRuntimeIdentity(root) {
  const runtimeRoot = resolve(root);
  const manifestPath = join(runtimeRoot, RUNTIME_IDENTITY_FILE);
  const entry = await lstat(manifestPath);
  if (!entry.isFile() || entry.size > MAX_MANIFEST_BYTES) {
    throw new Error("Runtime identity manifest is not a bounded regular file.");
  }
  const manifest = parseManifest(await readFile(manifestPath, "utf8"));
  const observed = await computeRuntimeIdentity(runtimeRoot);
  if (manifest.runtimeIdentity !== observed) {
    throw new Error("Runtime identity does not match the deployed runtime contents.");
  }
  return manifest.runtimeIdentity;
}

export async function resolveRuntimeIdentity(root) {
  try {
    return await readRuntimeIdentity(root);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    return computeRuntimeIdentity(root);
  }
}

export function readyPayload(runtimeIdentity, protocolVersion) {
  assertRuntimeIdentity(runtimeIdentity);
  if (!Number.isSafeInteger(protocolVersion) || protocolVersion < 1) {
    throw new TypeError("Protocol version must be a positive integer.");
  }
  return {
    ok: true,
    service: SERVICE_NAME,
    runtimeIdentity,
    protocolVersion,
  };
}

export function readyPayloadMatches(expected, observed) {
  try {
    validateReadyPayload(expected);
    validateReadyPayload(observed);
    return isDeepStrictEqual(observed, expected);
  } catch {
    return false;
  }
}

async function collectFiles(root, directory = root) {
  const entries = await readdir(directory, { withFileTypes: true });
  entries.sort((left, right) => compareNames(left.name, right.name));
  const files = [];
  for (const entry of entries) {
    const absolutePath = join(directory, entry.name);
    const path = relative(root, absolutePath).split(sep).join("/");
    if (path === RUNTIME_IDENTITY_FILE) continue;
    if (entry.isSymbolicLink()) {
      throw new Error(`Runtime identity cannot include symbolic links: ${path}`);
    }
    if (entry.isDirectory()) {
      files.push(...await collectFiles(root, absolutePath));
      continue;
    }
    if (!entry.isFile()) {
      throw new Error(`Runtime identity cannot include special files: ${path}`);
    }
    files.push({ path, absolutePath });
  }
  return files;
}

function compareNames(left, right) {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

function updateFrame(hash, value) {
  const length = Buffer.allocUnsafe(8);
  length.writeBigUInt64BE(BigInt(value.length));
  hash.update(length);
  hash.update(value);
}

async function removeExistingManifest(manifestPath) {
  try {
    const entry = await lstat(manifestPath);
    if (!entry.isFile()) throw new Error("Runtime identity manifest must be a regular file.");
    await rm(manifestPath);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }
}

function parseManifest(source) {
  let manifest;
  try {
    manifest = JSON.parse(source);
  } catch (error) {
    throw new Error("Runtime identity manifest is not valid JSON.", { cause: error });
  }
  if (!isPlainObject(manifest)
    || !hasExactKeys(manifest, ["runtimeIdentity", "schemaVersion"])
    || manifest.schemaVersion !== RUNTIME_IDENTITY_SCHEMA_VERSION) {
    throw new Error("Runtime identity manifest has an unsupported structure.");
  }
  assertRuntimeIdentity(manifest.runtimeIdentity);
  return manifest;
}

function validateReadyPayload(payload) {
  if (!isPlainObject(payload)
    || !hasExactKeys(payload, ["ok", "protocolVersion", "runtimeIdentity", "service"])
    || payload.ok !== true
    || payload.service !== SERVICE_NAME) {
    throw new TypeError("Readiness payload has an unexpected structure.");
  }
  assertRuntimeIdentity(payload.runtimeIdentity);
  if (!Number.isSafeInteger(payload.protocolVersion) || payload.protocolVersion < 1) {
    throw new TypeError("Readiness payload has an invalid protocol version.");
  }
}

function assertRuntimeIdentity(value) {
  if (typeof value !== "string" || !IDENTITY_PATTERN.test(value)) {
    throw new TypeError("Runtime identity must be a bounded SHA-256 identity.");
  }
}

function isPlainObject(value) {
  if (value == null || typeof value !== "object" || Array.isArray(value)) return false;
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function hasExactKeys(value, expected) {
  const keys = Object.keys(value).sort(compareNames);
  return keys.length === expected.length && keys.every((key, index) => key === expected[index]);
}

async function readStandardInput() {
  const chunks = [];
  let size = 0;
  for await (const chunk of process.stdin) {
    size += chunk.length;
    if (size > MAX_READY_BYTES) throw new Error("Readiness response exceeds the allowed size.");
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function cli(args) {
  const [command, rootOrExpected, ...rest] = args;
  if (rest.length > 0) throw new Error("Unexpected runtime identity arguments.");
  if (command === "write" && rootOrExpected) {
    process.stdout.write(`${await writeRuntimeIdentity(rootOrExpected)}\n`);
    return;
  }
  if (command === "expected" && rootOrExpected) {
    const [{ PROTOCOL_VERSION }, runtimeIdentity] = await Promise.all([
      import("../protocol.mjs"),
      readRuntimeIdentity(rootOrExpected),
    ]);
    process.stdout.write(`${JSON.stringify(readyPayload(runtimeIdentity, PROTOCOL_VERSION))}\n`);
    return;
  }
  if (command === "matches" && rootOrExpected) {
    let expected;
    let observed;
    try {
      expected = JSON.parse(rootOrExpected);
      observed = JSON.parse(await readStandardInput());
    } catch {
      process.exitCode = 1;
      return;
    }
    if (!readyPayloadMatches(expected, observed)) process.exitCode = 1;
    return;
  }
  throw new Error("usage: identity.mjs {write ROOT|expected ROOT|matches EXPECTED_JSON}");
}

if (process.argv[1]
  && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  await cli(process.argv.slice(2));
}
