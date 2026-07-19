import { dictation, printable } from "./text.mjs";

const APPROVALS = new Set([
  "item/commandExecution/requestApproval",
  "item/fileChange/requestApproval",
  "item/permissions/requestApproval",
]);
const USER_INPUT = "item/tool/requestUserInput";

export class Intent {
  #rpc;
  #approvals = new Map();
  #inputs = new Map();
  #voice = false;
  #draft = null;

  constructor(rpc) {
    this.#rpc = rpc;
  }

  get voice() {
    return { available: true, active: this.#voice };
  }

  get draft() {
    return this.#draft;
  }

  status(threadId) {
    const approval = this.approval(threadId);
    const input = this.input(threadId);
    const question = this.#question(input);
    return {
      approval,
      input,
      hasDraft: Boolean(this.#draft?.text),
      hasAnswer: Boolean(question && input.customAnswers.has(question.id)),
      userInput: this.#public(input),
    };
  }

  waiting(threadId) {
    return Boolean(this.approval(threadId) || this.input(threadId));
  }

  approval(threadId) {
    return [...this.#approvals.values()].find((approval) => approval.threadId === threadId) ?? null;
  }

  input(threadId) {
    return [...this.#inputs.values()].find((input) => input.threadId === threadId) ?? null;
  }

  setVoice(active) {
    this.#voice = active;
    return active ? "Started phone dictation." : "Stopped phone dictation.";
  }

  dictate(text, threadId) {
    if (!dictation(text)) {
      throw new Error("Phone dictation must contain printable non-empty text up to 12,000 characters.");
    }
    const input = this.input(threadId);
    if (input) {
      const question = this.#question(input);
      input.customAnswers.set(question.id, text.trim());
      this.#voice = false;
      return `Stored a spoken answer for ${question.header}. Press Accept to send it.`;
    }
    this.#draft = { text: text.trim(), threadId };
    this.#voice = false;
    return "Stored phone dictation for the focused task. Press Accept to send it.";
  }

  clear(threadId) {
    const input = this.input(threadId);
    const question = this.#question(input);
    if (question && input.customAnswers.delete(question.id)) {
      return `Cleared the spoken answer for ${question.header}.`;
    }
    this.#draft = null;
    return "Cleared the pending phone dictation.";
  }

  navigate(threadId, direction) {
    const input = this.input(threadId);
    if (!input) return null;
    if (direction === "left" || direction === "right") {
      const delta = direction === "left" ? -1 : 1;
      input.activeQuestionIndex = wrap(input.activeQuestionIndex + delta, input.questions.length);
      const question = this.#question(input);
      return `Selected question ${input.activeQuestionIndex + 1}: ${question.header}.`;
    }

    const question = this.#question(input);
    if (question.options.length === 0) {
      throw new Error("This Codex question needs a spoken answer. Hold Voice, then press Accept.");
    }
    const delta = direction === "up" ? -1 : 1;
    const selected = input.selectedOptionIndexes.get(question.id) ?? 0;
    const next = wrap(selected + delta, question.options.length);
    input.selectedOptionIndexes.set(question.id, next);
    input.customAnswers.delete(question.id);
    return `Selected ${question.options[next].label} for ${question.header}.`;
  }

  accept(threadId) {
    const approval = this.approval(threadId);
    if (approval) {
      this.#respondApproval(approval, true);
      return "Approved the focused Codex request.";
    }
    const input = this.input(threadId);
    if (input) {
      this.#respondInput(input, true);
      return "Answered the focused Codex question.";
    }
    return null;
  }

  reject(threadId) {
    const approval = this.approval(threadId);
    if (approval) {
      this.#respondApproval(approval, false);
      return "Rejected the focused Codex request.";
    }
    const input = this.input(threadId);
    if (input) {
      this.#respondInput(input, false);
      return "Dismissed the focused Codex question.";
    }
    if (this.#draft) {
      this.#draft = null;
      return "Discarded the pending phone dictation.";
    }
    return null;
  }

  submitted() {
    this.#draft = null;
  }

  receive(message) {
    if (message.method === USER_INPUT) {
      const input = normalize(message);
      if (!input) {
        this.#rpc.respondWithError(message.id, -32602, "Invalid Codex user-input request.");
        return;
      }
      this.#inputs.set(message.id, input);
      return;
    }
    if (!APPROVALS.has(message.method)) {
      this.#rpc.respondWithError(message.id, -32601, "Unsupported Vibe Pocket server request.");
      return;
    }
    this.#approvals.set(message.id, {
      requestId: message.id,
      threadId: message.params?.threadId ?? "unknown",
      turnId: message.params?.turnId ?? "unknown",
      method: message.method,
      params: message.params ?? {},
    });
  }

  resolved(requestId) {
    this.#approvals.delete(requestId);
    this.#inputs.delete(requestId);
  }

  #respondApproval(approval, accepted) {
    if (approval.method === "item/permissions/requestApproval") {
      this.#rpc.respond(approval.requestId, {
        permissions: accepted ? approval.params.permissions ?? {} : {},
        scope: "turn",
      });
    } else {
      const decision = accepted
        ? choose(approval.params.availableDecisions, ["accept", "acceptForSession"])
        : choose(approval.params.availableDecisions, ["decline", "cancel"]);
      if (!decision) throw new Error(`Codex did not offer a supported ${accepted ? "accept" : "reject"} decision.`);
      this.#rpc.respond(approval.requestId, { decision });
    }
    this.#approvals.delete(approval.requestId);
  }

  #respondInput(input, accepted) {
    const answers = Object.fromEntries(input.questions.map((question) => {
      if (!accepted) return [question.id, { answers: [] }];
      const customAnswer = input.customAnswers.get(question.id);
      if (customAnswer) return [question.id, { answers: [customAnswer] }];
      const selected = input.selectedOptionIndexes.get(question.id) ?? 0;
      const label = question.options[selected]?.label;
      return [question.id, { answers: label ? [label] : [] }];
    }));
    this.#rpc.respond(input.requestId, { answers });
    this.#inputs.delete(input.requestId);
  }

  #question(input) {
    return input?.questions[input.activeQuestionIndex] ?? null;
  }

  #public(input) {
    if (!input) return null;
    const question = this.#question(input);
    const selectedOptionIndex = input.selectedOptionIndexes.get(question.id) ?? 0;
    return {
      questionIndex: input.activeQuestionIndex,
      questionCount: input.questions.length,
      header: question.header,
      question: question.question,
      options: question.options,
      selectedOptionIndex: question.options.length > 0 ? selectedOptionIndex : -1,
      hasSpokenAnswer: input.customAnswers.has(question.id),
      isSecret: question.isSecret,
    };
  }
}

function choose(available, preferred) {
  if (!Array.isArray(available) || available.length === 0) return preferred[0];
  const offered = new Set(available.filter((value) => typeof value === "string"));
  return preferred.find((value) => offered.has(value)) ?? null;
}

function normalize(message) {
  const params = message.params;
  if (
    (typeof message.id !== "string" && typeof message.id !== "number")
    || !params
    || typeof params.threadId !== "string"
    || typeof params.turnId !== "string"
    || !Array.isArray(params.questions)
    || params.questions.length === 0
    || params.questions.length > 3
  ) return null;

  const questions = params.questions.map((question) => {
    if (!question || typeof question !== "object" || Array.isArray(question)) return null;
    const id = printable(question.id, 64);
    const header = printable(question.header, 64);
    const prompt = printable(question.question, 2_000);
    if (!id || !header || !prompt) return null;
    if (question.options !== null && question.options !== undefined && !Array.isArray(question.options)) return null;
    const options = (question.options ?? []).map((option) => {
      if (!option || typeof option !== "object" || Array.isArray(option)) return null;
      const label = printable(option.label, 120);
      const description = printable(option.description, 500, { allowEmpty: true });
      return label && description !== null ? { label, description } : null;
    });
    if (options.some((option) => option === null) || options.length > 8) return null;
    return { id, header, question: prompt, options, isSecret: question.isSecret === true };
  });
  if (questions.some((question) => question === null) || new Set(questions.map(({ id }) => id)).size !== questions.length) {
    return null;
  }
  return {
    requestId: message.id,
    threadId: params.threadId,
    turnId: params.turnId,
    questions,
    activeQuestionIndex: 0,
    selectedOptionIndexes: new Map(questions.map(({ id }) => [id, 0])),
    customAnswers: new Map(),
  };
}

function wrap(index, length) {
  return ((index % length) + length) % length;
}
