import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic messages and stream events only; never calls a model or a real account.
const origin = "http://127.0.0.1:3130";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3130"], { stdio: "pipe" });
let logs = "", browser, liveDone = false;
const date = new Date().toISOString();
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const savedTrace = JSON.stringify({
  elapsedMs: 12400, status: "COMPLETED", thinkingOutput: "Synthetic private reasoning",
  steps: [
    { label: "模型输出与上游等待（非纯思考时间）", elapsedMs: 12000, status: "MODEL_WAIT" },
    { label: "我先查看今天的日历。", elapsedMs: 0, status: "COMMENTARY" },
    { label: "读取日历", elapsedMs: 19, status: "COMPLETED" },
    { label: "读取学习计划", elapsedMs: 27, status: "COMPLETED" },
    { label: "日历里 11 月 3 日上午空闲，我再核对学习计划。", elapsedMs: 0, status: "COMMENTARY" },
    { label: "更新学习计划", elapsedMs: 18, status: "COMPLETED" },
  ],
});
const liveTrace = JSON.stringify({
  elapsedMs: 50, status: "COMPLETED",
  steps: [
    { label: "模型输出与上游等待（非纯思考时间）", elapsedMs: 30, status: "MODEL_WAIT" },
    { label: "我先看一下你本周的日历。", elapsedMs: 0, status: "COMMENTARY" },
    { label: "列出日程", elapsedMs: 20, status: "COMPLETED" },
    { label: "周四下午空闲，我来创建组会。", elapsedMs: 0, status: "COMMENTARY" },
  ],
});
const messages = () => [
  { id: "narrate-u0", role: "USER", content: "早上好", createdAt: date },
  { id: "narrate-a0", role: "ASSISTANT", content: "旧回答", runId: "narrate-run-0", createdAt: date, runTrace: savedTrace },
  ...liveDone ? [
    { id: "narrate-u1", role: "USER", content: "帮我安排组会", createdAt: date },
    { id: "narrate-a1", role: "ASSISTANT", content: "最终回答", runId: "narrate-run-1", createdAt: date, runTrace: liveTrace },
  ] : [],
];
const timelineShape = element => [...element.querySelectorAll("[data-run-say], [data-tool-group]")]
  .map(node => node.hasAttribute("data-run-say") ? "say" : "tools");

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
    if (path === "/api/auth/me") body = { id: "narrate-owner", email: "narrate@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/profile/sidebar") body = { userId: "narrate-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/agent") body = { conversationId: "narrate-chat", llmConfigured: true, memory: { content: "" },
      conversations: [{ id: "narrate-chat", title: "Synthetic narration", createdAt: date, updatedAt: date }], archivedConversations: [], messages: messages() };
    else if (path === "/api/agent/runs/latest") return route.fulfill({ status: 204 });
    else if (path === "/api/agent/models") body = [{ id: "PRIMARY", label: "synthetic-model", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true }];
    else if (path === "/api/agent/permissions") body = { mode: "AUTO", globalLevel: 2, modules: [] };
    else if (path === "/api/settings/llm") body = { configured: true };
    else if (path === "/api/usage") body = null;
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app`);

  // A finished turn: spoken lines stay visible, tools collapse under each line.
  const savedTimeline = page.locator("[data-execution-timeline]").first();
  await savedTimeline.waitFor();
  assert.equal(await page.locator("[data-execution-timeline]").count(), 1);
  assert.deepEqual((await savedTimeline.locator("[data-run-say]").allInnerTexts()).map(text => text.trim()),
    ["我先查看今天的日历。", "日历里 11 月 3 日上午空闲，我再核对学习计划。"]);
  assert.deepEqual(await savedTimeline.evaluate(timelineShape), ["say", "tools", "say", "tools"],
    "Nothing may be reordered between the spoken line and its tools");
  assert.equal(await savedTimeline.locator("[data-run-say]").first().evaluate(node => !node.closest("details")), true,
    "Narration is read directly, not buried inside a disclosure");
  const groups = savedTimeline.locator("[data-tool-group]");
  assert.equal(await groups.count(), 2);
  assert.equal((await groups.nth(0).locator("summary").innerText()).trim(), "Used 2 tools");
  assert.equal((await groups.nth(1).locator("summary").innerText()).trim(), "Used 1 tool");
  assert.equal(await groups.nth(0).getAttribute("open"), null, "Tool groups default to collapsed");
  assert.equal(await groups.nth(1).getAttribute("open"), null);
  await groups.nth(0).locator("summary").click();
  await groups.nth(0).getByText("读取日历", { exact: true }).waitFor();
  assert.match(await groups.nth(0).innerText(), /读取学习计划/);
  assert.doesNotMatch(await groups.nth(0).textContent(), /我先查看今天的日历/,
    "The tool group does not repeat the spoken line");
  const reasoning = savedTimeline.locator("details").first();
  await reasoning.getByText("Model reasoning output", { exact: true }).click();
  await reasoning.getByText("Synthetic private reasoning", { exact: true }).waitFor();
  const savedArticle = page.getByRole("article").filter({ hasText: "旧回答" });
  const savedAnswer = savedArticle.locator("[data-answer]");
  assert.equal((await savedAnswer.innerText()).trim(), "旧回答");
  assert.equal(await savedArticle.evaluate(element => {
    const timeline = element.querySelector("[data-execution-timeline]").getBoundingClientRect();
    const answer = element.querySelector("[data-answer]").getBoundingClientRect();
    return timeline.bottom <= answer.top;
  }), true, "The whole timeline stays above the final answer");
  await page.screenshot({ path: ".next/run-timeline-saved.png", fullPage: true });

  // The same interleaving appears while the run is still streaming.
  await page.evaluate(() => {
    const original = window.fetch.bind(window);
    const encoder = new TextEncoder();
    window.__runStream = [
      { event: { type: "status", label: "等待模型输出" }, hold: true },
      { event: { type: "intermediate", content: "我先看一下你本周的日历。" }, hold: true },
      { event: { type: "tool_start", toolName: "list_calendar", label: "列出日程" }, hold: true },
      { event: { type: "tool_end", toolName: "list_calendar", label: "列出日程", elapsedMs: 20, toolStatus: "COMPLETED" }, hold: true },
      { event: { type: "intermediate", content: "周四下午空闲，我来创建组会。" }, hold: true },
      { event: { type: "delta", content: "最终回答" }, hold: true },
      { event: { type: "done", conversationId: "narrate-chat", messageId: "narrate-a1", content: "最终回答", elapsedMs: 50 } },
    ];
    window.fetch = async (input, init) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url;
      if (!url.includes("/api/agent/messages/stream")) return original(input, init);
      return new Response(new ReadableStream({
        async start(controller) {
          window.__runStreamHolds = 0;
          for (const item of window.__runStream) {
            controller.enqueue(encoder.encode(`data: ${JSON.stringify(item.event)}\n\n`));
            if (item.hold) {
              window.__runStreamHolds++;
              await new Promise(resolve => { window.__releaseRunStream = resolve; });
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
    await page.waitForFunction(expected => window.__runStreamHolds >= expected, holds, { timeout: 5000 });
    await page.evaluate(() => window.__releaseRunStream());
  };
  await release(1);
  await release(2);
  const liveTimeline = page.locator("[data-execution-timeline]").last();
  await liveTimeline.getByText("我先看一下你本周的日历。", { exact: true }).waitFor();
  const liveGroup = liveTimeline.locator("[data-tool-group]").first();
  await liveGroup.getByText("Used 1 tool", { exact: true }).waitFor();
  await liveGroup.getByText("in progress", { exact: true }).waitFor();
  assert.deepEqual(await liveTimeline.evaluate(timelineShape), ["say", "tools"]);
  await page.screenshot({ path: ".next/run-timeline-live.png", fullPage: true });
  await release(3);
  await release(4);
  await liveTimeline.getByText("周四下午空闲，我来创建组会。", { exact: true }).waitFor();
  assert.equal(await liveGroup.getByText("in progress", { exact: true }).count(), 0);
  await liveGroup.locator("summary").click();
  await liveGroup.getByText("列出日程", { exact: true }).waitFor();
  liveDone = true;
  await release(5);
  await release(6);
  await page.getByRole("button", { name: "Send", exact: true }).waitFor();
  await page.locator("[data-answer]").last().getByText("最终回答", { exact: true }).waitFor();
  assert.equal(await page.locator("[data-execution-timeline]").count(), 2, "One timeline per turn, no duplicate live copy");
  const finalTimeline = page.locator("[data-execution-timeline]").last();
  assert.deepEqual((await finalTimeline.locator("[data-run-say]").allInnerTexts()).map(text => text.trim()),
    ["我先看一下你本周的日历。", "周四下午空闲，我来创建组会。"]);
  assert.equal(await finalTimeline.locator("[data-tool-group]").count(), 1);
  assert.equal(await finalTimeline.locator("[data-tool-group]").first().getAttribute("open"), null,
    "A reloaded turn restores collapsed tool groups");
  console.log("Run timeline UI: narration interleaved with collapsed tool groups, live and saved, no duplicated lines passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
