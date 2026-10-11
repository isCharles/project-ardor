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
  await page.getByRole("button", { name: /Start learning|开始学习/ }).click();
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
  assert.deepEqual(pageErrors, [], "No page should throw an uncaught browser error");
  console.log("Browser Golden Path passed: login → Agent confirmation → two learning rounds and calendar completion → interview report → weak-question replay → calendar.");
} catch (error) {
  const screenshot = join(process.env.RUNNER_TEMP ?? ".", "ardor-ui-failure.png");
  await page.screenshot({ path: screenshot, fullPage: true }).catch(() => {});
  console.error(`Browser screenshot on failure: ${screenshot}`);
  throw error;
} finally {
  await browser.close();
}
