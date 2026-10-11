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
  console.log("Browser Golden Path passed: login → interview report → weak-question replay → learning → calendar.");
} catch (error) {
  const screenshot = join(process.env.RUNNER_TEMP ?? ".", "ardor-ui-failure.png");
  await page.screenshot({ path: screenshot, fullPage: true }).catch(() => {});
  console.error(`Browser screenshot on failure: ${screenshot}`);
  throw error;
} finally {
  await browser.close();
}
