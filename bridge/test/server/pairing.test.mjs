import assert from "node:assert/strict";
import test from "node:test";

import { Invitations } from "../../src/pairing/invitations.mjs";

const TOKEN = "test-token-with-at-least-24-characters";
const DEVICE_TOKEN = "vp1.testdevice.abcdefghijklmnopqrstuvwxyzABCDEFG";
const CODE = "a".repeat(43);
const NONCE = "b".repeat(43);

test("pairing invitations contain only a short-lived claim code", () => {
  const pairing = new Invitations({ issue: () => DEVICE_TOKEN, now: () => 1_000, random: () => CODE });
  const invitation = pairing.create("https://M5.example.ts.net/");
  const url = new URL(invitation.pairingUrl);

  assert.equal(url.protocol, "vibepocket:");
  assert.equal(url.host, "pair");
  assert.equal(url.searchParams.get("origin"), "https://m5.example.ts.net");
  assert.equal(url.searchParams.get("code"), CODE);
  assert.equal(url.searchParams.get("expiresAt"), "1970-01-01T00:05:01.000Z");
  assert.equal(invitation.pairingUrl.includes(TOKEN), false);
  assert.equal(invitation.expiresAt, "1970-01-01T00:05:01.000Z");
});

test("a lost claim response retries the same credential only for the same phone nonce", () => {
  const issued = [];
  const pairing = new Invitations({
    issue: (expiresAt) => {
      issued.push(expiresAt);
      return DEVICE_TOKEN;
    },
    now: () => 1_000,
    random: () => CODE,
  });
  pairing.create("https://m5.example.ts.net");

  const expected = {
    baseUrl: "https://m5.example.ts.net",
    token: DEVICE_TOKEN,
    credentialState: "pending",
    credentialExpiresAt: "1970-01-01T00:05:01.000Z",
    protocolVersion: 7,
    capabilities: ["device_credentials", "events", "virtual_hardware", "pairing_commit"],
  };
  assert.deepEqual(pairing.claim(CODE, NONCE), expected);
  assert.deepEqual(pairing.claim(CODE, NONCE), expected);
  assert.deepEqual(issued, ["1970-01-01T00:05:01.000Z"]);
  assert.throws(() => pairing.claim(CODE, "c".repeat(43)), /expired or has already been used/);
});

test("expired invitations and non-HTTPS origins are rejected", () => {
  let now = 1_000;
  const pairing = new Invitations({ issue: () => DEVICE_TOKEN, now: () => now, random: () => CODE });
  pairing.create("https://m5.example.ts.net", 100);
  now = 1_100;

  assert.throws(() => pairing.claim(CODE, NONCE), /expired or has already been used/);
  assert.throws(() => pairing.create("http://m5.example.ts.net"), /HTTPS Bridge origin/);
  assert.throws(() => pairing.create("https://m5.example.ts.net/path"), /HTTPS Bridge origin/);
});

test("claiming requires a strong phone nonce", () => {
  const pairing = new Invitations({ issue: () => DEVICE_TOKEN, now: () => 1_000, random: () => CODE });
  pairing.create("https://m5.example.ts.net");

  assert.throws(() => pairing.claim(CODE, "short"), /request identity is invalid/);
});
