import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic reports for one resume and three job descriptions; never calls a model or a real account.
const origin = "http://127.0.0.1:3124";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3124"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const resumeJava = { id: "multi-jd-resume", originalFilename: "java-candidate.pdf", sizeBytes: 1024, parseStatus: "PARSED",
  createdAt: "2026-10-28T08:00:00Z", analysisId: "report-java", analysisStatus: "RUNNING", analysisError: null, overallScore: 78 };
const resumeOther = { id: "other-resume", originalFilename: "other-candidate.pdf", sizeBytes: 2048, parseStatus: "PARSED",
  createdAt: "2026-10-27T08:00:00Z", analysisId: "report-other", analysisStatus: "COMPLETED", analysisError: null, overallScore: 66 };

const report = (id, resumeId, role, jobDescription, marker, createdAt) => ({
  id, resumeId, modelName: "synthetic-model", createdAt,
  analysis: { rubricVersion: "ardor-resume-rubric-v3", summary: `synthetic summary ${marker}`, overallScore: 78,
    roleFamily: "engineering", scoringContext: { targetRole: role, jobDescription, roleFamily: "engineering",
      mode: jobDescription ? "USER_JD" : "GENERAL" } } });

const javaReport = report("report-java", resumeJava.id, "Java 后端工程师", "要求 Spring Boot、MySQL 与高并发经验", "JAVA-MARK-ONE", "2026-10-28T08:00:00Z");
const dataReport = report("report-data", resumeJava.id, "数据分析师", "要求 SQL、指标建模与 A/B 实验", "DATA-MARK-TWO", "2026-10-28T09:00:00Z");
const supportReport = report("report-support", resumeJava.id, "用户运营", "要求 客服与社群运营经验", "SUPPORT-MARK-FOUR", "2026-10-28T10:00:00Z");
const otherReport = report("report-other", resumeOther.id, "文档专员", "要求 技术写作经验", "OTHER-MARK-THREE", "2026-10-27T08:00:00Z");
const pool = [javaReport, dataReport, supportReport, otherReport];

// What the resume list admits, kept a step behind the job so a report can only arrive through its own request.
let listAnalysisId = "report-java";
let listStatus = "RUNNING";
let historyJava = [javaReport];
let polls = 0;
let slow = false;
const requested = [];
const exports = [];
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

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
  await context.route("**/report-api/**", async route => {
    exports.push(route.request().url());
    return route.fulfill({ contentType: "application/pdf", body: "%PDF-1.4\nsynthetic\n%%EOF\n" });
  });
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (route.request().method() !== "GET") {
      if (/\/analysis$/.test(path)) { polls = 0; slow = true;
        return route.fulfill({ json: { jobId: "job-support", resumeId: resumeJava.id, status: "QUEUED",
          analysisId: null, attempts: 1, errorCode: null, errorMessage: null } }); }
      return route.fulfill({ json: {} });
    }
    if (url.searchParams.has("analysisId")) requested.push(url.toString());
    let body = [];
    if (path === "/api/auth/me") body = { id: "multi-jd-owner", email: "multi@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "multi-jd-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path.startsWith("/api/background-tasks/")) body = { activeCount: 0, items: [] };
    else if (path === "/api/opportunities") body = [];
    else if (path === "/api/resumes") body = [{ ...resumeJava, analysisId: listAnalysisId, analysisStatus: listStatus }, resumeOther];
    else if (path.endsWith("/analysis/history")) body = path.includes(resumeOther.id) ? [otherReport] : historyJava;
    else if (path.endsWith("/analysis/status")) {
      polls += 1;
      const done = polls >= 2;
      if (done) historyJava = [javaReport, dataReport, supportReport];
      body = done
        ? { jobId: "job-support", resumeId: resumeJava.id, status: "COMPLETED", analysisId: "report-support", attempts: 1, errorCode: null, errorMessage: null }
        : { jobId: "job-support", resumeId: resumeJava.id, status: "RUNNING", analysisId: null, attempts: 1, errorCode: null, errorMessage: null };
    }
    else if (/\/analysis\/?$/.test(path)) {
      const resumeId = path.split("/")[3];
      const opened = pool.find((item) => item.id === url.searchParams.get("analysisId"));
      const newest = pool.find((item) => item.id === (resumeId === resumeOther.id ? "report-other" : listAnalysisId));
      if (slow && opened) await delay(900);
      body = opened ?? newest ?? pool[0];
    }
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    return route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const bodyText = async () => (await page.locator("main").innerText().catch(() => "")).replace(/\s+/g, " ");
  const describe = async () => `urls=${JSON.stringify(requested)} exports=${JSON.stringify(exports)} errors=${JSON.stringify(consoleErrors)} text=${await bodyText()}`;
  const waitFor = async (probe, label, timeout = 20000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (await probe()) return; await delay(150); }
    throw new Error(`timed out waiting for ${label}; ${await describe()}`);
  };
  const chips = page.locator("[data-resume-evaluations] [data-evaluation]");

  // Another tab finished a second job description while this page sat open: only the list says the report changed.
  await page.goto(`${origin}/app/resumes?selected=${resumeJava.id}`);
  await waitFor(async () => (await bodyText()).includes("JAVA-MARK-ONE"), "the first report");
  assert.equal(await chips.count(), 0, `one report needs no switcher; ${await describe()}`);
  setTimeout(() => { listAnalysisId = "report-data"; listStatus = "COMPLETED"; historyJava = [javaReport, dataReport]; }, 2700);
  await waitFor(async () => (await bodyText()).includes("DATA-MARK-TWO") && (await chips.count()) === 2,
    "a report finished elsewhere to switch the body and open the switcher");
  assert.match(await chips.nth(1).innerText(), /数据分析师/);
  assert.equal(await chips.nth(1).getAttribute("data-evaluation-active"), "true", `the finished report owns the chip; ${await describe()}`);

  // A third job description started from this page: only its own job request knows the report it produced.
  await page.getByRole("button", { name: /Another JD|换个 JD/ }).click();
  const dialog = page.getByRole("dialog");
  await dialog.waitFor();
  await dialog.locator("input").first().fill("用户运营");
  await dialog.locator("textarea").first().fill("要求 客服与社群运营经验");
  await dialog.getByRole("button", { name: /Analyze|开始分析/ }).click();
  await waitFor(async () => (await bodyText()).includes("SUPPORT-MARK-FOUR"),
    "the report of the job this page started, which the resume list has not caught up with");
  assert.ok(requested.some((entry) => entry.includes("analysisId=report-support")),
    `the finished report is fetched by its own id: ${JSON.stringify(requested)}`);

  // Exporting has to produce the evaluation on screen, not whichever one the server lists first.
  await page.getByRole("button", { name: /Export report|导出报告/ }).click();
  await waitFor(() => exports.some((entry) => entry.includes("analysisId=report-support")), "the export to carry the picked report");

  // A slow response for another report must not land on top of a different resume.
  requested.length = 0; exports.length = 0;
  slow = true;
  await chips.nth(0).click();
  await delay(250);
  await page.getByRole("button", { name: /All resumes|所有简历/ }).first().click();
  await page.locator("button:has-text(\"other-candidate.pdf\")").first().click();
  await waitFor(async () => (await bodyText()).includes("OTHER-MARK-THREE"), "the second resume's own report");
  await delay(1600);
  assert.ok(!(await bodyText()).includes("JAVA-MARK-ONE"),
    `an abandoned report request cannot overwrite another resume; ${await describe()}`);

  console.log("Resume multi-JD UI: single report, a report finished elsewhere, a job started here, export identity and the stale-response guard passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
