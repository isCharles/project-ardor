import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const expectedWindow = process.env.ARDOR_ACCEPTANCE_MODEL_WINDOW ? Number(process.env.ARDOR_ACCEPTANCE_MODEL_WINDOW) : null;

class Client {
  cookies = new Map();
  async request(path, { method = "GET", body, expected = 200, raw = false, streaming = false } = {}) {
    const headers = {};
    if (!["GET", "HEAD"].includes(method)) {
      const csrf = await this.request("/api/auth/csrf");
      headers[csrf.headerName] = csrf.token;
    }
    headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    if (body) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, {
      method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(45_000),
    });
    for (const line of response.headers.getSetCookie()) {
      const pair = line.split(";")[0], at = pair.indexOf("=");
      this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    if (streaming && response.status === expected) return response;
    const text = await response.text();
    assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 160)}`);
    return raw ? text : text ? JSON.parse(text) : null;
  }
}

const owner = new Client(), other = new Client();
await owner.request("/api/auth/me", { expected: 401 });
for (const client of [owner, other]) {
  await client.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `graph-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
}
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "runtime-integration-probe", apiKey: "fake-only",
} });
const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
for (let index = 0; index < 2; index++) {
  const requestId = randomUUID();
  const text = await owner.request("/api/agent/messages/stream", { method: "POST", raw: true, body: {
    conversationId: conversation.id, requestId, message: `请只读分析准备情况，第 ${index} 轮`, reasoningDepth: "AUTO",
  } });
  const events = text.split(/\r?\n/).filter((line) => line.startsWith("data:"))
    .map((line) => JSON.parse(line.slice(5).trim()));
  assert.equal(events.filter((event) => event.type === "done").length, 1,
    JSON.stringify(events.filter((event) => event.type === "error")));
  assert.equal(events.filter(event => ["phase", "phase_end"].includes(event.type)).length, 0,
    "Read-only keywords must not dispatch a separate PLAN/GATHER/REFLECT runtime");
  assert.ok(events.some(event => event.type === "delta" && event.content), "Ordinary model output remains streamed");
  const contextEvents = events.filter(event => event.type === "context").map(event => event.contextUsage);
  assert.equal(contextEvents[0].state, "PREPARING");
  assert.ok(contextEvents.length >= 3, "Publish assembly plus the actual request measurement");
  assert.ok(contextEvents.slice(1).every(item => item.state === "READY"));
  const contextUsage = contextEvents.at(-1);
  assert.ok(contextUsage.estimatedTokens > 0, "The prompt estimate must be measured by the builder");
  assert.equal(contextUsage.modelWindowTokens, expectedWindow, "Only verified or explicitly configured model windows are reported");
  assert.ok(contextUsage.compressionBudgetTokens > 0);
  assert.equal((await owner.request(`/api/agent/runs/${requestId}`)).status, "COMPLETED");
  const trace = await owner.request(`/api/agent/runs/${requestId}/trace`);
  assert.equal(trace.conversationId, conversation.id);
  assert.equal(trace.status, "COMPLETED");
  assert.equal(trace.totals.llmCalls, 1, "No extra phase calls are injected by keywords");
  const assembled = trace.spans.find(span => span.name === "context.load").attributes.assembledEstimatedTokens;
  assert.ok(contextEvents.some(item => item.estimatedTokens > assembled), "Actual request includes tool definitions omitted by initial assembly");
  const llm = trace.spans.find(span => span.name === "llm.call.1");
  if (llm.inputTokens > 0) assert.equal(contextUsage.estimatedTokens, llm.inputTokens, "Final usage is this provider request, not cumulative billing");
  const progress = await owner.request(`/api/agent/runs/${requestId}/progress`);
  assert.deepEqual(progress.contextUsage, contextUsage, "Progress snapshots and SSE use the same measured context");
  await other.request(`/api/agent/runs/${requestId}/progress`, { expected: 404 });
  await other.request(`/api/agent/runs/${requestId}/trace`, { expected: 404 });
  await other.request(`/api/agent/runs/${requestId}`, { expected: 404 });
  await other.request(`/api/agent/conversations/${conversation.id}/trace`, { expected: 404 });
}
const conversationTrace = await owner.request(`/api/agent/conversations/${conversation.id}/trace`);
assert.equal(conversationTrace.runs.length, 2);
assert.ok(conversationTrace.runs.every((run) => run.status === "COMPLETED" && run.assistantMessage && run.spans.length));
const latestPage = await owner.request(`/api/agent/conversations/${conversation.id}/trace?limit=1`);
assert.equal(latestPage.runs.length, 1);
assert.ok(latestPage.nextBefore);
const olderPage = await owner.request(`/api/agent/conversations/${conversation.id}/trace?limit=1&before=${latestPage.nextBefore}`);
assert.equal(olderPage.runs.length, 1);
assert.equal(olderPage.nextBefore, null);
assert.deepEqual([...olderPage.runs, ...latestPage.runs].map(run => run.runId), conversationTrace.runs.map(run => run.runId));
assert.ok(olderPage.runs[0].assistantMessage && latestPage.runs[0].assistantMessage);
await owner.request(`/api/agent/conversations/${conversation.id}/trace?before=${randomUUID()}`, { expected: 404 });
await other.request(`/api/agent/conversations/${conversation.id}/trace?before=${latestPage.nextBefore}`, { expected: 404 });
const schemas = await owner.request("/api/agent/tools/schemas");
assert.deepEqual(schemas.update_learning_plan.required, ["planId"]);
assert.deepEqual(schemas.search_knowledge.required, ["query"]);
await new Client().request("/api/agent/tools/schemas", { expected: 401 });
const state = await owner.request(`/api/agent?conversationId=${conversation.id}`);
assert.equal(state.messages.filter((message) => message.role === "USER").length, 2);
assert.equal(state.messages.filter((message) => message.role === "ASSISTANT").length, 2);
for (const message of state.messages.filter(message => message.role === "ASSISTANT")) {
  assert.equal(JSON.parse(message.runTrace).contextUsage.state, "READY", "Reloaded messages retain context metadata");
}
// Structural streaming assertion: the model deliberately delays its response.
// Receiving a buffered final response must not pass this check.
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "runtime-live-probe", apiKey: "fake-only",
} });
const liveId = randomUUID();
const live = await owner.request("/api/agent/messages/stream", { method: "POST", streaming: true, body: {
  conversationId: conversation.id, requestId: liveId, message: "请只读分析并显示实时进度", reasoningDepth: "AUTO",
} });
const reader = live.body.getReader();
const first = await reader.read();
assert.ok(first.value?.length, "A live progress event must arrive before completion");
assert.equal((await owner.request(`/api/agent/runs/${liveId}`)).status, "RUNNING",
  "SSE must not be buffered until the model completes");
const decoder = new TextDecoder();
let liveText = decoder.decode(first.value, { stream: true });
while (true) {
  const { value, done } = await reader.read();
  liveText += decoder.decode(value, { stream: !done });
  if (done) break;
}
assert.ok(liveText.includes('"type":"done"'));
assert.equal((await owner.request(`/api/agent/runs/${liveId}`)).status, "COMPLETED");
// Trigger real summary creation against the synthetic model rather than inventing UI-only events.
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "runtime-integration-probe", apiKey: "fake-only",
} });
let compacted = false, extraTurns = 0;
for (; extraTurns < 16; ) {
  extraTurns++;
  const requestId = randomUUID();
  const text = await owner.request("/api/agent/messages/stream", { method: "POST", raw: true, body: {
    conversationId: conversation.id, requestId,
    message: `Synthetic context retention probe ${extraTurns}\n${"Synthetic source material for budget growth. ".repeat(100)}`,
    reasoningDepth: "AUTO",
  } });
  const events = text.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => JSON.parse(line.slice(5).trim()));
  assert.ok(events.some(event => event.type === "done"), "Synthetic compaction run must complete");
  const contexts = events.filter(event => event.type === "context").map(event => event.contextUsage);
  if (contexts.some(item => item.state === "COMPACTING")) {
    const measured = contexts.at(-1);
    assert.equal(measured.state, "READY");
    assert.ok(measured.summaryVersion > 0 && measured.compactedMessages > 0);
    assert.ok(measured.estimatedTokens > 0);
    assert.deepEqual((await owner.request(`/api/agent/runs/${requestId}/progress`)).contextUsage, measured);
    const trace = await owner.request(`/api/agent/runs/${requestId}/trace`);
    const contextSpan = trace.spans.find(span => span.name === "context.load");
    const compactSpan = trace.spans.find(span => span.attributes.purpose === "CONTEXT_COMPACTION");
    assert.ok(compactSpan?.name.startsWith("context.compact."), "Summary is explicitly identified rather than an indistinguishable assistant call");
    assert.equal(compactSpan.parentSpanId, contextSpan.id, "Summary is nested under context preparation");
    const callNumbers = trace.spans.filter(span => span.kind === "LLM").map(span => span.attributes.call);
    assert.equal(new Set(callNumbers).size, callNumbers.length, "Call numbers are unique across summary and assistant listeners");
    compacted = true; break;
  }
}
assert.ok(compacted, "Actual compaction should occur within the bounded synthetic history");
const afterCompaction = await owner.request(`/api/agent?conversationId=${conversation.id}`);
assert.equal(afterCompaction.messages.filter(message => message.role === "USER").length, 3 + extraTurns, "Compaction must retain original user messages");
assert.equal(afterCompaction.messages.filter(message => message.role === "ASSISTANT").length, 3 + extraTurns);
console.log("Unified runtime HTTP acceptance: read-only keywords use normal streaming without phase calls; consecutive runs, persisted history, single-call trace, foreign run/trace 404 and live progress before delayed completion passed (synthetic model, not semantic quality).");
console.log(`Context usage HTTP acceptance: SSE/trace/progress/message estimates agree; actual summary and original history retained after ${3 + extraTurns} synthetic turns.`);
