import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

const origin = "http://127.0.0.1:3122";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3122"], { stdio: "pipe" });
let logs = "", browser, owner = "reader-a", version = 1;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
try {
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* Starting. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: "reduce" });
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: owner, email: `${owner}@ardor.invalid`, admin: false };
    else if (path === "/api/profile") body = { displayName: "Synthetic learner", timezone: "UTC", targetRoles: [] };
    else if (path === "/api/profile/sidebar") body = { userId: owner, hrefs: ["/app/learning"], configured: true, revision: 0 };
    else if (path === "/api/learning-plans") body = ["a", "b"].map(id => ({ id, concept: `Course ${id}`, status: "IN_PROGRESS", sourceType: "MANUAL", contentVersion: version,
      lesson: { explanation: Array.from({ length: 90 }, (_, i) => `Paragraph ${i}: a synthetic explanation for reading-position verification.`).join("\n\n") },
      exercises: [], lastEvaluation: null, lastScore: null, attemptCount: 0, scheduledAt: "2099-01-01T00:00:00Z", nextReviewAt: null }));
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const errors = []; page.on("pageerror", error => errors.push(error.message));
  const open = async id => {
    await page.goto(`${origin}/app/learning?id=${id}`);
    await page.getByRole("heading", { name: `Course ${id}`, exact: true }).waitFor();
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  };
  const top = () => page.locator("[data-app-scroll]").evaluate(el => el.scrollTop);
  const move = async value => {
    await page.locator("[data-app-scroll]").evaluate((el, y) => el.scrollTo({ top: y, behavior: "instant" }), value);
    await page.waitForFunction(y => Math.abs(document.querySelector("[data-app-scroll]").scrollTop - y) < 2, value);
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  };
  await open("a"); await move(1800);
  await page.goto(`${origin}/app/settings`);
  await open("a"); assert.ok(Math.abs(await top() - 1800) < 2, "Returning restores the course position");
  await page.reload(); await page.getByRole("heading", { name: "Course a", exact: true }).waitFor();
  await page.waitForFunction(() => Math.abs(document.querySelector("[data-app-scroll]").scrollTop - 1800) < 2);
  await open("b"); assert.equal(await top(), 0); await move(800);
  await open("a"); assert.ok(Math.abs(await top() - 1800) < 2, "Courses retain separate positions");
  owner = "reader-b"; await open("a"); assert.equal(await top(), 0, "Accounts never share coordinates");
  owner = "reader-a"; version = 2; await open("a"); assert.equal(await top(), 0, "Replaced course content starts at its beginning");
  await page.evaluate(() => localStorage.setItem("ardor:course-position:reader-a:a", "broken"));
  await open("a"); assert.equal(await top(), 0);
  assert.deepEqual(errors, []);
  await context.close();
  console.log("Course reading position: return, reload, per-course/account isolation, content replacement and damaged storage passed.");
} finally { await browser?.close(); server.kill(); }
