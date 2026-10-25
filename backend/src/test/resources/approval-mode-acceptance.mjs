import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
class Client {
  cookies = new Map();
  async request(path, { method = "GET", body, expected = 200 } = {}) {
    const headers = {};
    if (method !== "GET") {
      const csrf = await this.request("/api/auth/csrf"); headers[csrf.headerName] = csrf.token;
    }
    headers.Cookie = [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; ");
    if (body) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, { method, headers, body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(30000) });
    for (const value of response.headers.getSetCookie()) {
      const pair = value.split(";")[0], index = pair.indexOf("="); this.cookies.set(pair.slice(0, index), pair.slice(index + 1));
    }
    const text = await response.text();
    assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 150)}`);
    return text ? JSON.parse(text) : null;
  }
}
const owner = new Client(), other = new Client();
await new Client().request("/api/agent/permissions", { expected: 401 });
await new Client().request("/api/agent/permissions/mode", { method: "PUT", body: { mode: "FULL" }, expected: 401 });
for (const client of [owner, other]) await client.request("/api/auth/register", { method: "POST", expected: 201,
  body: { email: `approval-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
await owner.request("/api/agent/permissions/*", { method: "PUT", body: { level: 3 }, expected: 204 });
await owner.request("/api/agent/permissions/calendar", { method: "PUT", body: { level: 0 }, expected: 204 });
const legacy = await owner.request("/api/agent/permissions");
assert.equal(legacy.mode, "CUSTOM");
assert.equal(legacy.modules.find(item => item.module === "calendar").level, 0);
const foreignBefore = await other.request("/api/agent/permissions");
for (const mode of ["ASK", "AUTO", "FULL", "CUSTOM"]) {
  await owner.request("/api/agent/permissions/mode", { method: "PUT", body: { mode }, expected: 204 });
  const current = await owner.request("/api/agent/permissions");
  assert.equal(current.mode, mode);
  if (mode === "CUSTOM") assert.deepEqual(current, legacy, "Preset switching must retain original module grants");
  else {
    assert.equal(current.globalLevel, { ASK: 0, AUTO: 2, FULL: 3 }[mode]);
    assert.ok(current.modules.every(item => !item.overridden && item.level === current.globalLevel));
  }
  assert.deepEqual(await other.request("/api/agent/permissions"), foreignBefore);
}
await owner.request("/api/agent/permissions/mode", { method: "PUT", body: { mode: "INVALID" }, expected: 400 });
await owner.request("/api/agent/permissions/mode", { method: "PUT", body: {}, expected: 400 });
assert.deepEqual(await owner.request("/api/agent/permissions"), legacy);
await owner.request("/api/agent/permissions/mode", { method: "PUT", body: { mode: "FULL" }, expected: 204 });
await owner.request("/api/agent/permissions/calendar", { method: "PUT", body: { level: 2 }, expected: 204 });
assert.equal((await owner.request("/api/agent/permissions")).mode, "CUSTOM", "Legacy writes select custom mode");
assert.deepEqual(await other.request("/api/agent/permissions"), foreignBefore);
console.log("Approval mode HTTP: authenticated selection, four presets, preserved custom grants, legacy projection/write compatibility, invalid modes and cross-account isolation passed.");
