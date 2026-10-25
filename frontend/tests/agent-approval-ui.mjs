import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// All account/API data below is synthetic; no production service is contacted.
const origin = "http://127.0.0.1:3107";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3107"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", value => { logs += value; }); server.stderr.on("data", value => { logs += value; });
try {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  let mode = "CUSTOM", calendarLevel = 0, failMode = false;
  const requests = [];
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "approval-owner", email: "approval@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "approval-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", headline: "", targetRoles: [], timezone: "Asia/Shanghai" };
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    else if (path === "/api/agent") body = { conversationId: null, llmConfigured: true, memory: { content: "" }, conversations: [], archivedConversations: [], messages: [] };
    else if (path === "/api/agent/runs/latest") return route.fulfill({ status: 204 });
    else if (path === "/api/agent/permissions") body = { mode, globalLevel: mode === "CUSTOM" ? 1 : mode === "FULL" ? 3 : mode === "AUTO" ? 2 : 0,
      modules: [{ module: "calendar", level: calendarLevel, overridden: mode === "CUSTOM" }] };
    else if (path === "/api/agent/permissions/mode") {
      const value = route.request().postDataJSON().mode;
      requests.push(value);
      if (failMode) return route.fulfill({ status: 503, json: { message: "Synthetic mode save failed" } });
      mode = value; return route.fulfill({ status: 204 });
    } else if (path === "/api/agent/permissions/calendar") {
      calendarLevel = route.request().postDataJSON().level; mode = "CUSTOM";
      return route.fulfill({ status: 204 });
    }
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const errors = []; page.on("pageerror", error => errors.push(error.message));
  await page.goto(origin + "/app");
  assert.equal(await page.locator("[data-approval-trigger]").innerText(), "", "Composer permission control is icon-only");
  assert.equal(await page.locator("[data-approval-trigger]").getAttribute("title"), "Custom");
  await page.getByRole("button", { name: "Custom", exact: true }).click();
  assert.equal(await page.getByRole("menuitemradio").count(), 4);
  for (const item of await page.getByRole("menuitemradio").all()) assert.ok(await item.locator("svg").count() >= 1);
  await page.screenshot({ path: ".next/agent-approval-menu.png" });
  await page.getByRole("menuitemradio", { name: /^Approve for me/ }).click();
  await page.getByRole("button", { name: "Approve for me", exact: true }).waitFor();
  await page.reload();
  await page.getByRole("button", { name: "Approve for me", exact: true }).waitFor();
  mode = "CUSTOM";
  await page.evaluate(() => window.dispatchEvent(new Event("ardor:permissions-changed")));
  await page.getByRole("button", { name: "Custom", exact: true }).waitFor();
  mode = "AUTO";
  await page.evaluate(() => window.dispatchEvent(new Event("ardor:permissions-changed")));
  await page.getByRole("button", { name: "Approve for me", exact: true }).click();
  failMode = true;
  await page.getByRole("menuitemradio", { name: /^Full access/ }).click();
  await page.getByText("Synthetic mode save failed", { exact: true }).waitFor();
  assert.equal(mode, "AUTO");
  await page.getByRole("button", { name: "Approve for me", exact: true }).waitFor();
  failMode = false;
  await page.goto(origin + "/app/settings");
  await page.getByRole("button", { name: "Agent permissions", exact: true }).click();
  const modes = page.getByRole("group", { name: "Approval mode" });
  await modes.getByRole("button", { name: /^Full access/ }).click();
  await page.waitForFunction(() => document.querySelector('[aria-label="Approval mode"] button[aria-pressed="true"]')?.textContent?.startsWith("Full access"));
  assert.equal(await page.getByRole("combobox", { name: "Permission for Calendar", exact: true }).count(), 0);
  await modes.getByRole("button", { name: /^Custom/ }).click();
  const calendar = page.getByRole("combobox", { name: "Permission for Calendar", exact: true });
  await calendar.waitFor();
  await page.waitForFunction(() => {
    const field = document.querySelector('select[aria-label="Permission for Calendar"]');
    return field && !field.disabled && field.value === "0";
  });
  assert.equal(await calendar.inputValue(), "0", "Preset switching preserves the original custom calendar override");
  const saved = page.waitForResponse(response => response.url().endsWith("/api/agent/permissions") && response.request().method() === "GET");
  await calendar.selectOption("2");
  await saved;
  assert.equal(calendarLevel, 2); assert.equal(mode, "CUSTOM");
  await page.setViewportSize({ width: 390, height: 900 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  await page.screenshot({ path: ".next/agent-approval-modes.png", fullPage: true });
  assert.deepEqual(requests, ["AUTO", "FULL", "FULL", "CUSTOM"]);
  assert.deepEqual(errors, []);
  await context.close();
  console.log("Approval modes UI: four choices, saved mode reload, failed-save rollback, preserved custom grants and narrow layout passed.");
} finally { await browser?.close(); server.kill(); }
