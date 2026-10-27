import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

const origin = "http://127.0.0.1:3126";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3126"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
const plans = ["FREE", "PLUS", "PRO"].map(code => ({ code, nameEn: code === "FREE" ? "Free" : code,
  nameZh: code === "FREE" ? "免费版" : code, priceCents: code === "FREE" ? 0 : 3900,
  currency: "CNY", billingInterval: "MONTH", tokenLimit: 100000, paymentEnabled: false }));
// FREE gets two Agent chats a week but five a month: the week is the budget that actually denies them.
const chat = { feature: "AGENT_CHAT", used: 0, limit: 5, weeklyUsed: 2, weeklyLimit: 2 };
// Late in the month, with the week freshly reset, the month is the tighter budget again.
const monthTight = { feature: "AGENT_CHAT", used: 495, limit: 500, weeklyUsed: 0, weeklyLimit: 25 };
const tryResponses = async (page, context, features, expect) => {
  await context.unroute("**/api/**");
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "quota-owner", email: "quota@ardor.invalid", admin: false };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", targetRoles: [], timezone: "UTC" };
    else if (path === "/api/profile/sidebar") body = { userId: "quota-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/agent/permissions") body = { mode: "AUTO", globalLevel: 2, modules: [] };
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = { tier: "FREE", plan: plans[0], planExpiresAt: null,
      resetsAt: "2026-11-01T00:00:00Z", features, tokens: { used: 100, limit: 100000 }, plans };
    await route.fulfill({ json: body });
  });
  await page.goto(`${origin}/app/settings`);
  await page.getByRole("button", { name: "Usage", exact: true }).click();
  const cell = page.locator('[data-usage-feature="AGENT_CHAT"]');
  await cell.waitFor();
  assert.equal((await cell.innerText()).replace(/\s+/g, " "), expect);
};
try {
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* Starting. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, timezoneId: "UTC" });
  const page = await context.newPage();
  await tryResponses(page, context, [chat], "Agent chat 2 / 2 resets weekly");
  await tryResponses(page, context, [monthTight], "Agent chat 495 / 500 resets monthly");
  // A snapshot predating weekly quotas must not invent a zero weekly budget.
  await tryResponses(page, context, [{ feature: "AGENT_CHAT", used: 1, limit: 5 }], "Agent chat 1 / 5 resets monthly");
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await context.close();
  console.log("Usage quota window: weekly-bound and monthly-bound features each show the budget that denies the request, legacy snapshots and mobile passed.");
} finally { await browser?.close(); server.kill(); }
