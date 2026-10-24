import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

// Creates synthetic users and writes real records. Never run against daily data.
if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
class Client {
  cookies = new Map();
  async request(path, { method = "GET", body, expected = 200, raw = false } = {}) {
    const headers = {};
    if (method !== "GET") { const csrf = await this.request("/api/auth/csrf"); headers[csrf.headerName] = csrf.token; }
    headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    if (body) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(45000) });
    for (const line of response.headers.getSetCookie()) { const pair = line.split(";")[0], at = pair.indexOf("="); this.cookies.set(pair.slice(0, at), pair.slice(at + 1)); }
    const text = await response.text(); assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 250)}`);
    return raw ? text : text ? JSON.parse(text) : null;
  }
}
const owner = new Client(), foreign = new Client();
for (const client of [owner, foreign]) await client.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `learning-module-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
for (const client of [owner, foreign]) await client.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "learning-module-probe", apiKey: "fake-only" } });
const createPlan = (client, concept) => client.request("/api/learning-plans", { method: "POST", expected: 201,
  body: { concept, requestId: randomUUID(), sourceType: "MANUAL" } });
const plan = await createPlan(owner, "Owned learning fixture"), foreignPlan = await createPlan(foreign, "Foreign learning fixture");
await owner.request("/api/agent/permissions/*", { method: "PUT", expected: 204, body: { level: 3 } });
async function turn(mode, args = {}, client = owner) {
  const conversation = await client.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const requestId = randomUUID();
  const verb = { list: "查看学习计划", get: "查看学习计划", create: "建议学习计划", update: "修改学习计划日期", begin: "开始学习计划", delete: "删除学习计划" }[mode];
  const stream = await client.request("/api/agent/messages/stream", { method: "POST", raw: true,
    body: { conversationId: conversation.id, requestId, message: `${verb} learning-call:${mode} learning-args:${JSON.stringify(args)}`, reasoningDepth: "AUTO" } });
  const events = stream.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => JSON.parse(line.slice(5).trim()));
  assert.equal(events.filter(event => event.type === "done").length, 1, stream.slice(-1000));
  assert.equal(events.filter(event => event.type === "error").length, 0, stream.slice(-1000));
  assert.deepEqual(await client.request(`/api/agent/runs/${requestId}/approvals`), [], "These reads/proposals or explicitly permitted writes must not await approval");
  return { events, stream };
}
const list = await turn("list");
assert.ok(list.stream.includes("Owned learning fixture")); assert.ok(!list.stream.includes("Foreign learning fixture"));
assert.ok((await turn("get", { planId: plan.id })).stream.includes("Owned learning fixture"));
const original = await owner.request(`/api/learning-plans/${plan.id}`);
const scheduledAt = new Date(Date.now() + 3 * 86400000).toISOString();
const update = await turn("update", { planId: plan.id, reason: "Updated arrangement", scheduledAt });
assert.ok(!update.stream.includes("TOOL_NOT_PERMITTED"));
const updated = await owner.request(`/api/learning-plans/${plan.id}`);
assert.equal(updated.reason, "Updated arrangement"); assert.equal(Date.parse(updated.scheduledAt), Date.parse(scheduledAt));
for (const field of ["id", "concept", "lesson", "exercises", "lastEvaluation", "attemptCount", "contentVersion", "exercisesVersion"])
  assert.deepEqual(updated[field], original[field], `${field} must survive an arrangement update`);
await turn("begin", { planId: plan.id });
assert.equal((await owner.request(`/api/learning-plans/${plan.id}`)).status, "IN_PROGRESS");
const target = await owner.request("/api/opportunities", { method: "POST", expected: 201, body: { company: "Synthetic preparation target" } });
const withRound = await owner.request(`/api/opportunities/${target.id}/rounds`, { method: "POST", expected: 201, body: { stage: "TECHNICAL" } });
const before = await owner.request("/api/learning-plans");
const proposed = await turn("create", { concept: "Proposed learning fixture", originKind: "AGENT", targetInterviewId: target.id, targetRoundId: withRound.rounds[0].id });
const creation = proposed.events.find(event => event.type === "confirm")?.confirmation;
assert.equal(creation?.kind, "learning_plan"); assert.equal(creation?.endpoint, "/api/learning-plans/jobs");
assert.equal(creation.learningPlan.originKind, "AGENT"); assert.equal(creation.learningPlan.targetInterviewId, target.id);
assert.equal(creation.learningPlan.targetRoundId, withRound.rounds[0].id); assert.ok(creation.learningPlan.requestId);
assert.deepEqual(await owner.request("/api/learning-plans"), before, "Proposal must not create a plan");
// Confirmation uses the same command and the ordinary authenticated application endpoint.
const queued = await owner.request(creation.endpoint, { method: "POST", expected: 202, body: creation.learningPlan });
const repeated = await owner.request(creation.endpoint, { method: "POST", expected: 202, body: creation.learningPlan });
assert.equal(repeated.id, queued.id);
let job;
for (let attempt = 0; attempt < 160; attempt++) {
  job = await owner.request(`/api/ai-jobs/${queued.id}`);
  if (["COMPLETED", "FAILED"].includes(job.status)) break;
  await new Promise(resolve => setTimeout(resolve, 250));
}
assert.equal(job.status, "COMPLETED", JSON.stringify(job));
const confirmed = await owner.request(`/api/learning-plans/${new URL(job.resultPath, "http://fixture.invalid").searchParams.get("id")}`);
assert.equal(confirmed.targetRoundId, withRound.rounds[0].id);
assert.equal(confirmed.requestId, creation.learningPlan.requestId);
await foreign.request(`/api/learning-plans/${plan.id}`, { expected: 404 });
await foreign.request(`/api/learning-plans/${plan.id}/arrangement`, { method: "PATCH", expected: 404, body: { reason: "Foreign overwrite" } });
const foreignRead = await turn("get", { planId: plan.id }, foreign);
assert.ok(!foreignRead.stream.includes("Owned learning fixture"));
const rejected = await turn("delete", { planId: plan.id }, foreign);
assert.ok(!rejected.events.some(event => event.type === "confirm"), "Foreign target cannot produce a deletion card");
const deleted = await turn("delete", { planId: plan.id });
const deletion = deleted.events.find(event => event.type === "confirm")?.confirmation;
assert.equal(deletion?.targetId, plan.id); assert.equal(deletion?.label, plan.concept);
assert.equal(deletion.endpoint, `/api/learning-plans/${plan.id}`);
await owner.request(`/api/learning-plans/${plan.id}`);
await owner.request(deletion.endpoint, { method: "DELETE", expected: 204 });
await owner.request(`/api/learning-plans/${plan.id}`, { expected: 404 });
assert.equal((await foreign.request(`/api/learning-plans/${foreignPlan.id}`)).concept, foreignPlan.concept);
console.log("Learning module: six wrapped tools, owned snapshots/updates, independent target, proposal-only creation/deletion, idempotent confirmation and cross-user isolation passed.");
