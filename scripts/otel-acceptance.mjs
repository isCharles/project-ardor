/**
 * Local OpenTelemetry acceptance for Ardor's disposable stack.
 *
 * Uses the synthetic fake LLM (no paid model calls), a disposable PostgreSQL/Redis
 * pair and one local trace backend. It verifies that the product trace and the
 * OpenTelemetry trace describe the same work for a successful run, a failed run,
 * a durable queue item, a canceled stream, two concurrent users and an AI job.
 *
 * Environment:
 *   ARDOR_BASE_URL      backend origin (default http://127.0.0.1:8082)
 *   ARDOR_FAKE_BASE     fake LLM origin (default http://127.0.0.1:8090)
 *   ARDOR_JAEGER        Jaeger query origin (default http://127.0.0.1:16686)
 *   ARDOR_TRACE_SERVICE OTel service.name (default ardor-backend)
 */
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { SseParser } from "./agent-eval-sse.mjs";

const base = (process.env.ARDOR_BASE_URL ?? "http://127.0.0.1:8082").replace(/\/+$/, "");
const modelBase = (process.env.ARDOR_FAKE_BASE ?? "http://127.0.0.1:8090").replace(/\/+$/, "");
const jaeger = (process.env.ARDOR_JAEGER ?? "http://127.0.0.1:16686").replace(/\/+$/, "");
const service = process.env.ARDOR_TRACE_SERVICE ?? "ardor-backend";
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class Client {
  cookies = new Map();
  capture(response) {
    for (const line of response.headers.getSetCookie?.() ?? []) {
      const pair = line.split(";", 1)[0];
      const separator = pair.indexOf("=");
      if (separator > 0) this.cookies.set(pair.slice(0, separator), pair.slice(separator + 1));
    }
  }
  cookieHeader() { return [...this.cookies].map(([k, v]) => `${k}=${v}`).join("; "); }
  async csrf() {
    const response = await fetch(`${base}/api/auth/csrf`, { headers: { Cookie: this.cookieHeader() } });
    if (!response.ok) throw new Error(`CSRF ${response.status}`);
    this.capture(response);
    return response.json();
  }
  async raw(path, { method = "GET", body, accept = "application/json" } = {}) {
    const headers = { Accept: accept };
    if (body !== undefined || !["GET", "HEAD"].includes(method)) {
      const token = await this.csrf();
      headers[token.headerName] = token.token;
      if (body !== undefined) headers["Content-Type"] = "application/json";
    }
    headers.Cookie = this.cookieHeader();
    const response = await fetch(`${base}${path}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    this.capture(response);
    return response;
  }
  async json(path, options = {}) {
    const { expected = 200, ...rest } = options;
    const response = await this.raw(path, rest);
    const text = await response.text();
    if (response.status !== expected) throw new Error(`${rest.method ?? "GET"} ${path}: ${response.status} ${text.slice(0, 300)}`);
    return text ? JSON.parse(text) : null;
  }
}

async function register(label, model = "eval-script-probe") {
  const client = new Client();
  const suffix = randomUUID();
  await client.json("/api/auth/register", {
    method: "POST", expected: 201,
    body: { email: `otel-${label}-${suffix.slice(0, 8)}@ardor.invalid`, password: `Otel-${suffix}!`, displayName: `Otel ${label}` },
  });
  await client.json("/api/settings/llm", {
    method: "PUT",
    body: { provider: "OPENAI_COMPATIBLE", baseUrl: modelBase, model, apiKey: "disposable-fake-model-key" },
  });
  return client;
}

/** Starts a stream and exposes its request id before the model finishes. */
function beginStream(client, message) {
  const requestId = randomUUID();
  const result = (async () => {
    const events = [];
    const response = await client.raw("/api/agent/messages/stream", {
      method: "POST", accept: "text/event-stream",
      body: { message, requestId, reasoningDepth: "AUTO", contextReferences: [] },
    });
    if (!response.ok) throw new Error(`stream HTTP ${response.status}: ${(await response.text()).slice(0, 300)}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser((payload) => events.push(JSON.parse(payload)));
    while (true) {
      const { value, done } = await reader.read();
      if (value) parser.push(decoder.decode(value, { stream: !done }), done);
      if (done) break;
    }
    return { requestId, events, done: events.find((event) => event.type === "done"),
      error: events.find((event) => event.type === "error") };
  })();
  return { requestId, result };
}

const send = (client, message) => beginStream(client, message).result;

async function waitFor(predicate, message, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const value = await predicate();
      if (value) return value;
    } catch { /* retry transient reads */ }
    await sleep(250);
  }
  throw new Error(`Timed out: ${message}`);
}

async function businessTrace(client, requestId) {
  return waitFor(async () => {
    const trace = await client.json(`/api/agent/runs/${requestId}/trace`);
    return trace?.spans?.length && !["RUNNING", "QUEUED"].includes(trace.status) ? trace : null;
  }, `terminal business trace for ${requestId}`, 20_000);
}

async function jaegerTrace(traceId, complete = () => true) {
  return waitFor(async () => {
    const response = await fetch(`${jaeger}/api/traces/${traceId}`);
    if (!response.ok) return null;
    const body = await response.json();
    const trace = body.data?.[0] ?? null;
    return trace && complete(trace) ? trace : null;
  }, `complete Jaeger trace ${traceId}`, 20_000);
}

const tags = (span) => Object.fromEntries((span.tags ?? []).map((tag) => [tag.key, tag.value]));
const parentOf = (span) => span.references?.find((reference) => reference.refType === "CHILD_OF")?.spanID ?? null;
const spanNamed = (trace, name) => trace.spans.find((span) => span.operationName === name);
const rootOf = (trace) => trace.spans.find((span) => !parentOf(span));
const statusOf = (span) => tags(span)["otel.status_code"] ?? tags(span).error ?? "?";

function ancestorChain(trace, span) {
  const byId = new Map(trace.spans.map((item) => [item.spanID, item]));
  const chain = [];
  for (let current = span; current; current = byId.get(parentOf(current))) chain.push(current.operationName);
  return chain;
}

function report(title, trace) {
  console.log(`\n== ${title} trace=${trace.traceID} ==`);
  for (const span of trace.spans) {
    console.log(`${span.operationName} id=${span.spanID} parent=${parentOf(span) ?? "-"} status=${statusOf(span)}` +
      ` model=${tags(span)["gen_ai.request.model"] ?? "-"}`);
  }
}

const summary = {};

// 1. Successful stream: one product run and one technical chain.
const successClient = await register("success");
const success = await send(successClient, "你好，请简单回复。");
assert.ok(success.done || success.error, "a successful stream must terminate");
const successBusiness = await businessTrace(successClient, success.requestId);
assert.equal(successBusiness.status, "COMPLETED");
assert.match(successBusiness.traceId, /^[0-9a-f]{32}$/);
const successOtel = await jaegerTrace(successBusiness.traceId, (trace) =>
  successBusiness.spans.every((span) => !span.otelSpanId || trace.spans.some((item) => item.spanID === span.otelSpanId)));
const successRun = spanNamed(successOtel, "agent.run");
assert.ok(successRun, "agent.run span must exist");
assert.equal(tags(successRun)["ardor.run.id"], success.requestId);
assert.equal(statusOf(successRun), "OK");
assert.equal(tags(successRun)["gen_ai.operation.name"], "invoke_agent");
assert.ok(Number(tags(spanNamed(successOtel, "llm.call.1"))["ardor.llm.request_duration_ms"]) >= 0,
  "completion timing must be exported");
assert.match(ancestorChain(successOtel, spanNamed(successOtel, "llm.call.1")).join(" > "),
  /agent\.run > /, "LLM span must descend from the run");
for (const businessSpan of successBusiness.spans) {
  assert.ok(successOtel.spans.some((span) => span.spanID === businessSpan.otelSpanId),
    `business span ${businessSpan.kind}:${businessSpan.name} must exist in Jaeger`);
}
assert.equal(successBusiness.spans.find((span) => span.kind === "RUN").otelTraceId, successOtel.traceID);
report("success", successOtel);
summary.success = { traceId: successOtel.traceID, spans: successOtel.spans.length };

// 2. Failed model call: the product record and the technical span agree.
const failureClient = await register("failure", "queue-failure-probe");
const failure = await send(failureClient, "Queue failure marker");
assert.equal(failure.error?.errorCode, "LLM_HTTP_ERROR");
const failureBusiness = await businessTrace(failureClient, failure.requestId);
assert.equal(failureBusiness.status, "FAILED");
const failureOtel = await jaegerTrace(failureBusiness.traceId);
assert.equal(statusOf(spanNamed(failureOtel, "agent.run")), "ERROR");
assert.equal(statusOf(spanNamed(failureOtel, "llm.call.1")), "ERROR");
assert.equal(tags(spanNamed(failureOtel, "llm.call.1"))["error.type"], "LLM_HTTP_ERROR");
report("failure", failureOtel);
summary.failure = { traceId: failureOtel.traceID, status: statusOf(spanNamed(failureOtel, "agent.run")) };

// 3. Foreign access stays isolated.
assert.equal((await successClient.raw(`/api/agent/runs/${failure.requestId}/trace`)).status, 404);

// 4. Durable queue: wait is explicit and the run nests under queue.execute.
const state = await successClient.json("/api/agent");
const queueRequestId = randomUUID();
const queued = await successClient.json("/api/agent/queue", {
  method: "POST", expected: 202,
  body: { message: "排队消息，请简单回复。", requestId: queueRequestId, conversationId: state.conversationId,
    reasoningDepth: "AUTO", contextReferences: [] },
});
assert.equal(queued.status, "QUEUED");
const queueRun = await waitFor(async () => {
  const run = await successClient.json(`/api/agent/runs/${queueRequestId}`).catch(() => null);
  return run && !["QUEUED", "RUNNING"].includes(run.status) ? run : null;
}, "queued run to finish", 40_000);
assert.equal(queueRun.status, "COMPLETED");
const queueBusiness = await businessTrace(successClient, queueRequestId);
const queueOtel = await jaegerTrace(queueBusiness.traceId);
const queueSpan = spanNamed(queueOtel, "queue.execute");
assert.ok(queueSpan, "queue.execute span must exist");
assert.equal(tags(queueSpan)["ardor.queue.id"], "agent_message_queue");
assert.equal(tags(queueSpan)["ardor.queue.id.item"], queued.id);
assert.ok(Number(tags(queueSpan)["ardor.queue.wait_ms"]) >= 0);
const queueRunSpan = spanNamed(queueOtel, "agent.run");
assert.equal(parentOf(queueRunSpan), queueSpan.spanID, "agent.run must be a child of queue.execute");
assert.match(ancestorChain(queueOtel, queueRunSpan).join(" > "),
  /agent\.run > queue\.execute > secured request/, "queue execution must continue the enqueue trace");
assert.equal(rootOf(queueOtel).operationName, "http post /api/agent/queue");
report("queue", queueOtel);
summary.queue = { traceId: queueOtel.traceID, waitMs: tags(queueSpan)["ardor.queue.wait_ms"] };

// 5. Cancellation closes spans instead of leaving them open.
const cancelClient = await register("cancel");
const holdBefore = (await (await fetch(`${modelBase}/__probe/cancel-hold`)).json()).count;
const pendingCancel = beginStream(cancelClient, "CANCEL-HOLD 请稍等");
await waitFor(async () => (await (await fetch(`${modelBase}/__probe/cancel-hold`)).json()).count > holdBefore,
  "the fake model to hold the stream", 20_000);
const canceled = await cancelClient.json(`/api/agent/runs/${pendingCancel.requestId}/cancel`,
  { method: "POST", expected: 202 });
assert.ok(!["RUNNING", "QUEUED"].includes(canceled.status), "cancel must return a terminal run");
const canceledStream = await pendingCancel.result;
assert.notEqual(canceledStream.done?.status, "COMPLETED", "a canceled stream must not report a completed run");
const cancelRunId = pendingCancel.requestId;
await fetch(`${modelBase}/__probe/release-cancel-hold`);
const cancelBusiness = await businessTrace(cancelClient, cancelRunId);
const cancelOtel = await jaegerTrace(cancelBusiness.traceId);
assert.equal(tags(spanNamed(cancelOtel, "agent.run"))["error.type"], "AGENT_RUN_CANCELLED");
assert.equal(statusOf(spanNamed(cancelOtel, "llm.call.1")), "ERROR");
assert.ok(cancelOtel.spans.every((span) => span.duration > 0), "every exported span must have ended");
report("cancel", cancelOtel);
summary.cancel = { traceId: cancelOtel.traceID, businessStatus: cancelBusiness.status };

// 6. Two concurrent users never share a trace.
const [left, right] = await Promise.all([register("left"), register("right")]);
const leftStream = beginStream(left, "并发请求一");
const rightStream = beginStream(right, "并发请求二");
await Promise.all([leftStream.result, rightStream.result]);
const [leftTrace, rightTrace] = await Promise.all([
  businessTrace(left, leftStream.requestId), businessTrace(right, rightStream.requestId)]);
assert.notEqual(leftTrace.traceId, rightTrace.traceId);
const [leftOtel, rightOtel] = await Promise.all([jaegerTrace(leftTrace.traceId), jaegerTrace(rightTrace.traceId)]);
assert.equal(tags(spanNamed(leftOtel, "agent.run"))["ardor.run.id"], leftStream.requestId);
assert.equal(tags(spanNamed(rightOtel, "agent.run"))["ardor.run.id"], rightStream.requestId);
assert.ok(!leftOtel.spans.some((span) => span.spanID === spanNamed(rightOtel, "agent.run").spanID));
assert.equal((await left.raw(`/api/agent/runs/${rightStream.requestId}/trace`)).status, 404);
summary.concurrent = { left: leftOtel.traceID, right: rightOtel.traceID };

// 7. Tool proposal/refusal: an unconfirmed write is never reported as executed.
const toolClient = await register("tool", "profile-calendar-probe");
const seededTask = await toolClient.json("/api/calendar/tasks", {
  method: "POST", expected: 201,
  body: { title: "Otel tool refusal fixture", dueAt: "2028-04-01T09:00:00+08:00", priority: "HIGH" },
});
const toolStream = await send(toolClient, `profile-calendar-task ${seededTask.id}`);
const toolBusiness = await businessTrace(toolClient, toolStream.requestId);
const toolBusinessSpan = toolBusiness.spans.find((span) => span.kind === "TOOL");
assert.ok(toolBusinessSpan, "the business trace must record the proposed tool call");
assert.equal(toolBusinessSpan.status, "COMPLETED");
assert.equal(toolBusiness.status, "COMPLETED", "a proposal is a completed run without executing the write");
const toolOtel = await jaegerTrace(toolBusiness.traceId,
  (trace) => trace.spans.some((span) => tags(span)["ardor.span.kind"] === "TOOL"));
const toolSpan = toolOtel.spans.find((span) => tags(span)["ardor.span.kind"] === "TOOL");
assert.ok(toolSpan, "the technical trace must record the tool call");
assert.equal(tags(toolSpan)["gen_ai.tool.name"], "delete_calendar_task");
assert.equal(tags(toolSpan)["gen_ai.operation.name"], "execute_tool");
assert.equal(tags(toolSpan)["ardor.span.status"], "COMPLETED");
assert.equal(tags(toolSpan)["ardor.tool.executed"], true, "the tool call itself returned a result");
assert.equal(tags(toolSpan)["ardor.tool.proposal"], true, "the write was only proposed for confirmation");
assert.equal(statusOf(toolSpan), "OK");
const tasksAfterProposal = await toolClient.json("/api/calendar/tasks");
assert.ok(tasksAfterProposal.some((task) => task.id === seededTask.id),
  "the unconfirmed proposal must not delete the target task");
report("tool proposal", toolOtel);
summary.tool = { traceId: toolOtel.traceID, proposal: tags(toolSpan)["ardor.tool.proposal"] };

// 8. AI job: the enqueue request and the worker execution share one trace.
const jobClient = await register("job");
const jobRunId = randomUUID();
const jobRun = await jobClient.json("/api/job-discovery/runs", {
  method: "POST", expected: 202,
  body: { requestId: jobRunId, roles: ["Backend Engineer"], locations: ["Beijing"], keywords: [] },
});
assert.ok(jobRun.jobId, "discovery start must return the queued job id");
await waitFor(async () => {
  const run = await jobClient.json(`/api/job-discovery/runs/${jobRunId}`).catch(() => null);
  return run && !["QUEUED", "RUNNING"].includes(run.status) ? run : null;
}, "discovery job to finish", 60_000);
const jobTrace = await waitFor(async () => {
  const filters = encodeURIComponent(JSON.stringify({ "ardor.job.id": jobRun.jobId }));
  const response = await fetch(`${jaeger}/api/traces?service=${service}&tags=${filters}&limit=5&lookback=1h`);
  if (!response.ok) return null;
  const body = await response.json();
  return body.data?.find((trace) => spanNamed(trace, "job.execute")) ?? null;
}, "Jaeger trace carrying the AI job", 30_000);
const jobSpan = spanNamed(jobTrace, "job.execute");
assert.equal(tags(jobSpan)["ardor.job.type"], "JOB_DISCOVERY");
assert.equal(String(tags(jobSpan)["ardor.job.attempt"]), "1");
assert.ok(tags(jobSpan)["ardor.queue.wait_ms"] !== undefined, "job wait must be recorded");
assert.match(ancestorChain(jobTrace, jobSpan).join(" > "),
  /job\.execute > secured request > http post/, "job.execute must continue the enqueue request trace");
assert.equal(rootOf(jobTrace).operationName, "http post /api/job-discovery/runs");
report("ai job", jobTrace);
summary.job = { traceId: jobTrace.traceID, jobId: jobRun.jobId };

console.log("\nOTel acceptance passed:", JSON.stringify(summary, null, 2));
