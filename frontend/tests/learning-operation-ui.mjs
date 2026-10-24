import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Disposable API fixtures only: never reads or mutates a real user's data.
const origin = "http://127.0.0.1:3104";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3104"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", c => { logs += c; }); server.stderr.on("data", c => { logs += c; });
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
  let requestId = "learning-request-one", submissions = 0, failSubmission = false, job = null;
  const resolutions = {};
  const conversation = { id: "learning-chat", title: "Synthetic learning proposal", createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "learning-ui-owner", email: "learning-ui@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "learning-ui-owner", hrefs: ["/app"], revision: 0, configured: true };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/opportunities/proposal-target") body = { id: "proposal-target", company: "Preparation employer", targetRole: "Backend", rounds: [{ id: "proposal-round", roundNumber: 2 }] };
    else if (path === "/api/agent") body = { conversationId: conversation.id, llmConfigured: true, memory: { content: "" }, conversations: [conversation], archivedConversations: [], messages: [
      { id: "proposal-user", role: "USER", conversationId: conversation.id, content: "Prepare a JVM lesson", createdAt: conversation.createdAt },
      { id: "proposal-answer", role: "ASSISTANT", conversationId: conversation.id, content: "Synthetic learning proposal answer", createdAt: conversation.createdAt, proposalResolutions: resolutions,
        runTrace: JSON.stringify({ status: "COMPLETED", elapsedMs: 100, steps: [], proposals: [{ kind: "learning_plan", label: "JVM memory", detail: "Review memory regions and practice explaining GC.", endpoint: "/api/learning-plans/jobs", learningPlan: { requestId, concept: "JVM memory", reason: "Synthetic practice gap", sourceType: "RECAP", sourceId: "proposal-question", scheduledAt: "2099-10-19T01:00:00Z", originKind: "INTERVIEW_QUESTION", originResourceId: "proposal-question", targetInterviewId: "proposal-target", targetRoundId: "proposal-round" } }] }) },
    ] };
    else if (path.endsWith("/proposal-resolution") && route.request().method() === "PUT") {
      const decision = route.request().postDataJSON();
      resolutions[decision.key] ??= decision.resolution;
      return route.fulfill({ status: 200 });
    }
    else if (path === "/api/agent/runs/latest") return route.fulfill({ status: 204 });
    else if (path === "/api/learning-plans/request-status") body = [{ requestId, status: job?.status === "COMPLETED" ? "CREATED" : "MISSING", planId: job?.status === "COMPLETED" ? "created-fixture" : null }];
    else if (path === "/api/learning-plans/jobs" && route.request().method() === "POST") {
      submissions++; assert.equal(route.request().postDataJSON().requestId, requestId);
      assert.equal(route.request().postDataJSON().originKind, "INTERVIEW_QUESTION");
      assert.equal(route.request().postDataJSON().originResourceId, "proposal-question");
      assert.equal(route.request().postDataJSON().targetInterviewId, "proposal-target");
      assert.equal(route.request().postDataJSON().targetRoundId, "proposal-round");
      if (failSubmission) return route.fulfill({ status: 503, json: { message: "Synthetic creation failure" } });
      job = { id: requestId, status: "RUNNING", resultPath: null }; body = job;
    }
    else if (path === "/api/ai-jobs") body = job ? [job] : [];
    await route.fulfill({ json: body });
  });
  const empty = await context.newPage();
  await empty.route("**/api/agent**", route => new URL(route.request().url()).pathname === "/api/agent"
    ? route.fulfill({ json: { conversationId: null, llmConfigured: true, memory: { content: "" }, conversations: [], archivedConversations: [], messages: [] } })
    : route.fallback());
  await empty.goto(`${origin}/app`);
  await empty.getByRole("heading", { name: "What would you like to work on today?", exact: true }).waitFor();
  assert.equal(await empty.getByRole("heading", { name: /What should we build/ }).count(), 0);
  await empty.close();
  const page = await context.newPage(), errors = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.goto(`${origin}/app`);
  const dock = page.getByRole("region", { name: "Learning creation confirmation", exact: true });
  await dock.getByRole("heading", { name: "Create a learning plan for “JVM memory”?", exact: true }).waitFor();
  await dock.getByRole("link", { name: "Preparation target", exact: true }).getByText(/Preparation employer/).waitFor();
  assert.equal(submissions, 0);
  await page.locator(".ardor-composer-enter").evaluateAll(elements => Promise.all(elements.flatMap(element => element.getAnimations()).map(animation => animation.finished.catch(() => undefined))));
  const bounds = await dock.boundingBox(), composer = await page.getByRole("textbox", { name: "Message Ardor", exact: true }).boundingBox();
  assert.ok(bounds.y + bounds.height <= composer.y, "Suggestions sit above the composer");
  await page.screenshot({ path: ".next/learning-operation.png", fullPage: true });
  const savedRejection = page.waitForResponse(response => response.url().endsWith("/proposal-resolution") && response.request().method() === "PUT");
  await dock.getByRole("button", { name: "Reject", exact: true }).click();
  await savedRejection;
  await dock.waitFor({ state: "hidden" }); assert.equal(submissions, 0);
  const trace = page.locator("[data-execution-trace] > div > details");
  await trace.locator(":scope > summary").click();
  await trace.getByText("Creation declined · JVM memory", { exact: true }).waitFor();
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.reload(); await dock.waitFor({ state: "hidden" }); assert.equal(submissions, 0);
  requestId = "learning-request-two"; await page.reload();
  await dock.waitFor(); failSubmission = true;
  await dock.getByRole("button", { name: "Confirm creation", exact: true }).click();
  await dock.getByRole("alert").getByText("Synthetic creation failure", { exact: true }).waitFor();
  failSubmission = false;
  await dock.getByRole("button", { name: "Confirm creation", exact: true }).click();
  await dock.waitFor({ state: "hidden" }); assert.equal(submissions, 2);
  await trace.locator(":scope > summary").click();
  await trace.getByText("Preparing lesson in background · JVM memory", { exact: true }).waitFor();
  job = { ...job, status: "COMPLETED", resultPath: "/app/learning?id=created-fixture" };
  await trace.getByRole("link", { name: "Open lesson", exact: true }).waitFor({ timeout: 10000 });
  await page.getByRole("status").filter({ hasText: "Learning plan created:" }).waitFor();
  await page.waitForTimeout(5500);
  assert.equal(await page.getByRole("status").filter({ hasText: "Learning plan created:" }).count(), 0);
  await page.reload(); await trace.locator(":scope > summary").click();
  await trace.getByText("Learning plan created · JVM memory", { exact: true }).waitFor();
  await trace.getByRole("link", { name: "Open lesson", exact: true }).waitFor();
  assert.equal(await dock.count(), 0, "Completed proposals stay in the original trace");
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "zh-CN"); window.dispatchEvent(new Event("ardor:locale-change")); });
  await trace.getByText("学习计划已创建 · JVM memory", { exact: true }).waitFor();
  assert.deepEqual(errors, []);
  console.log("Learning operation UI: dock, consent, rejection/reload, retry, background completion, transient feedback and durable trace passed.");
} finally { await browser?.close(); server.kill(); }
