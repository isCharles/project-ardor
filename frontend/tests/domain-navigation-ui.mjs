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
