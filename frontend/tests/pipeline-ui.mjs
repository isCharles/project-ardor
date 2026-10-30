import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// No production API or account: every backend request uses synthetic fixtures.
const origin = "http://127.0.0.1:3107";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3107"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => { logs += chunk; });
server.stderr.on("data", chunk => { logs += chunk; });
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const recaps = ["alpha", "beta"].map(id => ({ id, title: `Fixture ${id}`, overview: `Unique ${id} overview`,
    strengths: [], weaknesses: [], questions: [], createdAt: id === "alpha" ? "2026-10-01T01:00:00Z" : "2026-09-03T01:00:00Z", jobOpportunityId: null }));
  const longTitle = `Long interview notes ${"很长的面试记录标题和技术关键词".repeat(60)}`;
  const longConcept = `Long learning topic ${"UnbrokenTechnicalConcept".repeat(80)}`;
  const cardFront = `PRIVATE_CARD_BODY_${"do-not-render-this-question".repeat(80)}`;
  recaps.push({ ...recaps[0], id: "linked", title: longTitle, jobOpportunityId: "process-one", questions: [{ id: "question-one" }] });
  const opportunities = [{ id: "process-one", company: "Fixture company", targetRole: "Backend intern", platform: "Fixture", status: "AWAITING_RESULT", currentRound: 1,
    jdContent: "Synthetic job description", inviteContent: "Synthetic interview invitation",
    createdAt: "2026-10-01T01:00:00Z", updatedAt: "2026-10-02T01:00:00Z",
    rounds: [{ id: "round-one", roundNumber: 1, stage: "TECHNICAL", scheduledAt: "2026-10-20T01:00:00Z", occurredAt: null, durationMinutes: 60,
      note: "改期需提前 1 天\n研发岗可能含代码考核且必须使用电脑" }] }];
  opportunities.push({ ...opportunities[0], id: "process-two", company: "Preparation employer", rounds: [] });
  opportunities.push({ ...opportunities[0], id: "process-three", company: "Newest employer", targetRole: "Newest role",
    status: "REJECTED", updatedAt: "2026-09-01T01:00:00Z",
    rounds: [{ id: "round-new", roundNumber: 1, stage: "FINAL", scheduledAt: "2026-12-01T01:00:00Z",
      occurredAt: null, durationMinutes: 45, note: null }] });
  let slowJob = false, jobPolls = 0;
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/interview-recaps/final-minutes/slow-job" && slowJob) {
      jobPolls++;
      if (jobPolls === 3) recaps.push({ ...recaps[0], id: "slow", title: "Slow completed notes" });
      return route.fulfill({ json: { jobId: "slow-job", minutes: "Saved final minutes fixture", errorMessage: null,
        status: jobPolls < 3 ? "RUNNING" : "COMPLETED", recapId: jobPolls < 3 ? null : "slow" } });
    }
    const body = path === "/api/auth/me" ? { id: "pipeline-fixture", email: "pipeline@ardor.invalid", admin: false }
      : path === "/api/auth/csrf" ? { token: "fixture", headerName: "X-CSRF-TOKEN" }
      : path === "/api/profile/sidebar" ? { userId: "pipeline-fixture", hrefs: ["/app"], revision: 0, configured: true }
      : path === "/api/background-tasks/summary" ? { activeCount: 0, unreadByModule: {} }
      : path === "/api/agent/permissions" ? { globalLevel: 0, modules: [] }
      : path === "/api/opportunities" ? opportunities
      : path === "/api/memory-cards" ? [1, 2].map(id => ({ id: `card-${id}`, recapQuestionId: "question-one", front: cardFront, status: "ACTIVE" }))
      : path === "/api/learning-plans" ? ["COMPLETED", "IN_PROGRESS", "NEEDS_REVIEW", "SCHEDULED"].map((status, index) => ({ id: index === 0 ? "plan-one" : `plan-${index + 1}`, concept: index === 0 ? longConcept : `Synthetic course ${index + 1}`, sourceType: "RECAP", sourceId: "question-one", originKind: "INTERVIEW_QUESTION", originResourceId: "question-one", originRecordId: "linked", targetInterviewId: index === 0 ? "process-two" : null, status }))
      : path === "/api/interview-recaps/final-minutes" ? slowJob ? [{ jobId: "slow-job", preview: "Slow submitted minutes", status: "RUNNING", recapId: null }] : []
      : path === "/api/interview-recaps" ? recaps : [];
    await route.fulfill({ json: body });
  });
  const page = await context.newPage(), errors = [];
  page.on("pageerror", error => errors.push(error.message));
  for (const path of ["/app/interviews", "/app/learning/practice/mock-interviews"]) {
    await page.goto(`${origin}${path}`);
    await page.getByRole("heading", { name: "Mock interviews", exact: true }).waitFor();
    await page.getByRole("heading", { name: "New mock interview", exact: true }).waitFor();
    await page.getByRole("heading", { name: "Past mock interviews", exact: true }).waitFor();
    assert.equal(await page.locator('main a[href^="/app/recaps"], main a[href^="/app/applications"]').count(), 0,
      "Real interview notes and application rhythm must not be children of mock interviews");
  }
  await page.goto(`${origin}/app/job-search`);
  await page.getByRole("heading", { name: "Job search", exact: true }).waitFor();
  await page.locator('main a[href="/app/applications"]').waitFor();
  // The home card follows the newest interview, not the most recently edited record.
  await page.getByText("Newest employer", { exact: true }).waitFor();
  assert.equal(await page.getByText("Closed", { exact: true }).count(), 1, "An ended interview reads as Closed");
  assert.equal(await page.getByText("Rejected", { exact: true }).count(), 0);
  await page.screenshot({ path: ".next/pipeline-home-card.png", fullPage: true });
  await page.goto(`${origin}/app/recaps`);
  await page.getByRole("link", { name: /Fixture alpha/ }).waitFor();
  for (const width of [1440, 900, 390]) {
    await page.setViewportSize({ width, height: 900 });
    const rightEdges = await page.locator("[data-recap-date]").evaluateAll(nodes => nodes.map(node => node.getBoundingClientRect().right));
    assert.ok(rightEdges.length >= 3 && Math.max(...rightEdges) - Math.min(...rightEdges) < 1, `Dates must share a right edge at ${width}px`);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: ".next/pipeline-notes-dates.png", fullPage: true });
  await page.getByRole("link", { name: /Fixture alpha/ }).click();
  await page.getByRole("heading", { name: "Fixture alpha", exact: true }).waitFor();
  await page.getByText("Unique alpha overview", { exact: true }).waitFor();
  await page.goBack();
  await page.getByRole("link", { name: /Fixture beta/ }).click();
  await page.getByRole("heading", { name: "Fixture beta", exact: true }).waitFor();
  await page.goBack();
  await page.getByRole("link", { name: /Fixture alpha/ }).waitFor();
  await page.goForward();
  await page.getByRole("heading", { name: "Fixture beta", exact: true }).waitFor();
  await page.screenshot({ path: ".next/pipeline-recap.png", fullPage: true });
  slowJob = true;
  await page.goto(`${origin}/app/recaps`);
  await page.getByText("Saved submissions", { exact: true }).click();
  await page.getByRole("link", { name: /Slow submitted minutes/ }).click();
  await page.getByRole("status").filter({ hasText: /Minutes saved/ }).waitFor();
  await page.getByRole("heading", { name: "Slow completed notes", exact: true }).waitFor({ timeout: 20000 });
  assert.ok(jobPolls >= 3, "Selected final minutes must keep polling while status remains RUNNING");
  assert.equal(new URL(page.url()).searchParams.get("minutes"), "slow-job");
  assert.equal(new URL(page.url()).searchParams.get("selected"), "slow");
  await page.getByText("Saved final minutes", { exact: true }).click();
  await page.getByText("Saved final minutes fixture", { exact: true }).waitFor();
  await page.goto(`${origin}/app/opportunities`);
  await page.getByRole("combobox", { name: "Result", exact: true }).first().waitFor();
  const initialResults = await page.getByRole("combobox", { name: "Result", exact: true }).evaluateAll(items => items.map(item => item.value));
  for (const width of [1440, 900, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.getByRole("button", { name: "Presentation mode", exact: true }).click();
    for (const label of ["Result", "Date", "Round"]) assert.equal(await page.getByRole("columnheader", { name: label, exact: true }).count(), 0);
    assert.equal(await page.getByRole("combobox", { name: "Result", exact: true }).count(), 0);
    assert.equal(await page.getByRole("button", { name: "Backend intern", exact: true }).count(), 2, "Role and record access stay available");
    assert.equal(await page.getByRole("cell", { name: "Fixture company", exact: true }).count(), 1);
    assert.equal(await page.locator("table col").count(), 5);
    await page.getByRole("button", { name: "Presentation mode", exact: true }).click();
    assert.deepEqual(await page.getByRole("combobox", { name: "Result", exact: true }).evaluateAll(items => items.map(item => item.value)), initialResults);
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Fixture company", exact: true }) })
    .getByRole("button", { name: "Backend intern", exact: true }).click();
  assert.equal(await page.getByRole("button", { name: "Round notes", exact: true }).count(), 0, "Round notes no longer sit as a row chip");
  assert.equal(await page.getByRole("link", { name: "Notes", exact: true }).count(), 0, "Interview notes no longer sit as a row chip");
  const recapRow = page.locator('table a[href="/app/recaps?selected=linked"]');
  await recapRow.waitFor();
  const cardsLink = page.getByRole("link", { name: "Flashcards 2", exact: true });
  await cardsLink.waitFor();
  assert.equal(await page.getByRole("link",{name:/View learning progress from this interview/}).getAttribute("href"),
    "/app/learning/progress?sourceInterviewId=process-one","Interview progress is scoped by provenance, not preparation target");
  const courseButton = page.getByRole("button", { name: /4 related courses/ });
  assert.doesNotMatch(await courseButton.innerText(), /Choose/);
  const otherButton = page.getByRole("button", { name: /^Other/ }).first();
  assert.doesNotMatch(await otherButton.innerText(), /Open/);
  await otherButton.click();
  const otherDialog = page.getByRole("dialog", { name: "Other", exact: true });
  await otherDialog.getByText("Synthetic job description", { exact: true }).waitFor();
  await otherDialog.getByText("Synthetic interview invitation", { exact: true }).waitFor();
  assert.match(await otherDialog.getByText(/研发岗可能含代码考核/).innerText(),
    /改期需提前 1 天\n研发岗可能含代码考核且必须使用电脑/, "Round notes keep their line breaks inside Other");
  assert.equal(await otherDialog.getByRole("link", { name: /Interview notes/ }).count(), 0,
    "Interview notes keep their own row instead of moving into Other");
  await page.screenshot({ path: ".next/pipeline-other-dialog.png", fullPage: true });
  await otherDialog.getByRole("button", { name: "Close", exact: true }).click();
  await courseButton.click();
  const courseDialog = page.getByRole("dialog", { name: "Courses", exact: true });
  const sourcePlan=courseDialog.locator('a[href="/app/learning?id=plan-one"]');
  assert.doesNotMatch(await sourcePlan.innerText(),/Learning from here/i);
  assert.doesNotMatch(await sourcePlan.innerText(),/Preparation/i);
  for (const label of ["Completed", "In progress", "Needs review", "Scheduled"]) await courseDialog.getByText(label, { exact: true }).waitFor();
  assert.match(await sourcePlan.getAttribute("class"), /bg-emerald-50/);
  assert.doesNotMatch(await courseDialog.innerText(), /COMPLETED|IN_PROGRESS|NEEDS_REVIEW|SCHEDULED/);
  await courseDialog.getByRole("button", { name: "Close", exact: true }).click();
  assert.ok(!(await page.locator("main").innerText()).includes("PRIVATE_CARD_BODY_"), "Card bodies must not render, including concatenated summaries");
  for (const width of [1440, 900, 390]) {
    await page.setViewportSize({ width, height: 900 });
    const geometry = await page.locator("table").evaluate(table => {
      const rect = table.getBoundingClientRect();
      const titles = [...table.querySelectorAll("a span[title]")];
      return { tableWidth: rect.width, containerWidth: table.parentElement.clientWidth,
        documentWidth: document.documentElement.scrollWidth, viewportWidth: window.innerWidth,
        controlsFit: [...table.querySelectorAll("select")].every(select => {
          const cell = select.closest("td").getBoundingClientRect(), control = select.getBoundingClientRect();
          return control.left >= cell.left && control.right <= cell.right;
        }),
        titles: titles.map(span => ({ ellipsis: getComputedStyle(span).textOverflow, clipped: span.scrollWidth > span.clientWidth,
          fits: span.getBoundingClientRect().right <= rect.right + 1 })) };
    });
    assert.ok(geometry.tableWidth <= Math.max(860, geometry.containerWidth) + 2, `Long association expanded table at ${width}px`);
    assert.ok(geometry.documentWidth <= geometry.viewportWidth + 1, `Long content expanded viewport at ${width}px`);
    assert.ok(geometry.controlsFit, `Result control overlaps another cell at ${width}px`);
    assert.ok(geometry.titles.length >= 1 && geometry.titles.every(title => title.ellipsis === "ellipsis" && title.clipped && title.fits),
      `Recap titles did not truncate at ${width}px`);
    await otherButton.click();
    const otherGeometry = await otherDialog.evaluate(dialog => ({
      fits: dialog.getBoundingClientRect().right <= window.innerWidth + 1,
      documentWidth: document.documentElement.scrollWidth, viewportWidth: window.innerWidth }));
    assert.ok(otherGeometry.fits && otherGeometry.documentWidth <= otherGeometry.viewportWidth + 1,
      `Other dialog expanded viewport at ${width}px`);
    await otherDialog.getByRole("button", { name: "Close", exact: true }).click();
    await courseButton.click();
    const courseGeometry = await sourcePlan.locator("span[title]").evaluate(span => {
      const dialog = span.closest("dialog").getBoundingClientRect();
      return { ellipsis: getComputedStyle(span).textOverflow, clipped: span.scrollWidth > span.clientWidth,
        fits: span.getBoundingClientRect().right <= dialog.right,
        documentWidth: document.documentElement.scrollWidth, viewportWidth: window.innerWidth };
    });
    assert.ok(courseGeometry.ellipsis === "ellipsis" && courseGeometry.clipped && courseGeometry.fits,
      `Course picker title did not truncate at ${width}px`);
    assert.ok(courseGeometry.documentWidth <= courseGeometry.viewportWidth + 1, `Course picker expanded viewport at ${width}px`);
    await page.screenshot({ path: `.next/pipeline-course-${width}.png`, fullPage: true });
    await courseDialog.getByRole("button", { name: "Close", exact: true }).click();
    assert.equal(await cardsLink.getAttribute("href"), "/app/cards");
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: ".next/pipeline-compact-associations.png", fullPage: true });
  await page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Preparation employer", exact: true }) })
    .getByRole("button", { name: "Backend intern", exact: true }).click();
  await page.getByRole("button", { name: /1 related course/ }).click();
  const targetPlan=courseDialog.locator('a[href="/app/learning?id=plan-one"]');
  await targetPlan.waitFor();
  assert.equal(await targetPlan.count(),1,"One plan must appear only once in a target interview");
  assert.match(await targetPlan.innerText(),/Preparation/i);
  assert.doesNotMatch(await targetPlan.innerText(),/Learning from here/i);
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "zh-CN"); window.dispatchEvent(new Event("ardor:locale-change")); });
  const chineseCourses = page.getByRole("dialog", { name: "课程", exact: true });
  await chineseCourses.getByText("已完成", { exact: true }).waitFor();
  await page.keyboard.press("Escape");
  await page.getByRole("row").filter({ has: page.getByRole("cell", { name: "Fixture company", exact: true }) })
    .getByRole("button", { name: "Backend intern", exact: true }).click();
  await page.getByRole("button", { name: /4 门相关课程/ }).click();
  for (const label of ["已完成", "学习中", "待巩固", "待学习"]) await chineseCourses.getByText(label, { exact: true }).waitFor();
  assert.doesNotMatch(await chineseCourses.innerText(), /来源于此|COMPLETED|IN_PROGRESS/);
  await page.screenshot({ path: ".next/pipeline-course-zh.png", fullPage: true });
  assert.deepEqual(errors, []);
  console.log("Pipeline UI: notes navigation, final-minutes polling, source/target course picker, compact associations and count-only cards passed at desktop/tablet/mobile widths.");
} catch (error) {
  await browser?.contexts()[0]?.pages()[0]?.screenshot({ path: ".next/pipeline-failure.png", fullPage: true }).catch(() => {});
  throw error;
} finally { await browser?.close(); server.kill(); }
