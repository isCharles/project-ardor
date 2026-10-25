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
  const legacy = { id: "legacy", resumeId: "fixture", modelName: "fixture", promptVersion: "resume-analysis-v2", createdAt: "2026-10-15T12:00:00Z", analysis: { overallScore: 82, summary: "Old report must stay offline", categoryScores: { content: 82 }, recommendations: [{ priority: "P0", problem: "Old recommendation must stay hidden" }] } };
  const modern = { id: "modern", resumeId: "fixture", modelName: "fixture", createdAt: "2026-10-21T12:00:00Z", analysis: {
    overallScore: 42,
    rubricVersion: "ardor-resume-rubric-v3",
    roleFamily: "algorithm",
    familyVersion: "ardor-resume-families-v1",
    dimensionScores: { technicalDepth: 50, outcomes: 25, ownership: 50, clarity: 75, roleFit: 50, background: 25 },
    dimensionGrades: { technicalDepth: "C", outcomes: "D", ownership: "C", clarity: "B", roleFit: "C", background: "D" },
    dimensionBenchmarks: { technicalDepth: "A", outcomes: "B", ownership: "B", clarity: "B", roleFit: "A", background: "B" },
    dimensionRatings: [{ id: "technicalDepth", level: 2, evidence: "Fixture evidence", rationale: "Fixture rationale" }],
    summary: "Structure is complete, but ownership and outcomes are missing.",
    background: "普通 211 本科，在当前算法岗市场不占优势；当务之急是补大厂实习。",
    strengths: ["有一段真实的性能优化经历，指标和验证方式可核验。"],
    issues: [
      { severity: "A", dimensionId: "roleFit", category: "岗位方向", problem: "目标算法岗，但项目全是后端 CRUD", why: "筛选者会直接判断方向不对", action: "要么补算法项目，要么改投后端", evidence: "Fixture project evidence", example: "固定占位符" },
      { severity: "B", dimensionId: "technicalDepth", category: "技术深度", problem: "技术栈罗列，没有使用深度", why: "无法判断技术深度", action: "每个核心技术写一个具体问题和一个限制", benchmark: "100 分标准：按满分标准写清一致性取舍与可核验结果" },
      { severity: "C", dimensionId: "clarity", category: "细节润色", problem: "单位和日期格式不一致", why: "降低对数字的信任", action: "统一单位和日期格式" },
    ],
    scoringContext: { targetRole: "Algorithm engineer", jobDescription: "Build recommendation models", mode: "USER_JD", roleFamily: "algorithm", familyVersion: "ardor-resume-families-v1", rubricVersion: "ardor-resume-rubric-v3", promptVersion: "resume-analysis-v5" },
  } };
  let complete = false, submitted;
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url()), path = url.pathname;
    let body = {};
    if (path === "/api/auth/me") body = { id: "resume-fixture", email: "resume-fixture@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "resume-fixture", hrefs: ["/app", "/app/resumes"], revision: 1, configured: true };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/opportunities") body = [{ id: "opp", company: "Acme", targetRole: "Backend intern", jdContent: "Build recommendation models", status: "SCHEDULED" }];
    else if (path === "/api/resumes") body = [{ id: "fixture", originalFilename: "Disposable scoring fixture.pdf", sizeBytes: 400, parseStatus: "PARSED", createdAt: legacy.createdAt, analysisId: complete ? "modern" : "legacy", analysisStatus: "COMPLETED", analysisError: null }];
    else if (path === "/api/resumes/fixture/analysis" && route.request().method() === "POST") {
      submitted = route.request().postDataJSON();
      complete = true;
      return route.fulfill({ status: 202, json: { status: "COMPLETED", analysisId: "modern" } });
    }
    else if (path === "/api/resumes/fixture/analysis") body = complete ? modern : legacy;
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app/resumes?selected=fixture`);
  await page.getByText("The old scoring is offline", { exact: true }).waitFor();
  assert.equal(await page.getByText("Old report must stay offline", { exact: true }).count(), 0, "Legacy report content must not render");
  assert.equal(await page.getByText("Old recommendation must stay hidden", { exact: true }).count(), 0, "Legacy recommendations must not render");

  await page.getByRole("button", { name: "Start analysis", exact: true }).click();
  await page.getByRole("combobox", { name: "Role family", exact: true }).waitFor();
  await page.getByText("No JD: this will be a general assessment under General technology", { exact: false }).waitFor();
  await page.getByRole("combobox", { name: "Pick from interview pipeline", exact: true }).selectOption({ label: "Acme · Backend intern" });
  assert.equal(await page.getByLabel("Target role (optional)", { exact: true }).inputValue(), "Acme · Backend intern");
  assert.equal(await page.locator("textarea").first().inputValue(), "Build recommendation models");
  await page.getByRole("combobox", { name: "Role family", exact: true }).selectOption("algorithm");
  await page.getByRole("button", { name: "Analyze", exact: true }).click();

  await page.getByText("42", { exact: true }).waitFor({ timeout: 10000 });
  await page.getByRole("button", { name: "Export report", exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Re-analyze", exact: true }).count(), 0, "Report must not offer a re-analyze action");
  assert.deepEqual(submitted, { targetRole: "Acme · Backend intern", jobDescription: "Build recommendation models", roleFamily: "algorithm" });
  await page.getByText("Must fix first", { exact: true }).waitFor();
  await page.getByText("目标算法岗，但项目全是后端 CRUD", { exact: true }).waitFor();
  await page.getByText("普通 211 本科，在当前算法岗市场不占优势；当务之急是补大厂实习。", { exact: true }).waitFor();
  await page.getByText("有一段真实的性能优化经历，指标和验证方式可核验。", { exact: false }).waitFor();
  await page.getByText("Algorithm / AI · Algorithm engineer", { exact: true }).waitFor();
  await page.getByText("Expertise", { exact: true }).waitFor();
  assert.equal(await page.getByText(/100\s*分标准|满分标准|顶级标准/).count(), 0, "Old framing must not dominate the displayed improvement targets");
  await page.getByText("按目标岗位要求写清一致性取舍与可核验结果", { exact: true }).waitFor();
  await page.getByText("C", { exact: true }).first().waitFor();

  await page.evaluate(() => { localStorage.setItem("ardor:locale", "zh-CN"); window.dispatchEvent(new Event("ardor:locale-change")); });
  await page.getByText("A 区 · 最该改", { exact: true }).waitFor();
  await page.getByText("简历诊断", { exact: true }).waitFor();
  await page.getByText("算法 · Algorithm engineer", { exact: true }).waitFor();
  await page.getByText("专业深度", { exact: true }).waitFor();
  await page.screenshot({ path: ".next/resume-v3-report.png", fullPage: true });
  await context.close();
  console.log("Resume v3 report: legacy offline, interview picker fills company/role/JD, family dialog, targeted payload, score, radar, A/B/C issues, background and strengths passed.");
} finally {
  await browser?.close(); server.kill();
}
