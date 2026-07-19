import { createHash, randomBytes } from "node:crypto";

import { Failure } from "../server/failure.mjs";

const DEFAULT_TTL_MILLIS = 5 * 60 * 1_000;
const MAXIMUM_INVITATIONS = 12;

export class Invitations {
  constructor({ issue, now = Date.now, random = () => randomBytes(32).toString("base64url") }) {
    if (typeof issue !== "function") throw new Error("Invitations requires a device credential issuer.");
    this.issue = issue;
    this.now = now;
    this.random = random;
    this.entries = new Map();
  }

  create(origin, ttlMillis = DEFAULT_TTL_MILLIS) {
    const baseUrl = normalizeOrigin(origin);
    const now = this.now();
    this.#prune(now);
    while (this.entries.size >= MAXIMUM_INVITATIONS) {
      this.entries.delete(this.entries.keys().next().value);
    }

    const code = this.random();
    if (!/^[A-Za-z0-9_-]{32,128}$/.test(code)) {
      throw new Error("The pairing code generator returned an invalid code.");
    }
    const expiresAt = now + ttlMillis;
    this.entries.set(digest(code), { baseUrl, expiresAt });
    const url = new URL("vibepocket://pair");
    url.searchParams.set("origin", baseUrl);
    url.searchParams.set("code", code);
    url.searchParams.set("expiresAt", new Date(expiresAt).toISOString());
    return {
      pairingUrl: url.toString(),
      expiresAt: new Date(expiresAt).toISOString(),
    };
  }

  claim(code, nonce) {
    const now = this.now();
    this.#prune(now);
    if (typeof code !== "string" || !/^[A-Za-z0-9_-]{32,128}$/.test(code)) {
      throw unavailable();
    }
    if (typeof nonce !== "string" || !/^[A-Za-z0-9_-]{16,128}$/.test(nonce)) {
      throw new Failure(400, "invalid_pairing_nonce", "The pairing request identity is invalid.");
    }
    const key = digest(code);
    const invitation = this.entries.get(key);
    if (!invitation) throw unavailable();
    const nonceDigest = digest(nonce);
    if (invitation.nonceDigest && invitation.nonceDigest !== nonceDigest) throw unavailable();
    if (!invitation.credential) {
      invitation.nonceDigest = nonceDigest;
      invitation.credential = this.issue(new Date(invitation.expiresAt).toISOString());
    }
    return {
      baseUrl: invitation.baseUrl,
      token: invitation.credential,
      credentialState: "pending",
      credentialExpiresAt: new Date(invitation.expiresAt).toISOString(),
      protocolVersion: 8,
      capabilities: ["device_credentials", "events", "virtual_hardware", "pairing_commit", "command_results"],
    };
  }

  #prune(now) {
    for (const [key, invitation] of this.entries) {
      if (invitation.expiresAt <= now) this.entries.delete(key);
    }
  }
}

function normalizeOrigin(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Failure(400, "invalid_pairing_origin", "The Bridge pairing address is invalid.");
  }
  if (
    url.protocol !== "https:"
    || !url.hostname
    || url.username
    || url.password
    || url.search
    || url.hash
    || (url.pathname !== "/" && url.pathname !== "")
  ) {
    throw new Failure(400, "invalid_pairing_origin", "Pairing requires an HTTPS Bridge origin.");
  }
  url.pathname = "";
  return url.origin;
}

function digest(code) {
  return createHash("sha256").update(code).digest("base64url");
}

function unavailable() {
  return new Failure(
    410,
    "pairing_unavailable",
    "This pairing invitation has expired or has already been used.",
  );
}
