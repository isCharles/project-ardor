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
  startedAt, finishedAt: new Date(Date.parse(startedAt) + (extra.durationMs ?? 100)).toISOString(), durationMs: 100, model: null,
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
    totals: { llmCalls: 3, toolCalls: 1, loopRounds: 1, inputTokens: 12500, outputTokens: 230,
      cacheReadInputTokens: 9200, cacheCreationInputTokens: 640, cacheUsageMissingCalls: 1 },
    spans: [
      span("root", null, "RUN", "agent.run", "2026-10-23T08:00:00Z", { durationMs: 5000, attributes: { model: "fixture-model" } }),
      span("thought", "root", "THOUGHT", "phase.plan", "2026-10-23T08:00:00Z", { durationMs: 40 }),
      span("context", "root", "CONTEXT", "context.load", "2026-10-23T08:00:01Z", { durationMs: 120 }),
      span("compaction", "context", "LLM", "context.compact.1", "2026-10-23T08:00:01.010Z", { durationMs: 100, model: "fixture-model", inputTokens: 500, outputTokens: 50, attributes: { purpose: "CONTEXT_COMPACTION", call: 1 } }),
      span("llm-1", "root", "LLM", "llm.call.2", "2026-10-23T08:00:02Z", { model: "fixture-model", inputTokens: 6000, outputTokens: 32, cacheReadInputTokens: 6000, cacheCreationInputTokens: 320, attributes: { toolRound: true } }),
      span("tool-1", "root", "TOOL", "list_resumes", "2026-10-23T08:00:03Z", { durationMs: 80 }),
      span("llm-2", "root", "LLM", "llm.call.3", "2026-10-23T08:00:04Z", { model: "fixture-model", inputTokens: 6000, outputTokens: 148, attributes: { timingSource: "client-monotonic-stream-v1", firstContentMs: 250, streamAfterFirstContentMs: 750, outputTokensPerRequestSecond: 148 } }),
      // The kinds the record actually carries today, as opposed to the legacy shape above.
      span("compaction-kind", "context", "COMPACTION", "context.compact.2", "2026-10-23T08:00:01.200Z", { durationMs: 140, model: "fixture-model", inputTokens: 900, outputTokens: 40, attributes: { purpose: "CONTEXT_COMPACTION", windowTokens: null, softThresholdTokens: 43_622, projectedInputTokens: 57_431 } }),
      span("plan", "root", "PLAN", "agent.plan", "2026-10-23T08:00:00.500Z", { durationMs: 300, attributes: { revision: 0, stepCount: 2, verifiedSteps: 1, goalPreview: "把薄弱点排进本周计划" } }),
      span("plan-step", "plan", "PLAN_STEP", "plan.step.2", "2026-10-23T08:00:03.500Z", { durationMs: 60, status: "FAILED", errorCode: "STEP_UNVERIFIED", attributes: { stepIndex: 1, verified: false } }),
    ],
  };
  let traceRequests = 0;
  const conversation = {
    conversationId: "fixture-conv",
    title: "Fixture conversation",
    runs: [
      { runId: "fixture-run", message: "第一轮问题", assistantMessage: "第一轮回答", status: "COMPLETED", createdAt: "2026-10-23T08:00:00Z", updatedAt: "2026-10-23T08:00:02Z", spans: trace.spans },
      { runId: "run-2", message: "第二轮问题", assistantMessage: "第二轮回答", status: "COMPLETED", createdAt: "2026-10-23T12:00:00Z", updatedAt: "2026-10-23T12:00:05Z", spans: trace.spans.map((item) => ({ ...item, id: `${item.id}-2`, runId: "run-2", startedAt: new Date(Date.parse(item.startedAt) + 4 * 3600_000).toISOString(), finishedAt: new Date(Date.parse(item.finishedAt) + 4 * 3600_000).toISOString() })) },
    ],
  };
  let conversationRequests = 0;
  let largeTrace = false, largePages = 0;
  let denseTrace = false, activeTrace = false;
  const denseRuns = Array.from({ length: 400 }, (_, index) => {
    const start = Date.parse("2026-10-23T08:00:00Z") + index * 3600_000;
    const startedAt = new Date(start).toISOString();
    return { ...conversation.runs[0], runId: `dense-${index}`, message: `Dense turn ${index + 1}`, spans: [
      span(`dense-root-${index}`, null, "RUN", "agent.run", startedAt, { durationMs: 1000 }),
      span(`dense-model-${index}`, `dense-root-${index}`, "LLM", "llm.call.1", startedAt, { durationMs: 1000 }),
    ] };
  });
  const activeRun = { ...conversation.runs[0], runId: "active", message: "Active turn", status: "RUNNING", spans: [
    span("active-root", null, "RUN", "agent.run", "2026-10-23T08:00:00Z", { durationMs: null, finishedAt: null, status: "RUNNING" }),
    span("active-context", "active-root", "CONTEXT", "context.load", "2026-10-23T08:00:00.200Z", { durationMs: 300 }),
    span("active-model", "active-root", "LLM", "llm.call.1", "2026-10-23T08:00:00.500Z", { durationMs: 400 }),
  ] };
  const largeRuns = Array.from({ length: 105 }, (_, index) => ({ ...conversation.runs[0], runId: `large-${index}`, message: `Synthetic turn ${index + 1}`,
    spans: trace.spans.map(item => ({ ...item, id: `${item.id}-large-${index}`, runId: `large-${index}`,
      startedAt: new Date(Date.parse(item.startedAt) + index * 4 * 3600_000).toISOString(),
      finishedAt: new Date(Date.parse(item.finishedAt) + index * 4 * 3600_000).toISOString() })) }));
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
    else if (path === "/api/agent/conversations/fixture-conv/trace") {
      if (activeTrace) body = { ...conversation, runs: [activeRun], nextBefore: null };
      else if (denseTrace) {
        const cursor = new URL(route.request().url()).searchParams.get("before");
        const end = cursor ? Number(cursor.replace("dense-", "")) : denseRuns.length;
        const start = Math.max(0, end - 30);
        body = { ...conversation, runs: denseRuns.slice(start, end), nextBefore: start ? `dense-${start}` : null };
      } else if (largeTrace) {
        largePages++;
        const cursor = new URL(route.request().url()).searchParams.get("before");
        const end = cursor ? Number(cursor.replace("large-", "")) : largeRuns.length;
        const start = Math.max(0, end - 30);
        body = { ...conversation, runs: largeRuns.slice(start, end), nextBefore: start ? `large-${start}` : null };
      } else if (++conversationRequests === 1) body = { ...conversation, runs: [], nextBefore: null };
      else if (new URL(route.request().url()).searchParams.has("before")) body = { ...conversation, runs: conversation.runs.slice(0, 1), nextBefore: null };
      else body = { ...conversation, runs: conversation.runs.slice(1), nextBefore: "fixture-run" };
    }
    await route.fulfill({ json: body });
  });

  const page = await context.newPage();
  await page.goto(`${origin}/app/agent-runs/fixture-run`);
  await page.locator("[data-trace-node]").first().waitFor();
  assert.ok(traceRequests >= 2, "An initial not-found race must retry without requiring a page reload");
  assert.equal(await page.locator("[data-trace-node]").count(), 10, "Flow graph renders every span including THOUGHT and independent compaction");
  await page.getByText("Context compaction", { exact: true }).first().waitFor();
  await page.getByRole("button", { name: "Flow graph", exact: true }).waitFor();
  await page.getByRole("button", { name: "Timeline", exact: true }).waitFor();

  await page.locator("[data-trace-node]").nth(2).click();
  const fits = await page.locator("[data-flow-graph]").evaluate((element) => element.scrollWidth <= element.clientWidth + 1);
  assert.ok(fits, "Graph fits without horizontal scrolling");
  await page.getByRole("button", { name: "Inspector", exact: true }).click();
  await page.getByText("Metadata", { exact: true }).waitFor();
  await page.getByText("llm.call.2", { exact: true }).first().waitFor();
  await page.screenshot({ path: ".next/agent-trace-flow.png", fullPage: true });

  await page.getByRole("button", { name: "Inspector", exact: true }).click();
  await page.locator("[data-trace-inspector]").waitFor();
  await page.getByRole("tab", { name: "Overview", exact: true }).waitFor();
  assert.equal(await page.locator("[data-trace-inspector-item]").count(), 10, "Inspector lists every span");
  const compactions = page.locator("[data-trace-inspector-item]").filter({ hasText: "Context compaction" });
  assert.equal(await compactions.count(), 2, "The legacy LLM-shaped call and the dedicated kind both read as compaction");
  await compactions.nth(0).click();
  await page.locator("[data-trace-inspector-panel] h2").filter({ hasText: "Context compaction" }).waitFor();
  assert.equal(await page.locator("[data-trace-inspector-panel]").getByText("Trigger evidence", { exact: true }).count(), 0,
    "A pre-kind call invents no evidence it never recorded");
  await compactions.nth(1).click();
  await page.locator("[data-trace-inspector-panel]").getByText("Trigger evidence", { exact: true }).waitFor();
  // A window that was unknown when the decision was made stays visible as unknown, not hidden.
  assert.match(await page.locator("[data-trace-inspector-panel]").innerText(), /Model window[\s\S]*?unknown/i);
  assert.match(await page.locator("[data-trace-inspector-panel]").innerText(), /43\.6K tokens[\s\S]*?57\.4K tokens/i);
  const planRow = page.locator("[data-trace-inspector-item]").filter({ has: page.getByText("Plan", { exact: true }) });
  assert.equal(await planRow.count(), 1, "the accepted plan is listed as its own step");
  await planRow.click();
  await page.locator("[data-trace-inspector-panel]").getByText("Plan record", { exact: true }).waitFor();
  assert.match(await page.locator("[data-trace-inspector-panel]").innerText(), /Steps verified[\s\S]*?1 \/ 2/i);
  const inspector = page.locator("[data-trace-inspector-panel]");
  await page.locator("[data-trace-inspector-item]").filter({ hasText: "llm.call.3" }).click();
  await inspector.getByText("First content", { exact: true }).waitFor();
  await inspector.getByText("250 ms", { exact: true }).waitFor();
  await inspector.getByText("750 ms", { exact: true }).waitFor();
  await inspector.getByText("148.0 tok/s", { exact: true }).waitFor();
  await page.locator("[data-trace-inspector-item]").filter({ hasText: "llm.call.2" }).click();
  assert.equal(await inspector.getByText("148.0 tok/s", { exact: true }).count(), 0, "Historical spans never inherit another call's timing");
  // Prompt-cache counters belong to the call that reported them.
  assert.match(await inspector.innerText(), /Cache read[\s\S]*?6000/i);
  assert.match(await inspector.innerText(), /Cache write[\s\S]*?320/i);
  await page.locator("[data-trace-inspector-item]").filter({ hasText: "Context assembly" }).click();
  assert.equal(await inspector.getByText(/cache read/i).count(), 0, "A span that never reported cache usage is not given one");
  await page.locator("[data-trace-inspector-item]").filter({ hasText: "Agent run" }).click();
  await page.getByRole("tab", { name: "Timing", exact: true }).click();
  await page.getByText("Started at", { exact: true }).waitFor();
  await page.locator("[data-trace-inspector-item]").filter({ hasText: "list_resumes" }).click();
  await page.getByRole("tab", { name: "Input", exact: true }).click();
  await page.screenshot({ path: ".next/agent-trace-inspector.png", fullPage: true });

  await page.getByRole("button", { name: "Timeline", exact: true }).click();
  await page.getByText("Loop rounds", { exact: true }).waitFor();
  const header = (await page.locator("main").innerText()).replace(/\s+/g, " ");
  assert.match(header, /Cache read:\s*9200/i, header.slice(0, 400));
  assert.match(header, /Cache write:\s*640/i);
  assert.match(header, /Cache unknown:\s*1/i);
  assert.equal(await page.locator("[data-trace-node]").count(), 0, "Timeline does not keep the graph mounted");

  await page.getByRole("link", { name: "Conversation trace", exact: true }).click();
  await page.waitForURL("**?conversation=fixture-conv");
  await page.locator("[data-conversation-trace]").waitFor();
  await page.locator("[data-conversation-run]").nth(1).waitFor();
  assert.ok(conversationRequests >= 2, "An initially empty conversation trace must recover");
  assert.equal(await page.locator("[data-conversation-run]").count(), 2, "Conversation trace lists every turn");
  assert.equal(await page.locator("[data-trace-overview]").getAttribute("data-active-duration-ms"), "10000",
    "Cumulative run time sums root durations and excludes the four-hour idle gap; nested spans are not double counted");
  assert.equal(await page.locator("[data-trace-timeline]").getAttribute("data-timeline-turns"), "2");
  assert.equal(await page.locator("[data-trace-lane]").count(), 18, "Timing lanes show all turns, including older pages");
  // Compaction and plan spans belong to the same record, so they have to appear on the timing overview.
  for (const [kind, tone] of [["COMPACTION", "bg-amber-400"], ["PLAN", "bg-indigo-400"], ["PLAN_STEP", "bg-rose-400"]]) {
    const bar = page.locator(`[data-trace-lane][data-lane-kind="${kind}"]`).first();
    await bar.waitFor();
    assert.match((await bar.getAttribute("class")) ?? "", new RegExp(tone),
      kind === "PLAN_STEP" ? "an unverified step reads as a failure on the overview" : `${kind} keeps its legend colour on the overview`);
  }
  const positions = await page.locator("[data-trace-lane]").evaluateAll(items => items.map(item => item.getAttribute("style")));
  await page.locator("[data-conversation-run]").first().click();
  assert.deepEqual(await page.locator("[data-trace-lane]").evaluateAll(items => items.map(item => item.getAttribute("style"))), positions, "Selecting a turn never rescales the conversation overview");
  await page.getByRole("button", { name: "List", exact: true }).waitFor();
  await page.locator('[data-trace-lane][title^="llm.call.2"]').first().click();
  await page.locator("[data-trace-inspector-panel] h2").filter({ hasText: "llm.call.2" }).waitFor();
  await page.getByRole("button", { name: "Flow graph", exact: true }).click();
  assert.ok(await page.locator("[data-trace-node]").count() > 0, "Graph step view renders the flow");
  const graphLayout = await page.locator("[data-conversation-trace]").evaluate((element) => {
    const section = element.getBoundingClientRect();
    const steps = element.querySelector("[data-trace-steps]").getBoundingClientRect();
    const turns = element.querySelector("[data-trace-turns]").getBoundingClientRect();
    const inspector = element.querySelector("[data-trace-selection]").getBoundingClientRect();
    return { width: section.width, graphWidth: steps.width, graphBottom: steps.bottom, turnsTop: turns.top, inspectorTop: inspector.top };
  });
  assert.ok(graphLayout.graphWidth >= graphLayout.width - 2, "Graph occupies the entire trace width");
  assert.ok(graphLayout.turnsTop >= graphLayout.graphBottom && graphLayout.inspectorTop >= graphLayout.graphBottom,
    "Conversation turns and inspector move below the full-width graph");
  await page.screenshot({ path: ".next/agent-trace-conversation-graph.png", fullPage: true });
  await page.locator("[data-trace-node]").filter({ hasText: "llm.call.2" }).click();
  await page.locator("[data-trace-inspector-panel] h2").filter({ hasText: "llm.call.2" }).waitFor();
  for (const width of [900, 390]) {
    await page.setViewportSize({ width, height: 1100 });
    await page.waitForFunction(() => {
      const graph = document.querySelector("[data-flow-graph]");
      return graph && graph.scrollWidth <= graph.clientWidth + 1;
    });
    const layoutFits = await page.locator("[data-conversation-trace]").evaluate((element) => {
      const steps = element.querySelector("[data-trace-steps]").getBoundingClientRect();
      const turns = element.querySelector("[data-trace-turns]").getBoundingClientRect();
      const inspector = element.querySelector("[data-trace-selection]").getBoundingClientRect();
      return steps.width >= element.clientWidth - 2 && turns.top >= steps.bottom && inspector.top >= steps.bottom
        && document.documentElement.scrollWidth <= window.innerWidth + 1;
    });
    assert.ok(layoutFits, `Full-width graph and lower panels fit at ${width}px`);
  }
  await page.screenshot({ path: ".next/agent-trace-conversation-graph-mobile.png", fullPage: true });
  await page.setViewportSize({ width: 1400, height: 1100 });
  await page.getByRole("button", { name: "List", exact: true }).click();
  const listLayout = await page.locator("[data-conversation-trace]").evaluate((element) => {
    const steps = element.querySelector("[data-trace-steps]").getBoundingClientRect();
    const turns = element.querySelector("[data-trace-turns]").getBoundingClientRect();
    const inspector = element.querySelector("[data-trace-selection]").getBoundingClientRect();
    return { stepsTop: steps.top, turnsTop: turns.top, inspectorTop: inspector.top };
  });
  assert.equal(listLayout.stepsTop, listLayout.turnsTop, "List restores the compact three-column structure");
  assert.equal(listLayout.stepsTop, listLayout.inspectorTop);
  await page.locator("[data-conversation-run]").nth(1).click();
  await page.getByText("第二轮问题", { exact: true }).first().waitFor();
  await page.screenshot({ path: ".next/agent-trace-conversation.png", fullPage: true });
  largeTrace = true;
  await page.goto(`${origin}/app/agent-runs/fixture-run?conversation=fixture-conv`);
  await page.waitForFunction(() => document.querySelector("[data-trace-timeline]")?.getAttribute("data-timeline-turns") === "105");
  assert.equal(largePages, 4, "All 105 recorded turns load through four bounded pages");
  assert.equal(await page.locator("[data-conversation-run]").count(), 105);
  assert.equal(await page.locator("[data-trace-timeline]").getAttribute("data-timeline-duration-ms"), "525000");
  assert.equal(await page.locator("[data-trace-lane]").count(), 945);
  const globalPositions = await page.locator("[data-trace-lane]").evaluateAll(items => items.map(item => item.getAttribute("style")));
  await page.locator("[data-conversation-run]").first().click();
  assert.deepEqual(await page.locator("[data-trace-lane]").evaluateAll(items => items.map(item => item.getAttribute("style"))), globalPositions);
  await page.setViewportSize({ width: 390, height: 900 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  denseTrace = true;
  await page.goto(`${origin}/app/agent-runs/fixture-run?conversation=fixture-conv`);
  await page.waitForFunction(() => document.querySelector("[data-trace-timeline]")?.getAttribute("data-timeline-turns") === "400");
  assert.equal(await page.locator("[data-trace-lane]").count(), 400);
  const tail = await page.locator('[data-trace-lane][data-lane-run="dense-398"], [data-trace-lane][data-lane-run="dense-399"]').evaluateAll(items => items.map(item => item.style.left));
  assert.notEqual(tail[0], tail[1], "Final turns must retain distinct start positions on the scrollable canvas");
  await page.locator('[data-trace-lane][data-lane-run="dense-398"]').click();
  await page.locator("[data-trace-inspector-panel]").getByText("Turn #399", { exact: true }).waitFor();
  await page.locator('[data-trace-lane][data-lane-run="dense-399"]').click();
  await page.locator("[data-trace-inspector-panel]").getByText("Turn #400", { exact: true }).waitFor();
  activeTrace = true;
  await page.goto(`${origin}/app/agent-runs/fixture-run?conversation=fixture-conv`);
  await page.waitForFunction(() => document.querySelector("[data-trace-timeline]")?.getAttribute("data-timeline-turns") === "1");
  assert.equal(await page.locator("[data-trace-lane]").count(), 2, "Completed children remain visible before the root finishes");
  assert.equal(await page.locator("[data-trace-timeline]").getAttribute("data-timeline-duration-ms"), "900");
  await context.close();
  console.log("Agent trace UI: graph nodes, metadata selection, inspector, timeline, multi-turn trace passed.");
} finally {
  await browser?.close();
  server.kill();
}
