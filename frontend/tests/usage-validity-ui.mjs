import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

const origin = "http://127.0.0.1:3125";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3125"], { stdio: "pipe" });
let logs = "", browser, tier = "PLUS", expiry = "2026-11-18T12:30:00Z";
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
const plans = ["FREE", "PLUS", "PRO"].map(code => ({ code, nameEn: code === "FREE" ? "Free" : code,
  nameZh: code === "FREE" ? "免费版" : code, priceCents: code === "FREE" ? 0 : 3900,
  currency: "CNY", billingInterval: "MONTH", tokenLimit: 100000, paymentEnabled: false }));
try {
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* Starting. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, timezoneId: "UTC" });
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "validity-owner", email: "validity@ardor.invalid", admin: false };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", targetRoles: [], timezone: "UTC" };
    else if (path === "/api/profile/sidebar") body = { userId: "validity-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/agent/permissions") body = { mode: "AUTO", globalLevel: 2, modules: [] };
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = { tier, plan: plans.find(value => value.code === tier),
      ...(expiry === undefined ? {} : { planExpiresAt: expiry }), resetsAt: "2026-11-01T00:00:00Z",
      features: [], tokens: { used: 100, limit: 100000 }, plans };
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const open = async () => { await page.goto(`${origin}/app/settings`); await page.getByRole("button", { name: "Usage", exact: true }).click(); };
  const validity = page.getByLabel("Plan validity", { exact: true });
  await open(); await validity.waitFor();
  assert.match(await validity.innerText(), /Expires.*11\/18\/2026.*12:30/);
  assert.doesNotMatch(await validity.innerText(), /11\/1\/2026/);
  assert.equal(await page.locator('[data-plan-code="FREE"]').getByRole("button").count(), 0);
  expiry = null; await open(); await validity.getByText("No expiry", { exact: true }).waitFor();
  expiry = undefined; await open(); await validity.getByText("Expiry unavailable", { exact: true }).waitFor();
  expiry = "invalid"; await open(); await validity.getByText("Expiry unavailable", { exact: true }).waitFor();
  tier = "FREE"; expiry = "2026-01-01T00:00:00Z"; await open(); await validity.getByText("Free plan", { exact: true }).waitFor();
  assert.doesNotMatch(await validity.innerText(), /Expires|2026/);
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await context.close(); console.log("Usage validity: real expiry separate from quota reset, free/indefinite/legacy/invalid cases and mobile passed.");
} finally { await browser?.close(); server.kill(); }
