import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// No production API or account: every backend request uses synthetic fixtures.
const origin = "http://127.0.0.1:3107";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3107"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => { logs += chunk; });
server.stderr.on("data", chunk => { logs += chunk; });
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const recaps = ["alpha", "beta"].map(id => ({ id, title: `Fixture ${id}`, overview: `Unique ${id} overview`,
    strengths: [], weaknesses: [], questions: [], createdAt: "2026-10-01T01:00:00Z", jobOpportunityId: null }));
  let slowJob = false, jobPolls = 0;
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/interview-recaps/jobs" && slowJob) {
      jobPolls++;
      if (jobPolls === 3) recaps.push({ ...recaps[0], id: "slow", title: "Slow completed notes" });
      return route.fulfill({ json: [{ jobId: "slow-job", status: jobPolls < 3 ? "RUNNING" : "COMPLETED", recapId: jobPolls < 3 ? null : "slow" }] });
    }
    const body = path === "/api/auth/me" ? { id: "pipeline-fixture", email: "pipeline@ardor.invalid", admin: false }
      : path === "/api/auth/csrf" ? { token: "fixture", headerName: "X-CSRF-TOKEN" }
      : path === "/api/profile/sidebar" ? { userId: "pipeline-fixture", hrefs: ["/app"], revision: 0, configured: true }
      : path === "/api/background-tasks/summary" ? { activeCount: 0, unreadByModule: {} }
      : path === "/api/agent/permissions" ? { globalLevel: 0, modules: [] }
      : path === "/api/interview-recaps" ? recaps : [];
    await route.fulfill({ json: body });
  });
  const page = await context.newPage(), errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`${origin}/app/recaps`);
  await page.getByRole("link", { name: /Fixture alpha/ }).click();
  await page.getByRole("heading", { name: "Fixture alpha", exact: true }).waitFor();
  await page.getByText("Unique alpha overview", { exact: true }).waitFor();
  await page.goBack();
  await page.getByRole("link", { name: /Fixture beta/ }).click();
  await page.getByRole("heading", { name: "Fixture beta", exact: true }).waitFor();
  await page.goBack();
  await page.getByRole("link", { name: /Fixture alpha/ }).waitFor();
  await page.goForward();
  await page.getByRole("heading", { name: "Fixture beta", exact: true }).waitFor();
  await page.screenshot({ path: ".next/pipeline-recap.png", fullPage: true });
  slowJob = true;
  await page.goto(`${origin}/app/recaps`);
  await page.getByRole("link", { name: /Slow completed notes/ }).waitFor({ timeout: 20000 });
  assert.ok(jobPolls >= 3, "Polling must continue when the active job count stays unchanged");
  assert.deepEqual(errors, []);
  console.log("Pipeline recap UI: client query selection and browser back/forward passed.");
} finally { await browser?.close(); server.kill(); }
