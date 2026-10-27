import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const turns = Number(process.env.ARDOR_ACCEPTANCE_SEMANTIC_TURNS ?? 100);
const repeatedCompaction = process.env.ARDOR_ACCEPTANCE_SEMANTIC_MODE !== "default";
const modelId = repeatedCompaction ? "semantic-recall-probe" : "semantic-recall-default-probe";
const ceiling = Number(process.env.ARDOR_ACCEPTANCE_CONTEXT_CEILING ?? (repeatedCompaction ? 40_000 : 200_000));
const marker = `ARDOR-RECALL-${Math.floor(Math.random() * 90_000) + 10_000}`;
const planted = `我的项目暗号是 ${marker}，请记住。`;
const filler = "Synthetic source material for budget growth. ".repeat(100);

class Client {
  cookies = new Map();
  async request(path, { method = "GET", body, expected = 200, raw = false } = {}) {
    const headers = {};
    if (!["GET", "HEAD"].includes(method)) {
      const csrf = await this.request("/api/auth/csrf");
      headers[csrf.headerName] = csrf.token;
    }
    headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    if (body) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, {
      method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(60_000),
    });
    for (const line of response.headers.getSetCookie()) {
      const pair = line.split(";")[0], at = pair.indexOf("=");
      this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    const text = await response.text();
    assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 160)}`);
    return raw ? text : text ? JSON.parse(text) : null;
  }
}

const sse = (text) => text.split(/\r?\n/).filter((line) => line.startsWith("data:"))
  .map((line) => JSON.parse(line.slice(5).trim()));

const owner = new Client();
await owner.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `recall-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: modelId, apiKey: "fake-only",
} });
const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });

let compactions = 0, maxEstimated = 0, maxSummaryVersion = 0;
for (let turn = 1; turn <= turns; turn++) {
  const requestId = randomUUID();
  // The fact is older than every later summary generation; nothing re-states it.
  const message = turn === 1 ? planted
    : turn === turns ? `RECALL-CHECK：我之前让你记住的项目暗号是什么？\n${filler}`
    : `第 ${turn} 轮：继续记录合成排查日志。\n${filler}`;
  const events = sse(await owner.request("/api/agent/messages/stream", { method: "POST", raw: true, body: {
    conversationId: conversation.id, requestId, message, reasoningDepth: "AUTO",
  } }));
  assert.ok(events.some((event) => event.type === "done"),
    `turn ${turn} must complete: ${JSON.stringify(events.filter((event) => event.type === "error"))}`);
  for (const event of events.filter((item) => item.type === "context")) {
    const usage = event.contextUsage;
    if (usage.state === "COMPACTING") compactions++;
    if (usage.state === "READY") {
      if (!repeatedCompaction) assert.equal(usage.compressionBudgetTokens, 200_000,
        `turn ${turn} must use the unknown-window application default`);
      maxEstimated = Math.max(maxEstimated, usage.estimatedTokens);
      maxSummaryVersion = Math.max(maxSummaryVersion, usage.summaryVersion ?? 0);
    }
  }
  assert.equal((await owner.request(`/api/agent/runs/${requestId}`)).status, "COMPLETED");
}

const state = await owner.request(`/api/agent?conversationId=${conversation.id}`);
const assistants = state.messages.filter((message) => message.role === "ASSISTANT");
assert.equal(state.messages.filter((message) => message.role === "USER").length, turns,
  "Compaction must never delete original user messages");
assert.equal(assistants.length, turns);
if (repeatedCompaction) {
  assert.ok(compactions >= 2, `The run must rewrite the summary more than once, saw ${compactions}`);
  assert.ok(maxSummaryVersion >= 2, `Summary generations must advance, saw ${maxSummaryVersion}`);
} else {
  assert.equal(compactions, 0, "The default 200K application budget must not repeatedly compact this run");
  assert.equal(maxSummaryVersion, 0, "The default-budget run must not create a summary");
}
assert.ok(maxEstimated <= ceiling,
  `The request estimate must stay within the expected budget; largest estimate was ${maxEstimated} tokens`);

const answer = assistants.at(-1).content;
const expectedRecall = repeatedCompaction ? "present-summary" : "present-raw";
const unexpectedRecall = repeatedCompaction ? "present-raw" : "present-summary";
assert.ok(answer.includes(expectedRecall),
  `A fact stated ${turns} turns earlier must survive through ${expectedRecall}, got: ${answer}`);
assert.ok(!answer.includes(unexpectedRecall),
  `The fact arrived through an unexpected context path: ${answer}`);

console.log(`Semantic recall acceptance (${repeatedCompaction ? "64K window" : "default 200K budget"}): `
  + `a fact planted in turn 1 was still visible after ${turns} turns, ${compactions} compaction passes and `
  + `summary generation ${maxSummaryVersion}; original messages retained, largest request estimate `
  + `${maxEstimated} tokens (synthetic model, not vendor tokenizer precision).`);
