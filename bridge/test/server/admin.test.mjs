import assert from "node:assert/strict";
import test from "node:test";

import { create } from "../../src/server/admin.mjs";
import { Invitations } from "../../src/pairing/invitations.mjs";

const TOKEN = "test-token-with-at-least-24-characters";

test("the local admin service creates pairing invitations", async () => {
  const invitations = new Invitations({ issue: () => "vp1.testdevice.abcdefghijklmnopqrstuvwxyzABCDEFG" });
  const server = create({ invitations });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  try {
    const response = await fetch(`http://127.0.0.1:${port}/v1/pairing/invitations`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ origin: "https://m5.example.ts.net" }),
    });
    assert.equal(response.status, 201);
    const invitation = await response.json();
    assert.equal(new URL(invitation.pairingUrl).protocol, "vibepocket:");
  } finally {
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
});
