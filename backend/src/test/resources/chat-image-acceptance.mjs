import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") throw new Error("Disposable database required");
const base = process.env.ARDOR_ACCEPTANCE_API_BASE ?? "http://127.0.0.1:8080";
const model = process.env.ARDOR_ACCEPTANCE_LLM_BASE ?? "http://127.0.0.1:8090";
const png = Uint8Array.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);

class Client {
  cookies = new Map();
  async csrfHeaders() {
    const csrf = await this.request("/api/auth/csrf");
    return { [csrf.headerName]: csrf.token, Cookie: this.cookieHeader() };
  }
  cookieHeader() { return [...this.cookies].map(([key, value]) => `${key}=${value}`).join("; "); }
  capture(response) {
    for (const line of response.headers.getSetCookie()) {
      const pair = line.split(";")[0], at = pair.indexOf("=");
      this.cookies.set(pair.slice(0, at), pair.slice(at + 1));
    }
  }
  async request(path, { method = "GET", body, expected = 200 } = {}) {
    const headers = { Cookie: this.cookieHeader() };
    if (!["GET", "HEAD"].includes(method)) Object.assign(headers, await this.csrfHeaders());
    if (body) headers["Content-Type"] = "application/json";
    const response = await fetch(base + path, {
      method, headers, body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(45_000),
    });
    this.capture(response);
    const text = await response.text();
    assert.equal(response.status, expected, `${method} ${path}: ${text.slice(0, 200)}`);
    return text ? JSON.parse(text) : null;
  }
  async upload(path, bytes, type) {
    const headers = await this.csrfHeaders();
    const form = new FormData();
    form.append("file", new Blob([bytes], { type }), "synthetic-image");
    const response = await fetch(base + path, { method: "POST", headers, body: form, signal: AbortSignal.timeout(45_000) });
    this.capture(response);
    const text = await response.text();
    assert.equal(response.status, 201, `POST ${path}: ${text.slice(0, 200)}`);
    return JSON.parse(text);
  }
  async image(path, expected = 200) {
    const response = await fetch(base + path, { headers: { Cookie: this.cookieHeader() }, signal: AbortSignal.timeout(45_000) });
    this.capture(response);
    const bytes = new Uint8Array(await response.arrayBuffer());
    assert.equal(response.status, expected, `GET ${path}`);
    return { bytes, contentType: response.headers.get("content-type") };
  }
  async openStream(body) {
    const headers = { ...(await this.csrfHeaders()), "Content-Type": "application/json" };
    return fetch(base + "/api/agent/messages/stream", {
      method: "POST", headers, body: JSON.stringify(body), signal: AbortSignal.timeout(60_000),
    });
  }
}

async function imageRequests() {
  const response = await fetch(`${model}/__probe/image-input`, { signal: AbortSignal.timeout(15_000) });
  assert.equal(response.status, 200);
  return (await response.json()).count;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function cancelHolds() {
  const response = await fetch(`${model}/__probe/cancel-hold`, { signal: AbortSignal.timeout(15_000) });
  assert.equal(response.status, 200);
  return (await response.json()).count;
}
const sse = (text) => text.split(/\r?\n/).filter((line) => line.startsWith("data:"))
  .map((line) => JSON.parse(line.slice(5).trim()));

const owner = new Client(), other = new Client();
await owner.request("/api/auth/me", { expected: 401 });
for (const client of [owner, other]) {
  await client.request("/api/auth/register", { method: "POST", expected: 201,
    body: { email: `images-${randomUUID()}@ardor.invalid`, password: `Synthetic-${randomUUID()}!` } });
}
await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "gpt-4o", apiKey: "fake-only",
} });
await other.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "gpt-4o", apiKey: "fake-only",
} });

const upload = await owner.upload("/api/agent/images", png, "image/png");
assert.equal(upload.mediaType, "image/png");
const stored = await owner.image(`/api/agent/images/${upload.id}`);
assert.equal(stored.contentType, "image/png");
assert.deepEqual([...stored.bytes], [...png]);
await other.image(`/api/agent/images/${upload.id}`, 404);
await owner.upload("/api/agent/images", Uint8Array.from([1, 2, 3, 4]), "image/png").then(
  () => { throw new Error("Non-image bytes must be rejected"); },
  (failure) => assert.match(String(failure), /仅支持 PNG、JPEG、WebP 或 GIF 图片/));

const conversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
const before = await imageRequests();
const imageReply = await owner.request("/api/agent/messages", { method: "POST", body: {
  conversationId: conversation.id, message: "", imageIds: [upload.id], requestId: randomUUID(),
} });
assert.ok(imageReply.content, "The assistant answer is persisted");
assert.equal(await imageRequests(), before + 1, "The image reaches the model request as multimodal content");

const state = await owner.request(`/api/agent?conversationId=${conversation.id}`);
const userMessage = state.messages.find((message) => message.role === "USER");
assert.deepEqual(userMessage.imageIds, [upload.id], "History keeps the image reference");
assert.ok(state.messages.some((message) => message.role === "ASSISTANT"), "History keeps the assistant answer");

await owner.request("/api/agent/messages", { method: "POST", body: {
  conversationId: conversation.id, message: "文字消息", requestId: randomUUID(),
} });
assert.equal(await imageRequests(), before + 2, "Historical images are re-sent with later turns");
const fresh = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
await owner.request("/api/agent/messages", { method: "POST", body: {
  conversationId: fresh.id, message: "新会话文字消息", requestId: randomUUID(),
} });
assert.equal(await imageRequests(), before + 2, "Conversations without images never send image content");

const cancelConversation = await owner.request("/api/agent/conversations", { method: "POST", expected: 201 });
const cancelImage = await owner.upload("/api/agent/images", png, "image/png");
const cancelId = randomUUID();
const beforeCancel = await imageRequests();
const holdsBefore = await cancelHolds();
const pending = await owner.openStream({ conversationId: cancelConversation.id, requestId: cancelId,
  message: "CANCEL-HOLD 图片轮次：这轮被取消，但图片必须留在下一轮历史里。",
  imageIds: [cancelImage.id], reasoningDepth: "AUTO" });
for (let attempt = 0; attempt < 400 && (await cancelHolds()) === holdsBefore; attempt++) await sleep(50);
assert.ok((await cancelHolds()) > holdsBefore, "The provider must receive the image turn before it is cancelled");
const cancelled = await owner.request(`/api/agent/runs/${cancelId}/cancel`, { method: "POST", expected: 202 });
assert.equal(cancelled.errorCode, "AGENT_RUN_CANCELLED");
assert.ok(["INTERRUPTED", "FAILED"].includes(cancelled.status), `Unexpected cancelled status: ${cancelled.status}`);
await fetch(`${model}/__probe/release-cancel-hold`, { method: "POST", signal: AbortSignal.timeout(15_000) });
const cancelEvents = sse(await (await pending).text());
assert.ok(cancelEvents.some((event) => event.type === "error" && event.errorCode === "AGENT_RUN_CANCELLED"),
  JSON.stringify(cancelEvents.filter((event) => event.type === "error")));
assert.ok(!cancelEvents.some((event) => event.type === "done"), "A cancelled run must not finish as done");
assert.equal(await imageRequests(), beforeCancel + 1, "The cancelled turn itself reached the model with its image");

const cancelledState = await owner.request(`/api/agent?conversationId=${cancelConversation.id}`);
const cancelledUser = cancelledState.messages.find((message) => message.role === "USER" && message.requestId === cancelId);
assert.ok(cancelledUser, "The cancelled image turn stays in history");
assert.deepEqual(cancelledUser.imageIds, [cancelImage.id], "The cancelled turn keeps its image reference");
assert.ok(cancelledState.messages.some((message) => message.role === "ASSISTANT" && message.requestId === cancelId
  && message.content.includes("本轮已停止")), "The cancelled turn keeps its truthful status");
await owner.request("/api/agent/messages", { method: "POST", body: {
  conversationId: cancelConversation.id, message: "继续分析刚才的图片", requestId: randomUUID(),
} });
assert.equal(await imageRequests(), beforeCancel + 2, "The cancelled turn's image is re-sent with the next request");

await owner.request("/api/agent/messages", { method: "POST", expected: 400, body: {
  conversationId: conversation.id, message: "", imageIds: [upload.id, upload.id], requestId: randomUUID(),
} });
const tooMany = { conversationId: conversation.id, message: "",
  imageIds: Array.from({ length: 5 }, () => randomUUID()), requestId: randomUUID() };
const rejected = await owner.request("/api/agent/messages", { method: "POST", expected: 400, body: tooMany });
assert.equal(rejected.code, "VALIDATION_ERROR");
const foreign = await other.request("/api/agent/messages", { method: "POST", expected: 404, body: {
  conversationId: conversation.id, message: "", imageIds: [upload.id], requestId: randomUUID(),
} });
assert.equal(foreign.code, "NOT_FOUND");

await owner.request("/api/settings/llm", { method: "PUT", body: {
  provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "runtime-integration-probe", apiKey: "fake-only",
} });
const beforeUnsupported = await imageRequests();
const unsupported = await owner.request("/api/agent/messages", { method: "POST", expected: 400, body: {
  conversationId: conversation.id, message: "", imageIds: [upload.id], requestId: randomUUID(),
} });
assert.match(unsupported.message, /当前模型未声明支持图片/);
assert.equal(await imageRequests(), beforeUnsupported, "A rejected model never receives image content");

console.log("chat image acceptance passed");
