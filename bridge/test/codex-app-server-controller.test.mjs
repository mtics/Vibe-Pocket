import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";

import { CodexAppServerController } from "../src/codex-app-server-controller.mjs";

class FakeAppServer extends EventEmitter {
  calls = [];
  responses = [];
  threads = [];
  nextThread = 1;
  nextTurn = 1;
  stopped = false;
  completeTurnBeforeStartReturns = false;
  settings = new Map();

  async start() { this.calls.push({ method: "initialize" }); }
  async stop() { this.stopped = true; }

  async request(method, params) {
    this.calls.push({ method, params });
    if (method === "model/list") {
      return {
        data: [{
          id: "gpt-test",
          isDefault: true,
          defaultReasoningEffort: "medium",
          supportedReasoningEfforts: ["low", "medium", "high"].map((reasoningEffort) => ({ reasoningEffort })),
        }],
      };
    }
    if (method === "permissionProfile/list") {
      return {
        data: [":read-only", ":workspace", ":danger-full-access"].map((id) => ({ id, allowed: true })),
      };
    }
    if (method === "collaborationMode/list") {
      return {
        data: [
          { name: "Default", mode: "default", model: null, reasoning_effort: null },
          { name: "Plan", mode: "plan", model: null, reasoning_effort: "medium" },
        ],
      };
    }
    if (method === "thread/list") {
      const roots = this.threads.filter((thread) => thread.sourceKind === "appServer");
      const agents = this.threads.filter((thread) => thread.sourceKind?.startsWith("subAgent"));
      return { data: params.sourceKinds.includes("appServer") ? roots : agents };
    }
    if (method === "thread/read") {
      const thread = this.threads.find(({ id }) => id === params.threadId);
      if (!thread) throw new Error("Unknown fake thread");
      return { thread };
    }
    if (method === "thread/start") {
      const thread = ownedThread({
        id: `thread-${this.nextThread++}`,
        cwd: params.cwd,
        preview: "Untitled task",
        sessionId: `session-${this.nextThread}`,
        threadSource: params.threadSource,
      });
      this.threads.unshift(thread);
      const threadSettings = {
        model: "gpt-test",
        effort: "medium",
        activePermissionProfile: { id: params.permissions },
        approvalPolicy: params.approvalPolicy,
        collaborationMode: params.collaborationMode,
      };
      this.settings.set(thread.id, threadSettings);
      return {
        thread,
        model: "gpt-test",
        reasoningEffort: "medium",
        activePermissionProfile: { id: params.permissions },
        approvalPolicy: params.approvalPolicy,
      };
    }
    if (method === "thread/resume") {
      const thread = this.threads.find(({ id }) => id === params.threadId);
      if (!thread) throw new Error("Unknown fake thread");
      const settings = this.settings.get(thread.id) ?? {
        model: "gpt-test",
        reasoningEffort: "medium",
        activePermissionProfile: { id: ":workspace" },
        collaborationMode: { mode: "default", settings: { model: "gpt-test" } },
      };
      return { thread, ...settings };
    }
    if (method === "thread/settings/update") {
      const current = this.settings.get(params.threadId) ?? {
        model: "gpt-test",
        effort: "medium",
        activePermissionProfile: { id: ":workspace" },
      };
      const threadSettings = {
        ...current,
        effort: params.effort ?? current.effort,
        approvalPolicy: params.approvalPolicy ?? current.approvalPolicy,
        activePermissionProfile: params.permissions
          ? { id: params.permissions }
          : current.activePermissionProfile,
        collaborationMode: params.collaborationMode ?? current.collaborationMode,
      };
      this.settings.set(params.threadId, threadSettings);
      return { threadSettings };
    }
    if (method === "turn/start") {
      const turn = { id: `turn-${this.nextTurn++}` };
      if (this.completeTurnBeforeStartReturns) {
        this.emit("notification", {
          method: "turn/completed",
          params: { threadId: params.threadId, turn: { ...turn, status: "completed" } },
        });
      }
      return { turn };
    }
    if (method === "turn/steer") return { turnId: params.expectedTurnId };
    if (method === "turn/interrupt") return {};
    throw new Error(`Unexpected method ${method}`);
  }

  respond(id, result) { this.responses.push({ id, result }); }
  respondWithError(id, code, message) { this.responses.push({ id, error: { code, message } }); }
}

class FakeOwnershipStore {
  constructor(threadIds = []) { this.threadIds = [...threadIds]; }
  async load() { return [...this.threadIds]; }
  async add(threadId) {
    this.threadIds = [threadId, ...this.threadIds.filter((id) => id !== threadId)];
    return [...this.threadIds];
  }
  async replace(threadIds) {
    this.threadIds = [...threadIds];
    return [...this.threadIds];
  }
}

function ownedThread(overrides = {}) {
  return {
    id: "thread-a",
    cwd: "/Users/lizhw/Project",
    name: null,
    preview: "Vibe Pocket task",
    status: { type: "idle" },
    sourceKind: "appServer",
    threadSource: "vibePocket",
    sessionId: "session-a",
    updatedAt: 1,
    ...overrides,
  };
}

function makeController(appServer = new FakeAppServer(), ownedThreadIds = []) {
  const openedThreads = [];
  return {
    appServer,
    openedThreads,
    ownershipStore: new FakeOwnershipStore(ownedThreadIds),
    controller: new CodexAppServerController({
      appServer,
      workspaces: { project: "/Users/lizhw/Project" },
      ownershipStore: new FakeOwnershipStore(ownedThreadIds),
      openThread: async (threadId) => { openedThreads.push(threadId); },
    }),
  };
}

test("maps only owned tasks and their subagents to stable Agent keys", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [
    ownedThread({ id: "thread-a", name: "Review API", updatedAt: 3 }),
    ownedThread({
      id: "thread-child",
      parentThreadId: "thread-a",
      agentNickname: "Scout",
      sourceKind: "subAgent",
      threadSource: null,
      status: { type: "active", activeFlags: [] },
      updatedAt: 2,
    }),
    ownedThread({ id: "thread-other", name: "Unowned", threadSource: "other", updatedAt: 4 }),
    ownedThread({ id: "thread-outside", cwd: "/tmp/private", updatedAt: 5 }),
  ];
  const { controller } = makeController(appServer, ["thread-a"]);

  const first = await controller.status();
  const second = await controller.status();

  assert.equal(first.agents.length, 2);
  assert.match(first.agents[0].id, /^agent-[a-f0-9]{24}$/);
  assert.equal(first.agents[0].id, second.agents[0].id);
  assert.equal(first.agents[0].focused, true);
  assert.equal(first.agents[1].label, "Scout");
  assert.equal(first.agents[1].state, "thinking");
});

test("opens the exact Codex desktop thread selected by Agent controls", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [
    ownedThread({ id: "thread-a", name: "First", updatedAt: 2 }),
    ownedThread({ id: "thread-b", name: "Second", sessionId: "session-b", updatedAt: 1 }),
  ];
  const { controller, openedThreads } = makeController(appServer, ["thread-a", "thread-b"]);

  const snapshot = await controller.status();
  await controller.attach();
  await controller.navigate("down");
  await controller.focusAgent(snapshot.agents[0].id);

  assert.deepEqual(openedThreads, ["thread-a", "thread-b", "thread-a"]);
});

test("submits phone dictation with direct turn APIs and selected reasoning", async () => {
  const { appServer, controller } = makeController();
  await controller.status();
  await controller.setDictationDraft("Inspect the failing test.");
  await controller.adjustReasoning(1);
  await controller.press("approve");

  const settings = appServer.calls.findLast(({ method }) => method === "thread/settings/update");
  const turn = appServer.calls.findLast(({ method }) => method === "turn/start");
  assert.equal(settings.params.effort, "high");
  assert.deepEqual(turn.params.input, [{ type: "text", text: "Inspect the failing test." }]);
  assert.equal(turn.params.effort, "high");
  assert.equal((await controller.status()).controls["clear-input"], false);
});

test("cycles collaboration mode and access independently before a workflow", async () => {
  const { appServer, controller } = makeController();
  await controller.status();
  await controller.attach();
  await controller.cycleMode();
  assert.equal((await controller.status()).mode.label, "Plan");
  assert.equal((await controller.status()).access.label, "Workspace");
  assert.equal(appServer.calls.findLast(({ method }) => method === "thread/settings/update").params.collaborationMode.mode, "plan");
  await controller.cycleAccess();
  assert.equal((await controller.status()).access.label, "Full access");

  await controller.workflow("Review this change.");

  const start = appServer.calls.findLast(({ method }) => method === "thread/start");
  const turn = appServer.calls.findLast(({ method }) => method === "turn/start");
  assert.equal(start.params.permissions, ":danger-full-access");
  assert.equal(start.params.approvalPolicy, "never");
  assert.deepEqual(turn.params.input, [{ type: "text", text: "Review this change." }]);
});

test("resolves command and permission approvals with their distinct schemas", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [ownedThread()];
  const { controller } = makeController(appServer, ["thread-a"]);
  await controller.status();
  appServer.emit("serverRequest", {
    id: "approval-1",
    method: "item/commandExecution/requestApproval",
    params: {
      threadId: "thread-a",
      turnId: "turn-a",
      availableDecisions: ["accept", "decline"],
    },
  });

  assert.equal((await controller.status()).taskState, "waiting");
  await controller.press("approve");
  assert.deepEqual(appServer.responses.at(-1), { id: "approval-1", result: { decision: "accept" } });

  appServer.emit("serverRequest", {
    id: "approval-2",
    method: "item/permissions/requestApproval",
    params: { threadId: "thread-a", turnId: "turn-a", permissions: { network: { enabled: true } } },
  });
  await controller.press("reject");
  assert.deepEqual(appServer.responses.at(-1), {
    id: "approval-2",
    result: { permissions: {}, scope: "turn" },
  });
});

test("navigates and answers Codex user-input requests through JSON-RPC", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [ownedThread()];
  const { controller } = makeController(appServer, ["thread-a"]);
  await controller.status();
  appServer.emit("serverRequest", {
    id: "question-1",
    method: "item/tool/requestUserInput",
    params: {
      threadId: "thread-a",
      turnId: "turn-a",
      itemId: "item-a",
      questions: [
        {
          id: "scope",
          header: "Scope",
          question: "Which implementation scope should Codex use?",
          options: [
            { label: "Focused", description: "Change only the affected module." },
            { label: "Broad", description: "Include adjacent cleanup." },
          ],
        },
        {
          id: "tests",
          header: "Tests",
          question: "How much verification should Codex run?",
          options: [
            { label: "Targeted", description: "Run focused tests." },
            { label: "Full", description: "Run the complete suite." },
          ],
        },
      ],
    },
  });

  let snapshot = await controller.status();
  assert.equal(snapshot.taskState, "waiting");
  assert.equal(snapshot.controls.approve, true);
  assert.equal(snapshot.controls.navigate, true);
  assert.deepEqual(snapshot.userInput, {
    questionIndex: 0,
    questionCount: 2,
    header: "Scope",
    question: "Which implementation scope should Codex use?",
    options: [
      { label: "Focused", description: "Change only the affected module." },
      { label: "Broad", description: "Include adjacent cleanup." },
    ],
    selectedOptionIndex: 0,
    hasSpokenAnswer: false,
    isSecret: false,
  });

  await controller.navigate("down");
  await controller.navigate("right");
  await controller.navigate("down");
  snapshot = await controller.status();
  assert.equal(snapshot.userInput.header, "Tests");
  assert.equal(snapshot.userInput.selectedOptionIndex, 1);

  await controller.press("approve");
  assert.deepEqual(appServer.responses.at(-1), {
    id: "question-1",
    result: {
      answers: {
        scope: { answers: ["Broad"] },
        tests: { answers: ["Full"] },
      },
    },
  });
  assert.equal((await controller.status()).userInput, null);
});

test("uses phone dictation for free-form Codex questions and clears it safely", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [ownedThread()];
  const { controller } = makeController(appServer, ["thread-a"]);
  await controller.status();
  appServer.emit("serverRequest", {
    id: "question-voice",
    method: "item/tool/requestUserInput",
    params: {
      threadId: "thread-a",
      turnId: "turn-a",
      itemId: "item-a",
      questions: [{
        id: "details",
        header: "Details",
        question: "What behavior do you want?",
        options: null,
      }],
    },
  });

  await controller.setDictationDraft("Keep the public API unchanged.");
  assert.equal((await controller.status()).userInput.hasSpokenAnswer, true);
  assert.equal((await controller.status()).controls["clear-input"], true);
  await controller.clearInput();
  assert.equal((await controller.status()).userInput.hasSpokenAnswer, false);
  await controller.setDictationDraft("Preserve the current API.");
  await controller.press("approve");
  assert.deepEqual(appServer.responses.at(-1), {
    id: "question-voice",
    result: { answers: { details: { answers: ["Preserve the current API."] } } },
  });

  appServer.emit("serverRequest", {
    id: "question-reject",
    method: "item/tool/requestUserInput",
    params: {
      threadId: "thread-a",
      turnId: "turn-b",
      itemId: "item-b",
      questions: [{
        id: "confirm",
        header: "Confirm",
        question: "Continue?",
        options: [{ label: "Yes", description: "Continue." }],
      }],
    },
  });
  await controller.press("reject");
  assert.deepEqual(appServer.responses.at(-1), {
    id: "question-reject",
    result: { answers: { confirm: { answers: [] } } },
  });
});

test("keeps a dictation draft bound to its original thread across focus changes", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [
    ownedThread({ id: "thread-a", name: "First", updatedAt: 2 }),
    ownedThread({ id: "thread-b", name: "Second", sessionId: "session-b", updatedAt: 1 }),
  ];
  const { controller } = makeController(appServer, ["thread-a", "thread-b"]);
  const snapshot = await controller.status();
  await controller.setDictationDraft("Stay with the first task.");
  await controller.focusAgent(snapshot.agents[1].id);
  await controller.press("approve");

  const resumeCalls = appServer.calls.filter(({ method }) => method === "thread/resume");
  assert.deepEqual(resumeCalls.map(({ params }) => params.threadId), ["thread-a", "thread-b"]);
  assert.equal(appServer.calls.findLast(({ method }) => method === "turn/start").params.threadId, "thread-a");
});

test("maps completion, unread, interruption, failure, and waiting flags", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [
    ownedThread({ id: "thread-a", updatedAt: 2 }),
    ownedThread({ id: "thread-b", sessionId: "session-b", updatedAt: 1 }),
  ];
  const { controller } = makeController(appServer, ["thread-a", "thread-b"]);
  await controller.status();
  appServer.emit("notification", {
    method: "turn/completed",
    params: { threadId: "thread-b", turn: { id: "turn-b", status: "completed" } },
  });
  assert.equal((await controller.status()).agents.find(({ focused }) => !focused).state, "unread");

  appServer.emit("notification", {
    method: "turn/completed",
    params: { threadId: "thread-a", turn: { id: "turn-a", status: "failed" } },
  });
  assert.equal((await controller.status()).taskState, "error");

  appServer.emit("notification", {
    method: "thread/status/changed",
    params: { threadId: "thread-a", status: { type: "active", activeFlags: ["waitingOnUserInput"] } },
  });
  assert.equal((await controller.status()).taskState, "waiting");

  appServer.emit("notification", {
    method: "turn/completed",
    params: { threadId: "thread-a", turn: { id: "turn-a", status: "interrupted" } },
  });
  appServer.emit("notification", {
    method: "thread/status/changed",
    params: { threadId: "thread-a", status: { type: "idle" } },
  });
  assert.equal((await controller.status()).taskState, "idle");
});

test("restores thread mode and reasoning before calculating the next step", async () => {
  const appServer = new FakeAppServer();
  appServer.threads = [ownedThread()];
  appServer.settings.set("thread-a", {
    model: "gpt-test",
    reasoningEffort: "high",
    activePermissionProfile: { id: ":danger-full-access" },
    approvalPolicy: "never",
    collaborationMode: { mode: "default", settings: { model: "gpt-test" } },
  });
  const { controller } = makeController(appServer, ["thread-a"]);

  const restored = await controller.status();
  assert.equal(restored.mode.label, "Default");
  assert.equal(restored.access.label, "Full access");
  assert.equal(restored.reasoning.label, "high");

  await controller.cycleMode();
  await controller.cycleAccess();
  await controller.adjustReasoning(1);
  const updated = await controller.status();
  assert.equal(updated.mode.label, "Plan");
  assert.equal(updated.access.label, "Read only");
  assert.equal(updated.reasoning.label, "high");
});

test("interrupts only a directly owned focused turn", async () => {
  const { appServer, controller } = makeController();
  await controller.workflow("Run tests.");
  assert.equal((await controller.status()).controls.stop, true);

  await controller.press("stop");

  const interrupt = appServer.calls.findLast(({ method }) => method === "turn/interrupt");
  assert.deepEqual(interrupt.params, { threadId: "thread-1", turnId: "turn-1" });
  await controller.dispose();
  assert.equal(appServer.stopped, true);
});

test("does not resurrect a turn that completed before turn/start returned", async () => {
  const appServer = new FakeAppServer();
  appServer.completeTurnBeforeStartReturns = true;
  const { controller } = makeController(appServer);

  await controller.workflow("Reply immediately.");
  const snapshot = await controller.status();

  assert.equal(snapshot.taskState, "complete");
  assert.equal(snapshot.controls.stop, false);
});
