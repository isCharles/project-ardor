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
    const text = await response.text(); assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 150)}`);
    return raw ? text : text ? JSON.parse(text) : null;
  }
}
const owner = new Client(), foreign = new Client();
for (const client of [owner, foreign]) await client.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `profile-calendar-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
const original = await owner.request("/api/profile");
assert.deepEqual(Object.keys(original).sort(), ["displayName", "headline", "targetRoles", "timezone"].sort());
const initialSidebar = await owner.request("/api/profile/sidebar");
const sidebar = await owner.request("/api/profile/sidebar", { method: "PUT", body: {
  hrefs: ["/app", "/app/learning"], expectedRevision: initialSidebar.revision, expectedUserId: initialSidebar.userId } });
await owner.request("/api/profile", { method: "PUT", body: { displayName: "Owner Profile Fixture", headline: null, targetRoles: ["Backend"], timezone: "UTC" } });
await foreign.request("/api/profile", { method: "PUT", body: { displayName: "Foreign Profile Fixture", headline: "Foreign", targetRoles: ["Other"], timezone: "America/Los_Angeles" } });
await owner.request("/api/profile", { method: "PUT", expected: 400, body: { displayName: "Invalid", targetRoles: [], timezone: "not-a-zone" } });
assert.equal((await owner.request("/api/profile")).displayName, "Owner Profile Fixture");
assert.deepEqual(await owner.request("/api/profile/sidebar"), sidebar);
const createTask = (client, title) => client.request("/api/calendar/tasks", { method: "POST", expected: 201,
  body: { title, description: "Synthetic", dueAt: null, priority: "MEDIUM" } });
const task = await createTask(owner, "Owned task fixture"), foreignTask = await createTask(foreign, "Foreign task fixture");
const startDate = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
const createRule = (client, title) => client.request("/api/calendar/series", { method: "POST", expected: 201,
  body: { title, frequency: "DAILY", interval: 1, timeOfDay: "09:00", startDate, occurrenceLimit: 3, priority: "MEDIUM" } });
const rule = await createRule(owner, "Owned rule fixture"), foreignRule = await createRule(foreign, "Foreign rule fixture");
assert.equal(rule.timezone, "UTC"); assert.equal(foreignRule.timezone, "America/Los_Angeles");
let tasks = await owner.request("/api/calendar/tasks");
const occurrences = tasks.filter(item => item.seriesId === rule.id);
assert.equal(occurrences.length, 3);
const kept = occurrences[0];
await owner.request(`/api/calendar/tasks/${kept.id}`, { method: "PUT", body: {
  title: kept.title, description: kept.description ?? "", dueAt: kept.dueAt, priority: kept.priority, status: "COMPLETED" } });
await foreign.request(`/api/calendar/tasks/${task.id}`, { method: "DELETE", expected: 404 });
await foreign.request(`/api/calendar/series/${rule.id}`, { method: "DELETE", expected: 404 });
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "profile-calendar-probe", apiKey: "fake-only" } });
async function turn(message) {
  const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
  const stream = await owner.request("/api/agent/messages/stream", { method: "POST", raw: true,
    body: { conversationId: conversation.id, requestId: randomUUID(), message, reasoningDepth: "AUTO" } });
  const events = stream.split(/\r?\n/).filter(line => line.startsWith("data:")).map(line => JSON.parse(line.slice(5).trim()));
  assert.equal(events.filter(event => event.type === "done").length, 1, stream.slice(-1200));
  assert.equal(events.filter(event => event.type === "error").length, 0);
  return events;
}
await turn("读取我的个人资料 profile-calendar-read");
const taskEvents = await turn(`删除这个日程 ${task.id} profile-calendar-task`);
const taskCard = taskEvents.find(event => event.type === "confirm")?.confirmation;
assert.equal(taskCard?.label, task.title); assert.equal(taskCard?.detail, "未安排时间");
assert.equal(taskCard?.endpoint, `/api/calendar/tasks/${task.id}`);
assert.ok((await owner.request("/api/calendar/tasks")).some(item => item.id === task.id));
await owner.request(taskCard.endpoint, { method: "DELETE", expected: 204 });
const ruleEvents = await turn(`取消这个重复安排 ${rule.id} profile-calendar-rule`);
const ruleCard = ruleEvents.find(event => event.type === "confirm")?.confirmation;
assert.equal(ruleCard?.label, `${rule.title}（${rule.summary}）`);
assert.equal(ruleCard?.endpoint, `/api/calendar/series/${rule.id}`);
assert.equal((await owner.request("/api/calendar/series")).find(item => item.id === rule.id).status, "ACTIVE");
assert.equal((await owner.request("/api/calendar/tasks")).filter(item => item.seriesId === rule.id).length, 3);
const cancelled = await owner.request(ruleCard.endpoint, { method: "DELETE" });
assert.equal(cancelled.cancelled, true); assert.equal(cancelled.withdrawnOccurrences, 2);
tasks = await owner.request("/api/calendar/tasks");
assert.ok(!tasks.some(item => item.id === task.id));
assert.deepEqual(tasks.filter(item => item.seriesId === rule.id).map(item => [item.id, item.status]), [[kept.id, "COMPLETED"]]);
assert.equal((await foreign.request("/api/calendar/series")).find(item => item.id === foreignRule.id).status, "ACTIVE");
assert.ok((await foreign.request("/api/calendar/tasks")).some(item => item.id === foreignTask.id));
assert.equal((await foreign.request("/api/profile")).displayName, "Foreign Profile Fixture");
console.log("Profile/calendar: stable profile JSON and ownership, timezone propagation, real deletion proposals and preserved completed recurrence passed.");
