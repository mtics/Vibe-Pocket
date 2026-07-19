import assert from "node:assert/strict";
import test from "node:test";
import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";

import { Rpc } from "../../src/codex/rpc.mjs";

function fakeProcess() {
  const child = new EventEmitter();
  child.stdin = new PassThrough();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  child.exitCode = null;
  child.kill = () => {
    child.exitCode = 0;
    child.emit("exit", 0, null);
  };
  return child;
}

function scriptedProcess(handle) {
  const child = fakeProcess();
  child.messages = [];
  child.stdin.on("data", (chunk) => {
    const message = JSON.parse(chunk.toString());
    child.messages.push(message);
    handle(message, child);
  });
  return child;
}

function reply(child, request, result) {
  queueMicrotask(() => child.stdout.write(`${JSON.stringify({ id: request.id, result })}\n`));
}

test("initializes and routes JSON-RPC responses", async () => {
  const child = fakeProcess();
  const sent = [];
  child.stdin.on("data", (chunk) => sent.push(JSON.parse(chunk.toString())));
  const appServer = new Rpc({ spawnProcess: () => child });

  const started = appServer.start();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(sent[0].method, "initialize");
  child.stdout.write(`${JSON.stringify({ id: sent[0].id, result: { serverInfo: {} } })}\n`);
  await started;
  assert.deepEqual(sent[1], { method: "initialized", params: {} });

  const listed = appServer.request("thread/list", { limit: 3 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(sent[2].method, "thread/list");
  child.stdout.write(`${JSON.stringify({ id: sent[2].id, result: { data: [] } })}\n`);
  assert.deepEqual(await listed, { data: [] });
});

test("forwards server requests and supports a response", async () => {
  const child = fakeProcess();
  const sent = [];
  child.stdin.on("data", (chunk) => sent.push(JSON.parse(chunk.toString())));
  const appServer = new Rpc({ spawnProcess: () => child });

  const started = appServer.start();
  await new Promise((resolve) => setImmediate(resolve));
  child.stdout.write(`${JSON.stringify({ id: sent[0].id, result: {} })}\n`);
  await started;

  const request = new Promise((resolve) => appServer.once("serverRequest", resolve));
  child.stdout.write(`${JSON.stringify({
    id: "approval-1",
    method: "item/commandExecution/requestApproval",
    params: { command: "npm test" },
  })}\n`);
  const approval = await request;
  assert.equal(approval.method, "item/commandExecution/requestApproval");

  appServer.respond(approval.id, { decision: "accept" });
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(sent.at(-1), {
    id: "approval-1",
    result: { decision: "accept" },
  });
});

test("single-flights concurrent starts", async () => {
  const child = fakeProcess();
  const sent = [];
  let spawns = 0;
  child.stdin.on("data", (chunk) => sent.push(JSON.parse(chunk.toString())));
  const appServer = new Rpc({
    spawnProcess: () => { spawns += 1; return child; },
  });

  const starts = [appServer.start(), appServer.start(), appServer.start()];
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(spawns, 1);
  assert.equal(sent.filter(({ method }) => method === "initialize").length, 1);
  child.stdout.write(`${JSON.stringify({ id: sent[0].id, result: {} })}\n`);
  await Promise.all(starts);
  assert.equal(sent.filter(({ method }) => method === "initialized").length, 1);
});

test("recovers after exit and retries one in-flight read-only request", async () => {
  const children = [];
  const resets = [];
  const appServer = new Rpc({
    spawnProcess: () => {
      const generation = children.length;
      const child = scriptedProcess((message, process) => {
        if (message.method === "initialize") return reply(process, message, {});
        if (message.method === "thread/list" && generation === 0) {
          queueMicrotask(() => {
            process.exitCode = 9;
            process.emit("exit", 9, null);
          });
          return;
        }
        if (message.method === "thread/list") reply(process, message, { data: [{ id: "recovered" }] });
      });
      children.push(child);
      return child;
    },
  });
  appServer.on("transportReset", (event) => resets.push(event));

  const result = await appServer.request("thread/list", { limit: 1 });

  assert.deepEqual(result, { data: [{ id: "recovered" }] });
  assert.equal(children.length, 2);
  assert.equal(resets.length, 1);
  assert.match(resets[0].reason.message, /code 9/);
  assert.deepEqual(
    children.flatMap((child) => child.messages.filter(({ method }) => method === "thread/list")).length,
    2,
  );
});

test("recovers after a child error before the next request", async () => {
  const children = [];
  const appServer = new Rpc({
    spawnProcess: () => {
      const child = scriptedProcess((message, process) => {
        if (message.method === "initialize") reply(process, message, {});
        if (message.method === "model/list") reply(process, message, { data: [] });
      });
      children.push(child);
      return child;
    },
  });
  appServer.on("childError", () => {});
  await appServer.start();

  children[0].emit("error", new Error("broken pipe"));
  assert.deepEqual(await appServer.request("model/list"), { data: [] });
  assert.equal(children.length, 2);
});

test("restarts a hung read-only request once and bounds the retry", async () => {
  const children = [];
  let resets = 0;
  const appServer = new Rpc({
    requestTimeoutMs: 20,
    spawnProcess: () => {
      const generation = children.length;
      const child = scriptedProcess((message, process) => {
        if (message.method === "initialize") return reply(process, message, {});
        if (message.method === "thread/resume" && generation === 1) {
          reply(process, message, { model: "gpt-test" });
        }
      });
      children.push(child);
      return child;
    },
  });
  appServer.on("transportReset", () => { resets += 1; });

  assert.deepEqual(await appServer.request("thread/resume", { threadId: "thread-a" }), {
    model: "gpt-test",
  });
  assert.equal(children.length, 2);
  assert.equal(resets, 1);
  assert.equal(
    children.flatMap((child) => child.messages).filter(({ method }) => method === "thread/resume").length,
    2,
  );
});

test("times out but never retries an in-flight settings mutation", async () => {
  const children = [];
  const appServer = new Rpc({
    requestTimeoutMs: 20,
    spawnProcess: () => {
      const child = scriptedProcess((message, process) => {
        if (message.method === "initialize") reply(process, message, {});
      });
      children.push(child);
      return child;
    },
  });

  await assert.rejects(
    () => appServer.request("thread/settings/update", { threadId: "thread-a", effort: "high" }),
    /timed out.*thread\/settings\/update/i,
  );
  assert.equal(children.length, 1);
  assert.equal(
    children[0].messages.filter(({ method }) => method === "thread/settings/update").length,
    1,
  );
});
