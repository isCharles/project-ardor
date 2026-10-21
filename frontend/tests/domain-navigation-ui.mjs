import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic owned resources only; no production requests or model calls.
const origin = "http://127.0.0.1:3110";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3110"], { stdio: "pipe" });
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
  const owner = "domain-fixture", writes = [], calls = [];
  let preferences = { userId: owner, hrefs: ["/app", "/app/opportunities", "/app/learning", "/app/calendar"],
    destinationIds: ["chat", "job-search", "learning", "calendar"], revision: 0, configured: true };
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    calls.push(path);
    if (path === "/api/profile/sidebar" && route.request().method() === "PUT") {
      const request = route.request().postDataJSON();
      writes.push(request);
      assert.equal(request.expectedUserId, owner);
      assert.equal(request.expectedRevision, preferences.revision);
      assert.equal(request.hrefs, undefined, "New server must use stable IDs, not compatibility URLs");
      preferences = { ...preferences, destinationIds: request.destinationIds, revision: preferences.revision + 1 };
      return route.fulfill({ json: preferences });
    }
    const json = path === "/api/auth/me" ? { id: owner, email: "domain@ardor.invalid", admin: false }
      : path === "/api/auth/csrf" ? { token: "fixture", headerName: "X-CSRF-TOKEN" }
      : path === "/api/profile/sidebar" ? preferences
      : path === "/api/background-tasks/summary" ? { activeCount: 0, unreadByModule: {} }
      : path === "/api/agent/permissions" ? { globalLevel: 0, modules: [] }
      : path === "/api/resumes" ? [{ id: "resume-fixture", originalFilename: "fixture-resume.pdf", parseStatus: "PARSED", createdAt: "2026-10-01T00:00:00Z", analysisId: "analysis-fixture", analysisStatus: "COMPLETED", analysisError: null }]
      : path === "/api/resumes/resume-fixture/analysis" ? { id: "analysis-fixture", resumeId: "resume-fixture", modelName: "fixture", analysis: { overallScore: 82, rubricVersion: "ardor-resume-rubric-v2", criteria: [
        { id: "ownership", level: 2, rationale: "Add your personal contribution and the trade-off behind it." },
        { id: "outcomes", level: 1, rationale: "Add a baseline or an acceptance criterion." },
        { id: "timeline", level: 4, rationale: "The timeline is clear." },
      ] } }
      : path === "/api/opportunities" ? [{ id: "opportunity-fixture", company: "Kuaishou", targetRole: "Agent system engineer", platform: "ViewCoder", status: "AWAITING_RESULT", currentRound: 2, rounds: [{ id: "round-fixture", roundNumber: 1, stage: "TECHNICAL", scheduledAt: null, occurredAt: "2026-10-19T00:00:00Z", durationMinutes: 47, note: null }], createdAt: "2026-10-01T00:00:00Z", updatedAt: "2026-10-19T00:00:00Z" }]
      : path === "/api/applications/rhythm" ? { today: "2026-10-20", weekStart: "2026-10-19", todayCount: 2, weeklyCount: 5, weeklyGoal: 10, reminderEnabled: false, reminderTime: "19:00", weekdaysOnly: true, reminderDue: false, days: [{ date: "2026-10-19", count: 1 }, { date: "2026-10-20", count: 2 }] }
      : path === "/api/interview-replays/questions/question-fixture" ? { questionId: "question-fixture", recapId: "report-fixture", recapTitle: "Confirmed notes", questionText: "Fixture practice question", attempts: [] }
      : path === "/api/interview-replays/questions/question-fixture/retest" ? { scheduled: null }
      : path === "/api/interview-recaps/drafts/missing-fixture" ? { id: "missing-fixture", rawContent: "Synthetic preserved transcript", minutes: "## Synthetic minutes\nPlease verify these facts before generating the review.", revision: 1, status: "COMPLETED", reviews: [] }
      : [];
    return route.fulfill({ json });
  });
  const page = await context.newPage(), errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`${origin}/app/job-search`);
  await page.getByRole("heading", { name: "Job search", exact: true }).waitFor();
  await page.screenshot({ path: ".next/domain-job-search.png", fullPage: true });
  const navigation = page.getByRole("navigation", { name: "Section navigation" });
  await navigation.waitFor();
  await page.getByText("Latest resume score", { exact: true }).waitFor();
  assert.ok(await page.getByText("82", { exact: true }).isVisible(), "The overview must show the latest resume score");
  assert.ok(await page.getByText("fixture-resume.pdf", { exact: true }).isVisible(), "The overview must name the scored resume");
  assert.ok(await page.getByText("Kuaishou", { exact: true }).isVisible(), "The overview must show the latest interview company");
  assert.ok(await page.getByText("Round 2", { exact: true }).isVisible(), "The overview must show the current interview round");
  assert.ok(await page.getByText("Application rhythm", { exact: true }).isVisible(), "The overview must preview the application rhythm");
  const placement = await page.evaluate(() => {
    const nav = document.querySelector('nav[aria-label="Section navigation"]');
    const main = nav?.closest("main");
    if (!nav || !main) return null;
    const header = main.querySelector("header");
    return {
      insideMain: main.contains(nav),
      afterHeader: header ? !!(header.compareDocumentPosition(nav) & Node.DOCUMENT_POSITION_FOLLOWING) : true,
      position: nav.parentElement ? getComputedStyle(nav.parentElement).position : "",
    };
  });
  assert.ok(placement?.insideMain, "Section navigation must be rendered inside the page main");
  assert.ok(placement?.afterHeader, "Section navigation must follow the page header");
  assert.ok(placement?.position !== "sticky" && placement?.position !== "fixed", "Section navigation must scroll with the page");
  await navigation.getByRole("link", { name: "Interviews", exact: true }).click();
  await page.waitForURL("**/app/job-search/interviews");
  assert.equal(await navigation.getByRole("link", { name: "Interviews", exact: true }).getAttribute("aria-current"), "page");
  await page.goto(`${origin}/app/opportunities?selected=fixture`);
  assert.equal(await navigation.getByRole("link", { name: "Interviews", exact: true }).getAttribute("aria-current"), "page");
  assert.ok(page.url().endsWith("?selected=fixture"));
  for (const path of ["/app/interviews", "/app/learning/practice/mock-interviews"]) {
    await page.goto(`${origin}${path}`);
    assert.equal(await navigation.getByRole("link", { name: "Practice", exact: true }).getAttribute("aria-current"), "page");
    assert.equal(await navigation.getByRole("link", { name: "Interviews", exact: true }).count(), 0);
  }
  for (const path of ["/app/replay", "/app/learning/practice/questions"]) {
    await page.goto(`${origin}${path}?question=question-fixture&retest=retest-fixture`);
    await page.getByText("Fixture practice question", { exact: true }).waitFor();
    assert.ok(page.url().includes("retest=retest-fixture"));
    assert.equal(await navigation.getByRole("link", { name: "Practice", exact: true }).getAttribute("aria-current"), "page");
  }
  await page.goto(`${origin}/app/job-search/notes?draft=missing-fixture`);
  await page.getByRole("textbox").first().waitFor();
  assert.equal(await navigation.getByRole("link", { name: "Interviews", exact: true }).getAttribute("aria-current"), "page");
  await page.getByRole("button", { name: "More workspaces", exact: true }).click();
  const morePanel = page.locator("#ardor-more-workspaces");
  const groupLabels = (await morePanel.locator("p").allTextContents()).map(label => label.trim()).filter(Boolean);
  for (const label of ["Job search", "Learning", "Workspace"]) {
    assert.ok(groupLabels.filter(item => item === label).length <= 1, `${label} group header must appear once: ${groupLabels.join(", ")}`);
  }
  const fitViewport = async (label) => {
    const viewport = page.viewportSize(), box = await morePanel.boundingBox();
    assert.ok(box && viewport && box.y >= 0 && box.y + box.height <= viewport.height + 1,
      `${label}: More workspaces panel must stay inside the viewport: ${JSON.stringify(box)} vs ${JSON.stringify(viewport)}`);
    return viewport;
  };
  await fitViewport("default window");
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 1280, height: 560 });
  await page.getByRole("button", { name: "More workspaces", exact: true }).click();
  const shortViewport = await fitViewport("short window");
  const shortMetrics = await morePanel.evaluate(element => ({ scrollHeight: element.scrollHeight, clientHeight: element.clientHeight, overflowY: getComputedStyle(element).overflowY }));
  assert.ok(shortMetrics.scrollHeight > shortMetrics.clientHeight, "A short window must make the More panel scrollable");
  assert.notEqual(shortMetrics.overflowY, "visible", "A clipped More panel must scroll instead of overflowing the window");
  await morePanel.evaluate(element => { element.scrollTop = element.scrollHeight; });
  const customizeAtBottom = await page.getByRole("button", { name: "Customize sidebar", exact: true }).boundingBox();
  assert.ok(customizeAtBottom && customizeAtBottom.y + customizeAtBottom.height <= shortViewport.height + 1, "Customize entry must be reachable inside a short window after scrolling");
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("button", { name: "More workspaces", exact: true }).click();
  await page.getByRole("button", { name: "Customize sidebar", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("checkbox", { name: "Flashcards", exact: true }).check();
  await dialog.getByRole("button", { name: "Save", exact: true }).click();
  await dialog.waitFor({ state: "hidden" });
  assert.equal(writes.length, 1);
  assert.ok(writes[0].destinationIds.includes("learning.flashcards"));
  await page.reload();
  await page.getByRole("button", { name: "More workspaces", exact: true }).click();
  await page.getByRole("button", { name: "Customize sidebar", exact: true }).click();
  await dialog.getByRole("checkbox", { name: "Flashcards", exact: true }).waitFor();
  assert.equal(await dialog.getByRole("checkbox", { name: "Flashcards", exact: true }).isChecked(), true);
  assert.ok(calls.includes("/api/interview-replays/questions/question-fixture"));
  assert.deepEqual(errors, []);
  console.log("Domain UI: canonical/legacy destinations, real/mock separation, query preservation and stable account preferences passed.");
} finally { await browser?.close(); server.kill(); }
