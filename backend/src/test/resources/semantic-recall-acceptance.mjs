import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const turns = Number(process.env.ARDOR_ACCEPTANCE_SEMANTIC_TURNS ?? 100);
const repeatedCompaction = process.env.ARDOR_ACCEPTANCE_SEMANTIC_MODE !== "default";
const modelId = repeatedCompaction ? "semantic-recall-probe" : "semantic-recall-default-probe";
const ceiling = Number(process.env.ARDOR_ACCEPTANCE_CONTEXT_CEILING ?? (repeatedCompaction ? 60_000 : 220_000));
const marker = `ARDOR-RECALL-${Math.floor(Math.random() * 90_000) + 10_000}`;
const planted = `我的项目暗号是 ${marker}，请记住。`;
const filler = "Synthetic source material for budget growth. ".repeat(100);

class Client {
  cookies = new Map();
  cookieHeader() { return [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; "); }
  async headers() {
    const csrf = await this.request("/api/auth/csrf");
    return { [csrf.headerName]: csrf.token, Cookie: this.cookieHeader() };
  }
  async request(path, { method = "GET", body, expected = 200, raw = false } = {}) {
    const headers = { Cookie: this.cookieHeader() };
    if (!["GET", "HEAD"].includes(method)) Object.assign(headers, await this.headers());
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
  async openStream(body) {
    const headers = { ...(await this.headers()), "Content-Type": "application/json" };
    return fetch(base + "/api/agent/messages/stream", {
      method: "POST", headers, body: JSON.stringify(body), signal: AbortSignal.timeout(60_000),
    });
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function cancelHolds() {
  const response = await fetch(`${model}/__probe/cancel-hold`, { signal: AbortSignal.timeout(15_000) });
  assert.equal(response.status, 200);
  return (await response.json()).count;
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
  assert.ok(compactions <= 20,
    `The default 200K budget must avoid per-turn compaction, saw ${compactions}; largest estimate ${maxEstimated}`);
  assert.equal(maxSummaryVersion, compactions, "Summary generations must match actual compaction passes");
}
assert.ok(maxEstimated <= ceiling,
  `The request estimate must stay within the expected budget; largest estimate was ${maxEstimated} tokens`);

const answer = assistants.at(-1).content;
const recalledThroughSummary = repeatedCompaction || compactions > 0;
const expectedRecall = recalledThroughSummary ? "present-summary" : "present-raw";
const unexpectedRecall = recalledThroughSummary ? "present-raw" : "present-summary";
assert.ok(answer.includes(expectedRecall),
  `A fact stated ${turns} turns earlier must survive through ${expectedRecall}, got: ${answer}`);
assert.ok(!answer.includes(unexpectedRecall),
  `The fact arrived through an unexpected context path: ${answer}`);

// Cancel a turn after the provider received it, then prove the interrupted
// instruction still reaches the next request through the compacted history.
const cancelId = randomUUID();
const cancelMessage = "CANCEL-HOLD 合成中断指令：这条消息在模型生成前被取消，但必须进入下一轮上下文。";
const beforeHolds = await cancelHolds();
const pendingStream = await owner.openStream({ conversationId: conversation.id, requestId: cancelId,
  message: cancelMessage, reasoningDepth: "AUTO" });
for (let attempt = 0; attempt < 400 && (await cancelHolds()) === beforeHolds; attempt++) await sleep(50);
assert.ok((await cancelHolds()) > beforeHolds, "The provider must receive the turn before it is cancelled");
const cancelled = await owner.request(`/api/agent/runs/${cancelId}/cancel`, { method: "POST", expected: 202 });
assert.equal(cancelled.errorCode, "AGENT_RUN_CANCELLED");
assert.ok(["INTERRUPTED", "FAILED"].includes(cancelled.status), `Unexpected cancelled status: ${cancelled.status}`);
await fetch(`${model}/__probe/release-cancel-hold`, { method: "POST", signal: AbortSignal.timeout(15_000) });
const cancelEvents = sse(await (await pendingStream).text());
assert.ok(cancelEvents.some((event) => event.type === "error" && event.errorCode === "AGENT_RUN_CANCELLED"),
  JSON.stringify(cancelEvents.filter((event) => event.type === "error")));
assert.ok(!cancelEvents.some((event) => event.type === "done"), "A cancelled run must not finish as done");

const recoveryId = randomUUID();
const recoveryEvents = sse(await owner.request("/api/agent/messages/stream", { method: "POST", raw: true, body: {
  conversationId: conversation.id, requestId: recoveryId,
  message: `RECOVERY-CHECK：被取消的合成指令是否仍在上下文中？\n${filler}`, reasoningDepth: "AUTO",
} }));
assert.ok(recoveryEvents.some((event) => event.type === "done"),
  JSON.stringify(recoveryEvents.filter((event) => event.type === "error")));
assert.equal((await owner.request(`/api/agent/runs/${recoveryId}`)).status, "COMPLETED");

const recoveredState = await owner.request(`/api/agent?conversationId=${conversation.id}`);
assert.ok(recoveredState.messages.some((message) => message.role === "USER"
  && message.requestId === cancelId && message.content === cancelMessage), "The interrupted turn stays in history");
assert.ok(recoveredState.messages.some((message) => message.role === "ASSISTANT"
  && message.requestId === cancelId && message.content.includes("本轮已停止")), "The interrupted turn keeps its truthful status");
const recoveryAnswer = recoveredState.messages.filter((message) => message.role === "ASSISTANT"
  && message.requestId === recoveryId).at(-1)?.content ?? "";
assert.ok(recoveryAnswer.includes("recovered"),
  `The cancelled instruction must reach the next provider request, got: ${recoveryAnswer}`);

const traceRuns = [];
let traceBefore = null;
for (;;) {
  const page = await owner.request(`/api/agent/conversations/${conversation.id}/trace?limit=30`
    + (traceBefore ? `&before=${traceBefore}` : ""));
  traceRuns.push(...page.runs);
  if (!page.nextBefore) break;
  traceBefore = page.nextBefore;
}
assert.equal(traceRuns.length, turns + 2, "Every recorded turn must appear in the conversation trace");
assert.equal(traceRuns.filter((run) => run.status === "COMPLETED").length, turns + 1);
assert.equal(traceRuns.filter((run) => run.status !== "COMPLETED").length, 1,
  "The cancelled turn keeps a non-completed status");
assert.ok(traceRuns.every((run) => Array.isArray(run.spans)), "Each turn exposes spans for the global timeline");
assert.ok(traceRuns.some((run) => run.spans.some((span) => span.startedAt && span.finishedAt)),
  "The global timeline has measurable span windows");

console.log(`Semantic recall acceptance (${repeatedCompaction ? "64K window" : "default 200K budget"}): `
  + `a fact planted in turn 1 was still visible after ${turns} turns, ${compactions} compaction passes and `
  + `summary generation ${maxSummaryVersion}; original messages retained, largest request estimate `
  + `${maxEstimated} tokens. A turn cancelled after provider delivery stayed truthful in history, `
  + `reached the next request, and all ${turns + 2} runs appeared in the paged conversation timeline `
  + `(synthetic model, not vendor tokenizer precision).`);
