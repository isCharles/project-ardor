import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Real production UI with synthetic API responses; never fetch a real account.
const origin = "http://127.0.0.1:3117";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3117"], { stdio: "pipe" });
let logs = "", browser, page;
server.stdout.on("data", value => { logs += value; }); server.stderr.on("data", value => { logs += value; });
const items = Array.from({ length: 6 }, (_, i) => ({ question: `Synthetic question ${i + 1}`, answer: `Answer ${i + 1}`, type: "SHORT_ANSWER" }));
const feedback = items.map((item, i) => ({ question: [0, 3, 4].includes(i) ? `Paraphrased question ${i + 1}` : item.question, feedback: `Legacy comment ${i + 1}` }));
function plan(id, concept, status, identified = false) {
  const answered = items.map((item, i) => ({ ...item, ...(identified ? { exerciseId: `q-${i}` } : {}) }));
  return { id, concept, status, requestId: null, reason: "Synthetic fixture", sourceType: "MANUAL", sourceId: null,
    originKind: "MANUAL", originResourceId: null, originRecordId: null, targetInterviewId: null, targetRoundId: null,
    lesson: { summary: "Synthetic lesson", explanation: "A short fixture lesson." }, exercises: [], lastScore: 87,
    attemptCount: 1, contentVersion: 1, exercisesVersion: 1, scheduledAt: "2099-04-01T00:00:00Z", nextReviewAt: null,
    completedAt: status === "COMPLETED" ? "2026-10-01T00:00:00Z" : null, createdAt: "2026-10-01T00:00:00Z",
    lastEvaluation: { feedback: "Round summary fixture", strengths: ["Verified strength"], gaps: ["Knowledge gap fixture"], nextFocus: "Next focus fixture",
      attempts: [{ items: answered, score: 87, submittedAt: "2026-10-01T00:00:00Z", feedback: "Round summary fixture",
        questionFeedback: identified ? answered.map((item, i) => ({ exerciseId: item.exerciseId, question: "Changed title", feedback: `Identified comment ${i + 1}` })).reverse() : feedback }] } };
}
const plans = [plan("legacy", "Legacy course", "COMPLETED"), plan("identified", "Identified course", "IN_PROGRESS", true),
  plan("scheduled", "Scheduled course", "SCHEDULED"), plan("review", "Review course", "NEEDS_REVIEW")];
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext();
  const errors = [];
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "feedback-owner", email: "feedback@ardor.invalid", admin: false };
    else if (path === "/api/profile/sidebar") body = { userId: "feedback-owner", revision: 1, destinationIds: ["learning"] };
    else if (path === "/api/profile") body = { displayName: "Synthetic learner", targetRoles: [], timezone: "UTC" };
    else if (path === "/api/learning-plans") body = plans;
    else if (path === "/api/auth/csrf") body = { headerName: "X-CSRF-TOKEN", token: "fixture" };
    await route.fulfill({ json: body });
  });
  page = await context.newPage(); page.on("pageerror", error => errors.push(error.message));
  for (const width of [1440, 900, 390]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(`${origin}/app/learning?id=legacy`);
    const questions = page.getByRole("region", { name: "Question-by-question review", exact: true });
    await questions.waitFor();
    const unassigned = page.getByRole("region", { name: "Unassigned feedback", exact: true });
    await unassigned.getByRole("heading", { name: "Unassigned feedback (3)", exact: true }).waitFor();
    for (let i = 1; i <= 6; i++) assert.equal(await page.getByText(`Legacy comment ${i}`, { exact: true }).count(), 1);
    assert.equal(await questions.getByRole("article", { name: "Question 1", exact: true }).getByText("Legacy comment 1").count(), 0,
      "A paraphrase must not be blindly assigned by array position");
    assert.equal(await questions.getByRole("article", { name: "Question 2", exact: true }).getByText("Legacy comment 2", { exact: true }).count(), 1);
    assert.ok(await questions.evaluate(element => Boolean(element.compareDocumentPosition(document.querySelector('[aria-label="Round summary"]')) & Node.DOCUMENT_POSITION_FOLLOWING)));
    assert.ok(await page.getByRole("region", { name: "Round summary", exact: true }).evaluate(element => Boolean(element.compareDocumentPosition(document.querySelector('[aria-label="Next steps and knowledge gaps"]')) & Node.DOCUMENT_POSITION_FOLLOWING)));
    const navigation = page.getByRole("navigation", { name: "Learning plans", exact: true });
    for (const name of ["In progress", "Needs review", "Scheduled"]) assert.equal(await navigation.getByRole("region", { name, exact: true }).count(), 1);
    await navigation.getByText("Mastered archive", { exact: false }).waitFor();
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, `No overflow at ${width}`);
    await page.screenshot({ path: `.next/learning-feedback-legacy-${width}.png`, fullPage: true });
    await unassigned.scrollIntoViewIfNeeded();
    await page.screenshot({ path: `.next/learning-feedback-unassigned-${width}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole("navigation", { name: "Learning plans", exact: true }).getByRole("button", { name: /Identified course/ }).click();
  await page.getByRole("heading", { name: "Identified course", exact: true }).waitFor();
  assert.equal(await page.getByRole("region", { name: "Unassigned feedback", exact: true }).count(), 0);
  const questions = page.getByRole("region", { name: "Question-by-question review", exact: true });
  for (let i = 1; i <= 6; i++) await questions.getByRole("article", { name: `Question ${i}`, exact: true }).getByText(`Identified comment ${i}`, { exact: true }).waitFor();
  const archive = page.getByRole("navigation", { name: "Learning plans", exact: true }).locator("details");
  assert.equal(await archive.getAttribute("open"), null, "Mastered plans are archived while working on an active plan");
  await archive.locator("summary").click();
  await archive.getByRole("button", { name: /Legacy course/ }).waitFor();
  assert.deepEqual(errors, []);
  console.log("Learning review UI: six legacy comments preserved, stable-ID matching despite reordered paraphrases, questions before summary, status groups and archived mastery at 1440/900/390 passed (synthetic APIs).");
} catch (error) {
  await page?.screenshot({ path: ".next/learning-feedback-failure.png", fullPage: true }).catch(() => {});
  throw error;
} finally { await browser?.close(); server.kill(); }
