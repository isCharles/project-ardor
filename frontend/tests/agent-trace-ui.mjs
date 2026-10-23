import assert from "node:assert/strict";
import { spawn } from "node:child_process";

import { chromium } from "playwright";

// Isolated developer-trace fixture. Never reads a real run or model payload.
const origin = "http://127.0.0.1:3104";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3104"], { stdio: "pipe" });
let logs = "";
server.stdout.on("data", (chunk) => { logs += chunk; });
server.stderr.on("data", (chunk) => { logs += chunk; });
let browser;

const span = (id, parentSpanId, kind, name, startedAt, extra = {}) => ({
  id, runId: "fixture-run", parentSpanId, kind, name, status: "COMPLETED",
  startedAt, finishedAt: startedAt, durationMs: 100, model: null,
  promptVersion: null, inputTokens: null, outputTokens: null,
  errorCode: null, attributes: {}, ...extra,
});

try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1100 } });
  const trace = {
    runId: "fixture-run",
    conversationId: "fixture-conv",
    status: "COMPLETED",
    durationMs: 2400,
    totals: { llmCalls: 2, toolCalls: 1, loopRounds: 1, inputTokens: 12000, outputTokens: 180 },
    spans: [
      span("root", null, "RUN", "agent.run", "2026-10-23T08:00:00Z", { durationMs: 2400, attributes: { model: "fixture-model" } }),
      span("thought", "root", "THOUGHT", "phase.plan", "2026-10-23T08:00:00Z", { durationMs: 40 }),
      span("context", "root", "CONTEXT", "context.load", "2026-10-23T08:00:01Z", { durationMs: 120 }),
      span("llm-1", "root", "LLM", "llm.call.1", "2026-10-23T08:00:02Z", { model: "fixture-model", inputTokens: 6000, outputTokens: 32, attributes: { toolRound: true } }),
      span("tool-1", "root", "TOOL", "list_resumes", "2026-10-23T08:00:03Z", { durationMs: 80 }),
      span("llm-2", "root", "LLM", "llm.call.2", "2026-10-23T08:00:04Z", { model: "fixture-model", inputTokens: 6000, outputTokens: 148 }),
    ],
  };
  let traceRequests = 0;
  const conversation = {
    conversationId: "fixture-conv",
    title: "Fixture conversation",
    runs: [
      { runId: "fixture-run", message: "第一轮问题", assistantMessage: "第一轮回答", status: "COMPLETED", createdAt: "2026-10-23T08:00:00Z", updatedAt: "2026-10-23T08:00:02Z", spans: trace.spans },
      { runId: "run-2", message: "第二轮问题", assistantMessage: "第二轮回答", status: "COMPLETED", createdAt: "2026-10-23T08:01:00Z", updatedAt: "2026-10-23T08:01:02Z", spans: trace.spans.map((item) => ({ ...item, id: `${item.id}-2`, runId: "run-2", startedAt: "2026-10-23T08:01:00Z", finishedAt: "2026-10-23T08:01:02Z" })) },
    ],
  };
  let conversationRequests = 0;
  await context.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let body = {};
    if (path === "/api/auth/me") body = { id: "trace-fixture", email: "trace-fixture@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "trace-fixture", hrefs: ["/app"], revision: 1, configured: true };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/agent/runs/fixture-run/trace") {
      if (++traceRequests === 1) return route.fulfill({ status: 404, json: { message: "Trace is starting" } });
      body = trace;
    }
    else if (path === "/api/agent/conversations/fixture-conv/trace") body = ++conversationRequests === 1 ? { ...conversation, runs: [] } : conversation;
    await route.fulfill({ json: body });
  });

  const page = await context.newPage();
  await page.goto(`${origin}/app/agent-runs/fixture-run`);
  await page.locator("[data-trace-node]").first().waitFor();
  assert.ok(traceRequests >= 2, "An initial not-found race must retry without requiring a page reload");
  assert.equal(await page.locator("[data-trace-node]").count(), 6, "Flow graph renders every span including THOUGHT phases");
  await page.getByRole("button", { name: "Flow", exact: true }).waitFor();
  await page.getByRole("button", { name: "Timeline", exact: true }).waitFor();

  await page.locator("[data-trace-node]").nth(2).click();
  const fits = await page.locator("[data-flow-graph]").evaluate((element) => element.scrollWidth <= element.clientWidth + 1);
  assert.ok(fits, "Graph fits without horizontal scrolling");
  await page.getByRole("button", { name: "Trajectory", exact: true }).click();
  await page.getByText("Metadata", { exact: true }).waitFor();
  await page.getByText("llm.call.1", { exact: true }).first().waitFor();
  await page.screenshot({ path: ".next/agent-trace-flow.png", fullPage: true });

  await page.getByRole("button", { name: "Trajectory", exact: true }).click();
  await page.locator("[data-trace-inspector]").waitFor();
  await page.getByRole("tab", { name: "Overview", exact: true }).waitFor();
  assert.equal(await page.locator("[data-trace-inspector-item]").count(), 6, "Inspector lists every span");
  await page.getByRole("tab", { name: "Timing", exact: true }).click();
  await page.getByText("Started at", { exact: true }).waitFor();
  await page.locator("[data-trace-inspector-item]").filter({ hasText: "list_resumes" }).click();
  await page.getByRole("tab", { name: "Input", exact: true }).click();
  await page.screenshot({ path: ".next/agent-trace-inspector.png", fullPage: true });

  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByText("Loop rounds", { exact: true }).waitFor();
  assert.equal(await page.locator("[data-trace-node]").count(), 0, "Timeline does not keep the graph mounted");

  await page.getByRole("link", { name: "Conversation trace", exact: true }).click();
  await page.waitForURL("**?conversation=fixture-conv");
  await page.locator("[data-conversation-trace]").waitFor();
  await page.locator("[data-conversation-run]").nth(1).waitFor();
  assert.ok(conversationRequests >= 2, "An initially empty conversation trace must recover");
  assert.equal(await page.locator("[data-conversation-run]").count(), 2, "Conversation trace lists every turn");
  await page.getByRole("button", { name: "List", exact: true }).waitFor();
  await page.locator('[data-trace-lane][title^="llm.call.1"]').first().click();
  await page.locator("[data-trace-inspector-panel] h2").filter({ hasText: "llm.call.1" }).waitFor();
  await page.getByRole("button", { name: "Graph", exact: true }).click();
  assert.ok(await page.locator("[data-trace-node]").count() > 0, "Graph step view renders the flow");
  await page.getByRole("button", { name: "List", exact: true }).click();
  await page.locator("[data-conversation-run]").nth(1).click();
  await page.getByText("第二轮问题", { exact: true }).first().waitFor();
  await page.screenshot({ path: ".next/agent-trace-conversation.png", fullPage: true });
  await context.close();
  console.log("Agent trace UI: graph nodes, metadata selection, inspector, timeline, multi-turn trace passed.");
} finally {
  await browser?.close();
  server.kill();
}
