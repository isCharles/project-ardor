import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic preferences, runs and postings only; no real search, account or model.
const origin = "http://127.0.0.1:3131";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3131"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk);
server.stderr.on("data", chunk => logs += chunk);

const now = new Date().toISOString();
const preferences = {
  roles: ["Backend Engineer"], locations: ["Beijing"], keywords: [],
  suggestedRoles: ["Backend Engineer", "Platform Engineer"],
};
const posting = overrides => ({
  id: "posting-acme", state: "NEW", company: "Acme", title: "Backend Engineer", location: "Beijing",
  applyUrl: "https://boards.greenhouse.io/acme/jobs/123", source: "greenhouse.io",
  salaryText: "30k–45k CNY", experienceText: "3+ years", deadlineText: "Nov 30, 2026",
  linkStatus: "OPEN", firstSeenAt: now, lastVerifiedAt: now, discoveredAt: now, lastSeenAt: now,
  appliedAt: null, matchReason: "Role matches Backend Engineer",
  matchEvidence: "Title: Backend Engineer · Location: Beijing", discoveryQuery: "Backend Engineer Beijing",
  sources: [
    { source: "greenhouse.io", url: "https://boards.greenhouse.io/acme/jobs/123", discoveredAt: now },
    { source: "example-jobs.com", url: "https://example-jobs.com/acme/backend?utm_source=feed", discoveredAt: now },
  ],
  ...overrides,
});

let postings = [];
let activeRunId = null;
const summary = () => ({
  inbox: postings.filter(item => item.state === "NEW").length,
  saved: postings.filter(item => item.state === "SAVED").length,
  ignored: postings.filter(item => item.state === "IGNORED").length,
  activeRunId,
});
const makeRun = (id, overrides = {}) => ({
  id, jobId: `job-${id}`, status: "QUEUED", roles: ["Backend Engineer"], locations: ["Beijing"], keywords: [],
  queries: ["Backend Engineer Beijing", "Backend Engineer"], queriesDone: 0,
  candidateCount: 0, newCount: 0, updatedCount: 0, skippedCount: 0, errorCount: 0, errors: [],
  cancelRequested: false, createdAt: now, startedAt: now, finishedAt: null,
  resultPath: `/app/job-search/discovery?run=${id}`, ...overrides,
});

const writes = [];
const moduleReads = [];
const runStarts = [];
const appliedBodies = [];
let appliedCalls = 0;
let runPolls = 0;
let runSeq = 0;
let canceled = false;
let failNextCompletedInbox = false;
let failInboxLoads = 0;

async function waitForNode(predicate, message, timeout = 5000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline && !predicate()) {
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(predicate(), message);
}

try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1360, height: 1100 }, reducedMotion: "reduce" });
  await context.addInitScript(() => localStorage.setItem("ardor:locale", "en"));
  await context.route("**/api/**", route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    const method = request.method();
    if (method !== "GET") writes.push(`${method} ${path}`);
    if (path === "/api/auth/me") return route.fulfill({ json: { id: "discovery-owner", email: "owner@ardor.invalid", admin: false } });
    if (path === "/api/auth/csrf") return route.fulfill({ json: { token: "fixture", headerName: "X-CSRF-TOKEN" } });
    if (path === "/api/profile/sidebar") return route.fulfill({ json: { userId: "discovery-owner", hrefs: ["/app"], configured: true, revision: 0 } });
    if (path === "/api/profile") return route.fulfill({ json: { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] } });
    if (path === "/api/background-tasks/summary") return route.fulfill({ json: { activeCount: 0, unreadByModule: {} } });
    if (path === "/api/background-tasks/modules/applications/read" && method === "POST") {
      moduleReads.push("applications");
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/background-tasks" && method === "GET") {
      return route.fulfill({ json: [{
        id: "discovery-task", kind: "JOB_DISCOVERY", module: "applications", title: "Job discovery",
        status: "COMPLETED", attempts: 1, createdAt: now, startedAt: now, finishedAt: now,
        errorCode: null, resultPath: "/app/job-search/discovery?run=run-1", resourceId: "run-1",
        completionToken: "token", unread: true, estimateMinSeconds: null, estimateMaxSeconds: null,
      }] });
    }
    if (path === "/api/resumes" || path === "/api/opportunities") return route.fulfill({ json: [] });
    if (path === "/api/job-discovery/preferences") return route.fulfill({ json: preferences });
    if (path === "/api/job-discovery/summary") return route.fulfill({ json: summary() });
    if (path === "/api/job-discovery/postings" && method === "GET") {
      if (failInboxLoads > 0) {
        failInboxLoads -= 1;
        return route.fulfill({ status: 503, json: { message: "Inbox refresh failed" } });
      }
      const state = url.searchParams.get("state");
      const text = (url.searchParams.get("q") ?? "").toLowerCase();
      const offset = Number(url.searchParams.get("offset") ?? 0);
      const list = postings
        .filter(item => item.state === state)
        .filter(item => !text || `${item.company} ${item.title} ${item.location ?? ""}`.toLowerCase().includes(text))
        .slice(offset, offset + 20);
      return route.fulfill({ json: list });
    }
    const statePath = path.match(/^\/api\/job-discovery\/postings\/([^/]+)$/);
    if (statePath && method === "PATCH") {
      const body = request.postDataJSON();
      postings = postings.map(item => item.id === statePath[1] ? { ...item, state: body.state } : item);
      return route.fulfill({ json: postings.find(item => item.id === statePath[1]) });
    }
    const appliedPath = path.match(/^\/api\/job-discovery\/postings\/([^/]+)\/applied$/);
    if (appliedPath && method === "POST") {
      appliedCalls += 1;
      appliedBodies.push(request.postDataJSON());
      postings = postings.map(item => item.id === appliedPath[1] ? { ...item, appliedAt: now } : item);
      return route.fulfill({ json: postings.find(item => item.id === appliedPath[1]) });
    }
    if (path === "/api/job-discovery/runs" && method === "POST") {
      const body = request.postDataJSON();
      runStarts.push(body);
      runSeq += 1;
      runPolls = 0;
      canceled = false;
      activeRunId = `run-${runSeq}`;
      return route.fulfill({ status: 202, json: makeRun(activeRunId, { roles: body.roles, locations: body.locations, keywords: body.keywords }) });
    }
    const retryPath = path.match(/^\/api\/job-discovery\/runs\/([^/]+)\/retry$/);
    if (retryPath && method === "POST") {
      runSeq += 1;
      runPolls = 0;
      canceled = false;
      activeRunId = `run-${runSeq}`;
      return route.fulfill({ status: 202, json: makeRun(activeRunId) });
    }
    const cancelPath = path.match(/^\/api\/job-discovery\/runs\/([^/]+)\/cancel$/);
    if (cancelPath && method === "POST") {
      canceled = true;
      activeRunId = null;
      return route.fulfill({ json: makeRun(cancelPath[1], { status: "CANCELED", cancelRequested: true, finishedAt: now }) });
    }
    const runPath = path.match(/^\/api\/job-discovery\/runs\/([^/]+)$/);
    if (runPath && method === "GET") {
      runPolls += 1;
      if (runPath[1] === "run-1") {
        if (runPolls === 1) return route.fulfill({ json: makeRun("run-1", { status: "RUNNING", queriesDone: 1, candidateCount: 1 }) });
        if (postings.length === 0) {
          postings = [
            posting({}),
            posting({
              id: "posting-beta", company: "Beta", title: "Data Analyst", location: null,
              applyUrl: "https://jobs.example.com/beta/42", source: "jobs.example.com",
              salaryText: null, experienceText: null, deadlineText: null, linkStatus: "RESTRICTED",
              matchReason: "Keyword match: Java", matchEvidence: "Title: Data Analyst · Keyword: Java",
              discoveryQuery: "Backend Engineer", sources: [
                { source: "jobs.example.com", url: "https://jobs.example.com/beta/42", discoveredAt: now },
              ],
            }),
          ];
        }
        activeRunId = null;
        return route.fulfill({ json: makeRun("run-1", {
          status: "PARTIAL", queriesDone: 2, candidateCount: 3, newCount: 2, updatedCount: 0, skippedCount: 1,
          errorCount: 1, finishedAt: now,
          errors: [{ query: "Backend Engineer", url: null, code: "SEARCH_FAILED", message: "Search provider timed out" }],
        }) });
      }
      if (canceled) return route.fulfill({ json: makeRun(runPath[1], { status: "CANCELED", cancelRequested: true, finishedAt: now }) });
      if (runPolls === 1) return route.fulfill({ json: makeRun(runPath[1], { status: "RUNNING" }) });
      if (failNextCompletedInbox) {
        failNextCompletedInbox = false;
        postings = [posting({ id: "posting-deferred", company: "Deferred", title: "Deferred result" })];
        failInboxLoads += 1;
      }
      activeRunId = null;
      return route.fulfill({ json: makeRun(runPath[1], { status: "COMPLETED", queriesDone: 2, finishedAt: now }) });
    }
    return route.fulfill({ status: 404, json: { message: `Unmatched ${method} ${path}` } });
  });

  const page = await context.newPage();
  const inboxAcknowledged = page.waitForResponse(response =>
    response.request().method() === "POST" && new URL(response.url()).pathname === "/api/background-tasks/modules/applications/read");
  await page.goto(`${origin}/app/job-search/discovery`);
  await page.getByRole("heading", { name: "Job inbox" }).waitFor();
  await page.getByText("No jobs in the inbox yet").waitFor();
  await inboxAcknowledged;
  assert.ok(moduleReads.length >= 1, "Opening the discovered inbox acknowledges its finished background tasks");

  // Criteria start from preferences; suggested roles are one click away.
  const roles = page.getByLabel("Target roles");
  await roles.waitFor();
  assert.equal(await roles.inputValue(), "Backend Engineer");
  assert.equal(await page.getByLabel("Locations (optional)").inputValue(), "Beijing");
  await page.getByRole("button", { name: "+ Platform Engineer" }).click();
  assert.equal(await roles.inputValue(), "Backend Engineer, Platform Engineer");

  // Start a persistent search with a client request id for idempotency.
  await page.getByRole("button", { name: "Find jobs" }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-discovery-run]").length === 1);
  assert.equal(runStarts.length, 1);
  assert.match(runStarts[0].requestId, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  assert.deepEqual(runStarts[0].roles, ["Backend Engineer", "Platform Engineer"]);
  assert.deepEqual(runStarts[0].locations, ["Beijing"]);
  const runPanel = page.locator("[data-discovery-run]");
  await runPanel.getByText("Searching", { exact: true }).waitFor({ timeout: 10000 });
  await runPanel.getByText(/1\/2 searches/).waitFor();

  // Partial failure keeps successful results and names the failed query.
  await runPanel.getByText("Partially completed", { exact: true }).waitFor({ timeout: 10000 });
  await runPanel.getByText(/Search provider timed out/).waitFor();
  await runPanel.getByText("Some sources failed; the results below are kept. Retrying will not duplicate them.").waitFor();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 2);

  const acme = page.locator("[data-job-posting]").filter({ hasText: "Backend Engineer" });
  const beta = page.locator("[data-job-posting]").filter({ hasText: "Data Analyst" });
  await acme.getByText("Acme", { exact: true }).waitFor();
  await acme.getByText("Beijing", { exact: true }).waitFor();
  await acme.getByText("Link reachable", { exact: true }).waitFor();
  await acme.getByText("Match · Role matches Backend Engineer", { exact: true }).waitFor();
  await acme.getByText("Match evidence", { exact: true }).click();
  await acme.getByText("Title: Backend Engineer · Location: Beijing", { exact: true }).waitFor();
  assert.match(await acme.innerText(), /Source greenhouse\.io \+1/);
  assert.match(await acme.innerText(), /Found/);
  assert.match(await acme.innerText(), /Verified/);
  await acme.getByText("Salary 30k–45k CNY", { exact: true }).waitFor();
  await acme.getByText("Experience 3+ years", { exact: true }).waitFor();
  await acme.getByText("Deadline Nov 30, 2026", { exact: true }).waitFor();
  const applyLink = acme.getByRole("link", { name: "Open application for Backend Engineer" });
  assert.equal(await applyLink.getAttribute("target"), "_blank");
  assert.match(await applyLink.getAttribute("rel"), /noopener/);
  assert.match(await applyLink.getAttribute("rel"), /noreferrer/);
  assert.equal(await applyLink.getAttribute("href"), "https://boards.greenhouse.io/acme/jobs/123");

  await beta.getByText("Location not provided", { exact: true }).waitFor();
  await beta.getByText("Login or verification required", { exact: true }).waitFor();
  await beta.getByText("Salary, experience and deadline are not stated on the page.", { exact: true }).waitFor();
  await page.screenshot({ path: ".next/job-discovery-partial.png", fullPage: true });

  // Save and ignore only move user state; they never record an application.
  await acme.getByRole("button", { name: "Save", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 1);
  assert.equal(appliedCalls, 0);
  await page.getByRole("tab", { name: /^Saved/ }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 1);
  const savedAcme = page.locator("[data-job-posting]").filter({ hasText: "Backend Engineer" });
  await savedAcme.getByRole("button", { name: "Back to inbox", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 0);
  await page.getByRole("tab", { name: /^Inbox/ }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 2);

  await beta.getByRole("button", { name: "Ignore", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 1);
  await page.getByRole("tab", { name: /^Ignored/ }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 1);
  await page.locator("[data-job-posting]").getByRole("button", { name: "Move back to inbox", exact: true }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 0);
  await page.getByRole("tab", { name: /^Inbox/ }).click();
  await page.waitForFunction(() => document.querySelectorAll("[data-job-posting]").length === 2);
  assert.equal(appliedCalls, 0, "Saving, ignoring and browsing never count as an application");
  assert.equal(writes.filter(entry => entry.includes("/api/opportunities")).length, 0);
  assert.equal(writes.filter(entry => entry.includes("/api/applications")).length, 0);

  // Only an explicit confirmation records a sent resume, exactly once.
  await acme.getByRole("button", { name: "I sent my resume", exact: true }).click();
  await acme.getByText("Has your resume actually been sent?", { exact: true }).waitFor();
  assert.equal(appliedCalls, 0, "Opening the confirmation must not write anything");
  await acme.getByRole("button", { name: "Yes, record it", exact: true }).click();
  await page.getByText("Recorded as sent. This counts once toward today's resume sends.").waitFor();
  await acme.getByText("Marked as sent", { exact: true }).waitFor();
  assert.equal(appliedCalls, 1);
  assert.deepEqual(appliedBodies, [{ confirm: true }]);
  assert.equal(await acme.getByRole("button", { name: "I sent my resume", exact: true }).count(), 0);
  assert.equal(await acme.getByText("Recorded as sent, counted once in resume sends.", { exact: true }).count(), 1);

  // Retry starts a fresh run; cancel stops it.
  await runPanel.getByRole("button", { name: "Retry", exact: true }).click();
  await runPanel.getByText("Queued", { exact: true }).waitFor();
  await runPanel.getByRole("button", { name: "Cancel", exact: true }).click();
  await runPanel.getByText("Canceled", { exact: true }).waitFor();
  assert.equal(writes.filter(entry => entry.startsWith("POST /api/job-discovery/runs/run-1/retry")).length, 1);
  assert.equal(writes.filter(entry => entry.endsWith("/cancel")).length, 1);

  // Zero results have their own readable state.
  postings = [];
  await page.getByRole("button", { name: "Find jobs" }).click();
  await runPanel.getByText("Completed", { exact: true }).waitFor({ timeout: 10000 });
  await runPanel.getByText("No matching postings this time. Try fewer locations or a broader role name.").waitFor();
  await page.getByText("No jobs in the inbox yet").waitFor();
  assert.equal(runStarts.length, 2);
  assert.equal(appliedCalls, 1, "Discovery writes stay limited to explicit user actions");
  await page.screenshot({ path: ".next/job-discovery-inbox.png", fullPage: true });

  // A completion whose refreshed inbox fails must keep the finished results unread.
  await waitForNode(() => moduleReads.length >= 3, "Each successful inbox load acknowledges once");
  await page.waitForTimeout(400);
  const readsBeforeFailure = moduleReads.length;
  failNextCompletedInbox = true;
  await page.getByRole("button", { name: "Find jobs" }).click();
  await page.getByText("Inbox refresh failed").waitFor({ timeout: 10000 });
  await page.waitForTimeout(600);
  assert.equal(moduleReads.length, readsBeforeFailure,
    "A failed refresh after completion leaves the notification unread");
  assert.equal(await page.getByText("Deferred result", { exact: true }).count(), 0,
    "The stale inbox stays on screen until a refresh succeeds");

  // A later successful completion clears it.
  await page.getByRole("button", { name: "Find jobs" }).click();
  await waitForNode(() => moduleReads.length > readsBeforeFailure,
    "A successful refresh after completion acknowledges the results");
  await page.getByText("Deferred result", { exact: true }).waitFor();
  assert.equal(runStarts.length, 4);

  // The job-search home previews the inbox and links into the same page.
  postings = [posting({})];
  await page.goto(`${origin}/app/job-search`);
  await page.getByRole("heading", { name: "Job search" }).waitFor();
  const inboxCard = page.getByRole("link", { name: "Review discovered jobs" });
  await inboxCard.getByText("Job inbox", { exact: true }).waitFor();
  assert.match(await inboxCard.innerText(), /1\s*to review/);
  await Promise.all([page.waitForURL(/\/app\/job-search\/discovery$/), inboxCard.click()]);
  await page.getByRole("heading", { name: "Job inbox", level: 1 }).waitFor();

  // Background task rows use the product label rather than the raw job kind.
  await page.goto(`${origin}/app/tasks`);
  await page.getByRole("heading", { name: "Job discovery", exact: true }).waitFor();
  assert.equal(await page.getByText("JOB_DISCOVERY", { exact: true }).count(), 0);

  console.log("Job discovery UI: inbox notification acknowledgment, hub entry, preferences, partial failure, state moves, apply confirmation once, retry/cancel and zero results passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
