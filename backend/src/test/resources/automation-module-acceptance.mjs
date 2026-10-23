import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
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
    const text = await response.text(); assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 180)}`);
    return raw ? text : text ? JSON.parse(text) : null;
  }
}
const owner = new Client(), foreign = new Client();
for (const client of [owner, foreign]) await client.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `automation-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
const create = (client, title) => client.request("/api/automations", { method: "POST", expected: 201,
  body: { title, prompt: "Synthetic prompt", cronExpression: "0 0 9 1 1 *", timezone: "UTC" } });
const own = await create(owner, "Automation owner fixture");
const theirs = await create(foreign, "Automation foreign fixture");
const keys = ["id", "title", "prompt", "cronExpression", "timezone", "enabled", "nextRunAt", "lastRunAt", "lastStatus", "lastError", "createdAt", "updatedAt"].sort();
assert.deepEqual(Object.keys(own).sort(), keys);
for (const method of ["GET", "PATCH", "DELETE"]) await foreign.request(`/api/automations/${own.id}`, {
  method, expected: 404, ...(method === "PATCH" ? { body: { enabled: false } } : {}) });
assert.equal((await owner.request(`/api/automations/${own.id}`)).enabled, true);
await owner.request("/api/settings/llm", { method: "PUT", body: { provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "automation-module-probe", apiKey: "fake-only" } });
async function turn(message) {
  const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const text = await owner.request("/api/agent/messages/stream", { method: "POST", raw: true,
    body: { conversationId: conversation.id, requestId: randomUUID(), message, reasoningDepth: "AUTO" } });
  const events = text.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => JSON.parse(line.slice(5).trim()));
  assert.equal(events.filter(event => event.type === "done").length, 1, text.slice(-1200));
  assert.equal(events.filter(event => event.type === "error").length, 0);
  return events;
}
await turn("查询我的自动化任务 automation-probe-list");
// Reads succeed at the default level. The test user then opts into existing write settings.
await owner.request("/api/agent/permissions/*", { method: "PUT", expected: 204, body: { level: 3 } });
await turn(`暂停这个自动化任务 ${own.id} automation-probe-update`);
const updated = await owner.request(`/api/automations/${own.id}`);
assert.deepEqual(Object.keys(updated).sort(), keys);
assert.equal(updated.enabled, false);
assert.equal(updated.prompt, own.prompt);
assert.equal(updated.nextRunAt, null);
const events = await turn(`删除这个自动化任务 ${own.id} automation-probe-delete`);
const confirmation = events.find(event => event.type === "confirm")?.confirmation;
assert.equal(confirmation?.targetId, own.id);
assert.equal(confirmation?.label, own.title);
assert.equal(confirmation?.endpoint, `/api/automations/${own.id}`);
assert.equal((await owner.request(`/api/automations/${own.id}`)).id, own.id, "Agent deletion must only propose");
await owner.request(confirmation.endpoint, { method: "DELETE", expected: 204 }); // Authenticated test-user confirmation.
await owner.request(`/api/automations/${own.id}`, { expected: 404 });
assert.equal((await foreign.request(`/api/automations/${theirs.id}`)).enabled, true);
await foreign.request(`/api/automations/${theirs.id}`, { method: "DELETE", expected: 204 });
console.log("Automation module: real model/tool/application flow, stable JSON, owned reads/update, foreign 404, proposal-only deletion and authenticated confirmation passed.");
