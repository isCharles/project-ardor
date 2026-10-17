import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Isolated browser fixtures only. Never reads a real resume or calls a model.
const origin = "http://127.0.0.1:3103";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3103"], { stdio: "pipe" });
let logs = "";
server.stdout.on("data", c => { logs += c; }); server.stderr.on("data", c => { logs += c; });
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const legacy = { id: "legacy", resumeId: "fixture", promptVersion: "resume-analysis-v2", createdAt: "2026-10-15T12:00:00Z", analysis: { overallScore: 82, summary: "Saved legacy report" } };
  const v1 = { ...legacy, id: "v1", promptVersion: "resume-analysis-v3", analysis: { ...legacy.analysis, rubricVersion: "ardor-resume-rubric-v1", summary: "Saved v1 report", rubricCriteria: [{ id: "outcomes", weight: 10, anchor: "旧版中文指标" }], criterionRatings: [{ id: "outcomes", level: 3, evidence: "Fixture", rationale: "Saved v1 rationale" }] } };
  const modern = { id: "modern", resumeId: "fixture", promptVersion: "resume-analysis-v4", createdAt: "2026-10-16T12:00:00Z", analysis: {
    overallScore: 75, categoryScores: { content: 75, impact: 75, clarity: 75, roleFit: 75 }, summary: "Versioned assessment",
    rubricVersion: "ardor-resume-rubric-v2", scoringContext: { targetRole: "Java backend", mode: "USER_JD", jobDescription: "Use Redis", marketSnapshotVersion: "user-jd-v1:fixture", researchDate: "2026-10-16", sources: [] },
    rubricCriteria: [{ id: "outcomes", weight: 10, anchor: "结果与口径", englishAnchor: "Outcomes and measurement", conditions: ["结果", "本人行动", "验收基线", "验证方法及范围"], englishConditions: ["Outcome stated", "Personal action", "Acceptance baseline", "Verification method and scope"] }],
    criterionRatings: [{ id: "outcomes", level: 3, checks: [true,true,true,false], evidence: "Reduced test latency", rationale: "Fixture rationale" }],
    recommendations: [{ priority: "P1", criterionId: "outcomes", problem: "State a measurement baseline", action: "Add verified context", evidence: "Reduced test latency", example: "Reduced [verified baseline] to [verified result]" }],
  } };
  let complete = false, pending = false, failed = true, submitted;
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    let body = {};
    if (path === "/api/auth/me") body = { id: "resume-fixture", email: "resume-fixture@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "resume-fixture", hrefs: ["/app", "/app/resumes"], revision: 1, configured: true };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/resumes") body = [{ id: "fixture", originalFilename: "Disposable scoring fixture.pdf", sizeBytes: 400, parseStatus: "PARSED", createdAt: legacy.createdAt, analysisId: complete ? "modern" : "legacy", analysisStatus: pending ? "RUNNING" : "COMPLETED", analysisError: null }];
    else if (path === "/api/resumes/fixture/analysis" && route.request().method() === "POST") {
      submitted = route.request().postDataJSON();
      if (failed) return route.fulfill({ status: 503, json: { message: "Synthetic submission failure" } });
      pending = true; return route.fulfill({ status: 202, json: { status: "QUEUED", analysisId: null } });
    }
    else if (path === "/api/resumes/fixture/analysis") body = complete ? modern : legacy;
    else if (path === "/api/resumes/fixture/analysis/history") body = complete ? [modern, v1, legacy] : [legacy];
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app/resumes?selected=fixture`);
  await page.getByText("Saved legacy report", { exact: true }).waitFor();
  assert.equal(await page.getByRole("region", { name: "Score summary", exact: true }).getByText("Not scored in this report", { exact: true }).count(), 4, "Never fabricate dimension scores for legacy reports");
  await page.getByText("Legacy free-form scoring. This report has not been silently recalculated.", { exact: true }).waitFor();
  await page.getByLabel("Target role (optional)", { exact: true }).fill("Java backend");
  await page.getByLabel("Job description (optional)", { exact: true }).fill("Use Redis");
  await page.getByRole("button", { name: "Analyze with these conditions", exact: true }).click();
  await page.getByText("Synthetic submission failure", { exact: true }).waitFor();
  assert.equal(await page.getByLabel("Job description (optional)", { exact: true }).inputValue(), "Use Redis");
  assert.equal(await page.getByText("Saved legacy report", { exact: true }).count(), 1);
  failed = false;
  await page.getByRole("button", { name: "Analyze with these conditions", exact: true }).click();
  await page.getByRole("button", { name: "Analyzing in background…", exact: true }).waitFor();
  assert.deepEqual(submitted, { targetRole: "Java backend", jobDescription: "Use Redis" });
  assert.equal(await page.getByText("Saved legacy report", { exact: true }).count(), 1, "Background assessment must not clear the previous report");
  pending = false; complete = true;
  await page.getByText("Versioned assessment", { exact: true }).waitFor({ timeout: 10000 });
  await page.getByText("Prioritized improvements", { exact: true }).waitFor();
  for (const name of ["Must fix", "Improve next", "Polish when ready"]) await page.getByRole("region", { name, exact: true }).waitFor();
  await page.getByText("Scoring anchors and evidence", { exact: true }).click();
  await page.getByText("Outcomes and measurement · 7.5/10", { exact: true }).waitFor();
  await page.getByText("Verification method and scope", { exact: false }).waitFor();
  await page.getByText("Fixture rationale", { exact: true }).waitFor();
  await page.getByRole("combobox", { name: "Saved reports", exact: true }).selectOption("legacy");
  await page.getByText("Saved legacy report", { exact: true }).waitFor();
  await page.getByRole("combobox", { name: "Saved reports", exact: true }).selectOption("modern");
  await page.getByRole("combobox", { name: "Saved reports", exact: true }).selectOption("v1");
  await page.getByText("Scoring anchors and evidence", { exact: true }).click();
  await page.getByText("outcomes · 7.5/10", { exact: true }).waitFor();
  assert.equal(await page.getByText("旧版中文指标", { exact: false }).count(), 0);
  await page.getByRole("combobox", { name: "Saved reports", exact: true }).selectOption("modern");
  const anchorSummary = page.getByText("Scoring anchors and evidence", { exact: true });
  if (await anchorSummary.locator("..").getAttribute("open") == null) await anchorSummary.click();
  await page.screenshot({ path: ".next/resume-scoring.png", fullPage: true });
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "zh-CN"); window.dispatchEvent(new Event("ardor:locale-change")); });
  for (const name of ["必须修改", "优先优化", "有余力再润色"]) await page.getByRole("region", { name, exact: true }).waitFor();
  await page.getByText("结果与口径 · 7.5/10", { exact: true }).waitFor();
  await page.getByText("验证方法及范围", { exact: false }).waitFor();
  await page.screenshot({ path: ".next/resume-scoring-zh.png", fullPage: true });
  await context.close();
  console.log("Resume scoring UI: conditions, failed submission drafts, old report retention, refreshed result, priorities and history passed.");
} finally {
  await browser?.close(); server.kill();
}
