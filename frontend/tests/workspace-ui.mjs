import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Mock every API: this regression suite never changes real accounts or calls models.
const origin = "http://127.0.0.1:3101";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3101"], { stdio: "pipe" });
let logs = "";
server.stdout.on("data", (chunk) => { logs += chunk; });
server.stderr.on("data", (chunk) => { logs += chunk; });
let browser;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  let userId = "workspace-owner";
  let admin = false;
  let taskRefreshFails = false;
  let displayedMessages = [];
  let sentMessage = null;
  let releaseStream;
  let activeRun = null;
  let permissions = [];
  let permissionDecisions = [];
  let deletionCalls = 0;
  let deletionFails = false;
  const steerRequests = [];
  let queueItems = [];
  let queuedRequests = [];
  let cancelCalls = 0;
  let calendarWrites = 0;
  let retryProbe = false;
  let retryProbeCalls = 0;
  let learningPlans = [];
  let learningDeleteCalls = 0;
  let learningDeleteFails = false;
  let failRename = true;
  let renameCalls = 0;
  let conversation = { id: "chat-one", title: "Original chat", pinned: false, titleManuallySet: false, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
  await context.addInitScript(() => { window.prompt = () => { throw new Error("Native prompt must not be used"); }; });
  await context.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    let body = {};
    if (path === "/api/auth/me") body = { id: userId, email: "fixture@ardor.invalid", admin };
    else if (path === "/api/agent/features") body = [
      { key: "LEARNING", id: "f1111111-1111-3111-8111-111111111111", label: "学习", englishLabel: "Learning", description: "学习概念", englishDescription: "Learn concepts and practice" },
      { key: "CARDS", id: "f2222222-2222-3222-8222-222222222222", label: "记忆卡", englishLabel: "Flashcards", description: "准备记忆卡" },
      { key: "RECAPS", id: "f3333333-3333-3333-8333-333333333333", label: "面经", englishLabel: "Interview notes", description: "整理面试内容" },
      { key: "CALENDAR", id: "f4444444-4444-3444-8444-444444444444", label: "日程", englishLabel: "Calendar", description: "管理日程" },
    ];
    else if (path === "/api/auth/csrf") body = { token: "test", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/calendar/tasks" || path === "/api/calendar/series") {
      if (route.request().method() !== "GET") calendarWrites++;
      body = path.endsWith("/series") ? [{ id: "series-fixture", title: "Weekly sync", summary: "Every Thursday 07:00", status: "ACTIVE", upcoming: [] }] : [];
    }
    else if (path === "/api/agent/messages/stream") {
      if (retryProbe) {
        retryProbeCalls++;
        return route.fulfill({ status: 503, json: { message: "Synthetic retryable failure", retryable: true } });
      }
      sentMessage = route.request().postDataJSON();
      await new Promise((resolve) => { releaseStream = resolve; });
      displayedMessages = [
        { id: "sent-user", conversationId: conversation.id, role: "USER", content: sentMessage.message, contextReferences: sentMessage.contextReferences.map((reference) => ({ ...reference, label: "学习" })), createdAt: new Date().toISOString() },
        { id: "sent-assistant", conversationId: conversation.id, role: "ASSISTANT", content: "Synthetic agent reply", runTrace: JSON.stringify({ elapsedMs: 42000, status: "COMPLETED", steps: [{ label: "Read plans", elapsedMs: 40003, approvalWaitMs: 40000, businessMs: 3, status: "COMPLETED" }, { label: "Denied tool", elapsedMs: 1200, approvalWaitMs: 1200, businessMs: 0, status: "NOT_EXECUTED" }], thinkingOutput: "Synthetic public reasoning", firstOutputMs: 80 }), createdAt: new Date().toISOString() },
      ];
      return route.fulfill({ contentType: "text/event-stream", body: `data: ${JSON.stringify({ type: "thinking", content: "Synthetic public reasoning" })}\n\ndata: ${JSON.stringify({ type: "done", conversationId: conversation.id, messageId: "sent-assistant", content: "Synthetic agent reply", elapsedMs: 100 })}\n\n` });
    }
    else if (path === "/api/agent") body = { conversationId: conversation.id, llmConfigured: true, displayName: "Fixture", conversations: [conversation], archivedConversations: [], messages: displayedMessages, memory: { content: "", updatedAt: null } };
    else if (path === "/api/agent/conversations/chat-one") {
      renameCalls++;
      if (failRename) return route.fulfill({ status: 503, json: { message: "Synthetic save failure" } });
      conversation = { ...conversation, ...route.request().postDataJSON(), titleManuallySet: true }; body = conversation;
    }
    else if (path === "/api/agent/conversations") body = [conversation];
    else if (path === "/api/agent/queue" && route.request().method() === "POST") {
      const request = route.request().postDataJSON();
      queuedRequests.push(request);
      const item = { id: request.requestId, owner: userId, request, status: "QUEUED", blockedBy: activeRun?.id ?? null };
      queueItems.push(item);
      return route.fulfill({ status: 202, json: item });
    }
    else if (path === "/api/agent/queue") body = queueItems.filter((item) => item.owner === userId);
    else if (path.startsWith("/api/agent/queue/") && path.endsWith("/steer") && route.request().method() === "POST") {
      steerRequests.push({ path, ...route.request().postDataJSON() });
      return route.fulfill({ status: 204 });
    }
    else if (path.startsWith("/api/agent/queue/") && route.request().method() === "DELETE") {
      queueItems = queueItems.filter((item) => !path.endsWith(item.id));
      return route.fulfill({ status: 204 });
    }
    else if (["/api/agent/runs/accepted-run/cancel", "/api/agent/runs/queued-active-run/cancel"].includes(path) && route.request().method() === "POST") {
      cancelCalls++;
      activeRun = { ...activeRun, status: "FAILED", errorCode: "AGENT_RUN_CANCELLED", errorMessage: "Synthetic stopped run" };
      return route.fulfill({ status: 204 });
    }
    else if (path === "/api/agent/memory/items/dock-fixture" && route.request().method() === "DELETE") {
      deletionCalls++;
      await new Promise(resolve => setTimeout(resolve, 200));
      if (deletionFails) return route.fulfill({ status: 503, json: { message: "Synthetic dock deletion failure" } });
      return route.fulfill({ status: 204 });
    }
    else if (path.endsWith("/approvals")) body = permissions;
    else if (/\/approvals\/[^/]+$/.test(path) && route.request().method() === "POST") {
      permissionDecisions.push({ path, ...route.request().postDataJSON() });
      permissions = [];
      return route.fulfill({ status: 204 });
    }
    else if (path === "/api/agent/runs/latest" && activeRun) body = activeRun;
    else if (path.endsWith("/progress") && activeRun) body = activeRun.status === "FAILED"
      ? { phase: "FAILED", label: "Stopped", thinkingOutput: "Saved public reasoning", answerPartial: "Saved incomplete answer", elapsedMs: 1200, steps: [{ label: "Recovered read plans", elapsedMs: 4, status: "COMPLETED" }] }
      : { phase: "WAITING_DATA", label: "Waiting", thinkingOutput: "", answerPartial: "", silentSeconds: 40 };
    else if (path.startsWith("/api/agent/runs")) return route.fulfill(retryProbe ? { status: 404, json: { message: "Not accepted" } } : { status: 204 });
    else if (path === "/api/agent/reasoning") body = { nativeControl: true };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/background-tasks") {
      if (taskRefreshFails) return route.fulfill({ status: 503, json: { message: "Synthetic refresh failure" } });
      body = [{ id: "finished", kind: "LEARNING_EVALUATE", title: "Practice assessment", status: "COMPLETED", resultPath: "/app/learning?id=fixture", unread: true }];
    }
    else if (path === "/api/profile") body = { displayName: "Fixture", headline: "", targetRoles: [], timezone: "Asia/Shanghai" };
    else if (path === "/api/resumes") body = [{ id: "resume", originalFilename: "Synthetic resume.pdf" }];
    else if (path === "/api/memory-cards") body = [];
    else if (path === "/api/learning-plans") body = learningPlans;
    else if (path === "/api/learning-plans/delete-fixture" && route.request().method() === "DELETE") {
      learningDeleteCalls++;
      if (learningDeleteFails) return route.fulfill({ status: 503, json: { message: "Synthetic deletion failure" } });
      learningPlans = [];
      return route.fulfill({ status: 204 });
    }
    else if (["/api/interview-recaps", "/api/interview-recaps/jobs", "/api/knowledge/documents"].includes(path)) body = [];
    else if (path === "/api/settings/llm") body = { configured: true, provider: "OPENAI_COMPATIBLE", baseUrl: "https://example.com", model: "fixture", configurationSource: "ADMIN", personalOverride: false };
    else if (path === "/api/settings/web-search") body = { configured: true, provider: "TAVILY", configurationSource: "ADMIN", personalOverride: false };
    else if (path === "/api/settings/integrations") body = ["EMBEDDING", "ASR", "TTS", "FALLBACK_LLM"].map((serviceType) => ({ serviceType, configured: true, provider: "fixture", baseUrl: "https://example.com", model: "fixture", configurationSource: "ADMIN", personalOverride: false }));
    else if (path === "/api/usage") body = { tier: "MEMBER", resetsAt: new Date().toISOString(), features: [] };
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(`${origin}/app`);
  const sidebar = page.getByRole("complementary", { name: "Conversations", exact: true });
  await sidebar.getByRole("button", { name: "Notifications", exact: true }).waitFor();
  assert.equal(await sidebar.getByRole("button", { name: "Project Ardor", exact: true }).count(), 0);
  assert.equal(await sidebar.getByRole("button", { name: "Close conversations", exact: true }).count(), 0);
  assert.equal(await page.getByRole("button", { name: "New chat", exact: true }).count(), 1, "Only the sidebar should show the new-chat button");
  const bounds = await sidebar.boundingBox();
  const composer = await page.getByRole("textbox", { name: "Message Ardor", exact: true }).boundingBox();
  assert.ok(composer.x >= bounds.x + bounds.width, "Desktop chat must sit beside the sidebar");
  await sidebar.getByRole("button", { name: "Recent chats", exact: true }).click();
  assert.equal(await page.getByRole("button", { name: "Chat actions", exact: true }).count(), 0);
  await sidebar.getByRole("button", { name: "Recent chats", exact: true }).click();
  await sidebar.getByRole("button", { name: "Search chats" }).click();
  await page.getByRole("textbox", { name: "Search chat titles" }).fill("missing title");
  await page.getByText("No matching chats.", { exact: true }).waitFor();
  await page.getByRole("textbox", { name: "Search chat titles" }).fill("");
  await sidebar.getByRole("button", { name: "Notifications", exact: true }).click();
  await page.getByRole("dialog", { name: "Notifications" }).getByRole("link", { name: /Practice assessment/ }).waitFor();
  await page.getByRole("dialog", { name: "Notifications" }).press("Escape");
  await page.getByRole("button", { name: "Add attachments" }).click();
  await page.getByRole("region", { name: "Attachments", exact: true }).getByRole("button", { name: "Synthetic resume.pdf" }).click();
  assert.equal(await page.getByRole("region", { name: "Attachments", exact: true }).count(), 1);
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).click();
  assert.equal(await page.getByRole("region", { name: "Attachments", exact: true }).count(), 0);
  await page.getByRole("button", { name: "Add attachments" }).click();
  await page.keyboard.press("Escape");
  assert.equal(await page.getByRole("region", { name: "Attachments", exact: true }).count(), 0);
  await page.getByRole("button", { name: "Thinking depth", exact: true }).click();
  const depth = page.getByRole("dialog", { name: "Thinking depth options" });
  await depth.getByRole("slider").focus();
  await page.keyboard.press("End");
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "DEEP");
  await page.keyboard.press("Home");
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "QUICK");
  await depth.getByRole("button", { name: "High", exact: true }).click();
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "STANDARD");
  await page.screenshot({ path: ".next/reasoning-slider.png" });
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Chat actions", exact: true }).click();
  await page.getByRole("button", { name: "Rename", exact: true }).click();
  const rename = page.getByRole("dialog", { name: "Rename chat" });
  await rename.getByLabel("Chat title").fill("x".repeat(241));
  await rename.getByRole("button", { name: "Save", exact: true }).click();
  await rename.getByRole("alert").getByText("Use 1–240 characters.").waitFor();
  assert.equal(renameCalls, 0);
  await rename.getByLabel("Chat title").fill("My custom title");
  await rename.getByRole("button", { name: "Save", exact: true }).click();
  await rename.getByRole("alert").getByText("Synthetic save failure").waitFor();
  assert.equal(await rename.getByLabel("Chat title").inputValue(), "My custom title");
  failRename = false;
  await rename.getByLabel("Chat title").press("Enter");
  await rename.waitFor({ state: "hidden" });
  assert.equal(renameCalls, 2);
  assert.equal(conversation.titleManuallySet, true);
  await page.reload();
  await page.getByRole("heading", { name: "My custom title", exact: true }).waitFor();
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "STANDARD");

  await page.getByRole("button", { name: "More workspaces" }).click();
  await page.getByRole("button", { name: "Customize sidebar", exact: true }).click();
  const custom = page.getByRole("dialog", { name: "Customize sidebar" });
  assert.equal(await custom.getByLabel("Chat", { exact: true }).isDisabled(), true);
  await custom.getByLabel("Resumes", { exact: true }).uncheck();
  await custom.getByLabel("Interview notes", { exact: true }).check();
  await custom.getByRole("button", { name: "Save", exact: true }).click();
  const nav = page.getByRole("navigation", { name: "Workspaces", exact: true });
  assert.equal(await nav.getByRole("link", { name: "Resumes", exact: true }).count(), 0);
  assert.equal(await nav.getByRole("link", { name: "Interview notes", exact: true }).count(), 1);
  await page.screenshot({ path: ".next/workspace-chat.png" });
  await page.reload();
  await nav.getByRole("link", { name: "Interview notes", exact: true }).waitFor();
  await page.getByRole("button", { name: "More workspaces" }).click();
  await page.locator("#ardor-more-workspaces").getByRole("link", { name: "Resumes", exact: true }).waitFor();

  await page.goto(`${origin}/app/settings`);
  const categories = page.getByRole("navigation", { name: "Settings categories" });
  await page.getByRole("heading", { name: "General & sidebar" }).waitFor();
  await page.screenshot({ path: ".next/workspace-settings.png" });
  assert.equal(await page.locator('input[name="displayName"]').isVisible(), false);
  await categories.getByRole("button", { name: "Profile", exact: true }).click();
  await page.locator('input[name="displayName"]').fill("Unsaved draft");
  await categories.getByRole("button", { name: "Account security" }).click();
  await page.getByLabel("Current password", { exact: true }).waitFor();
  await categories.getByRole("button", { name: "Profile", exact: true }).click();
  assert.equal(await page.locator('input[name="displayName"]').inputValue(), "Unsaved draft");
  await categories.getByRole("button", { name: "Models & services" }).click();
  await page.getByRole("button", { name: "Use your own APIs" }).click();
  await page.locator('#personal-api-primary-llm input[name="model"]').fill("Unsaved model");
  const services = page.getByRole("navigation", { name: "API services" });
  await services.getByRole("button", { name: "Web search", exact: true }).click();
  assert.equal(await page.locator('#personal-api-primary-llm input[name="model"]').isVisible(), false);
  await services.getByRole("button", { name: "Primary LLM", exact: true }).click();
  assert.equal(await page.locator('#personal-api-primary-llm input[name="model"]').inputValue(), "Unsaved model");
  await categories.getByRole("button", { name: "General & sidebar" }).click();
  await page.getByRole("button", { name: "Customize sidebar", exact: true }).click();
  await custom.getByRole("button", { name: "Reset defaults" }).click();
  await custom.press("Escape"); // cancel must not save defaults
  await page.reload();
  await nav.getByRole("link", { name: "Interview notes", exact: true }).waitFor();
  userId = "workspace-other";
  await page.reload();
  await nav.getByRole("link", { name: "Resumes", exact: true }).waitFor();
  assert.equal(await nav.getByRole("link", { name: "Interview notes", exact: true }).count(), 0);
  await page.evaluate(() => localStorage.setItem("ardor:workspace-rail:v1:workspace-other", "bad json"));
  await page.reload();
  await nav.getByRole("link", { name: "Resumes", exact: true }).waitFor();
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.setViewportSize({ width: 1440, height: 900 });
  admin = true;
  await page.goto(`${origin}/app?new=1`);
  const draftBox = page.getByRole("textbox", { name: "Message Ardor", exact: true });
  await draftBox.fill("@Learning");
  await page.getByRole("listbox", { name: "Services" }).getByText("Learn concepts and practice", { exact: true }).waitFor();
  await draftBox.press("Escape");
  assert.equal(await page.getByRole("listbox", { name: "Services" }).count(), 0);
  await draftBox.fill("@Learn");
  await page.getByRole("option", { name: "Learning", exact: true }).click();
  await page.screenshot({ path: ".next/service-tag.png", animations: "disabled" });
  assert.equal(await draftBox.inputValue(), "");
  await page.getByRole("button", { name: "Change service" }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Change service" }).getAttribute("title"), "Learn concepts and practice");
  await draftBox.fill("Keep my example draft");
  await page.reload();
  await page.getByRole("button", { name: "Change service" }).waitFor();
  assert.equal(await draftBox.inputValue(), "Keep my example draft");
  await page.getByRole("button", { name: "Remove service" }).click();
  await draftBox.fill("$Flashcards");
  await draftBox.press("ArrowDown");
  await page.getByRole("option", { name: "Flashcards", exact: true }).press("Enter");
  assert.equal(await draftBox.inputValue(), "");
  await page.goto(`${origin}/app/learning`);
  await page.getByRole("button", { name: "New topic", exact: true }).click();
  await page.getByRole("button", { name: "Change service" }).waitFor();
  assert.equal(await draftBox.inputValue(), "I'd like to learn…");
  await page.goto(`${origin}/app/cards`);
  await page.getByRole("button", { name: "New card", exact: true }).click();
  await page.getByRole("button", { name: "Change service" }).waitFor();
  assert.equal(await draftBox.inputValue(), "Help me prepare flashcards about…");
  await page.goto(`${origin}/app/tasks`);
  await page.getByRole("link", { name: "Open result", exact: true }).waitFor();
  const refreshTasks = page.getByRole("button", { name: "Refresh tasks", exact: true });
  await refreshTasks.click();
  assert.equal(await refreshTasks.isEnabled(), false);
  assert.equal(await refreshTasks.getAttribute("aria-busy"), "true");
  assert.equal(await refreshTasks.locator("svg.animate-spin").count(), 1);
  await page.waitForFunction(() => document.querySelector('[aria-label="Refresh tasks"]').getAttribute("aria-busy") === "false");
  taskRefreshFails = true;
  await refreshTasks.click();
  await page.getByRole("alert").filter({ hasText: "Connection interrupted. Your tasks are saved." }).waitFor();
  await page.waitForFunction(() => document.querySelector('[aria-label="Refresh tasks"]').getAttribute("aria-busy") === "false");
  assert.equal(await refreshTasks.isEnabled(), true);
  assert.equal(await page.getByRole("link", { name: "Open result", exact: true }).count(), 1);
  taskRefreshFails = false;
  await page.goto(`${origin}/app`);
  await sidebar.getByRole("button", { name: "Project Ardor", exact: true }).click();
  await sidebar.getByRole("link", { name: "Admin dashboard", exact: true }).waitFor();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).click();
  await page.getByRole("button", { name: "Account", exact: true }).click();
  assert.equal(await page.getByRole("link", { name: "Admin", exact: true }).count(), 0);
  assert.equal(await page.getByRole("button", { name: "中文", exact: true }).count(), 0);
  displayedMessages = [{ id: "long-history", conversationId: conversation.id, role: "ASSISTANT", content: "Synthetic history paragraph.\n\n".repeat(120), createdAt: new Date().toISOString() }];
  await page.goto(`${origin}/app`);
  await draftBox.fill("@Learning");
  await page.getByRole("option", { name: "Learning", exact: true }).click();
  await draftBox.fill("Make a study outline");
  permissions = [{ id: "permission-one", tool: "get_learning_plan", arguments: '{"planId":"exact-owned-target"}', status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
  await page.getByRole("button", { name: "Send", exact: true }).click();
  for (let attempt = 0; attempt < 100 && !releaseStream; attempt++) await page.waitForTimeout(20);
  assert.ok(releaseStream, "Synthetic stream must be in flight");
  const toolPermissions = page.getByRole("region", { name: "Tool permissions", exact: true });
  assert.equal(await toolPermissions.count(), 0, "Ordinary reads must not show permission prompts");
  assert.equal(permissionDecisions.length, 0);
  assert.equal(await draftBox.isEnabled(), true);
  await draftBox.fill("Next message draft survives completion");
  await page.getByRole("button", { name: "Thinking depth", exact: true }).click();
  await page.getByRole("button", { name: "Low", exact: true }).click();
  assert.equal(await page.getByRole("button", { name: "Thinking depth", exact: true }).getAttribute("data-reasoning-depth"), "QUICK");
  await draftBox.click();
  releaseStream();
  await page.getByText("Synthetic agent reply", { exact: true }).waitFor();
  // The live progress card is replaced after completion. Inspect the persisted
  // assistant article so a click cannot land on the soon-to-be-unmounted card.
  const reasoning = page.getByRole("article").filter({ hasText: "Synthetic agent reply" }).locator("details")
    .filter({ has: page.getByText("Model reasoning output", { exact: true }) }).first();
  await reasoning.locator("summary").click();
  await reasoning.getByText("Synthetic public reasoning", { exact: true }).waitFor();
  assert.deepEqual(sentMessage.contextReferences, [{ type: "SERVICE", id: "f1111111-1111-3111-8111-111111111111" }]);
  const sentBubble = page.getByRole("article").filter({ hasText: "Make a study outline" });
  await sentBubble.getByRole("link", { name: "Learning", exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "Next message draft survives completion");
  const replyTrace = page.getByRole("article").filter({ hasText: "Synthetic agent reply" }).locator("details").last();
  await replyTrace.locator("summary").click();
  await replyTrace.getByText("Approval wait 40.0 s", { exact: true }).waitFor();
  await replyTrace.getByText("Not executed", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Change service" }).count(), 0);
  await page.reload();
  await sentBubble.getByRole("link", { name: "Learning", exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "Next message draft survives completion");
  // Reconnect to an accepted run: it is history, never the next-message draft.
  activeRun = { id: "accepted-run", conversationId: conversation.id, message: "Accepted message after disconnect", status: "RUNNING", label: "Waiting", createdAt: new Date().toISOString() };
  permissions = [{ id: "permission-two", tool: "add_memory", arguments: '{"memory":"Fixture"}', status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
  await page.evaluate(({ accountId, conversationId }) => {
    window.sessionStorage.setItem(`ardor:outgoing:${accountId}:${conversationId}`, JSON.stringify({
      id: "pending-user-message-accepted-run", conversationId, role: "USER", content: "Accepted message after disconnect", createdAt: new Date().toISOString(),
    }));
  }, { accountId: userId, conversationId: conversation.id });
  await page.reload();
  await page.getByRole("article").filter({ hasText: "Accepted message after disconnect" }).waitFor();
  assert.equal(await draftBox.inputValue(), "Next message draft survives completion");
  assert.equal(await draftBox.isEnabled(), true);
  assert.equal(await toolPermissions.count(), 0, "Ordinary writes must not show permission prompts");
  assert.equal(permissionDecisions.length, 0);
  activeRun = { ...activeRun, id: "queued-active-run", message: "A new queued instruction after the original stopped" };
  await page.reload();
  await page.getByRole("article").filter({ hasText: activeRun.message }).waitFor();
  await page.waitForTimeout(6500); // More than two real recovery polls with a stale predecessor outbox.
  assert.equal(await page.getByRole("article").filter({ hasText: "Accepted message after disconnect" }).count(), 1);
  assert.equal(await page.getByRole("article").filter({ hasText: activeRun.message }).count(), 1);
  await draftBox.fill("Queued follow-up one");
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/api/agent/queue") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Send", exact: true }).click(),
  ]);
  await page.getByRole("region", { name: "Message queue", exact: true }).getByText("Queued follow-up one", { exact: true }).waitFor();
  assert.equal(queuedRequests[0].reasoningDepth, "QUICK");
  await Promise.all([
    page.waitForResponse(response => response.url().endsWith(`${queuedRequests[0].requestId}/steer`) && response.request().method() === "POST"),
    page.getByRole("region", { name: "Message queue", exact: true }).getByRole("button", { name: "Interrupt and handle now", exact: true }).click(),
  ]);
  assert.equal(steerRequests.length, 1);
  assert.equal(steerRequests[0].interruptRunId, "queued-active-run");
  assert.ok(steerRequests[0].path.endsWith(`${queuedRequests[0].requestId}/steer`));
  await draftBox.fill("Queued follow-up two");
  await page.getByRole("button", { name: "Thinking depth", exact: true }).click();
  await page.getByRole("button", { name: "Max", exact: true }).click();
  await draftBox.click();
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/api/agent/queue") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Send", exact: true }).click(),
  ]);
  assert.equal(queuedRequests[1].reasoningDepth, "DEEP");
  assert.notEqual(queuedRequests[0].requestId, queuedRequests[1].requestId);
  await page.reload();
  await page.getByRole("region", { name: "Message queue", exact: true }).getByText("Queued follow-up two", { exact: true }).waitFor();
  const secondQueued = page.getByRole("article").filter({ hasText: "Queued follow-up two" });
  await secondQueued.getByRole("button", { name: "Remove from queue", exact: true }).click();
  await secondQueued.waitFor({ state: "detached" });
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/runs/queued-active-run/cancel") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Stop", exact: true }).click(),
  ]);
  assert.equal(cancelCalls, 1);
  await page.reload();
  await page.getByText("Incomplete reply · saved before execution stopped", { exact: true }).click();
  await page.getByText("Saved incomplete answer", { exact: true }).waitFor();
  await page.getByText("Recovered read plans", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Thinking depth", exact: true }).isEnabled(), true);
  activeRun = null;
  queueItems = [];
  retryProbe = true;
  await page.reload();
  await draftBox.fill("Stop during retry backoff");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await page.getByText(/正在进行第 1\/5 次重试/).waitFor();
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await page.getByText("Sending stopped. No further automatic retries will be sent.", { exact: true }).waitFor();
  await page.waitForTimeout(2200);
  assert.equal(retryProbeCalls, 1, "Stop must prevent the next retry POST");
  assert.equal(cancelCalls, 1, "Unaccepted requests must not receive server cancellation");
  retryProbe = false;
  userId = "different-outbox-owner";
  await page.reload();
  await page.getByRole("button", { name: "Thinking depth", exact: true }).waitFor();
  assert.equal(await page.getByRole("article").filter({ hasText: "Accepted message after disconnect" }).count(), 0);
  learningPlans = [{ id: "delete-fixture", concept: "Synthetic JVM lesson", status: "COMPLETED", sourceType: "MANUAL", scheduledAt: new Date().toISOString(), lesson: { summary: "Fixture" }, exercises: [], lastScore: null, attemptCount: 0 }];
  await page.goto(`${origin}/app/learning`);
  await page.getByRole("button", { name: "Delete learning plan", exact: true }).click();
  assert.equal(learningDeleteCalls, 0);
  await page.getByRole("dialog", { name: "Delete learning plan?" }).getByRole("button", { name: "Cancel", exact: true }).click();
  assert.equal(learningDeleteCalls, 0);
  await page.getByRole("button", { name: "Delete learning plan", exact: true }).click();
  learningDeleteFails = true;
  await page.getByRole("button", { name: "Delete permanently", exact: true }).click();
  await page.getByRole("dialog").getByRole("alert").waitFor();
  learningDeleteFails = false;
  await page.getByRole("button", { name: "Delete permanently", exact: true }).click();
  await page.getByText("Start with one concept", { exact: true }).waitFor();
  assert.equal(learningDeleteCalls, 2);
  await page.goto(`${origin}/app/recaps`);
  await page.getByRole("link", { name: "Add interview notes", exact: true }).click();
  await page.getByRole("button", { name: "Change service", exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "Organize these interview notes and identify what I should improve:\n");
  assert.equal(await page.getByRole("article").count(), 0, "Guided entry must open an unsent blank conversation");
  await draftBox.fill("My edited interview draft");
  await page.reload();
  assert.equal(await draftBox.inputValue(), "My edited interview draft");
  await page.goto(`${origin}/app?new=1&feature=LEARNING&prefill=Draft%20with%20source&contextType=RECAP&contextId=11111111-1111-4111-8111-111111111111&contextLabel=Interview%20source`);
  await page.getByRole("button", { name: "Remove attachment", exact: true }).waitFor();
  await draftBox.fill("Preserve my draft and source together");
  await page.reload();
  await page.getByRole("button", { name: "Change service", exact: true }).waitFor();
  assert.equal(await page.getByText("Interview source", { exact: true }).count(), 1);
  assert.equal(await draftBox.inputValue(), "Preserve my draft and source together");
  const draftOwner = userId;
  userId = "another-draft-owner";
  await page.reload();
  await page.getByRole("button", { name: "Thinking depth", exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "");
  assert.equal(await page.getByRole("button", { name: "Remove attachment", exact: true }).count(), 0);
  assert.equal(await page.getByRole("button", { name: "Change service", exact: true }).count(), 0);
  userId = draftOwner;
  await page.reload();
  await page.getByRole("button", { name: "Remove attachment", exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "Preserve my draft and source together");
  await page.getByRole("button", { name: "Remove attachment", exact: true }).click();
  await page.reload();
  await page.getByRole("button", { name: "Change service", exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Remove attachment", exact: true }).count(), 0);
  await page.evaluate((owner) => {
    window.sessionStorage.setItem("ardor:conversation-draft:chat-one", "Legacy owned conversation draft");
    window.sessionStorage.removeItem(`ardor:conversation-draft:${owner}:chat-one`);
  }, userId);
  await page.goto(`${origin}/app?conversation=chat-one`);
  await page.waitForFunction(() => document.querySelector('textarea[aria-label="Message Ardor"]')?.value === "Legacy owned conversation draft");
  assert.equal(await page.evaluate(() => window.sessionStorage.getItem("ardor:conversation-draft:chat-one")), null);
  await page.goto(`${origin}/app/calendar`);
  await page.getByRole("link", { name: "Add a task", exact: true }).click();
  await page.getByRole("button", { name: "Change service", exact: true }).waitFor();
  assert.match(await draftBox.inputValue(), /^Add a task on \d{4}-\d{2}-\d{2}: /);
  await page.goto(`${origin}/app/calendar`);
  await page.getByRole("link", { name: "Edit Weekly sync", exact: true }).click();
  await page.getByRole("button", { name: "Change service", exact: true }).waitFor();
  assert.match(await draftBox.inputValue(), /Change the recurring schedule “Weekly sync”/);
  assert.equal(calendarWrites, 0, "Guided creation/editing must not mutate the calendar before sending and approving");
  displayedMessages = [{ id: "deletion-proposal", conversationId: conversation.id, role: "ASSISTANT", content: "Synthetic history.\n\n".repeat(120), createdAt: new Date().toISOString(),
    runTrace: JSON.stringify({ elapsedMs: 20, status: "COMPLETED", steps: [], proposals: [{ kind: "memory_item", targetId: "dock-fixture", label: "Synthetic career preference", detail: "Remove only this item from long-term memory.", endpoint: "/api/agent/memory/items/dock-fixture" }] }) }];
  await page.goto(`${origin}/app?conversation=chat-one`);
  const deletionDock = page.getByRole("region", { name: "Deletion confirmation", exact: true });
  await deletionDock.getByText('Delete “Synthetic career preference”?', { exact: true }).waitFor();
  await deletionDock.getByText("Remove only this item from long-term memory.", { exact: true }).waitFor();
  assert.equal(deletionCalls, 0);
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    const bounds = await page.evaluate(() => {
      const dock = document.querySelector('[aria-label="Deletion confirmation"]').getBoundingClientRect();
      const input = document.querySelector('textarea[aria-label="Message Ardor"]').getBoundingClientRect();
      return { top: dock.top, bottom: dock.bottom, inputTop: input.top, inputBottom: input.bottom, height: innerHeight };
    });
    assert.ok(bounds.top >= 0 && bounds.bottom <= bounds.inputTop && bounds.inputBottom <= bounds.height, "Confirmation must be visible directly above the composer, without covering it");
  }
  await deletionDock.getByRole("button", { name: "Keep", exact: true }).click();
  await deletionDock.waitFor({ state: "detached" });
  assert.equal(deletionCalls, 0);
  await page.reload();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).waitFor();
  assert.equal(await deletionDock.count(), 0, "Kept proposals must not revive on reload");
  displayedMessages[0].id = "new-deletion-proposal";
  await page.reload();
  await deletionDock.waitFor();
  deletionFails = true;
  await deletionDock.getByRole("button", { name: "Confirm deletion", exact: true }).click();
  await deletionDock.getByRole("alert").waitFor();
  assert.equal(deletionCalls, 1);
  deletionFails = false;
  await deletionDock.getByRole("button", { name: "Confirm deletion", exact: true }).click();
  await deletionDock.waitFor({ state: "detached" });
  assert.equal(deletionCalls, 2);
  await page.reload();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).waitFor();
  assert.equal(await deletionDock.count(), 0, "Completed deletion must not revive on reload");
  assert.deepEqual(errors, []);
  console.log("Workspace UI: rename, retry, sidebar persistence/isolation, categorized settings and draft retention passed.");
} finally {
  await browser?.close();
  server.kill();
  if (server.exitCode === null) await new Promise((resolve) => server.once("close", resolve));
}
