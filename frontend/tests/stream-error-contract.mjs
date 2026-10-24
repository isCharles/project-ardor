import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import ts from "typescript";

async function load(relative) {
  const source = await readFile(new URL(relative, import.meta.url), "utf8");
  const result = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } });
  return import(`data:text/javascript;base64,${Buffer.from(result.outputText).toString("base64")}`);
}
const { streamApi, ApiError } = await load("../src/lib/api.ts");
const { POST } = await load("../src/app/api/agent/messages/stream/route.ts");
const realFetch = globalThis.fetch;
const quota = { code: "QUOTA_EXCEEDED", message: "请求过于频繁，请稍后再试", retryable: false,
  fields: { feature: "AGENT_CHAT", dimension: "USER_MINUTE_LIMIT", resetAt: "2099-01-01T00:00:00Z" } };
const event = { type: "done", content: "Synthetic success" };
const sse = `data: ${JSON.stringify(event)}\n\n`;
try {
  for (const failed of [true, false]) {
    let dispatched = 0;
    globalThis.fetch = async (url, init) => {
      if (url === "/api/auth/csrf") return Response.json({ headerName: "X-CSRF-TOKEN", token: "synthetic" });
      dispatched++;
      assert.match(init.headers.get("Accept"), /text\/event-stream/);
      assert.match(init.headers.get("Accept"), /application\/json/);
      assert.equal(init.headers.get("X-CSRF-TOKEN"), "synthetic");
      return failed ? Response.json(quota, { status: 429 }) : new Response(sse, { headers: { "Content-Type": "text/event-stream" } });
    };
    const events = [];
    const send = streamApi("/api/agent/messages/stream", { message: "Synthetic" }, event => events.push(event));
    if (failed) await assert.rejects(send, error => error instanceof ApiError && error.status === 429
      && error.message === quota.message && assert.deepEqual(error.body, quota) === undefined);
    else { await send; assert.deepEqual(events, [event]); }
    assert.equal(dispatched, 1);
  }
  // Exercise the real proxy function, not a reimplementation of its headers.
  for (const failed of [true, false]) {
    const request = new Request("http://fixture.invalid/api/agent/messages/stream", { method: "POST",
      headers: { Accept: "text/event-stream", Cookie: "SESSION=synthetic", "X-CSRF-TOKEN": "synthetic" }, body: "{}" });
    globalThis.fetch = async (url, init) => {
      assert.ok(url.endsWith("/api/agent/messages/stream"));
      assert.match(init.headers.get("accept"), /application\/json/);
      assert.match(init.headers.get("accept"), /text\/event-stream/);
      assert.equal(init.headers.get("cookie"), "SESSION=synthetic");
      assert.equal(init.headers.get("X-CSRF-TOKEN"), "synthetic");
      assert.equal(init.signal, request.signal);
      return failed ? Response.json(quota, { status: 429, headers: { "Retry-After": "30" } })
        : new Response(sse, { headers: { "Content-Type": "text/event-stream" } });
    };
    const response = await POST(request);
    assert.equal(response.status, failed ? 429 : 200);
    if (failed) { assert.equal(response.headers.get("Retry-After"), "30"); assert.deepEqual(await response.json(), quota); }
    else { assert.match(response.headers.get("Content-Type"), /text\/event-stream/); assert.equal(await response.text(), sse); }
  }
  console.log("Streaming errors: JSON quota classification, proxy status/headers and successful SSE preserved.");
} finally { globalThis.fetch = realFetch; }
