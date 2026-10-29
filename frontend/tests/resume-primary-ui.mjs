import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic resumes and reports; no model call and no real account.
const origin = "http://127.0.0.1:3127";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3127"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const primaryId = "40000000-0000-4000-8000-000000000001";
const otherId = "40000000-0000-4000-8000-000000000002";
const primaryReport = "40000000-0000-4000-8000-0000000000a1";
const otherJava = "40000000-0000-4000-8000-0000000000a2";
const otherData = "40000000-0000-4000-8000-0000000000a3";
let primaryResumeId = primaryId;
let otherDisplay = null;
const primaryCalls = [], displayCalls = [], analysisRequests = [];
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

const report = (id, resumeId, marker, role) => ({
  id, resumeId, modelName: "synthetic-model", createdAt: "2026-10-28T08:00:00Z",
  analysis: { rubricVersion: "ardor-resume-rubric-v3", summary: `synthetic ${marker}`, overallScore: 78,
    roleFamily: "engineering", scoringContext: { targetRole: role, jobDescription: role ? "要求相关经验" : "",
      roleFamily: "engineering", mode: role ? "USER_JD" : "GENERAL" } } });

const primaryResume = (display) => ({
  id: primaryId, originalFilename: "primary-candidate.pdf", sizeBytes: 1024, parseStatus: "PARSED",
  createdAt: "2026-10-20T08:00:00Z", analysisId: primaryReport, analysisStatus: "COMPLETED",
  analysisError: null, overallScore: 78, primary: primaryResumeId === primaryId, displayAnalysisId: display });
const otherResume = () => ({
  id: otherId, originalFilename: "other-candidate.pdf", sizeBytes: 2048, parseStatus: "PARSED",
  createdAt: "2026-10-27T08:00:00Z", analysisId: otherDisplay ?? otherData, analysisStatus: "COMPLETED",
  analysisError: null, overallScore: 66, primary: primaryResumeId === otherId, displayAnalysisId: otherDisplay });

try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await delay(100);
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const consoleErrors = [];
  context.on("page", page => page.on("pageerror", error => consoleErrors.push(String(error))));
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();
    if (method === "POST") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      if (path === `/api/resumes/${otherId}/primary`) { primaryCalls.push(otherId); primaryResumeId = otherId; return route.fulfill({ status: 204, body: "" }); }
      if (path.endsWith("/display-analysis")) { displayCalls.push({ path, body }); if (path.includes(otherId)) otherDisplay = body.analysisId; return route.fulfill({ status: 204, body: "" }); }
      if (path.endsWith("/analysis")) { analysisRequests.push({ path, body }); return route.fulfill({ json: { jobId: "job-1", resumeId: path.split("/")[3], status: "QUEUED", analysisId: null, attempts: 1, errorCode: null, errorMessage: null } }); }
      return route.fulfill({ json: {} });
    }
    let body = [];
    if (path === "/api/auth/me") body = { id: "primary-owner", email: "primary@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "primary-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path.startsWith("/api/background-tasks/")) body = { activeCount: 0, items: [] };
    else if (path === "/api/opportunities") body = [];
    else if (path === "/api/resumes") body = [otherResume(), primaryResume(null)];
    else if (path.endsWith("/analysis/history")) {
      body = path.includes(otherId)
        ? [report(otherData, otherId, "DATA-MARK-TWO", "数据分析师"), report(otherJava, otherId, "JAVA-MARK-ONE", "Java 后端工程师")]
        : [report(primaryReport, primaryId, "PRIMARY-MARK", "")];
    }
    else if (path.endsWith("/analysis/status")) body = { jobId: "job-1", resumeId: otherId, status: "RUNNING", analysisId: null, attempts: 1, errorCode: null, errorMessage: null };
    else if (/\/analysis\/?$/.test(path)) {
      const resumeId = path.split("/")[3];
      const pool = resumeId === otherId
        ? [report(otherData, otherId, "DATA-MARK-TWO", "数据分析师"), report(otherJava, otherId, "JAVA-MARK-ONE", "Java 后端工程师")]
        : [report(primaryReport, primaryId, "PRIMARY-MARK", "")];
      const opened = pool.find((item) => item.id === url.searchParams.get("analysisId"));
      const newest = resumeId === otherId ? (otherDisplay ?? otherData) : primaryReport;
      body = opened ?? pool.find((item) => item.id === newest) ?? pool[0];
    }
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    return route.fulfill({ json: body });
  });

  const page = await context.newPage();
  const bodyText = async () => (await page.locator("main").innerText().catch(() => "")).replace(/\s+/g, " ");
  const waitFor = async (probe, label, timeout = 15000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (await probe()) return; await delay(120); }
    throw new Error(`timed out waiting for ${label}; text=${await bodyText()} calls=${JSON.stringify({ primaryCalls, displayCalls, analysisRequests })} errors=${JSON.stringify(consoleErrors)}`);
  };

  await page.goto(`${origin}/app/resumes`);
  await waitFor(async () => (await bodyText()).includes("primary-candidate.pdf"), "the library");

  // The chosen primary resume is the headline card even though it is older.
  const headline = await page.locator("h2").filter({ hasText: /Primary resume|主简历/ }).count();
  assert.equal(headline, 1, "the featured section is labelled as the primary resume");
  assert.match(await bodyText(), /Primary|主简历/);

  // A history resume can be promoted, and the list reorders.
  await page.getByRole("button", { name: /Set as primary resume|设为主简历/ }).first().click();
  await waitFor(() => primaryCalls.length === 1, "the primary selection request");
  await waitFor(async () => (await bodyText()).includes("other-candidate.pdf") && /Primary resume|主简历/.test(await bodyText()), "the promoted resume");

  // Opening the promoted resume shows both of its reports and remembers the chosen one.
  await page.locator(`button:has-text("other-candidate.pdf")`).first().click();
  await waitFor(async () => (await bodyText()).includes("DATA-MARK-TWO"), "the newest report of the promoted resume");
  const chips = page.locator("[data-resume-evaluations] [data-evaluation]");
  assert.equal(await chips.count(), 2);
  await chips.nth(1).click();
  await waitFor(() => displayCalls.length === 1, "the displayed-report selection request");
  assert.equal(displayCalls[0].body.analysisId, otherJava);
  await waitFor(async () => (await bodyText()).includes("JAVA-MARK-ONE"), "the pinned report body");

  // Reloading through the library keeps the pinned report instead of the newest.
  await page.getByRole("button", { name: /All resumes|所有简历/ }).first().click();
  await page.locator(`button:has-text("other-candidate.pdf")`).first().click();
  await waitFor(async () => (await bodyText()).includes("JAVA-MARK-ONE"), "the pinned report after reopening");

  // A JD decides the family; the dialog must not ask for one.
  await page.getByRole("button", { name: /Another JD|换个 JD/ }).click();
  const dialog = page.getByRole("dialog");
  await dialog.waitFor();
  await dialog.locator("input").first().fill("可灵 Agent 开发");
  await dialog.locator("textarea").first().fill("负责 Agent 后端服务开发，要求 Java、Spring Boot 与高并发经验");
  assert.equal(await dialog.locator("select").filter({ hasText: /General technology|通用技术/ }).count(), 0,
    "no family selector once a JD is present");
  assert.match(await dialog.innerText(), /inferred|自动判断/);
  await dialog.getByRole("button", { name: /Analyze|开始分析/ }).click();
  await waitFor(() => analysisRequests.length === 1, "the analysis request");
  assert.equal(analysisRequests[0].body.roleFamily, "", "the JD path must not send a family");
  assert.match(analysisRequests[0].body.jobDescription, /Agent/);

  assert.deepEqual(consoleErrors, []);
  console.log("Resume primary UI: primary selection, listing order, pinned report persistence and JD-driven family passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
