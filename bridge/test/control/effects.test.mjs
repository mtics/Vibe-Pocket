import assert from "node:assert/strict";
import test from "node:test";

import { Authority, EffectBoundary } from "../../src/control/effects.mjs";

test("revalidates authority immediately before the first effect", async () => {
  let valid = true;
  let effects = 0;
  const boundary = new EffectBoundary(() => valid);
  const authority = new Authority((operation) => operation(), boundary);

  valid = false;
  await assert.rejects(
    authority.immediate(async () => { effects += 1; }),
    (error) => error.status === 401 && error.code === "credential_revoked",
  );
  assert.equal(effects, 0);
  assert.equal(boundary.crossed, false);
});

test("allows one immediate effect and rejects a second crossing", async () => {
  const boundary = new EffectBoundary();
  const authority = new Authority((operation) => operation(), boundary);

  assert.equal(await authority.immediate(async () => "done"), "done");
  assert.equal(boundary.crossed, true);
  await assert.rejects(
    authority.immediate(async () => "again"),
    /may cross its effect boundary only once/,
  );
});

test("lets an effect-aware executor cross only at its queued effect", async () => {
  let valid = true;
  let effects = 0;
  const boundary = new EffectBoundary(() => valid);
  const authority = new Authority((operation) => operation(), boundary);
  const queued = Promise.withResolvers();

  const execution = authority.effect(async (effectsBoundary) => {
    await queued.promise;
    return effectsBoundary.commit(async () => { effects += 1; });
  });
  valid = false;
  queued.resolve();

  await assert.rejects(
    execution,
    (error) => error.status === 401 && error.code === "credential_revoked",
  );
  assert.equal(effects, 0);
  assert.equal(boundary.crossed, false);
});

test("requires deferred mutations to cross their supplied boundary", async () => {
  const boundary = new EffectBoundary();
  const authority = new Authority((operation) => operation(), boundary);

  await assert.rejects(
    authority.deferred(async () => ({ settings: {} })),
    /completed without crossing its effect boundary/,
  );
  assert.equal(boundary.crossed, false);
});

test("accepts a deferred mutation only after its exact effect commits", async () => {
  const boundary = new EffectBoundary();
  const authority = new Authority((operation) => operation(), boundary);
  let effects = 0;

  const result = await authority.deferred(async (effectsBoundary) => {
    await effectsBoundary.commit(async () => { effects += 1; });
    return { settings: { model: "gpt-test" } };
  });

  assert.deepEqual(result, { settings: { model: "gpt-test" } });
  assert.equal(effects, 1);
  assert.equal(boundary.crossed, true);
});
