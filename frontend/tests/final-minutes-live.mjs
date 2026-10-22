import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

assert.equal(process.env.ARDOR_ACCEPTANCE_DISPOSABLE_DB, "true", "Disposable backend required");
const backend = process.env.ARDOR_ACCEPTANCE_API_BASE, model = process.env.ARDOR_ACCEPTANCE_LLM_BASE;
assert.ok(backend && model, "Explicit disposable backend and fake model URLs required");
const origin = "http://127.0.0.1:3115";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3115"], { stdio: "pipe" });
let logs = "", browser, page;
server.stdout.on("data", value => { logs += value; });
server.stderr.on("data", value => { logs += value; });
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  async function req(path, method = "GET", data, expected = 200) {
    const headers = {};
    if (method !== "GET") {
      const csrf = await (await context.request.get(backend + "/api/auth/csrf")).json();
      headers[csrf.headerName] = csrf.token;
    }
    const response = await context.request.fetch(backend + path, { method, headers, data });
    assert.equal(response.status(), expected, `${method} ${path}: ${response.status()}`);
    return expected === 204 || expected === 404 ? null : response.json();
  }
  await req("/api/auth/register", "POST", { email: `final-minutes-${randomUUID()}@ardor.invalid`, password: `Live-${randomUUID()}!` }, 201);
  await req("/api/settings/llm", "PUT", { provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "golden-path-model", apiKey: "fake-only" });
  const interview = await req("/api/opportunities", "POST", { company: "Final minutes fixture" }, 201);
  const updated = await req(`/api/opportunities/${interview.id}/rounds`, "POST", { stage: "TECHNICAL" }, 201);
  const round = updated.rounds[0];
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    // Only translate the isolated test frontend port to the deployed CORS origin.
    // Cookies, CSRF, body and all actual backend responses remain unchanged.
    const response = await route.fetch({ url: backend + url.pathname + url.search,
      headers: { ...route.request().headers(), origin: "http://127.0.0.1:3000" } });
    await route.fulfill({ response });
  });
  page = await context.newPage();
  const errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(origin + "/app/job-search/notes");
  await page.getByRole("button", { name: "Organize a recap", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.locator('select[name="jobOpportunityId"]').selectOption(interview.id);
  await dialog.locator('select[name="interviewRoundId"]').selectOption(round.id);
  const minutes = "Final interview minutes: the interviewer asked about RDB and AOF. The candidate compared recovery loss and latency. The follow-up answer was incomplete.";
  await dialog.getByRole("textbox", { name: "Final interview minutes", exact: true }).fill(minutes);
  await dialog.getByRole("button", { name: "Organize", exact: true }).click();
  await page.waitForURL(url => Boolean(url.searchParams.get("minutes")));
  const id = new URL(page.url()).searchParams.get("minutes");
  assert.equal((await req(`/api/interview-recaps/final-minutes/${id}`)).minutes, minutes);
  await page.waitForURL(url => Boolean(url.searchParams.get("selected")), { timeout: 40000 });
  const finished = await req(`/api/interview-recaps/final-minutes/${id}`);
  assert.equal(finished.status, "COMPLETED");
  assert.equal(finished.minutes, minutes);
  const report = await req(`/api/interview-recaps/${finished.recapId}`);
  assert.equal(report.jobOpportunityId, interview.id);
  assert.equal(report.interviewRoundId, round.id);
  await page.getByRole("heading", { level: 2, name: report.title, exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: /Confirm minutes/ }).count(), 0);
  assert.equal(await page.getByText("Original transcript · read only", { exact: true }).count(), 0);
  await req(`/api/interview-recaps/drafts/${id}`, "GET", undefined, 404);
  const duplicate = await req("/api/interview-recaps/final-minutes", "POST", { content: minutes, jobOpportunityId: interview.id, interviewRoundId: round.id }, 202);
  assert.equal(duplicate.jobId, id);
  assert.equal(duplicate.recapId, finished.recapId);
  assert.equal((await req("/api/interview-recaps")).length, 1);
  await page.reload();
  await page.getByRole("heading", { level: 2, name: report.title, exact: true }).waitFor();
  await page.getByText("Saved final minutes", { exact: true }).click();
  await page.getByText(minutes, { exact: true }).waitFor();
  const tasks = await req("/api/background-tasks?limit=100");
  assert.equal(tasks.find(task => task.kind === "RECAP_ORGANIZE" && task.id === id)?.resultPath, `/app/recaps?minutes=${id}`);
  await req("/api/settings/llm", "PUT", { provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "final-minutes-failure-probe", apiKey: "fake-only" });
  const retryText = minutes + " The following submission tests failure recovery without re-pasting.";
  const failed = await req("/api/interview-recaps/final-minutes", "POST", { content: retryText, jobOpportunityId: interview.id }, 202);
  await page.goto(origin + `/app/recaps?minutes=${failed.jobId}`);
  await page.getByRole("button", { name: "Retry", exact: true }).waitFor({ timeout: 40000 });
  assert.equal((await req(`/api/interview-recaps/final-minutes/${failed.jobId}`)).minutes, retryText);
  await req("/api/settings/llm", "PUT", { provider: "OPENAI_COMPATIBLE", baseUrl: model, model: "golden-path-model", apiKey: "fake-only" });
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await page.waitForURL(url => Boolean(url.searchParams.get("selected")), { timeout: 40000 });
  assert.equal((await req(`/api/interview-recaps/final-minutes/${failed.jobId}`)).minutes, retryText);
  const stranger = await browser.newContext();
  try {
    const csrf = await (await stranger.request.get(backend + "/api/auth/csrf")).json();
    assert.equal((await stranger.request.post(backend + "/api/auth/register", { headers: { [csrf.headerName]: csrf.token }, data: { email: `stranger-${randomUUID()}@ardor.invalid`, password: `Live-${randomUUID()}!` } })).status(), 201);
    assert.equal((await stranger.request.get(backend + `/api/interview-recaps/final-minutes/${id}`)).status(), 404);
    assert.deepEqual(await (await stranger.request.get(backend + "/api/interview-recaps/final-minutes")).json(), []);
  } finally { await stranger.close(); }
  assert.deepEqual(errors, []);
  console.log("PASS: final minutes saved → direct normalization → rendered result; no transcript/draft/confirmation; failure/retry, reload, deduplication, round, task links and owner isolation verified.");
} catch (error) {
  await page?.screenshot({ path: ".next/final-minutes-live-failure.png", fullPage: true }).catch(() => {});
  if (page) console.error("Browser alerts:", await page.getByRole("alert").allTextContents());
  console.error(logs.slice(-1000)); throw error;
} finally { await browser?.close(); server.kill(); }
