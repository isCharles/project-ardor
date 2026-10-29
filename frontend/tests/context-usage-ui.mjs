import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic progress and persisted message fixtures only; never calls a model or a real account.
const origin = "http://127.0.0.1:3120";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3120"], { stdio: "pipe" });
let logs = "", browser, usage = null, conversationContext = null, running = false;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const conversation = { id: "context-chat", title: "Synthetic context", createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "context-owner", email: "context@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "context-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/agent") body = { conversationId: conversation.id, llmConfigured: true, memory: { content: "" }, conversations: [conversation], archivedConversations: [], messages: [{ id: "context-answer", role: "ASSISTANT", content: "Synthetic answer", runId: "context-run", createdAt: conversation.createdAt, runTrace: JSON.stringify({ elapsedMs: 10, status: "COMPLETED", steps: [], contextUsage: running ? null : usage, conversationContextUsage: conversationContext }) }] };
    else if (path === "/api/agent/runs/latest") {
      if (!running) return route.fulfill({ status: 204 });
      body = { id: "context-run", conversationId: conversation.id, message: "Synthetic request", status: "RUNNING", label: "Working", createdAt: conversation.createdAt };
    } else if (path.endsWith("/context-run/progress")) body = { phase: "WAITING", label: "Preparing", thinkingOutput: "", answerPartial: "北京晴 4~16℃，投递 30~40 份。~~旧安排~~", steps: [], contextUsage: usage };
    else if (path === "/api/agent/permissions") body = { mode: "CUSTOM", globalLevel: 1, modules: [] };
    else if (path === "/api/agent/models") body = [
      { id: "PRIMARY", label: "synthetic-model", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true },
      { id: "FALLBACK:other-model", label: "other-model", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true },
    ];
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    return route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app`);
  const indicator = page.getByRole("button", { name: "Context usage", exact: true });
  await indicator.waitFor();
  assert.equal(await indicator.getAttribute("data-context-state"), "UNKNOWN");
  usage = { state: "READY", model: "synthetic-model", estimatedTokens: 1800, compressionBudgetTokens: 12000, modelWindowTokens: null, summaryVersion: 2, compactedMessages: 8 };
  await page.reload();
  await page.locator('[data-context-state="READY"]').waitFor();
  await indicator.hover();
  const tooltip = page.getByRole("tooltip");
  await tooltip.getByText("Capacity unknown", { exact: true }).waitFor();
  // A streaming transcript scrolls under a pointer that never moved; the estimate has to stay readable.
  await page.evaluate(() => window.dispatchEvent(new Event("scroll")));
  await tooltip.getByText("Capacity unknown", { exact: true }).waitFor();
  assert.match(await tooltip.innerText(), /≈1\.8K \/ — tokens/);
  assert.equal(await tooltip.locator("p").count(), 3);
  assert.doesNotMatch(await tooltip.innerText(), /12,000|summary|schemas|synthetic-model/i);
  assert.doesNotMatch(await indicator.innerText(), /%/);
  assert.equal(await indicator.locator("svg").getAttribute("data-context-fill"), "unknown");
  for (const [tokens, fill] of [[25000, 0.25], [75000, 0.75], [150000, 1]]) {
    usage = { ...usage, estimatedTokens: tokens, modelWindowTokens: 100000 };
    await page.reload();
    await page.locator('[data-context-state="READY"]').waitFor();
    assert.equal(Number(await indicator.locator("svg").getAttribute("data-context-fill")), fill);
    await indicator.hover();
    await tooltip.getByText(`${Math.round(tokens / 100000 * 100)}% full`, { exact: true }).waitFor();
  }
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  await page.getByTitle("other-model", { exact: true }).click();
  assert.equal(await indicator.getAttribute("data-context-state"), "UNKNOWN", "Never show the previous model's percentage for a newly selected model");
  await page.getByTitle("synthetic-model", { exact: true }).click();
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  await page.locator('[data-context-state="READY"]').waitFor();
  // The ring reads the first provider request of the turn, not the tool-heavy last request.
  usage = { state: "READY", model: "synthetic-model", estimatedTokens: 62000, compressionBudgetTokens: 139428,
    modelWindowTokens: 200000, summaryVersion: 2, compactedMessages: 8, authoritative: true, conversationAnchor: false };
  conversationContext = { ...usage, estimatedTokens: 18000, conversationAnchor: true };
  await page.reload();
  await page.locator('[data-context-state="READY"]').waitFor();
  assert.equal(Number(await indicator.locator("svg").getAttribute("data-context-fill")), 0.09,
    "The ring must use the conversation anchor, not the transient tool-round peak");
  await indicator.hover();
  await tooltip.getByText("9% full", { exact: true }).waitFor();
  conversationContext = null;
  running = true;
  usage = { ...usage, state: "COMPACTING", estimatedTokens: null };
  await page.reload();
  await page.locator('[data-context-state="COMPACTING"]').waitFor();
  const partialReply = page.getByLabel("Reply in progress", { exact: true });
  await partialReply.waitFor();
  assert.match(await partialReply.innerText(), /4~16℃.*30~40/);
  assert.equal(await partialReply.locator("del").count(), 1);
  assert.equal(await partialReply.locator("del").innerText(), "旧安排");
  await indicator.hover();
  await tooltip.getByText("Summarizing earlier messages", { exact: true }).waitFor();
  usage = { ...usage, state: "COMPACTION_FAILED", estimatedTokens: 1800 };
  await page.locator('[data-context-state="COMPACTION_FAILED"]').waitFor();
  await indicator.focus();
  await tooltip.getByText("History summary failed; every message was kept", { exact: true }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  await indicator.click();
  await tooltip.waitFor();
  const tooltipBox = await tooltip.boundingBox();
  assert.ok(tooltipBox.x >= 0 && tooltipBox.x + tooltipBox.width <= 390, "Tooltip stays inside the viewport");
  assert.ok(tooltipBox.y >= 0 && tooltipBox.y + tooltipBox.height <= 844, "Tooltip is not vertically clipped");
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false, "No horizontal page overflow");
  await page.getByRole("button", { name: "Send", exact: true }).waitFor({ state: "visible" }).catch(async () => {
    await page.getByRole("button", { name: "Stop", exact: true }).waitFor({ state: "visible" });
  });
  await page.screenshot({ path: ".next/context-usage-narrow.png" });
  assert.ok((await indicator.boundingBox()).width > 0);
  // The compaction itself has to be readable in the transcript, not only in the tooltip.
  assert.equal(await page.locator("[data-compaction-notice]").count(), 0, "No notice when nothing was compacted");
  running = false;
  usage = { state: "READY", model: "synthetic-model", estimatedTokens: 34442, compressionBudgetTokens: 12000,
    modelWindowTokens: 65536, summaryVersion: 1, compactedMessages: 2,
    compaction: { outcome: "COMPACTED", spanId: "compact-span", windowTokens: 65536, softThresholdTokens: 43622,
      projectedInputTokens: 57431, estimatedInputTokensAfter: 34442, summarizedMessages: 2, firstMessageOrdinal: 1,
      summaryVersionBefore: 0, summaryVersionAfter: 1, errorCode: null } };
  await page.reload();
  const notice = page.locator("[data-compaction-notice]").first();
  await notice.waitFor();
  assert.equal(await page.locator("[data-compaction-notice]").count(), 1, "One notice per compacted turn");
  assert.match(await notice.innerText(), /Summarized messages 1-2[\s\S]*57\.4K → 34\.4K tokens \(threshold 43\.6K\)/);
  assert.equal(await notice.locator("a").first().getAttribute("href"), "/app/agent-runs/context-run?span=compact-span",
    "The notice points at the span that recorded the decision");
  usage = { ...usage, state: "COMPACTION_FAILED",
    compaction: { ...usage.compaction, outcome: "FAILED", estimatedInputTokensAfter: null,
      errorCode: "COMPACTION_MODEL_UNAVAILABLE" } };
  await page.reload();
  assert.match(await notice.innerText(), /could not be summarized \(COMPACTION_MODEL_UNAVAILABLE\)[\s\S]*kept every message verbatim/,
    "A degraded compaction is visible instead of silent");
  console.log("Context usage UI: legacy unknown, persisted estimate, honest unknown window, live compaction/failure, in-transcript notice and narrow viewport passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
