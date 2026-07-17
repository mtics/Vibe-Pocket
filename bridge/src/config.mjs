import { resolve } from "node:path";

const DEFAULT_PORT = 4318;

export function loadConfig(environment = process.env, cwd = process.cwd()) {
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
  return { host, port, token, workspaces, codexCommand };
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
