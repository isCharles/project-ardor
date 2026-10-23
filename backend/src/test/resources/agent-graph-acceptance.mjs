import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";

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
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "graph-integration-probe", apiKey: "fake-only",
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
  assert.deepEqual(events.filter((event) => event.type === "phase").map((event) => event.content),
    ["PLAN", "GATHER", "OBSERVE", "REFLECT", "ANSWER"]);
  const finishedPhases = events.filter((event) => event.type === "phase_end");
  assert.equal(finishedPhases.length, 5);
  for (const event of finishedPhases) {
    assert.equal(event.toolStatus, "COMPLETED");
    assert.ok(event.elapsedMs >= 0);
    assert.ok(events.findIndex((item) => item === event) > events.findIndex((item) => item.type === "phase" && item.content === event.content));
  }
  assert.equal((await owner.request(`/api/agent/runs/${requestId}`)).status, "COMPLETED");
  const trace = await owner.request(`/api/agent/runs/${requestId}/trace`);
  assert.equal(trace.status, "COMPLETED");
  assert.equal(trace.totals.llmCalls, 4);
  await other.request(`/api/agent/runs/${requestId}/trace`, { expected: 404 });
  await other.request(`/api/agent/runs/${requestId}`, { expected: 404 });
}
const state = await owner.request(`/api/agent?conversationId=${conversation.id}`);
assert.equal(state.messages.filter((message) => message.role === "USER").length, 2);
assert.equal(state.messages.filter((message) => message.role === "ASSISTANT").length, 2);
// Structural streaming assertion: the model deliberately delays every phase.
// Receiving a buffered final response must not pass this check.
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "graph-live-probe", apiKey: "fake-only",
} });
const liveId = randomUUID();
const live = await owner.request("/api/agent/messages/stream", { method: "POST", streaming: true, body: {
  conversationId: conversation.id, requestId: liveId, message: "请只读分析并显示实时进度", reasoningDepth: "AUTO",
} });
const reader = live.body.getReader();
const first = await reader.read();
assert.ok(first.value?.length, "A live progress event must arrive before completion");
assert.equal((await owner.request(`/api/agent/runs/${liveId}`)).status, "RUNNING",
  "SSE must not be buffered until the whole graph completes");
const decoder = new TextDecoder();
let liveText = decoder.decode(first.value, { stream: true });
while (true) {
  const { value, done } = await reader.read();
  liveText += decoder.decode(value, { stream: !done });
  if (done) break;
}
assert.ok(liveText.includes('"type":"done"'));
assert.equal((await owner.request(`/api/agent/runs/${liveId}`)).status, "COMPLETED");
console.log("Graph HTTP acceptance: session, consecutive SSE runs, phase start/end order, persisted completion/history, model-call totals, foreign run/trace 404 and live progress before delayed model completion passed (synthetic model, not semantic quality).");
