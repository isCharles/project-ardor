import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic messages and stream events only; never calls a model or a real account.
const origin = "http://127.0.0.1:3129";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3129"], { stdio: "pipe" });
let logs = "", browser, liveDone = false;
const date = new Date().toISOString();
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const savedTrace = JSON.stringify({
  elapsedMs: 2400, status: "COMPLETED",
  steps: [
    { label: "我先读取今天的日历。接下来确认空闲时段。", elapsedMs: 0, status: "COMMENTARY", done: true },
    { label: "列出日程", elapsedMs: 120, status: "COMPLETED", done: true },
    { label: "日历里 11 月 3 日上午空闲，我会把组会放进去。", elapsedMs: 0, status: "COMMENTARY", done: true },
    { label: "创建日程", elapsedMs: 90, status: "COMPLETED", done: true },
  ],
});
const liveTrace = JSON.stringify({
  elapsedMs: 50, status: "COMPLETED",
  steps: [
    { label: "我先看一下你本周的日历。然后确认周四下午是否空闲。", elapsedMs: 0, status: "COMMENTARY", done: true },
    { label: "列出日程", elapsedMs: 20, status: "COMPLETED", done: true },
    { label: "周四下午空闲，我来创建组会。", elapsedMs: 0, status: "COMMENTARY", done: true },
  ],
});
const messages = () => [
  { id: "intent-u0", role: "USER", content: "早上好", createdAt: date },
  { id: "intent-a0", role: "ASSISTANT", content: "旧回答", runId: "intent-run-0", createdAt: date, runTrace: savedTrace },
  ...liveDone ? [
    { id: "intent-u1", role: "USER", content: "帮我安排组会", createdAt: date },
    { id: "intent-a1", role: "ASSISTANT", content: "最终回答", runId: "intent-run-1", createdAt: date, runTrace: liveTrace },
  ] : [],
];

try {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: "reduce" });
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "intent-owner", email: "intent@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/profile/sidebar") body = { userId: "intent-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/agent") body = { conversationId: "intent-chat", llmConfigured: true, memory: { content: "" },
      conversations: [{ id: "intent-chat", title: "Synthetic intents", createdAt: date, updatedAt: date }], archivedConversations: [], messages: messages() };
    else if (path === "/api/agent/runs/latest") return route.fulfill({ status: 204 });
    else if (path === "/api/agent/models") body = [{ id: "PRIMARY", label: "synthetic-model", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true }];
    else if (path === "/api/agent/permissions") body = { mode: "AUTO", globalLevel: 2, modules: [] };
    else if (path === "/api/settings/llm") body = { configured: true };
    else if (path === "/api/usage") body = null;
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app`);

  // A finished turn keeps the model's next steps above the answer instead of inside the collapsed trace.
  const savedIntents = page.locator("[data-run-intents]").first();
  await savedIntents.waitFor();
  assert.equal(await page.locator("[data-run-intents]").count(), 1);
  const savedLines = (await savedIntents.innerText()).split("\n").map(line => line.trim()).filter(Boolean);
  assert.deepEqual(savedLines, ["我先读取今天的日历。", "日历里 11 月 3 日上午空闲，我会把组会放进去。"]);
  const trace = page.locator("[data-execution-trace]").first();
  assert.equal(await trace.locator("details").first().getAttribute("open"), null, "A finished trace stays collapsed");
  const traceText = await trace.textContent();
  assert.match(traceText, /列出日程/);
  assert.match(traceText, /创建日程/);
  assert.doesNotMatch(traceText, /我先读取今天的日历/, "Commentary is not repeated inside the trace");
  const savedOrder = await page.locator("main").evaluate(() => {
    const intents = document.querySelector("[data-run-intents]").getBoundingClientRect();
    const answer = document.querySelector(".ardor-markdown").getBoundingClientRect();
    return { intentsBottom: intents.bottom, answerTop: answer.top };
  });
  assert.ok(savedOrder.intentsBottom <= savedOrder.answerTop, "Next steps read before the answer");
  await page.screenshot({ path: ".next/run-intents-saved.png", fullPage: true });

  // The same lines appear while the run is still streaming.
  await page.evaluate(() => {
    const original = window.fetch.bind(window);
    const encoder = new TextEncoder();
    window.__intentStream = [
      { event: { type: "status", label: "等待模型输出" }, hold: true },
      { event: { type: "intermediate", content: "我先看一下你本周的日历。然后确认周四下午是否空闲。" }, hold: true },
      { event: { type: "tool_start", toolName: "list_calendar", label: "列出日程" }, hold: true },
      { event: { type: "tool_end", toolName: "list_calendar", label: "列出日程", elapsedMs: 20, toolStatus: "COMPLETED" }, hold: true },
      { event: { type: "intermediate", content: "周四下午空闲，我来创建组会。" }, hold: true },
      { event: { type: "delta", content: "最终回答" }, hold: true },
      { event: { type: "done", conversationId: "intent-chat", messageId: "intent-a1", content: "最终回答", elapsedMs: 50 } },
    ];
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.includes("/api/agent/messages/stream")) return original(input, init);
      return new Response(new ReadableStream({
        async start(controller) {
          window.__intentStreamHolds = 0;
          for (const item of window.__intentStream) {
            controller.enqueue(encoder.encode(`data: ${JSON.stringify(item.event)}\n\n`));
            if (item.hold) {
              window.__intentStreamHolds++;
              await new Promise(resolve => { window.__releaseIntentStream = resolve; });
            }
          }
          controller.close();
        },
      }), { status: 200, headers: { "Content-Type": "text/event-stream" } });
    };
  });
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).fill("帮我安排组会");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  const release = async holds => {
    await page.waitForFunction(expected => window.__intentStreamHolds >= expected, holds, { timeout: 5000 });
    await page.evaluate(() => window.__releaseIntentStream());
  };
  await release(1);
  await release(2);
  const liveIntents = page.locator("[data-run-intents]").last();
  await liveIntents.getByText("我先看一下你本周的日历。", { exact: true }).waitFor();
  assert.equal(await page.locator("[data-run-intents]").count(), 2, "The live turn reuses the same strip");
  await page.screenshot({ path: ".next/run-intents-live.png", fullPage: true });
  await release(3);
  await release(4);
  await release(5);
  await liveIntents.getByText("周四下午空闲，我来创建组会。", { exact: true }).waitFor();
  const liveTraceText = await page.locator("[data-execution-trace]").last().textContent();
  assert.match(liveTraceText, /列出日程/);
  assert.doesNotMatch(liveTraceText, /我先看一下你本周的日历/, "Live commentary is not repeated inside the trace");
  liveDone = true;
  await release(6);
  await page.getByRole("button", { name: "Send", exact: true }).waitFor();
  await page.locator(".ardor-markdown").getByText("最终回答", { exact: true }).waitFor();
  assert.equal(await page.locator("[data-run-intents]").count(), 2, "A reloaded conversation shows one strip per turn, never a duplicate");
  assert.deepEqual((await page.locator("[data-run-intents]").last().innerText()).split("\n").map(line => line.trim()).filter(Boolean),
    ["我先看一下你本周的日历。", "周四下午空闲，我来创建组会。"]);
  console.log("Run intents UI: saved and streaming next-step lines, commentary removed from the trace, no duplicate strips passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
