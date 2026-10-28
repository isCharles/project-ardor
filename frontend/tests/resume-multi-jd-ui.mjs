import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic reports for one resume and two job descriptions; never calls a model or a real account.
const origin = "http://127.0.0.1:3124";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3124"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
const resume = { id: "multi-jd-resume", originalFilename: "synthetic.pdf", sizeBytes: 1024, parseStatus: "PARSED",
  createdAt: "2026-10-28T08:00:00Z", analysisId: "report-java", analysisStatus: "COMPLETED", analysisError: null, overallScore: 78 };
const report = (id, role, jobDescription, summary) => ({ id, resumeId: resume.id, modelName: "synthetic-model",
  createdAt: "2026-10-28T08:00:00Z", analysis: { rubricVersion: "ardor-resume-rubric-v2", summary,
    scoringContext: { targetRole: role, jobDescription, roleFamily: "engineering" } } });
const reports = [report("report-java", "Java 后端工程师", "要求 Spring Boot、MySQL 与高并发经验", "Synthetic Java report"),
  report("report-data", "数据分析师", "要求 SQL、指标建模与 A/B 实验", "Synthetic data report")];
let historyRows = reports, requested = [];
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
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (url.searchParams.has("analysisId")) requested.push(route.request().url());
    let body = [];
    const opened = reports.find((item) => item.id === url.searchParams.get("analysisId"));
    if (path === "/api/auth/me") body = { id: "multi-jd-owner", email: "multi@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "multi-jd-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/opportunities") body = [];
    else if (path === "/api/resumes") body = [resume];
    else if (path.endsWith("/analysis/history")) body = historyRows;
    else if (path.endsWith("/analysis/status")) body = { jobId: "job-data", resumeId: resume.id, status: "COMPLETED", analysisId: "report-data", attempts: 1, errorCode: null, errorMessage: null };
    else if (/\/analysis\/?$/.test(path)) body = opened ?? reports.find((item) => item.id === resume.analysisId);
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    return route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app/resumes?selected=${resume.id}`);
  const chips = page.locator("[data-resume-evaluations] [data-evaluation]");
  await chips.first().waitFor();
  assert.equal(await chips.count(), 2, "one chip per job description the resume has been scored against");
  assert.match(await chips.nth(0).innerText(), /Java 后端工程师/);
  assert.match(await chips.nth(1).innerText(), /数据分析师/);
  assert.equal(await chips.nth(0).getAttribute("data-evaluation-active"), "true", "the opened report is the active chip");

  await chips.nth(1).click();
  await new Promise(resolve => setTimeout(resolve, 1500));
  const state = await chips.evaluateAll((items) => items.map((item) => item.getAttribute("data-evaluation-active")));
  assert.deepEqual(state, ["false", "true"],
    `switching marks the second report active; requested=${JSON.stringify(requested)} errors=${JSON.stringify(
      await page.locator("[role=status], .bg-red-50").allInnerTexts())}`);
  assert.ok(requested.some((entry) => entry.includes("analysisId=report-data")),
    `switching asks the server for that report: ${JSON.stringify(requested)}`);

  // One report only: nothing to choose between, so the bar stays out of the way.
  historyRows = [reports[0]];
  await page.reload();
  assert.equal(await page.locator("[data-resume-evaluations]").count(), 0, "a single report needs no switcher");
  console.log("Resume multi-JD UI: two job descriptions, two chips, per-report fetch and the single-report case passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
