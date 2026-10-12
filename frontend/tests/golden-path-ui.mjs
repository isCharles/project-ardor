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

try {
  await page.goto(`${origin}/login`);
  await page.locator('input[name="email"]').fill(`golden-owner-${suffix}@ardor.invalid`);
  await page.locator('input[name="password"]').fill(`Ardor-Acceptance-${suffix}!`);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(`${origin}/app`, { timeout: 15_000 });

  // The latest synthetic conversation contains both a deleted proposal and a
  // distinct pending one. Only the pending one may still offer creation.
  await page.getByText(/Plan removed|计划已移除/).waitFor();
  await page.getByRole("button", { name: /Create plan|生成计划/ }).waitFor();
  assert.equal(await page.getByRole("button", { name: /Create plan|生成计划/ }).count(), 1);
  await page.reload();
  await page.getByText(/Plan removed|计划已移除/).waitFor();
  assert.equal(await page.getByRole("button", { name: /Create plan|生成计划/ }).count(), 1);

  const plansBeforeConfirmation = await page.evaluate(async () => {
    const response = await fetch("/api/learning-plans");
    if (!response.ok) throw new Error(`Learning plans returned ${response.status}`);
    return response.json();
  });
  await page.getByRole("button", { name: /Create plan|生成计划/ }).click();
  await page.getByText(/Learning plan created|学习计划已创建/).waitFor();
  const createdPlanPath = await page.getByRole("link", { name: /Open lesson|打开学习/ }).getAttribute("href");
  assert.match(createdPlanPath, /^\/app\/learning\?id=[0-9a-f-]{36}$/i);
  await page.reload();
  await page.getByText(/Plan removed|计划已移除/).waitFor();
  await page.getByText(/Learning plan created|学习计划已创建/).waitFor();
  assert.equal(await page.getByRole("button", { name: /Create plan|生成计划/ }).count(), 0);
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
  await page.getByRole("radio", { name: "记录写命令" }).check();
  await page.locator("#learning-practice textarea").fill("第一次回答：RDB 是快照。");
  await page.getByRole("button", { name: /Submit answers|提交练习/ }).click();
  await page.getByLabel(/Latest score 65|最近得分 65 分/).waitFor();
  await page.locator("#learning-practice details").first().locator("summary").click();
  await page.getByText("第一次回答：RDB 是快照。").last().waitFor();

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
  await metadataPage.goto(`${origin}/app/recaps`);
  const interviewDate = metadataPage.locator('input[name="occurredOn"]');
  await interviewDate.fill("2026-09-02");
  await Promise.all([
    metadataPage.waitForResponse((response) => response.url().includes("/metadata") && response.status() === 200),
    metadataPage.getByRole("button", { name: /^(Save|保存)$/ }).click(),
  ]);
  await metadataPage.reload();
  await interviewDate.waitFor();
  assert.equal(await interviewDate.inputValue(), "2026-09-02",
    "A date entered in Shanghai must not shift to the prior UTC date on reload");
  await metadataPage.locator('input[name="company"]').fill("Ardor Labs");
  await metadataPage.locator('input[name="targetRole"]').fill("Java 后端工程师");
  await Promise.all([
    metadataPage.waitForResponse((response) => response.url().includes("/metadata") && response.status() === 200),
    metadataPage.getByRole("button", { name: /^(Save|保存)$/ }).click(),
  ]);
  await metadataPage.reload();
  await metadataPage.getByText("Ardor Labs").first().waitFor();
  const completedMetadata = await metadataPage.evaluate(async () => {
    const response = await fetch("/api/interview-recaps");
    if (!response.ok) throw new Error(`Interview notes returned ${response.status}`);
    return response.json();
  });
  assert.equal(completedMetadata.length, 1);
  assert.equal(completedMetadata[0].company, "Ardor Labs");
  assert.equal(completedMetadata[0].targetRole, "Java 后端工程师");
  assert.equal(await metadataPage.evaluate((occurredAt) => new Date(occurredAt).getDate(),
    completedMetadata[0].occurredAt), 2);

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
  await replayPage.goto(`${origin}/app/replay?question=${replayWeak.id}`);
  await replayPage.getByText(replayWeak.questionText).first().waitFor();
  await replayPage.locator("#replay-answer").fill("我会比较 RDB 快照和 AOF 日志的恢复窗口与成本。");
  await replayPage.getByRole("button", { name: /Submit & compare|提交并对比/ }).click();
  await replayPage.getByRole("alert").last().waitFor();
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
  await replayPage.getByRole("button", { name: /Submit & compare|提交并对比/ }).click();
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
