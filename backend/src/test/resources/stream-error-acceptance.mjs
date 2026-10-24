import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const expectQuota = process.env.ARDOR_ACCEPTANCE_EXPECT_QUOTA === "true";
class Client {
  cookies = new Map();
  async request(path, { method = "GET", body, expected = 200, accept = "application/json" } = {}) {
    const headers = { Accept: accept };
    if (method !== "GET") {
      const { body: csrf } = await this.request("/api/auth/csrf"); headers[csrf.headerName] = csrf.token;
    }
    headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    if (body) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(15000) });
    for (const line of response.headers.getSetCookie()) {
      const pair = line.split(";")[0], at = pair.indexOf("="); this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
    const text = await response.text();
    assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 200)}`);
    assert.match(response.headers.get("Content-Type") ?? "", /application\/json/);
    return { body: JSON.parse(text), headers: response.headers };
  }
}
const client = new Client();
await client.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `stream-error-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
const { body: conversation } = await client.request("/api/agent/conversations", { method: "POST", expected: 201 });
for (const accept of ["text/event-stream", "text/event-stream, application/json"]) {
  const invalid = await client.request("/api/agent/messages/stream", { method: "POST", expected: 400, accept,
    body: { conversationId: conversation.id, requestId: randomUUID(), message: "" } });
  assert.equal(invalid.body.code, "VALIDATION_ERROR");
  assert.equal(invalid.body.fields.message, "消息不能为空");
  if (expectQuota) {
    const requestId = randomUUID();
    const denied = await client.request("/api/agent/messages/stream", { method: "POST", expected: 429, accept,
      body: { conversationId: conversation.id, requestId, message: "Synthetic quota boundary" } });
    assert.equal(denied.body.code, "QUOTA_EXCEEDED");
    assert.equal(denied.body.fields.feature, "AGENT_CHAT");
    assert.equal(denied.body.fields.dimension, "MONTHLY_LIMIT");
    assert.ok(Date.parse(denied.body.fields.resetAt) > Date.now());
    assert.ok(Number(denied.headers.get("Retry-After")) > 0);
    assert.ok(denied.body.message.length > 0);
    await client.request(`/api/agent/runs/${requestId}`, { expected: 404 });
  }
}
const { body: state } = await client.request(`/api/agent?conversationId=${conversation.id}`);
assert.deepEqual(state.messages, [], "Rejected requests must not create a model run or chat message");
console.log(`Stream preflight: JSON validation${expectQuota ? ", quota status/reset/Retry-After" : ""}, old/new Accept and no persisted messages passed.`);
