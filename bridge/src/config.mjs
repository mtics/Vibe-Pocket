import { fileURLToPath } from "node:url";
import { existsSync, lstatSync, realpathSync } from "node:fs";
import { basename, dirname, isAbsolute, join, relative, resolve, sep } from "node:path";

import { defaultPath } from "./profile/store.mjs";

const DEFAULT_PORT = 4320;
const BRIDGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PROFILE_FORBIDDEN_ROOT = realpathSync(findRepositoryRoot(BRIDGE_ROOT) ?? BRIDGE_ROOT);

export function load(environment = process.env, cwd = process.cwd()) {
  const token = environment.VIBE_POCKET_TOKEN;
  if (!token || token.length < 24) {
    throw new Error(
      "VIBE_POCKET_TOKEN must be set to a random value of at least 24 characters.",
    );
  }

  const host = environment.VIBE_POCKET_HOST ?? "127.0.0.1";
  const port = Number.parseInt(environment.VIBE_POCKET_PORT ?? `${DEFAULT_PORT}`, 10);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("VIBE_POCKET_PORT must be a valid TCP port.");
  }

  const workspaces = parseWorkspaces(environment.VIBE_POCKET_WORKSPACES, environment.VIBE_POCKET_WORKSPACE, cwd);
  const codexCommand = environment.VIBE_POCKET_CODEX_COMMAND ?? "codex";
  const profilePath = parseProfilePath(environment.VIBE_POCKET_PROFILE_PATH, environment);
  const ownedThreadsPath = parseProfilePath(
    environment.VIBE_POCKET_OWNED_THREADS_PATH ?? join(dirname(profilePath), "owned-threads.json"),
    environment,
  );
  const pairingSocketPath = join(dirname(profilePath), "pairing.sock");
  const devicesPath = join(dirname(profilePath), "paired-devices.json");
  return { host, port, token, workspaces, codexCommand, profilePath, ownedThreadsPath, pairingSocketPath, devicesPath };
}

function parseProfilePath(value, environment) {
  const configuredPath = value || defaultPath({ environment });
  if (!isAbsolute(configuredPath)) {
    throw new Error("VIBE_POCKET_PROFILE_PATH must be an absolute path outside the repository.");
  }
  const profilePath = resolve(configuredPath);
  const canonicalPath = resolveFromExistingAncestor(profilePath);
  if (isWithin(PROFILE_FORBIDDEN_ROOT, canonicalPath)) {
    throw new Error("VIBE_POCKET_PROFILE_PATH must be outside the Vibe Pocket repository.");
  }
  return profilePath;
}

function resolveFromExistingAncestor(path) {
  let candidate = path;
  const suffix = [];
  while (true) {
    try {
      return resolve(realpathSync(candidate), ...suffix.reverse());
    } catch (error) {
      if (error?.code !== "ENOENT" && error?.code !== "ENOTDIR") {
        throw new Error("VIBE_POCKET_PROFILE_PATH could not be resolved safely.", { cause: error });
      }
      let existingEntry;
      try {
        existingEntry = lstatSync(candidate);
      } catch (statError) {
        if (statError?.code !== "ENOENT" && statError?.code !== "ENOTDIR") throw statError;
      }
      if (existingEntry?.isSymbolicLink()) {
        throw new Error("VIBE_POCKET_PROFILE_PATH contains an unresolved symbolic link.");
      }
      const parent = dirname(candidate);
      if (parent === candidate) throw error;
      suffix.push(basename(candidate));
      candidate = parent;
    }
  }
}

function isWithin(root, destination) {
  const rootRelativePath = relative(root, destination);
  return rootRelativePath === "" || (
    !isAbsolute(rootRelativePath)
    && rootRelativePath !== ".."
    && !rootRelativePath.startsWith(`..${sep}`)
  );
}

function findRepositoryRoot(start) {
  let candidate = start;
  while (true) {
    if (existsSync(join(candidate, ".git"))) return candidate;
    const parent = dirname(candidate);
    if (parent === candidate) return null;
    candidate = parent;
  }
}

function parseWorkspaces(value, fallback, cwd) {
  if (!value) {
    return { default: resolve(fallback ?? cwd) };
  }

  let parsed;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error("VIBE_POCKET_WORKSPACES must be a JSON object of alias-to-path entries.");
  }

  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error("VIBE_POCKET_WORKSPACES must be a JSON object of alias-to-path entries.");
  }

  const entries = Object.entries(parsed);
  if (entries.length === 0) {
    throw new Error("VIBE_POCKET_WORKSPACES must contain at least one workspace.");
  }

  return Object.fromEntries(
    entries.map(([alias, path]) => {
      if (!/^[a-zA-Z0-9_-]{1,48}$/.test(alias) || typeof path !== "string" || path.length === 0) {
        throw new Error("Each workspace needs a simple alias and a non-empty path.");
      }
      return [alias, resolve(path)];
    }),
  );
}
