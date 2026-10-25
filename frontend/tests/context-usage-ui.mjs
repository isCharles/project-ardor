import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic progress and persisted message fixtures only; never calls a model or a real account.
const origin = "http://127.0.0.1:3120";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3120"], { stdio: "pipe" });
let logs = "", browser, usage = null, running = false;
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
    else if (path === "/api/agent") body = { conversationId: conversation.id, llmConfigured: true, memory: { content: "" }, conversations: [conversation], archivedConversations: [], messages: [{ id: "context-answer", role: "ASSISTANT", content: "Synthetic answer", createdAt: conversation.createdAt, runTrace: JSON.stringify({ elapsedMs: 10, status: "COMPLETED", steps: [], contextUsage: running ? null : usage }) }] };
    else if (path === "/api/agent/runs/latest") {
      if (!running) return route.fulfill({ status: 204 });
      body = { id: "context-run", conversationId: conversation.id, message: "Synthetic request", status: "RUNNING", label: "Working", createdAt: conversation.createdAt };
    } else if (path.endsWith("/context-run/progress")) body = { phase: "WAITING", label: "Preparing", thinkingOutput: "", answerPartial: "", steps: [], contextUsage: usage };
    else if (path === "/api/agent/permissions") body = { mode: "CUSTOM", globalLevel: 1, modules: [] };
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
  await tooltip.getByText("Model window: unknown", { exact: true }).waitFor();
  assert.match(await tooltip.innerText(), /1,800 tokens/);
  assert.match(await tooltip.innerText(), /12,000 tokens/);
  assert.doesNotMatch(await indicator.innerText(), /%/);
  running = true;
  usage = { ...usage, state: "COMPACTING", estimatedTokens: null };
  await page.reload();
  await page.locator('[data-context-state="COMPACTING"]').waitFor();
  assert.match(await indicator.innerText(), /Compacting context/);
  usage = { ...usage, state: "COMPACTION_FAILED", estimatedTokens: 1800 };
  await page.locator('[data-context-state="COMPACTION_FAILED"]').waitFor();
  await indicator.focus();
  await tooltip.getByText("Original messages retained; not successfully compacted.", { exact: true }).waitFor();
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
  console.log("Context usage UI: legacy unknown, persisted estimate, honest unknown window, live compaction/failure and narrow viewport passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
