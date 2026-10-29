import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic usage snapshot: no real account, no payment provider, no token spend.
const origin = "http://127.0.0.1:3128";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3128"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const plans = ["FREE", "PLUS", "PRO"].map(code => ({ code, nameEn: code === "FREE" ? "Free" : code,
  nameZh: code === "FREE" ? "免费版" : code, priceCents: code === "FREE" ? 0 : 3900,
  currency: "CNY", billingInterval: "MONTH", tokenLimit: 100000, paymentEnabled: false }));
const packs = [
  { code: "PACK_1M", nameZh: "轻量资源包", nameEn: "Light pack", tokenAmount: 1000000, priceCents: 900, currency: "CNY" },
  { code: "PACK_5M", nameZh: "标准资源包", nameEn: "Standard pack", tokenAmount: 5000000, priceCents: 3900, currency: "CNY" },
];
let packBalance = 500000;
const packRedemptions = [], planRedemptions = [];

try {
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* Starting. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, timezoneId: "UTC" });
  await context.route("**/api/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/api/usage/pack-codes/redeem") {
      const body = JSON.parse(request.postData() ?? "{}");
      packRedemptions.push(body);
      packBalance += 1000000;
      return route.fulfill({ json: { packCode: "PACK_1M", nameZh: "轻量资源包", nameEn: "Light pack", tokens: 1000000, balance: packBalance } });
    }
    if (request.method() === "POST" && path === "/api/usage/redeem") {
      planRedemptions.push(JSON.parse(request.postData() ?? "{}"));
      return route.fulfill({ json: { tier: "PLUS", nameZh: "Plus", nameEn: "Plus", expiresAt: null } });
    }
    let body = [];
    if (path === "/api/auth/me") body = { id: "pack-owner", email: "pack@ardor.invalid", admin: false };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", targetRoles: [], timezone: "UTC" };
    else if (path === "/api/profile/sidebar") body = { userId: "pack-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/agent/permissions") body = { mode: "AUTO", globalLevel: 2, modules: [] };
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = { tier: "FREE", plan: plans[0], planExpiresAt: null,
      resetsAt: "2026-11-01T00:00:00Z", features: [{ feature: "AGENT_CHAT", used: 50000, limit: 50000, weeklyUsed: 0, weeklyLimit: 25 }],
      tokens: { used: 50000, limit: 50000, packBalance }, plans, packs };
    return route.fulfill({ json: body });
  });

  const page = await context.newPage();
  await page.goto(`${origin}/app/settings`);
  await page.getByRole("button", { name: "Usage", exact: true }).click();
  const panel = page.locator("section").filter({ has: page.getByRole("heading", { name: "Usage", exact: true }) });
  await page.locator('[data-pack-code="PACK_1M"]').waitFor();
  assert.match((await page.locator('[data-pack-code="PACK_1M"]').innerText()).replace(/\s+/g, " "), /Light pack ¥9 1M Token/);
  assert.match((await panel.innerText()).replace(/\s+/g, " "), /Pack balance 500K Token/, "the redeemed balance is visible next to the plan budget");

  const field = page.locator("#redeem-code");
  await field.fill("PACK-AAAA-BBBB");
  await page.getByRole("button", { name: "Redeem", exact: true }).click();
  await page.getByText("1M pack tokens added").waitFor();
  assert.deepEqual(packRedemptions, [{ code: "PACK-AAAA-BBBB" }], "pack codes go to the pack endpoint");
  assert.equal(planRedemptions.length, 0, "a PACK code is not sent to the plan endpoint");
  assert.match((await panel.innerText()).replace(/\s+/g, " "), /Pack balance 1\.5M Token/, "the refreshed balance includes the pack");

  await field.fill("ARDOR-CCCC-DDDD");
  await page.getByRole("button", { name: "Redeem", exact: true }).click();
  await page.getByText("Upgraded to Plus").waitFor();
  assert.deepEqual(planRedemptions, [{ code: "ARDOR-CCCC-DDDD" }], "plan codes keep using the plan endpoint");

  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, "the pack catalog stays inside a phone viewport");
  console.log("Token packs: catalog, pack balance, PACK-code redemption and plan-code routing passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
