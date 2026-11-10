import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {chromium} from "playwright";

const origin = "http://127.0.0.1:3136";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3136"], {stdio: "pipe"});
let logs = "", browser;
server.stdout.on("data", (x) => logs += x);
server.stderr.on("data", (x) => logs += x);

const sessionRow = {
  id: "adaptive-1", resumeAnalysisId: null, jobOpportunityId: null, modality: "TEXT",
  targetCompany: "Ardor", targetRole: "Java backend engineer", status: "IN_PROGRESS",
  startedAt: "2026-11-10T08:00:00Z", finishedAt: null, createdAt: "2026-11-10T08:00:00Z",
};

const profile = (state, extra) => ({
  sessionId: "adaptive-1", status: "IN_PROGRESS", targetRole: "Java backend engineer",
  targetCompany: "Ardor", seniority: "MID", durationMinutes: 30, deepDive: false,
  startedAt: "2026-11-10T08:00:00Z", askedCount: 1, answeredCount: 0,
  readyToFinish: false, awaitingEvaluation: false, evaluationError: null,
  competencies: [
    {skill: "数据库", jdPriority: 5, estimatedLevel: null, confidence: 0, evidenceCount: 0,
      state: "UNVERIFIED", strengths: [], knowledgeGaps: []},
  ],
  lastDecision: {action: "OPEN", reason: "从岗位核心维度开始", createdAt: "2026-11-10T08:00:00Z"},
  currentQuestion: {
    id: "question-1", sequenceNumber: 1, questionText: "什么情况下联合索引会失效？",
    questionType: "TECHNICAL", competency: "数据库", knowledgePoint: "索引失效", difficulty: 3,
    origin: "PLAN", leetcode: null,
  },
  ...extra,
  ...(state ? {competencies: state} : {}),
});

const opened = profile();
// The score never landed, so the run pauses on a retryable turn instead of asking blind.
const awaiting = profile(null, {
  answeredCount: 1, currentQuestion: null, awaitingEvaluation: true, evaluationError: "LLM_TIMEOUT",
});
const raised = profile([
  {skill: "数据库", jdPriority: 5, estimatedLevel: 3, confidence: 0.7, evidenceCount: 1, state: "VERIFIED",
    strengths: [{knowledgePoint: "索引失效", difficulty: 3, score: 3}],
    knowledgeGaps: [{knowledgePoint: "索引失效", difficulty: 4, detail: "说不清覆盖索引的写入代价"}]},
  {skill: "Spring", jdPriority: 3, estimatedLevel: null, confidence: 0, evidenceCount: 0, state: "UNVERIFIED",
    strengths: [], knowledgeGaps: []},
], {
  askedCount: 2, answeredCount: 1,
  currentQuestion: {
    id: "question-2", sequenceNumber: 2, questionText: "那覆盖索引为什么能避免回表？代价是什么？",
    questionType: "TECHNICAL", competency: "数据库", knowledgePoint: "索引失效", difficulty: 4,
    origin: "FOLLOW_UP", leetcode: null,
  },
  lastDecision: {action: "RAISE_DIFFICULTY", reason: "上一题覆盖了全部必答要点，提高一档", createdAt: "2026-11-10T08:02:00Z"},
});
const finished = profile(raised.competencies, {
  askedCount: 2, answeredCount: 2, currentQuestion: null, readyToFinish: true,
});

const report = {
  id: "evaluation-1", interviewSessionId: "adaptive-1", overallScore: 72, modelName: "test-model",
  promptVersion: "adaptive-interview-report-v1", createdAt: "2026-11-10T08:05:00Z",
  evaluation: {
    reportKind: "ADAPTIVE",
    jobFit: "匹配中高级后端，机制层达标",
    levelAssessment: "应用层扎实，原理层有缺口",
    performanceSummary: "两题作答，第一题覆盖全部要点。",
    knowledgeBoundary: [
      {competency: "数据库", knowledgePoint: "索引失效", difficulty: 4, detail: "说不清覆盖索引的写入代价"},
    ],
    learningRecommendations: [
      {competency: "数据库", knowledgePoint: "索引失效", jdPriority: 5, severity: 4,
        advice: "用 sysbench 对比加覆盖索引前后的写入耗时，把数字写进笔记。",
        evidence: "第二题只说了避免回表，没有提到维护代价。"},
    ],
  },
};

const states = {opened, awaiting, raised, finished, done: finished, idle: opened};

try {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(origin)).ok) break; } catch { /* starting */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? {channel: process.env.ARDOR_UI_BROWSER_CHANNEL} : {})});
  const context = await browser.newContext({viewport: {width: 1400, height: 1100}});
  const requests = [];
  let phase = "idle";
  await context.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    if (path.startsWith("/api/interviews/adaptive")) requests.push({method, path, body: request.postData()});
    let body = [];
    if (path === "/api/auth/me") body = {id: "adaptive-fixture", email: "fixture@ardor.invalid", admin: false};
    else if (path === "/api/auth/csrf") body = {token: "fixture", headerName: "X-XSRF-TOKEN"};
    else if (path === "/api/profile/sidebar") body = {userId: "adaptive-fixture", hrefs: ["/app"], revision: 0, configured: true};
    else if (path === "/api/background-tasks/summary") body = {activeCount: 0, unreadByModule: {}};
    else if (path === "/api/agent/permissions") body = {globalLevel: 0, modules: []};
    else if (path === "/api/resumes") body = [];
    else if (path === "/api/interviews" && method === "GET") body = [];
    else if (path === "/api/interviews/adaptive" && method === "POST") { phase = "opened"; body = sessionRow; }
    else if (path === "/api/interviews/adaptive" && method === "GET") body = phase === "idle" ? [] : [sessionRow];
    else if (path === "/api/interviews/adaptive/adaptive-1/answers" && method === "POST") {
      if (JSON.parse(request.postData()).questionId === "question-1") { phase = "awaiting"; body = awaiting; }
      else { phase = "done"; body = finished; }
    }
    else if (path === "/api/interviews/adaptive/adaptive-1/finish" && method === "POST") body = report;
    else if (path === "/api/interviews/adaptive/adaptive-1" && method === "GET") {
      // Reading the state is what recovers the pending score, so the mock advances on the read too.
      if (phase === "awaiting") phase = "raised";
      body = states[phase];
    }
    else if (path === "/api/interviews/evaluation-1") body = report;
    await route.fulfill({json: body});
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(`${origin}/app/interviews`);
  await page.getByRole("tab", {name: "Adaptive", exact: true}).click();
  await page.locator('input[name="targetRole"]').fill("Java backend engineer");
  await page.locator('textarea[name="jobDescription"]').fill("熟悉 MySQL 索引与事务隔离级别。");
  await page.getByRole("button", {name: "Start", exact: true}).click();

  await page.getByText("什么情况下联合索引会失效？").waitFor({timeout: 10_000});
  const create = requests.find((item) => item.method === "POST" && item.path === "/api/interviews/adaptive");
  assert.equal(JSON.parse(create.body).jobDescription, "熟悉 MySQL 索引与事务隔离级别。");
  // The line above the question is the only place the plan becomes visible to the candidate.
  await page.getByText("数据库 · 索引失效 · Application · Opening").waitFor({timeout: 5_000});
  assert.equal(await page.getByText("必答要点").count(), 0, "the scoring key must never reach the candidate screen");

  await page.getByRole("textbox", {name: /Answer as you would/}).fill("跳过最左列就用不上索引。");
  await page.getByRole("radio", {name: "Too hard", exact: true}).check({force: true});
  await page.getByRole("button", {name: "Submit", exact: true}).click();

  await page.getByText("An answer is waiting to be scored.").waitFor({timeout: 10_000});
  const answer = requests.find((item) => item.method === "POST" && item.path.endsWith("/answers"));
  const sent = JSON.parse(answer.body);
  assert.equal(sent.questionId, "question-1");
  assert.equal(sent.difficultyFeedback, "TOO_HARD", "the candidate rating must ride with the answer");
  assert.match(sent.answerText, /跳过最左列/);

  await page.getByRole("button", {name: "Retry", exact: true}).click();
  await page.getByText("那覆盖索引为什么能避免回表？代价是什么？").waitFor({timeout: 10_000});
  await page.getByText("数据库 · 索引失效 · Principles · Follow-up").waitFor({timeout: 5_000});
  await page.getByText("上一题覆盖了全部必答要点，提高一档").waitFor({timeout: 5_000});

  await page.getByText("Verified").first().waitFor({timeout: 5_000});
  await page.getByText("Not tested").first().waitFor({timeout: 5_000});
  await page.getByText("Gaps", {exact: true}).first().click();
  await page.getByText("说不清覆盖索引的写入代价").first().waitFor({timeout: 5_000});

  await page.getByRole("textbox", {name: /Answer as you would/}).fill("覆盖索引不用回表，但写入要维护。");
  await page.getByRole("button", {name: "Submit", exact: true}).click();
  await page.getByRole("button", {name: "Finish and report", exact: true}).waitFor({timeout: 10_000});
  await page.getByRole("button", {name: "Finish and report", exact: true}).click();

  await page.getByText("匹配中高级后端，机制层达标").waitFor({timeout: 10_000});
  await page.getByText("应用层扎实，原理层有缺口").waitFor({timeout: 5_000});
  await page.getByText("用 sysbench 对比加覆盖索引前后的写入耗时，把数字写进笔记。").waitFor({timeout: 5_000});
  await page.getByText("第二题只说了避免回表，没有提到维护代价。").waitFor({timeout: 5_000});
  assert.equal(await page.getByText("LLM_TIMEOUT").count(), 0, "a resolved failure must stop being advertised");

  // The classic flow shares the page, so it has to come back intact.
  await page.getByRole("tab", {name: "Fixed paper", exact: true}).click();
  await page.getByText("Set up manually", {exact: true}).waitFor({timeout: 5_000});
  assert.equal(await page.getByRole("tab", {name: "Adaptive", exact: true}).getAttribute("aria-selected"), "false");

  assert.deepEqual(errors, []);
  console.log("Adaptive interview UI: plan, retryable scoring, raised difficulty, evidence profile and report verified");
} catch (reason) {
  console.error(logs.slice(-4_000));
  throw reason;
} finally {
  await browser?.close();
  server.kill();
}
