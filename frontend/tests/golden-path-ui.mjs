import assert from "node:assert/strict";
import { join } from "node:path";
import { chromium } from "playwright";

if (process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB !== "true") {
  throw new Error("Browser acceptance requires a disposable backend database");
}
const suffix = process.env.ARDOR_ACCEPTANCE_UI_SUFFIX;
if (!suffix || !/^[0-9a-f-]{36}$/i.test(suffix)) {
  throw new Error("Browser acceptance needs the matching API fixture ID");
}

const origin = "http://127.0.0.1:3000";
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
let activePage = page;
const pageErrors = [];
page.on("pageerror", (error) => pageErrors.push(error.message));

async function waitForApiState(page,path,accept) {
  let latest;
  for(let attempt=0;attempt<200;attempt++) {
    latest=await page.evaluate(async(path)=>{
      const response=await fetch(path,{cache:"no-store"});
      if(!response.ok) throw new Error(`Status query failed: ${response.status}`);
      return response.json();
    },path);
    if(accept(latest)) return latest;
    await page.waitForTimeout(200);
  }
  throw new Error(`Status did not reach expected state: ${path} (${latest?.status})`);
}

async function openWorkRecords(page) {
  const records = page.locator("[data-execution-trace] > div > details");
  await records.first().waitFor();
  for (const record of await records.all()) {
    if (await record.getAttribute("open") == null) await record.locator(":scope > summary").click();
  }
}

try {
  await page.goto(`${origin}/login`);
  await page.locator('input[name="email"]').fill(`golden-owner-${suffix}@ardor.invalid`);
  await page.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(`${origin}/app`, { timeout: 15_000 });

  // The latest synthetic conversation contains both a deleted proposal and a
  // distinct pending one. Only the pending one may still offer creation.
  await page.locator("[data-execution-trace]").first().waitFor();
  assert.equal(await page.locator("[data-execution-trace] > div > details").first().getAttribute("open"), null, "Process records default to collapsed");
  await openWorkRecords(page);
  await page.getByText(/Plan removed|计划已移除/).waitFor();
  await page.getByRole("button", { name: /Confirm creation|确认创建/ }).waitFor();
  assert.equal(await page.getByRole("button", { name: /Confirm creation|确认创建/ }).count(), 1);
  await page.reload();
  await openWorkRecords(page);
  await page.getByText(/Plan removed|计划已移除/).waitFor();
  assert.equal(await page.getByRole("button", { name: /Confirm creation|确认创建/ }).count(), 1);

  const plansBeforeConfirmation = await page.evaluate(async () => {
    const response = await fetch("/api/learning-plans");
    if (!response.ok) throw new Error(`Learning plans returned ${response.status}`);
    return response.json();
  });
  await page.getByRole("button", { name: /Confirm creation|确认创建/ }).click();
  await page.locator("[data-execution-trace]").getByText(/^(Learning plan created|学习计划已创建) ·/).waitFor();
  const createdPlanPath = await page.getByRole("link", { name: /Open lesson|打开学习/ }).getAttribute("href");
  assert.match(createdPlanPath, /^\/app\/learning\?id=[0-9a-f-]{36}$/i);
  await page.reload();
  await openWorkRecords(page);
  await page.getByText(/Plan removed|计划已移除/).waitFor();
  await page.locator("[data-execution-trace]").getByText(/^(Learning plan created|学习计划已创建) ·/).waitFor();
  assert.equal(await page.getByRole("button", { name: /Confirm creation|确认创建/ }).count(), 0);
  const plansAfterConfirmation = await page.evaluate(async () => {
    const response = await fetch("/api/learning-plans");
    if (!response.ok) throw new Error(`Learning plans returned ${response.status}`);
    return response.json();
  });
  assert.equal(plansAfterConfirmation.length, plansBeforeConfirmation.length + 1);
  assert.equal(plansAfterConfirmation.filter((item) => `/app/learning?id=${item.id}` === createdPlanPath).length, 1);
  const calendarTasks = await page.evaluate(async () => {
    const response = await fetch("/api/calendar/tasks");
    if (!response.ok) throw new Error(`Calendar tasks returned ${response.status}`);
    return response.json();
  });
  assert.equal(calendarTasks.filter((item) => item.actionPath === createdPlanPath).length, 1);

  await page.getByRole("link", { name: /Open lesson|打开学习/ }).click();
  await page.waitForURL(new RegExp(`${createdPlanPath.replace("?", "\\?")}$`));
  await page.getByRole("button", { name: /^(Start learning|开始学习)$/ }).click();
  await page.getByRole("heading", { name: "恢复取舍" }).waitFor();
  assert.equal(await page.locator('.ardor-markdown strong').filter({ hasText: "RDB" }).count(), 1);
  await page.getByRole("radio", { name: "记录写命令" }).check();
  await page.locator("#learning-practice textarea").fill("第一次回答：RDB 是快照。");
  await page.goto(`${origin}/app/calendar`);
  await page.getByRole("heading", { level: 1, name: /Make today count|把目标，放进今天/ }).waitFor();
  await page.goto(`${origin}${createdPlanPath}`);
  await page.locator("#learning-practice textarea").waitFor();
  assert.equal(await page.locator("#learning-practice textarea").inputValue(), "第一次回答：RDB 是快照。");
  assert.equal(await page.getByRole("radio", { name: "记录写命令" }).isChecked(), true);
  await page.reload();
  await page.locator("#learning-practice textarea").waitFor();
  assert.equal(await page.locator("#learning-practice textarea").inputValue(), "第一次回答：RDB 是快照。");
  await page.getByRole("button", { name: /Submit answers|提交练习/ }).click();
  await page.getByText(/Queued for assessment|Assessing in the background|已提交，等待评估|正在后台评估/).waitFor();
  await page.goto(`${origin}/app/calendar`);
  await page.goto(`${origin}${createdPlanPath}`);
  await page.getByLabel(/Latest score 65|最近得分 65 分/).waitFor();
  await page.waitForFunction(async (id) => {
    const response = await fetch(`/api/background-tasks?kind=LEARNING_EVALUATE&resourceId=${id}&limit=20`);
    const tasks = await response.json();
    return tasks.length > 0 && tasks.every(task => !task.unread);
  }, createdPlanPath.split("id=")[1]);
  assert.equal(await page.locator("#learning-practice textarea").inputValue(), "",
    "A new practice round must not inherit an unsent answer from the old round");
  const latestAttempt = page.locator("#learning-practice details").first();
  if (await latestAttempt.getAttribute("open") === null) await latestAttempt.locator("summary").click();
  await page.getByText("第一次回答：RDB 是快照。").last().waitFor();

  await page.getByRole("radio", { name: "允许的数据丢失窗口" }).check();
  await page.locator("#learning-practice textarea").fill("二轮修订：RDB 快照和 AOF 日志的恢复目标不同。");
  await page.getByRole("button", { name: /Submit answers|提交练习/ }).click();
  await page.getByLabel(/Latest score 85|最近得分 85 分/).waitFor();
  await page.getByText(/Topic complete\. The calendar task|本次学习已完成，日历任务/).waitFor();
  await page.reload();
  await page.getByLabel(/Latest score 85|最近得分 85 分/).waitFor();
  assert.equal(await page.locator("#learning-practice details").count(), 2);
  const completedCalendarTasks = await page.evaluate(async () => {
    const response = await fetch("/api/calendar/tasks");
    if (!response.ok) throw new Error(`Calendar tasks returned ${response.status}`);
    return response.json();
  });
  assert.equal(completedCalendarTasks.find((item) => item.actionPath === createdPlanPath)?.status, "COMPLETED");

  const recaps = await page.evaluate(async () => {
    const response = await fetch("/api/interview-recaps");
    if (!response.ok) throw new Error(`Interview notes returned ${response.status}`);
    return response.json();
  });
  const recap = recaps.find((item) => item.title === "Java 后端一面复盘");
  assert.ok(recap, "The API fixture must be visible in this user's session");
  const weak = recap.questions.find((item) => item.performance === "WEAK");
  assert.ok(weak);

  await page.goto(`${origin}/app/recaps?selected=${recap.id}`);
  await page.getByRole("heading", { level: 2, name: recap.title }).waitFor();
  await page.getByRole("heading", { name: weak.questionText }).waitFor();

  await page.goto(`${origin}/app/evidence`);
  await page.getByRole("link", { name: /Try again|重新回答/ }).first().click();
  await page.waitForURL(new RegExp(`/app/replay\\?question=${weak.id}`));
  await page.getByText(weak.questionText).first().waitFor();

  const plans = await page.evaluate(async () => {
    const response = await fetch("/api/learning-plans");
    if (!response.ok) throw new Error(`Learning plans returned ${response.status}`);
    return response.json();
  });
  const plan = plans.find((item) => item.sourceId === weak.id);
  assert.ok(plan, "The API fixture must include a learning plan for the weak question");
  await page.goto(`${origin}/app/learning?id=${plan.id}`);
  await page.getByRole("heading", { level: 2, name: plan.concept }).waitFor();
  await page.getByLabel(/Latest score|最近得分/).waitFor();

  await page.goto(`${origin}/app/calendar`);
  await page.getByRole("heading", { level: 1, name: /Make today count|把目标，放进今天/ }).waitFor();

  const recoveryPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  activePage = recoveryPage;
  recoveryPage.on("pageerror", (error) => pageErrors.push(error.message));
  await recoveryPage.goto(`${origin}/login`);
  await recoveryPage.locator('input[name="email"]').fill(`golden-ui-recovery-${suffix}@ardor.invalid`);
  await recoveryPage.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await recoveryPage.locator('button[type="submit"]').click();
  await recoveryPage.waitForURL(`${origin}/app`, { timeout: 15_000 });
  await recoveryPage.goto(`${origin}/app/recaps`);
  await recoveryPage.getByText(/Could not organize:|整理失败：/).waitFor();
  const recapsBeforeRetry = await recoveryPage.evaluate(async () => {
    const response = await fetch("/api/interview-recaps");
    if (!response.ok) throw new Error(`Interview notes returned ${response.status}`);
    return response.json();
  });
  assert.equal(recapsBeforeRetry.length, 0, "The recovery account must not see another user's notes");
  const failedJobs = await recoveryPage.evaluate(async () => {
    const response = await fetch("/api/interview-recaps/jobs");
    if (!response.ok) throw new Error(`Interview jobs returned ${response.status}`);
    return response.json();
  });
  assert.equal(failedJobs.length, 1);
  assert.equal(failedJobs[0].status, "FAILED");
  await recoveryPage.getByRole("button", { name: /^(Retry|重试)$/ }).click();
  await recoveryPage.goto(`${origin}/app/recaps?draft=${failedJobs[0].jobId}`);
  await recoveryPage.getByLabel(/Editable minutes · auto-saved|纪要编辑区 · 自动保存/).waitFor({timeout:40000});
  const beforeConfirm = await recoveryPage.evaluate(async () => (await fetch("/api/interview-recaps")).json());
  assert.equal(beforeConfirm.length,0,"Retrying draft generation must not bypass human confirmation");
  await recoveryPage.getByRole("button",{name:/Confirm minutes & generate review|确认纪要并生成复盘/}).click();
  await recoveryPage.getByRole("link",{name:/Open detailed review|打开详细复盘/,exact:true}).waitFor({timeout:40000});
  await recoveryPage.getByRole("link",{name:/Open detailed review|打开详细复盘/,exact:true}).click();
  await recoveryPage.getByRole("heading", { level: 2, name: "Java 后端一面复盘" }).waitFor({ timeout: 40_000 });
  const recoveredJobs = await recoveryPage.evaluate(async () => {
    const response = await fetch("/api/interview-recaps/jobs");
    if (!response.ok) throw new Error(`Interview jobs returned ${response.status}`);
    return response.json();
  });
  assert.equal(recoveredJobs.filter((job) => job.status === "COMPLETED").length, 1);
  assert.equal(recoveredJobs[0].jobId, failedJobs[0].jobId,
    "The browser must retry the original job, not submit a duplicate");
  assert.ok(recoveredJobs[0].attempts >= 1,
    "A user-initiated retry resets its attempt budget before the new run");

  const metadataPage = await browser.newPage({
    viewport: { width: 1440, height: 900 }, timezoneId: "Asia/Shanghai",
  });
  activePage = metadataPage;
  metadataPage.on("pageerror", (error) => pageErrors.push(error.message));
  await metadataPage.goto(`${origin}/login`);
  await metadataPage.locator('input[name="email"]').fill(`golden-metadata-${suffix}@ardor.invalid`);
  await metadataPage.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await metadataPage.locator('button[type="submit"]').click();
  await metadataPage.waitForURL(`${origin}/app`, { timeout: 15_000 });
  await metadataPage.goto(`${origin}/app/opportunities`);
  await metadataPage.getByRole("button", { name: /^(Edit record|编辑记录)$/ }).click();
  const editInterview = metadataPage.getByRole("dialog", { name: /^(Edit interview|编辑面试)$/ });
  await editInterview.locator('input[name="targetRole"]').fill("Updated backend role");
  await editInterview.locator('textarea[name="jdContent"]').fill("Temporary JD");
  await Promise.all([
    metadataPage.waitForResponse((response) => response.url().includes("/api/opportunities/") && response.request().method() === "PATCH" && response.status() === 200),
    editInterview.getByRole("button", { name: /^(Save|保存)$/ }).click(),
  ]);
  await editInterview.waitFor({ state: "hidden" });
  await metadataPage.getByRole("button", { name: /^(Edit record|编辑记录)$/ }).click();
  await editInterview.locator('textarea[name="jdContent"]').fill("");
  await editInterview.getByRole("button", { name: /^(Save|保存)$/ }).click();
  await editInterview.waitFor({ state: "hidden" });
  await metadataPage.reload();
  const completedMetadata = await metadataPage.evaluate(async () => {
    const [processes, recaps] = await Promise.all([
      fetch("/api/opportunities").then(response => response.json()),
      fetch("/api/interview-recaps").then(response => response.json()),
    ]);
    return { processes, recaps };
  });
  assert.equal(completedMetadata.processes[0].targetRole, "Updated backend role");
  assert.equal(completedMetadata.processes[0].jdContent ?? "", "");
  assert.equal(completedMetadata.recaps[0].jobOpportunityId, completedMetadata.processes[0].id);
  assert.equal("company" in completedMetadata.recaps[0], false);
  await metadataPage.goto(`${origin}/app/recaps`);
  await metadataPage.getByRole("link", { name: new RegExp(completedMetadata.recaps[0].title) }).click();
  await metadataPage.getByRole("heading", { name: completedMetadata.recaps[0].title, exact: true }).waitFor();

  const replayPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  activePage = replayPage;
  replayPage.on("pageerror", (error) => pageErrors.push(error.message));
  await replayPage.goto(`${origin}/login`);
  await replayPage.locator('input[name="email"]').fill(`golden-ui-replay-${suffix}@ardor.invalid`);
  await replayPage.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await replayPage.locator('button[type="submit"]').click();
  await replayPage.waitForURL(`${origin}/app`, { timeout: 15_000 });
  const replayRecaps = await replayPage.evaluate(async () => {
    const response = await fetch("/api/interview-recaps");
    if (!response.ok) throw new Error(`Interview notes returned ${response.status}`);
    return response.json();
  });
  assert.equal(replayRecaps.length, 1);
  const replayWeak = replayRecaps[0].questions.find((item) => item.performance === "WEAK");
  assert.ok(replayWeak);
  await replayPage.goto(`${origin}/app/evidence`);
  await replayPage.getByRole("link", { name: /Make a learning plan|制定补强计划/ }).first().click();
  await replayPage.getByRole("textbox", { name: /Message Ardor|给 Ardor 发消息/ }).waitFor();
  const learningDraft = await replayPage.getByRole("textbox", { name: /Message Ardor|给 Ardor 发消息/ }).inputValue();
  assert.ok(learningDraft.includes(replayWeak.id),
    "The weak-question learning request must carry its source question ID");
  assert.equal(await replayPage.getByRole("button", { name: /Remove attachment|移除资料/ }).count(), 1,
    "The original recap must be attached to the unsent learning request");
  const untouchedPlans = await replayPage.evaluate(async () => {
    const response = await fetch("/api/learning-plans");
    if (!response.ok) throw new Error(`Learning plans returned ${response.status}`);
    return response.json();
  });
  assert.equal(untouchedPlans.length, 0, "Opening the Agent handoff must not create a plan");
  await replayPage.goto(`${origin}/app/replay?question=${replayWeak.id}`);
  await replayPage.getByText(replayWeak.questionText).first().waitFor();
  await replayPage.locator("#replay-answer").fill("我会比较 RDB 快照和 AOF 日志的恢复窗口与成本。");
  await replayPage.getByRole("button", { name: /Submit & compare|提交并对比/ }).click();
  await replayPage.getByText(/Assessment paused|评估未完成/).waitFor({ timeout: 40_000 });
  assert.equal(await replayPage.locator("#replay-answer").inputValue(),
    "我会比较 RDB 快照和 AOF 日志的恢复窗口与成本。");
  const failedReplay = await replayPage.evaluate(async (questionId) => {
    const response = await fetch(`/api/interview-replays/questions/${questionId}`);
    if (!response.ok) throw new Error(`Replay returned ${response.status}`);
    return response.json();
  }, replayWeak.id);
  assert.equal(failedReplay.attempts.length, 0, "A failed provider call must not save a replay attempt");
  await replayPage.goto(`${origin}/app/calendar`);
  await replayPage.getByRole("heading", { level: 1, name: /Make today count|把目标，放进今天/ }).waitFor();
  await replayPage.goto(`${origin}/app/replay?question=${replayWeak.id}`);
  await replayPage.getByText(replayWeak.questionText).first().waitFor();
  assert.equal(await replayPage.locator("#replay-answer").inputValue(),
    "我会比较 RDB 快照和 AOF 日志的恢复窗口与成本。",
    "The unsent answer must survive navigation after a provider error");
  await replayPage.getByRole("button", { name: /Retry assessment|重试评估/ }).click();
  await replayPage.getByRole("region", { name: /Latest replay result|最新回放结果/ }).waitFor();
  await replayPage.getByText("我会比较 RDB 快照和 AOF 日志的恢复窗口与成本。").first().waitFor();
  const recoveredReplay = await replayPage.evaluate(async (questionId) => {
    const response = await fetch(`/api/interview-replays/questions/${questionId}`);
    if (!response.ok) throw new Error(`Replay returned ${response.status}`);
    return response.json();
  }, replayWeak.id);
  assert.equal(recoveredReplay.attempts.length, 1, "Retrying the saved answer must create just one attempt");
  await replayPage.reload();
  await replayPage.getByRole("region", { name: /Latest replay result|最新回放结果/ }).waitFor();
  await replayPage.getByText("我会比较 RDB 快照和 AOF 日志的恢复窗口与成本。").first().waitFor();
  await replayPage.getByRole("button", { name: /Schedule retest|安排复测/ }).click();
  await replayPage.getByText(/Scheduled for |已安排 /).waitFor();
  const replayTasks = await replayPage.evaluate(async () => {
    const response = await fetch("/api/calendar/tasks");
    if (!response.ok) throw new Error(`Calendar tasks returned ${response.status}`);
    return response.json();
  });
  const retestTasks = replayTasks.filter((task) => task.taskKind === "INTERVIEW_RETEST");
  assert.equal(retestTasks.length, 1, "Scheduling from replay should create one calendar retest");
  assert.equal(retestTasks[0].status, "TODO");
  await replayPage.getByRole("link", { name: /View calendar|查看日历/ }).click();
  await replayPage.waitForURL(`${origin}/app/calendar`);
  const monthDelta = await replayPage.evaluate((dueAt) => {
    const due = new Date(dueAt), today = new Date();
    return (due.getFullYear() - today.getFullYear()) * 12 + due.getMonth() - today.getMonth();
  }, retestTasks[0].dueAt);
  assert.ok(monthDelta >= 0 && monthDelta <= 1);
  if (monthDelta === 1) await replayPage.getByRole("button", { name: /Next month|下个月/ }).click();
  await replayPage.locator("section.ardor-panel button").filter({ hasText: /Interview retest|面试复测/ }).first().click();
  await replayPage.getByRole("link", { name: /^(Start|开始)$/ }).click();
  await replayPage.waitForURL(new RegExp(`/app/replay\\?question=${replayWeak.id}&retest=${retestTasks[0].id}$`));
  await replayPage.getByText(/Variation retest|变式复测/).first().waitFor();
  await replayPage.locator("#replay-answer").fill("先确定可接受的丢失窗口，再选择快照与日志策略。");
  await replayPage.getByRole("button", { name: /Submit & compare|提交并对比/ }).click();
  await replayPage.getByText("先确定可接受的丢失窗口，再选择快照与日志策略。").first().waitFor();
  let completedRetest = null;
  for (let attempt = 0; attempt < 30; attempt++) {
    completedRetest = await replayPage.evaluate(async (path) => {
      const response = await fetch("/api/calendar/tasks", { cache: "no-store" });
      if (!response.ok) throw new Error(`Calendar tasks returned ${response.status}`);
      return (await response.json()).filter((task) => task.actionPath === path);
    }, retestTasks[0].actionPath);
    if (completedRetest.length === 1 && completedRetest[0].status === "COMPLETED") break;
    await replayPage.waitForTimeout(200);
  }
  assert.equal(completedRetest?.length, 1);
  if (completedRetest[0].status !== "COMPLETED") {
    const replayState = await replayPage.evaluate(async (questionId) => {
      const response = await fetch(`/api/interview-replays/questions/${questionId}`, { cache: "no-store" });
      return response.json();
    }, replayWeak.id);
    throw new Error(`Calendar retest remained ${completedRetest[0].status}; newest attempt retest ID: ${replayState.attempts[0]?.retestTaskId ?? "none"}; expected: ${retestTasks[0].id}`);
  }
  await replayPage.reload();
  await replayPage.getByText("先确定可接受的丢失窗口，再选择快照与日志策略。").first().waitFor();

  const depthPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  activePage = depthPage;
  depthPage.on("pageerror", (error) => pageErrors.push(error.message));
  await depthPage.goto(`${origin}/login`);
  await depthPage.locator('input[name="email"]').fill(`golden-depth-${suffix}@ardor.invalid`);
  await depthPage.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await depthPage.locator('button[type="submit"]').click();
  await depthPage.waitForURL(`${origin}/app`, { timeout: 15_000 });
  await depthPage.getByRole("button", { name: /Thinking depth|思考深度/, exact: true }).click();
  await depthPage.getByRole("button", { name: "Max", exact: true }).click();
  await depthPage.reload();
  await depthPage.getByRole("button", { name: /Thinking depth|思考深度/, exact: true }).waitFor();
  // Account settings load independently of the transcript.
  await depthPage.waitForFunction(() => document.querySelector('[data-reasoning-depth]')?.getAttribute("data-reasoning-depth") === "DEEP");
  await depthPage.getByRole("button", { name: /^(New chat|新对话)$/ }).last().click();
  await depthPage.getByRole("textbox", { name: /Message Ardor|给 Ardor 发消息/ }).fill("帮我继续规划 Redis 面试学习");
  await depthPage.getByRole("button", { name: /^(Send|发送)$/ }).click();
  await depthPage.getByText("Redis 学习规划", { exact: true }).first().waitFor({ timeout: 30_000 });
  const depthConversations = await depthPage.evaluate(async () => {
    const response = await fetch("/api/agent/conversations");
    if (!response.ok) throw new Error(`Conversations returned ${response.status}`);
    return response.json();
  });
  assert.equal(depthConversations.filter((item) => item.title === "我的手动标题").length, 1);
  assert.equal(depthConversations.filter((item) => item.title === "Redis 学习规划").length, 1);
  const taskPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  // This server process did not execute the silent/cancelled runs. Progress must
  // therefore come from PostgreSQL rather than a retained in-memory buffer.
  const restoredContext = await browser.newContext();
  const restoredLogin = await restoredContext.request.get(`${origin}/api/auth/csrf`);
  const restoredCsrf = await restoredLogin.json();
  const restoredAuth = await restoredContext.request.post(`${origin}/api/auth/login`, { data: { email: `golden-silence-${suffix}@ardor.invalid`, password: `Ardor-Acceptance-${suffix}!` }, headers: { [restoredCsrf.headerName]: restoredCsrf.token } });
  assert.equal(restoredAuth.ok(), true);
  const restoredConversations = await (await restoredContext.request.get(`${origin}/api/agent/conversations`)).json();
  const restoredQueue = await (await restoredContext.request.get(`${origin}/api/agent/queue?conversationId=${restoredConversations[0].id}`)).json();
  const cancelledRequest = restoredQueue.find(item => item.request.message.startsWith("Explicit user cancellation probe"));
  assert.ok(cancelledRequest, "Restore the cancelled request, not a later successful correction");
  const restoredRun = await (await restoredContext.request.get(`${origin}/api/agent/runs/${cancelledRequest.id}`)).json();
  const restoredProgress = await restoredContext.request.get(`${origin}/api/agent/runs/${restoredRun.id}/progress`);
  assert.equal(restoredProgress.ok(), true);
  assert.equal((await restoredProgress.json()).phase, "FAILED");
  assert.equal(restoredRun.errorCode, "AGENT_RUN_CANCELLED");
  await restoredContext.close();
  activePage=taskPage;
  taskPage.on("pageerror",error => pageErrors.push(error.message));
  await taskPage.goto(`${origin}/login`);
  await taskPage.locator('input[name="email"]').fill(`golden-async-${suffix}@ardor.invalid`);
  await taskPage.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await taskPage.locator('button[type="submit"]').click();
  await taskPage.waitForURL(`${origin}/app`);
  await taskPage.getByRole("button",{name:/More workspaces|更多工作区/}).hover();
  await taskPage.getByRole("link",{name:/Background tasks|后台任务/}).click();
  await taskPage.waitForURL(`${origin}/app/tasks`);
  await taskPage.getByRole("heading",{level:1,name:/Background tasks|后台任务/}).waitFor();
  assert.equal(await taskPage.getByRole("complementary",{name:/Main navigation|主导航/}).count(),1);
  const beforeReads = await taskPage.evaluate(async () => (await fetch("/api/background-tasks?limit=100")).json());
  const gradedTask = beforeReads.find(item => item.kind==="LEARNING_EVALUATE" && item.status==="COMPLETED");
  const interruptedTask = beforeReads.find(item => item.kind==="LEARNING_EVALUATE" && item.status==="FAILED");
  assert.equal(interruptedTask?.errorCode,"AI_JOB_INTERRUPTED","An interrupted worker must surface a recoverable task, not lose its submission");
  await taskPage.locator(`[data-task-id="${interruptedTask.id}"]`).getByRole("button",{name:/^Retry$|^重试$/}).click();
  await waitForApiState(taskPage,`/api/background-tasks/LEARNING_EVALUATE/${interruptedTask.id}`,task=>task.status==="COMPLETED");
  const recoveredPlan = await taskPage.evaluate(async(id) => (await fetch(`/api/learning-plans/${id}`,{cache:"no-store"})).json(),interruptedTask.resourceId);
  assert.equal(recoveredPlan.attemptCount,1,`Interrupted task ${interruptedTask.id} resource ${interruptedTask.resourceId} returned plan ${recoveredPlan.id} with ${recoveredPlan.attemptCount} attempts`);
  assert.equal(recoveredPlan.lastEvaluation.attempts[0].items[1].answer,"restart saved answer");
  const vectorTask = beforeReads.find(item => item.kind==="KNOWLEDGE_INDEX");
  assert.equal(gradedTask.unread,false,"Read state must survive the backend restart");
  assert.equal(vectorTask.status,"COMPLETED");
  assert.equal(vectorTask.unread,true);
  const vectorArticle = taskPage.getByRole("article",{name:/Knowledge indexing|知识库向量化/});
  await taskPage.route(/\/api\/resumes\/[^/]+\/analysis$/,route=>route.abort());
  const resumeTask = beforeReads.find(item=>item.kind==="RESUME_ANALYSIS");
  await taskPage.locator(`[data-task-id="${resumeTask.id}"]`).getByRole("link",{name:/Open result|查看结果/}).click();
  await taskPage.waitForURL(/\/app\/resumes\?selected=/);
  await taskPage.getByRole("button",{name:/All resumes|所有简历/}).waitFor();
  await taskPage.getByRole("alert").waitFor();
  assert.equal(await taskPage.evaluate(async(id)=>(await (await fetch(`/api/background-tasks/RESUME_ANALYSIS/${id}`)).json()).unread,resumeTask.id),true,"Failed report loading must not clear a notification");
  await taskPage.unroute(/\/api\/resumes\/[^/]+\/analysis$/);
  await taskPage.reload();
  await taskPage.getByText(/技术栈聚焦，项目经历清晰/).waitFor();
  await waitForApiState(taskPage,`/api/background-tasks/RESUME_ANALYSIS/${resumeTask.id}`,task=>!task.unread);
  await taskPage.goto(`${origin}/app/tasks`);
  await vectorArticle.getByRole("link",{name:/Open result|查看结果/}).click();
  await taskPage.waitForURL(/\/app\/knowledge\?document=/);
  await taskPage.getByText("redis-learning").first().waitFor();
  await waitForApiState(taskPage,`/api/background-tasks/KNOWLEDGE_INDEX/${vectorTask.id}`,task=>!task.unread);
  await taskPage.goto(`${origin}/app/tasks`);
  await vectorArticle.waitFor();
  assert.equal(await vectorArticle.getByLabel(/Unread result|未读结果/).count(),0);
  const afterReads = await taskPage.evaluate(async () => (await fetch("/api/background-tasks?limit=100")).json());
  assert.equal(afterReads.find(item => item.kind==="LEARNING_CREATE").unread,true,"Reading one result must not clear unrelated notifications");
  const blockedStatus = /\/api\/background-tasks\?/;
  await taskPage.route(blockedStatus,route=>route.abort());
  await taskPage.getByRole("button",{name:/Refresh tasks|刷新任务/}).click();
  await taskPage.getByRole("alert").filter({hasText:/Connection interrupted|连接暂时中断/}).waitFor();
  await vectorArticle.waitFor();
  await taskPage.unroute(blockedStatus);
  await taskPage.getByRole("button",{name:/Refresh tasks|刷新任务/}).click();
  await taskPage.getByRole("alert").filter({hasText:/Connection interrupted|连接暂时中断/}).waitFor({state:"hidden"});
  await taskPage.goto(`${origin}/app/settings`);
  await taskPage.getByRole("button",{name:"中文",exact:true}).click();
  await taskPage.goto(`${origin}/app/tasks`);
  await taskPage.getByRole("heading",{level:1,name:"后台任务",exact:true}).waitFor();
  await taskPage.getByRole("article",{name:"知识库向量化",exact:true}).waitFor();
  assert.deepEqual(pageErrors, [], "No page should throw an uncaught browser error");
  console.log("Browser Golden Path passed: Agent confirmation → two learning rounds → interview report → failed recap retry → Shanghai-date metadata completion → replay → calendar retest completion.");
} catch (error) {
  const screenshot = join(process.env.RUNNER_TEMP ?? ".", "ardor-ui-failure.png");
  await activePage.screenshot({ path: screenshot, fullPage: true }).catch(() => {});
  console.error(`Browser screenshot on failure: ${screenshot}`);
  throw error;
} finally {
  await browser.close();
}
