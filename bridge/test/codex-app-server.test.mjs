import assert from "node:assert/strict";
import test from "node:test";
import { EventEmitter } from "node:events";
import { PassThrough } from "node:stream";

import { CodexAppServer } from "../src/codex-app-server.mjs";

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

test("initializes and routes JSON-RPC responses", async () => {
  const child = fakeProcess();
  const sent = [];
  child.stdin.on("data", (chunk) => sent.push(JSON.parse(chunk.toString())));
  const appServer = new CodexAppServer({ spawnProcess: () => child });

  const started = appServer.start();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(sent[0].method, "initialize");
  child.stdout.write(`${JSON.stringify({ id: sent[0].id, result: { serverInfo: {} } })}\n`);
  await started;

  const listed = appServer.request("thread/list", { limit: 3 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(sent[1].method, "thread/list");
  child.stdout.write(`${JSON.stringify({ id: sent[1].id, result: { data: [] } })}\n`);
  assert.deepEqual(await listed, { data: [] });
});

test("forwards server requests and supports a response", async () => {
  const child = fakeProcess();
  const sent = [];
  child.stdin.on("data", (chunk) => sent.push(JSON.parse(chunk.toString())));
  const appServer = new CodexAppServer({ spawnProcess: () => child });

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
