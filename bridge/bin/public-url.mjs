#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";

const port = Number.parseInt(process.argv[2] ?? process.env.VIBE_POCKET_PORT ?? "4320", 10);
const override = process.env.VIBE_POCKET_PUBLIC_URL;
if (override) {
  console.log(normalize(override));
  process.exit(0);
}

const candidates = [
  process.env.VIBE_POCKET_TAILSCALE,
  "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
  "/opt/homebrew/bin/tailscale",
  "/usr/local/bin/tailscale",
].filter(Boolean);

for (const executable of candidates) {
  if (!existsSync(executable)) continue;
  try {
    const output = execFileSync(executable, ["serve", "status", "--json"], { encoding: "utf8" });
    const status = JSON.parse(output);
    const matches = [];
    for (const [authority, web] of Object.entries(status.Web ?? {})) {
      const proxy = web.Handlers?.["/"]?.Proxy;
      if (proxy && targetsPort(proxy, port)) matches.push(normalize(`https://${authority}`));
    }
    if (matches.length === 1) {
      console.log(matches[0]);
      process.exit(0);
    }
    if (matches.length > 1) throw new Error("Multiple Tailscale Serve origins target the Vibe Pocket Bridge.");
  } catch {
    // Try the next known Tailscale CLI location.
  }
}

process.exitCode = 1;

function targetsPort(value, expectedPort) {
  try {
    const url = new URL(value);
    const actualPort = Number.parseInt(url.port || (url.protocol === "https:" ? "443" : "80"), 10);
    return ["127.0.0.1", "localhost", "::1"].includes(url.hostname) && actualPort === expectedPort;
  } catch {
    return false;
  }
}

function normalize(value) {
  const url = new URL(value);
  if (url.protocol !== "https:" || !url.hostname || url.username || url.password || url.search || url.hash) {
    throw new Error("VIBE_POCKET_PUBLIC_URL must be an HTTPS origin.");
  }
  return url.origin;
}
