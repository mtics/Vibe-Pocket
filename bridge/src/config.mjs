import { fileURLToPath } from "node:url";
import { existsSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";

import { defaultPath } from "./profile/store.mjs";

const DEFAULT_PORT = 4318;
const BRIDGE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const PROFILE_FORBIDDEN_ROOT = findRepositoryRoot(BRIDGE_ROOT) ?? BRIDGE_ROOT;

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
  return { host, port, token, workspaces, codexCommand, profilePath, ownedThreadsPath };
}

function parseProfilePath(value, environment) {
  if (!value) return defaultPath({ environment });
  if (!isAbsolute(value)) {
    throw new Error("VIBE_POCKET_PROFILE_PATH must be an absolute path outside the repository.");
  }
  const repositoryRelativePath = relative(PROFILE_FORBIDDEN_ROOT, value);
  if (repositoryRelativePath === "" || (!repositoryRelativePath.startsWith("..") && !isAbsolute(repositoryRelativePath))) {
    throw new Error("VIBE_POCKET_PROFILE_PATH must be outside the Vibe Pocket repository.");
  }
  return value;
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
