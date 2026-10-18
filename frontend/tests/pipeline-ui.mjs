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
    strengths: [], weaknesses: [], questions: [], createdAt: "2026-10-01T01:00:00Z", jobOpportunityId: null }));
  const longTitle = `Long interview notes ${"很长的面试记录标题和技术关键词".repeat(60)}`;
  const longConcept = `Long learning topic ${"UnbrokenTechnicalConcept".repeat(80)}`;
  const cardFront = `PRIVATE_CARD_BODY_${"do-not-render-this-question".repeat(80)}`;
  recaps.push({ ...recaps[0], id: "linked", title: longTitle, jobOpportunityId: "process-one", questions: [{ id: "question-one" }] });
  const opportunities = [{ id: "process-one", company: "Fixture company", targetRole: "Backend intern", platform: "Fixture", status: "SCHEDULED", currentRound: 1,
    rounds: [{ id: "round-one", roundNumber: 1, stage: "TECHNICAL", scheduledAt: "2026-10-20T01:00:00Z", occurredAt: null, durationMinutes: 60, note: null }] }];
  let slowJob = false, jobPolls = 0;
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/interview-recaps/jobs" && slowJob) {
      jobPolls++;
      if (jobPolls === 3) recaps.push({ ...recaps[0], id: "slow", title: "Slow completed notes" });
      return route.fulfill({ json: [{ jobId: "slow-job", status: jobPolls < 3 ? "RUNNING" : "COMPLETED", recapId: jobPolls < 3 ? null : "slow" }] });
    }
    const body = path === "/api/auth/me" ? { id: "pipeline-fixture", email: "pipeline@ardor.invalid", admin: false }
      : path === "/api/auth/csrf" ? { token: "fixture", headerName: "X-CSRF-TOKEN" }
      : path === "/api/profile/sidebar" ? { userId: "pipeline-fixture", hrefs: ["/app"], revision: 0, configured: true }
      : path === "/api/background-tasks/summary" ? { activeCount: 0, unreadByModule: {} }
      : path === "/api/agent/permissions" ? { globalLevel: 0, modules: [] }
      : path === "/api/opportunities" ? opportunities
      : path === "/api/memory-cards" ? [1, 2].map(id => ({ id: `card-${id}`, recapQuestionId: "question-one", front: cardFront, status: "ACTIVE" }))
      : path === "/api/learning-plans" ? [{ id: "plan-one", concept: longConcept, sourceType: "RECAP", sourceId: "question-one", status: "SCHEDULED" }]
      : path === "/api/interview-recaps" ? recaps : [];
    await route.fulfill({ json: body });
  });
  const page = await context.newPage(), errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`${origin}/app/recaps`);
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
  await page.getByRole("link", { name: /Slow completed notes/ }).waitFor({ timeout: 20000 });
  assert.ok(jobPolls >= 3, "Polling must continue when the active job count stays unchanged");
  await page.goto(`${origin}/app/opportunities?selected=process-one`);
  const cardsLink = page.getByRole("link", { name: "Flashcards 2", exact: true });
  await cardsLink.waitFor();
  assert.equal(await page.getByText(cardFront, { exact: true }).count(), 0, "Card body must not render in the interview summary");
  for (const width of [1440, 900, 390]) {
    await page.setViewportSize({ width, height: 900 });
    const geometry = await page.locator("table").evaluate(table => {
      const rect = table.getBoundingClientRect();
      const titles = [...table.querySelectorAll("a span[title]")];
      return { tableWidth: rect.width, containerWidth: table.parentElement.clientWidth,
        documentWidth: document.documentElement.scrollWidth, viewportWidth: window.innerWidth,
        titles: titles.map(span => ({ ellipsis: getComputedStyle(span).textOverflow, clipped: span.scrollWidth > span.clientWidth,
          fits: span.getBoundingClientRect().right <= rect.right + 1 })) };
    });
    assert.ok(geometry.tableWidth <= Math.max(860, geometry.containerWidth) + 2, `Long association expanded table at ${width}px`);
    assert.ok(geometry.documentWidth <= geometry.viewportWidth + 1, `Long content expanded viewport at ${width}px`);
    assert.ok(geometry.titles.length >= 2 && geometry.titles.every(title => title.ellipsis === "ellipsis" && title.clipped && title.fits), `Association titles did not truncate at ${width}px`);
    assert.equal(await cardsLink.getAttribute("href"), "/app/cards");
  }
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.screenshot({ path: ".next/pipeline-compact-associations.png", fullPage: true });
  assert.deepEqual(errors, []);
  console.log("Pipeline UI: notes navigation, polling, compact associations and count-only cards passed at desktop/tablet/mobile widths.");
} finally { await browser?.close(); server.kill(); }
