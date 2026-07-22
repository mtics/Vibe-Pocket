import assert from "node:assert/strict";
import test from "node:test";

import { Operations } from "../../src/control/operations.mjs";
import { TargetBinding } from "../../src/task/target.mjs";

const identity = "bridge-0123456789abcdef";

test("binds immutable targets and rejects epoch, instance, and generation changes", () => {
  const binding = new TargetBinding({ bridgeInstanceId: identity });
  const first = binding.bind({
    threadId: "thread-a",
    agentId: "agent-111111111111111111111111",
    canonicalWorkspaceId: "workspace-a",
  });
  assert.equal(Object.isFrozen(first), true);
  assert.equal(binding.require(first), first);
  assert.throws(() => binding.require({ ...first, bindingEpoch: first.bindingEpoch + 1 }), /bindingEpoch/);
  assert.throws(() => binding.require({ ...first, bridgeInstanceId: "bridge-fedcba9876543210" }), /bridgeInstanceId/);

  binding.transportReset();
  assert.equal(binding.current, null);
  assert.throws(() => binding.require(first), /No Codex task target/);
  const second = binding.bind({
    threadId: "thread-a",
    agentId: "agent-111111111111111111111111",
    canonicalWorkspaceId: "workspace-a",
  });
  assert.equal(second.appServerGeneration, first.appServerGeneration + 1);
});

test("idempotency keys conflict when only the target changes", () => {
  const operations = new Operations();
  const binding = new TargetBinding({ bridgeInstanceId: identity });
  const first = binding.bind({
    threadId: "thread-a",
    agentId: "agent-111111111111111111111111",
    canonicalWorkspaceId: "workspace-a",
  });
  const second = binding.bind({
    threadId: "thread-a",
    agentId: "agent-111111111111111111111111",
    canonicalWorkspaceId: "workspace-a",
  });
  operations.create("same-key", { kind: "select_model", modelId: "gpt-test", target: first }, "principal-a");
  assert.throws(
    () => operations.match("same-key", { kind: "select_model", modelId: "gpt-test", target: second }, "principal-a"),
    (error) => error.code === "idempotency_key_reused",
  );
});
