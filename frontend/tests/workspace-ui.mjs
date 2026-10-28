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
  let permissionDecisionFails = false;
  let targetLookupFails = true;
  let deletionCalls = 0;
  let deletionFails = false;
  const steerRequests = [];
  let queueItems = [];
  let queuedRequests = [];
  let cancelCalls = 0;
  let calendarWrites = 0;
  let retryProbe = false;
  let retryProbeCalls = 0;
  let quotaProbe = false;
  let quotaProbeCalls = 0;
  let learningPlans = [];
  let learningDeleteCalls = 0;
  let learningDeleteFails = false;
  let failRename = true;
  let renameCalls = 0;
  let previewScenario = true;
  const descriptionRequests = [];
  let descriptionQuotaExhausted = false;
  let holdModels = true;
  let releaseModels;
  const modelDescriptions = {
    "deepseek-v4-flash": { en: "Fast, high-value everyday model", zh: "高性价比日常模型" },
    "claude-opus-5-5": { en: "Flagship reasoning and coding", zh: "旗舰推理与编程模型" },
    "long-context-research": { en: "Long-context research model", zh: "长上下文调研模型" },
  };
  const modelSettings = {};
  let modelSettingsWrites = 0;
  const catalogModelSeeds = () => [
    { id: "PRIMARY", label: "deepseek-v4-flash", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true, reasoningLevels: [{ value: "QUICK", label: "Low" }, { value: "STANDARD", label: "Medium" }, { value: "DEEP", label: "High" }], settingsKey: "settings-primary" },
    { id: "FALLBACK:claude-opus-5-5", label: "claude-opus-5-5", provider: "ANTHROPIC_COMPATIBLE", available: true, nativeControl: true, settingsKey: "settings-claude" },
    { id: "FALLBACK:long-context-research", label: "long-context-research", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true, settingsKey: "settings-long" },
    { id: "FALLBACK:deepseek-relay-alias", label: "deepseek-relay-alias", provider: "OPENAI_COMPATIBLE", available: true, nativeControl: true, settingsKey: "settings-primary" },
  ];
  const catalogModels = () => catalogModelSeeds().map((model) => ({
    ...model, visible: true, thinkingEffort: "AUTO", contextWindowTokens: null, ...(modelSettings[model.settingsKey] ?? {}),
  }));
  let conversation = { id: "chat-one", title: "Original chat", pinned: false, titleManuallySet: false, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
  await context.addInitScript(() => { window.prompt = () => { throw new Error("Native prompt must not be used"); }; });
  const handleApi = async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    let body = {};
    if (path === "/api/auth/me") body = { id: userId, email: "fixture@ardor.invalid", admin };
    else if (path === "/api/agent/models/description") {
      const request = route.request().postDataJSON();
      descriptionRequests.push(request);
      if (descriptionQuotaExhausted) {
        return route.fulfill({ status: 429, json: { code: "QUOTA_EXCEEDED", message: "Synthetic summary quota reached", retryable: false } });
      }
      const localized = modelDescriptions[request.model] ?? { en: "Synthetic summary", zh: "合成简述" };
      body = { description: request.locale === "zh" ? localized.zh : localized.en };
    }
    else if (path === "/api/agent/models") {
      if (holdModels) await new Promise((resolve) => { releaseModels = resolve; });
      body = catalogModels();
    }
    else if (path === "/api/agent/models/settings") {
      modelSettingsWrites++;
      for (const setting of route.request().postDataJSON().settings ?? []) {
        const model = catalogModelSeeds().find((candidate) => candidate.id === setting.modelChoice);
        if (!model) continue;
        modelSettings[model.settingsKey] = {
          visible: setting.visible, thinkingEffort: setting.thinkingEffort,
          contextWindowTokens: setting.contextWindowTokens,
        };
      }
      body = catalogModels();
    }
    else if (path === "/api/agent/permissions") body = { globalLevel: 0, modules: [] };
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
      if (quotaProbe) {
        quotaProbeCalls++;
        return route.fulfill({ status: 429, json: { code: "QUOTA_EXCEEDED", message: "Synthetic quota limit reached", retryable: false } });
      }
      if (retryProbe) {
        retryProbeCalls++;
        return route.fulfill({ status: 503, json: { message: "Synthetic retryable failure", retryable: true } });
      }
      sentMessage = route.request().postDataJSON();
      await new Promise((resolve) => { releaseStream = resolve; });
      displayedMessages = [
        { id: "sent-user", conversationId: conversation.id, role: "USER", content: sentMessage.message, contextReferences: sentMessage.contextReferences.map((reference) => ({ ...reference, label: "学习" })), createdAt: new Date().toISOString() },
        { id: "sent-assistant", conversationId: conversation.id, role: "ASSISTANT", content: "Synthetic agent reply", runTrace: JSON.stringify({ elapsedMs: 42000, status: "COMPLETED", steps: [{ label: "I'll inspect the plans.", elapsedMs: 0, status: "COMMENTARY" }, { label: "Read plans", elapsedMs: 40003, approvalWaitMs: 40000, businessMs: 3, status: "COMPLETED" }, { label: "Denied tool", elapsedMs: 1200, approvalWaitMs: 1200, businessMs: 0, status: "NOT_EXECUTED" }], thinkingOutput: "Synthetic public reasoning", firstOutputMs: 80 }), createdAt: new Date().toISOString() },
      ];
      return route.fulfill({ contentType: "text/event-stream", body: [
        { type: "thinking", content: "Synthetic public reasoning" },
        { type: "delta", content: "I'll inspect the plans." },
        { type: "intermediate", content: "I'll inspect the plans." },
        { type: "tool_start", toolName: "list_learning_plans", label: "Read plans" },
        { type: "tool_end", toolName: "list_learning_plans", label: "Read plans", elapsedMs: 4, toolStatus: "COMPLETED" },
        { type: "delta", content: "Synthetic agent reply" },
        { type: "done", conversationId: conversation.id, messageId: "sent-assistant", content: "Synthetic agent reply", elapsedMs: 100 },
      ].map(event => `data: ${JSON.stringify(event)}\n\n`).join("") });
    }
    else if (path === "/api/agent") body = { conversationId: url.searchParams.get("conversationId") || conversation.id, llmConfigured: true, displayName: "Fixture", conversations: [conversation, ...(previewScenario ? [{ ...conversation, id: "chat-two", title: "Second preview chat" }] : [])], archivedConversations: [], messages: url.searchParams.get("conversationId") === "chat-two" ? [{ id: "preview-message", role: "ASSISTANT", content: "Second conversation content", createdAt: new Date().toISOString() }] : displayedMessages, memory: { content: "", updatedAt: null } };
    else if (path === "/api/agent/conversations/chat-one") {
      renameCalls++;
      if (failRename) return route.fulfill({ status: 503, json: { message: "Synthetic save failure" } });
      conversation = { ...conversation, ...route.request().postDataJSON(), titleManuallySet: true }; body = conversation;
    }
    else if (path === "/api/agent/conversations") body = route.request().method() === "POST"
      ? conversation : [conversation, { ...conversation, id: "chat-two", title: "Second preview chat" }];
    else if (path === "/api/agent/messages/steer" && route.request().method() === "POST") {
      const request = route.request().postDataJSON();
      steerRequests.push(request);
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
    else if (path.endsWith("/proposal-resolution") && route.request().method() === "PUT") {
      const messageId = path.split("/").at(-2);
      const message = displayedMessages.find(item => item.id === messageId);
      const decision = route.request().postDataJSON();
      if (message) message.proposalResolutions = { ...message.proposalResolutions, [decision.key]: decision.resolution };
      return route.fulfill({ status: 200 });
    }
    else if (path.endsWith("/approvals")) body = permissions;
    else if (/\/approvals\/[^/]+$/.test(path) && route.request().method() === "POST") {
      if (permissionDecisionFails) return route.fulfill({ status: 503, json: { message: "Synthetic confirmation failure" } });
      permissionDecisions.push({ path, ...route.request().postDataJSON() });
      permissions = [];
      return route.fulfill({ status: 204 });
    }
    else if (path === "/api/agent/runs/latest" && activeRun) body = activeRun;
    else if (path.endsWith("/progress") && activeRun) body = activeRun.status === "FAILED"
      ? { phase: "FAILED", label: "Stopped", thinkingOutput: "Saved public reasoning", answerPartial: "Saved incomplete answer", elapsedMs: 1200, steps: [{ label: "Recovered read plans", elapsedMs: 4, status: "COMPLETED" }] }
      : { phase: activeRun?.progressPhase ?? "WAITING_DATA", label: "Waiting", thinkingOutput: activeRun?.progressPhase === "THINKING" ? "Provider supplied reasoning" : "", answerPartial: "", silentSeconds: 40, steps: activeRun?.showCommentary ? [{ label: "Recovered live commentary", elapsedMs: 0, status: "COMMENTARY" }] : [] };
    else if (path.startsWith("/api/agent/runs")) return route.fulfill(retryProbe || quotaProbe ? { status: 404, json: { message: "Not accepted" } } : { status: 204 });
    else if (path === "/api/agent/reasoning") body = { nativeControl: true };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/background-tasks") {
      if (taskRefreshFails) return route.fulfill({ status: 503, json: { message: "Synthetic refresh failure" } });
      body = [{ id: "finished", kind: "LEARNING_EVALUATE", title: "Practice assessment", status: "COMPLETED", resultPath: "/app/learning?id=fixture", unread: true }];
    }
    else if (path === "/api/profile") body = { displayName: "Fixture", headline: "", targetRoles: [], timezone: "Asia/Shanghai" };
    else if (path === "/api/resumes") body = [{ id: "resume", originalFilename: "Synthetic resume.pdf" }];
    else if (path === "/api/memory-cards") body = [{ id: "card-fixture", front: "Synthetic card to review", back: "Fixture answer" }];
    else if (path === "/api/learning-plans") body = learningPlans;
    else if (path === "/api/learning-plans/owned-plan") {
      if (targetLookupFails) return route.fulfill({ status: 503, json: { message: "Synthetic lookup failure" } });
      body = { id: "owned-plan", concept: "JVM memory", scheduledAt: "2026-10-18T01:00:00Z" };
    }
    else if (path === "/api/interviews") body = [{ id: "interview-fixture", targetRole: "Synthetic Java interview" }];
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
  };
  await context.route("**/api/**", handleApi);
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(`${origin}/app`);
  const initialDraft = page.getByRole("textbox", { name: "Message Ardor", exact: true });
  const initialSend = page.getByRole("button", { name: "Send", exact: true });
  await page.waitForFunction(() => {
    const trigger = document.querySelector('[data-reasoning-depth]');
    return trigger instanceof HTMLButtonElement && trigger.disabled;
  });
  await initialDraft.fill("Catalog gate draft");
  assert.equal(await initialSend.isDisabled(), true, "Sending must wait for model settings to load");
  holdModels = false;
  releaseModels?.();
  await page.waitForFunction(() => {
    const trigger = document.querySelector('[data-reasoning-depth]');
    return trigger instanceof HTMLButtonElement && !trigger.disabled;
  });
  await initialDraft.fill("");
  async function assertComposerActions(label) {
    const footer = page.locator("[data-composer-footer]");
    const actions = footer.locator("[data-composer-actions]");
    const model = actions.getByRole("button", { name: "Model and thinking depth", exact: true });
    const voice = actions.getByRole("button", { name: "Voice input", exact: true });
    const send = actions.getByRole("button", { name: "Send", exact: true });
    await voice.waitFor();
    const [footerBox, actionBox, modelBox, voiceBox, sendBox] = await Promise.all(
      [footer, actions, model, voice, send].map(locator => locator.boundingBox()));
    assert.ok(modelBox.x + modelBox.width <= voiceBox.x + 1 && voiceBox.x + voiceBox.width <= sendBox.x + 1,
      `${label}: model, microphone and send must share the same right-hand group`);
    assert.ok(Math.abs(footerBox.x + footerBox.width - actionBox.x - actionBox.width) <= 12,
      `${label}: composer actions must stay right aligned`);
    assert.equal(await page.getByRole("button", { name: "Voice input", exact: true }).count(), 1,
      `${label}: one microphone must be available`);
  }
  await assertComposerActions("Empty conversation");
  const sidebar = page.getByRole("complementary", { name: "Conversations", exact: true });
  assert.equal(await sidebar.getByRole("button", { name: "Notifications", exact: true }).count(), 0, "Background task status lives above the composer, not in the chat header");
  assert.equal(await sidebar.getByRole("button", { name: "Ardor", exact: true }).count(), 0);
  assert.equal(await sidebar.getByRole("button", { name: "Close conversations", exact: true }).count(), 0);
  const bounds = await sidebar.boundingBox();
  const composer = await page.getByRole("textbox", { name: "Message Ardor", exact: true }).boundingBox();
  assert.ok(composer.x >= bounds.x + bounds.width, "Desktop chat must sit beside the sidebar");
  const home = page.getByRole("navigation", { name: "Workspaces", exact: true }).getByRole("link", { name: "Chat", exact: true });
  const chatPreview = page.getByRole("dialog", { name: "Recent chats preview", exact: true });
  await home.hover();
  assert.equal(await chatPreview.count(), 0, "Pinned conversations must not get a duplicate floating list");
  await page.getByRole("button", { name: "Toggle conversations", exact: true }).click();
  await sidebar.waitFor({ state: "hidden" });
  await home.hover();
  await chatPreview.getByRole("link", { name: "Original chat", exact: true }).waitFor();
  await chatPreview.hover();
  await page.waitForTimeout(250);
  assert.equal(await chatPreview.isVisible(), true, "Moving the pointer into the preview keeps it open");
  await chatPreview.getByRole("button", { name: "Recent chats", exact: true }).click();
  assert.equal(await chatPreview.getByRole("link", { name: "Original chat", exact: true }).count(), 0);
  await chatPreview.getByRole("button", { name: "Recent chats", exact: true }).click();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).hover();
  await chatPreview.waitFor({ state: "hidden" });
  await home.hover();
  await chatPreview.waitFor();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).click();
  await chatPreview.waitFor({ state: "hidden" });
  await home.focus();
  await home.press("ArrowRight");
  await chatPreview.waitFor();
  await page.waitForFunction(() => document.activeElement?.id === "ardor-chat-preview");
  await chatPreview.press("Escape");
  await chatPreview.waitFor({ state: "hidden" });
  await home.hover();
  await chatPreview.getByRole("link", { name: "Second preview chat", exact: true }).click();
  await page.getByText("Second conversation content", { exact: true }).waitFor();
  assert.equal(await sidebar.isVisible(), false);
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).fill("Second conversation draft");
  await home.hover();
  await chatPreview.getByRole("link", { name: "New chat", exact: true }).click();
  await page.getByText("Second conversation content", { exact: true }).waitFor({ state: "hidden" });
  assert.equal(await page.getByRole("textbox", { name: "Message Ardor", exact: true }).inputValue(), "");
  previewScenario = false;
  await home.hover();
  await chatPreview.getByRole("link", { name: "Original chat", exact: true }).click();
  await chatPreview.waitFor({ state: "hidden" });
  assert.equal(await sidebar.isVisible(), false, "Choosing a preview conversation does not force the pinned sidebar open");
  await home.hover();
  await chatPreview.getByRole("button", { name: "Pin conversations sidebar", exact: true }).click();
  await sidebar.waitFor();
  await chatPreview.waitFor({ state: "hidden" });
  const touchContext = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  await touchContext.route("**/api/**", handleApi);
  const touchPage = await touchContext.newPage();
  await touchPage.goto(`${origin}/app`);
  await touchPage.getByRole("navigation", { name: "Workspaces", exact: true }).getByRole("link", { name: "Chat", exact: true }).tap();
  await touchPage.getByRole("complementary", { name: "Conversations", exact: true }).waitFor();
  assert.equal(await touchPage.getByRole("dialog", { name: "Recent chats preview", exact: true }).count(), 0,
    "Touch opens the normal drawer instead of requiring hover");
  await touchContext.close();
  await sidebar.getByRole("button", { name: "Recent chats", exact: true }).click();
  assert.equal(await page.getByRole("button", { name: "Chat actions", exact: true }).count(), 0);
  await sidebar.getByRole("button", { name: "Recent chats", exact: true }).click();
  await sidebar.getByRole("button", { name: "Search chats" }).click();
  await page.getByRole("textbox", { name: "Search chat titles" }).fill("missing title");
  await page.getByText("No matching chats.", { exact: true }).waitFor();
  await page.getByRole("textbox", { name: "Search chat titles" }).fill("");
  await page.getByRole("button", { name: "Add attachments" }).click();
  await page.getByRole("region", { name: "Attachments", exact: true }).getByRole("button", { name: "Synthetic resume.pdf" }).click();
  assert.equal(await page.getByRole("region", { name: "Attachments", exact: true }).count(), 1);
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).click();
  assert.equal(await page.getByRole("region", { name: "Attachments", exact: true }).count(), 0);
  await page.getByRole("button", { name: "Add attachments" }).click();
  await page.keyboard.press("Escape");
  assert.equal(await page.getByRole("region", { name: "Attachments", exact: true }).count(), 0);
  assert.equal(descriptionRequests.length, 0, "Model research must wait until the user opens the model picker");
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  const depth = page.getByRole("dialog", { name: "Model and thinking depth options" });
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "AUTO", "Provider default remains the default");
  await depth.getByText("DeepSeek V4 Flash", { exact: true }).waitFor();
  await page.screenshot({ path: ".next/model-picker.png" });
  await depth.getByText("Fast, high-value everyday model", { exact: true }).waitFor();
  await depth.getByText("Flagship reasoning and coding", { exact: true }).waitFor();
  await depth.getByText("Long-context research model", { exact: true }).waitFor();
  assert.equal(await depth.getByText("Fast, high-value everyday model", { exact: true }).getAttribute("title"), "Fast, high-value everyday model",
    "The truncated summary must expose its full text on hover");
  assert.equal(await depth.getByText("Primary", { exact: true }).count(), 0);
  assert.equal(await depth.getByText("Relay", { exact: true }).count(), 0);
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "zh-CN"); window.dispatchEvent(new Event("ardor:locale-change")); });
  const chineseDepth = page.getByRole("dialog", { name: "模型与思考深度选项" });
  await chineseDepth.getByText("高性价比日常模型", { exact: true }).waitFor();
  await chineseDepth.getByText("旗舰推理与编程模型", { exact: true }).waitFor();
  await chineseDepth.getByText("长上下文调研模型", { exact: true }).waitFor();
  assert.ok(descriptionRequests.slice(-3).every((request) => request.locale === "zh"), "Application language must drive model research");
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "en"); window.dispatchEvent(new Event("ardor:locale-change")); });
  await depth.getByText("Fast, high-value everyday model", { exact: true }).waitFor();
  await depth.getByRole("button", { name: /^Claude Opus 5\.5/ }).click();
  assert.equal(await page.locator('[data-model-choice]').getAttribute("data-model-choice"), "FALLBACK:claude-opus-5-5", "Friendly names must not change selection keys");
  await depth.getByRole("button", { name: /^DeepSeek V4 Flash/ }).click();
  assert.equal(await page.locator('[data-model-choice]').getAttribute("data-model-choice"), "PRIMARY");
  for (const [icon, count] of [["color-deepseek.svg", 2], ["color-claude.svg", 1]]) {
    assert.equal(await depth.locator(`[style*="${icon}"]`).count(), count, `Model must render its ${icon} mark`);
    const asset = await context.request.get(`${origin}/model-icons/${icon}`);
    assert.equal(asset.status(), 200, `Model icon must be served: ${icon}`);
    assert.match(asset.headers()["content-type"], /image\/svg\+xml/);
  }
  assert.equal(await depth.getByRole("slider").count(), 0, "Reasoning uses controls, not the removed range slider");
  assert.equal(await depth.getByRole("button", { name: /^(Auto|Low|High|Max)$/ }).count(), 0,
    "Thinking depth must no longer occupy an inline picker row");
  await depth.getByRole("button", { name: "Manage models", exact: true }).click();
  const manage = page.getByRole("dialog", { name: "Manage models" });
  assert.equal(await manage.locator("[data-model-setting]").count(), 3,
    "Catalog entries resolving to one configuration must collapse into one settings row");
  await manage.getByTitle(/Shared by 2 entries/).waitFor();
  await page.screenshot({ path: ".next/model-settings-dialog.png" });
  assert.equal(await manage.locator('[style*="color-deepseek.svg"]').count(), 1, "Settings rows must reuse the model brand icon");
  assert.equal(await manage.locator('[style*="color-claude.svg"]').count(), 1, "Settings rows must reuse the model brand icon");
  assert.equal(await manage.getByRole("tab", { name: "Default", exact: true }).getAttribute("aria-selected"), "true");
  assert.equal(await manage.getByRole("button", { name: "Save", exact: true }).isDisabled(), true,
    "Save stays disabled until a setting changes");
  await manage.getByRole("tab", { name: "Custom", exact: true }).click();
  await manage.getByText("No custom settings.", { exact: true }).waitFor();
  await manage.getByRole("tab", { name: "Default", exact: true }).click();
  await manage.getByLabel("Thinking level: deepseek-v4-flash").selectOption("DEEP");
  assert.equal(await manage.getByLabel("Thinking level: deepseek-v4-flash").locator("option:checked").textContent(), "High",
    "Model-specific effort labels must be preserved");
  assert.equal(await manage.getByRole("switch", { name: "Thinking: deepseek-v4-flash" }).getAttribute("aria-checked"), "true");
  const windowSlider = manage.getByLabel("Context window: deepseek-v4-flash");
  await windowSlider.focus();
  await windowSlider.press("End");
  await manage.getByLabel("Window tokens: deepseek-v4-flash").fill("64000");
  assert.equal(await windowSlider.getAttribute("aria-valuetext"), "64K");
  await manage.getByRole("switch", { name: "Visible in picker: claude-opus-5-5" }).click();
  await manage.getByRole("button", { name: "Save", exact: true }).click();
  await manage.waitFor({ state: "hidden" });
  assert.equal(modelSettingsWrites, 1);
  assert.deepEqual(modelSettings["settings-primary"], { visible: true, thinkingEffort: "DEEP", contextWindowTokens: 64000 },
    "Saving must send the exact per-model settings");
  assert.equal(modelSettings["settings-claude"].visible, false);
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "DEEP",
    "The selected model's saved effort must drive requests");
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  const filteredDepth = page.getByRole("dialog", { name: "Model and thinking depth options" });
  assert.equal(await filteredDepth.getByRole("button", { name: /^Claude Opus 5\.5/ }).count(), 0,
    "Hidden models must disappear from the picker");
  await filteredDepth.getByText("DeepSeek Relay Alias", { exact: true }).waitFor();
  await filteredDepth.getByRole("button", { name: "Manage models", exact: true }).click();
  const manageAgain = page.getByRole("dialog", { name: "Manage models" });
  assert.equal(await manageAgain.getByLabel("Thinking level: deepseek-v4-flash").inputValue(), "DEEP");
  assert.equal(await manageAgain.getByLabel("Context window: deepseek-v4-flash").inputValue(), "4");
  assert.equal(await manageAgain.getByLabel("Context window: deepseek-v4-flash").getAttribute("aria-valuetext"), "64K");
  assert.equal(await manageAgain.getByLabel("Window tokens: deepseek-v4-flash").inputValue(), "64000");
  assert.equal(await manageAgain.getByRole("switch", { name: "Visible in picker: claude-opus-5-5" }).getAttribute("aria-checked"), "false");
  await manageAgain.getByLabel("Thinking level: deepseek-v4-flash").selectOption("STANDARD");
  await manageAgain.getByRole("button", { name: "Save", exact: true }).click();
  await manageAgain.waitFor({ state: "hidden" });
  assert.equal(modelSettingsWrites, 2);
  assert.equal(await page.locator('[data-reasoning-depth]').getAttribute("data-reasoning-depth"), "STANDARD");
  await page.screenshot({ path: ".next/model-settings.png" });
  descriptionQuotaExhausted = true;
  const descriptionQuotaStart = descriptionRequests.length;
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "zh-CN"); window.dispatchEvent(new Event("ardor:locale-change")); });
  for (let attempt = 0; attempt < 60 && descriptionRequests.length === descriptionQuotaStart; attempt += 1) {
    await page.waitForTimeout(50);
  }
  await page.waitForTimeout(500);
  const descriptionQuotaCalls = descriptionRequests.length - descriptionQuotaStart;
  assert.ok(descriptionQuotaCalls >= 1 && descriptionQuotaCalls <= 2,
    `A quota denial must stop the remaining automatic model research, got ${descriptionQuotaCalls}`);
  assert.equal(await chineseDepth.getByText("正在调研").count(), 0, "Pending summaries must not render placeholder text");
  assert.equal(await chineseDepth.getByText("暂无简述").count(), 0, "Failed summaries must not render placeholder text");
  await page.evaluate(() => { localStorage.setItem("ardor:locale", "en"); window.dispatchEvent(new Event("ardor:locale-change")); });
  await page.waitForTimeout(500);
  assert.equal(descriptionRequests.length - descriptionQuotaStart, descriptionQuotaCalls,
    "A stopped description budget must not resume automatically in the same page session");
  assert.equal(await depth.getByText("Researching model", { exact: true }).count(), 0);
  assert.equal(await depth.getByText("No model summary", { exact: true }).count(), 0);
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

  const nav = page.getByRole("navigation", { name: "Workspaces", exact: true });
  await page.getByRole("button", { name: "More workspaces" }).click();
  await page.getByRole("button", { name: "Customize sidebar", exact: true }).click();
  const customizeSidebar = page.getByRole("dialog", { name: "Customize sidebar", exact: true });
  await customizeSidebar.waitFor();
  assert.ok(await customizeSidebar.getByRole("checkbox").count() > 3,
    "Sidebar customization must expose the actual module choices");
  await page.keyboard.press("Escape");
  await customizeSidebar.waitFor({ state: "hidden" });
  await page.screenshot({ path: ".next/workspace-chat.png" });
  await page.reload();
  await nav.getByRole("link", { name: "Chat", exact: true }).waitFor();
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
  await services.getByRole("button", { name: /^Web search\b/ }).click();
  assert.equal(await page.locator('#personal-api-primary-llm input[name="model"]').isVisible(), false);
  await services.getByRole("button", { name: /^Primary LLM\b/ }).click();
  assert.equal(await page.locator('#personal-api-primary-llm input[name="model"]').inputValue(), "Unsaved model");
  await categories.getByRole("button", { name: "General & sidebar" }).click();
  await page.reload();
  userId = "workspace-other";
  await page.reload();
  await nav.getByRole("link", { name: "Job search", exact: true }).waitFor();
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
  await page.getByRole("button", { name: "New course", exact: true }).click();
  await page.getByRole("button", { name: "Change service" }).waitFor();
  assert.equal(await draftBox.inputValue(), "Recommend some courses for me");
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
  await sidebar.getByRole("button", { name: "Ardor", exact: true }).click();
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
  permissions = [];
  await page.getByRole("button", { name: "Send", exact: true }).click();
  for (let attempt = 0; attempt < 100 && !releaseStream; attempt++) await page.waitForTimeout(20);
  assert.ok(releaseStream, "Synthetic stream must be in flight");
  const toolPermissions = page.getByRole("region", { name: "Tool permissions", exact: true });
  assert.equal(await toolPermissions.count(), 0, "Ordinary reads must not show permission prompts");
  assert.equal(permissionDecisions.length, 0);
  permissions = [{ id: "permission-date", tool: "update_learning_plan", arguments: JSON.stringify({ planId: "owned-plan", scheduledAt: "2026-10-19T01:00:00Z" }), status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
  const suggestedOperations = page.getByRole("region", { name: "Suggested operations", exact: true });
  await suggestedOperations.getByRole("button", { name: "Retry target lookup", exact: true }).waitFor();
  assert.equal(await suggestedOperations.getByRole("button", { name: "Confirm", exact: true }).isDisabled(), true, "Do not confirm an unidentified target");
  assert.equal(permissionDecisions.length, 0);
  targetLookupFails = false;
  await suggestedOperations.getByRole("button", { name: "Retry target lookup", exact: true }).click();
  await suggestedOperations.getByRole("heading", { name: "Change a learning plan · JVM memory", exact: true }).waitFor();
  await suggestedOperations.getByText("Lesson content and practice records are preserved.", { exact: true }).waitFor();
  assert.equal(calendarWrites, 0, "A suggestion must not write business data");
  const operationBounds = await suggestedOperations.boundingBox();
  const inputBounds = await draftBox.boundingBox();
  assert.ok(operationBounds.y + operationBounds.height <= inputBounds.y, "Operation suggestions sit above the composer");
  await suggestedOperations.getByRole("button", { name: "Reject", exact: true }).click();
  await suggestedOperations.waitFor({ state: "hidden" });
  assert.deepEqual(permissionDecisions.map(value => value.allow), [false]);
  permissions = [{ id: "permission-date", tool: "update_learning_plan", arguments: "{}", status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
  await page.waitForTimeout(1300);
  assert.equal(await suggestedOperations.count(), 0, "A stale poll response must not revive a decided approval");
  permissions = [];
  assert.equal(await draftBox.isEnabled(), true);
  await draftBox.fill("Next message draft survives completion");
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  await page.getByRole("dialog", { name: "Model and thinking depth options" }).getByRole("button", { name: "Manage models", exact: true }).click();
  const effortDialog = page.getByRole("dialog", { name: "Manage models" });
  await effortDialog.getByLabel("Thinking level: deepseek-v4-flash").selectOption("QUICK");
  await effortDialog.getByRole("button", { name: "Save", exact: true }).click();
  await effortDialog.waitFor({ state: "hidden" });
  assert.equal(await page.getByRole("button", { name: "Model and thinking depth", exact: true }).getAttribute("data-reasoning-depth"), "QUICK");
  await draftBox.click();
  releaseStream();
  await page.getByText("Synthetic agent reply", { exact: true }).waitFor();
  await assertComposerActions("Conversation with messages");
  // The live progress card is replaced after completion. Inspect the persisted
  // assistant article so a click cannot land on the soon-to-be-unmounted card.
  const replyArticle = page.getByRole("article").filter({ hasText: "Synthetic agent reply" });
  const replyTrace = replyArticle.locator("[data-execution-trace] > div > details");
  assert.equal(await page.locator("[data-execution-trace]").count(), 1, "Live state must not survive beside the committed trace");
  assert.equal(await replyTrace.getAttribute("open"), null, "Process defaults to collapsed");
  assert.ok(await replyArticle.evaluate(element => {
    const trace = element.querySelector('[data-execution-trace]');
    const answer = element.querySelector('.ardor-markdown');
    return !!(trace.compareDocumentPosition(answer) & Node.DOCUMENT_POSITION_FOLLOWING);
  }), "Execution process must precede the final answer");
  await replyTrace.locator(":scope > summary").click();
  await replyTrace.getByText("I'll inspect the plans.", { exact: true }).waitFor();
  assert.equal(await replyArticle.locator(".ardor-markdown").innerText(), "Synthetic agent reply", "Intermediate text must not be repeated in the final answer");
  const reasoning = replyTrace.locator("details");
  await reasoning.locator("summary").click();
  await reasoning.getByText("Synthetic public reasoning", { exact: true }).waitFor();
  assert.deepEqual(sentMessage.contextReferences, [{ type: "SERVICE", id: "f1111111-1111-3111-8111-111111111111" }]);
  const sentBubble = page.getByRole("article").filter({ hasText: "Make a study outline" });
  await sentBubble.getByRole("link", { name: "Learning", exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "Next message draft survives completion");
  await replyTrace.getByText("Approval wait 40.0 s", { exact: true }).waitFor();
  await replyTrace.getByText("Not executed", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Change service" }).count(), 0);
  await page.reload();
  await sentBubble.getByRole("link", { name: "Learning", exact: true }).waitFor();
  assert.equal(await page.locator("[data-execution-trace]").count(), 1);
  assert.equal(await replyTrace.getAttribute("open"), null, "Reload restores one collapsed trace");
  assert.equal(await draftBox.inputValue(), "Next message draft survives completion");
  // Reconnect to an accepted run: it is history, never the next-message draft.
  activeRun = { id: "accepted-run", conversationId: conversation.id, message: "Accepted message after disconnect", status: "RUNNING", label: "Waiting", showCommentary: true, createdAt: new Date().toISOString() };
  permissions = [{ id: "permission-two", tool: "add_memory", arguments: '{"memory":"Fixture"}', status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
  await page.evaluate(({ accountId, conversationId }) => {
    window.sessionStorage.setItem(`ardor:outgoing:${accountId}:${conversationId}`, JSON.stringify({
      id: "pending-user-message-accepted-run", conversationId, role: "USER", content: "Accepted message after disconnect", createdAt: new Date().toISOString(),
    }));
  }, { accountId: userId, conversationId: conversation.id });
  await page.reload();
  await page.getByRole("article").filter({ hasText: "Accepted message after disconnect" }).waitFor();
  await page.getByText("Recovered live commentary", { exact: true }).waitFor();
  assert.equal(await draftBox.inputValue(), "Next message draft survives completion");
  assert.equal(await draftBox.isEnabled(), true);
  assert.equal(await toolPermissions.count(), 0, "Ordinary writes must not show permission prompts");
  assert.equal(permissionDecisions.length, 1);
  await suggestedOperations.getByRole("heading", { name: "Remember this information", exact: true }).waitFor();
  permissionDecisionFails = true;
  await suggestedOperations.getByRole("button", { name: "Confirm", exact: true }).click();
  await suggestedOperations.getByText("Synthetic confirmation failure", { exact: true }).waitFor();
  await page.waitForTimeout(2200);
  await suggestedOperations.getByText("Synthetic confirmation failure", { exact: true }).waitFor();
  assert.equal(permissionDecisions.length, 1, "Failed decisions do not grant execution");
  permissionDecisionFails = false;
  await suggestedOperations.getByRole("button", { name: "Confirm", exact: true }).click();
  await suggestedOperations.waitFor({ state: "hidden" });
  assert.deepEqual(permissionDecisions.map(value => value.allow), [false, true]);
  for (const tool of ["update_memory_card", "review_memory_card", "suspend_memory_card"]) {
    permissions = [{ id: `permission-${tool}`, tool, arguments: JSON.stringify({ cardId: "card-fixture" }), status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
    await suggestedOperations.getByRole("heading").filter({ hasText: "Synthetic card to review" }).waitFor();
    await suggestedOperations.getByRole("button", { name: "Reject", exact: true }).click();
    await suggestedOperations.waitFor({ state: "hidden" });
  }
  for (const tool of ["submit_interview_answer", "finish_interview", "cancel_interview"]) {
    permissions = [{ id: `permission-${tool}`, tool, arguments: JSON.stringify({ interviewId: "interview-fixture" }), status: "PENDING", expiresAt: new Date(Date.now() + 120000).toISOString() }];
    await suggestedOperations.getByRole("heading").filter({ hasText: "Synthetic Java interview" }).waitFor();
    assert.equal(await suggestedOperations.getByRole("button", { name: "Confirm", exact: true }).isEnabled(), true);
    await suggestedOperations.getByRole("button", { name: "Reject", exact: true }).click();
    await suggestedOperations.waitFor({ state: "hidden" });
  }
  activeRun = { ...activeRun, id: "queued-active-run", message: "A new queued instruction after the original stopped", progressPhase: "THINKING" };
  await page.reload();
  await page.getByRole("article").filter({ hasText: activeRun.message }).waitFor();
  await page.waitForTimeout(6500); // More than two real recovery polls with a stale predecessor outbox.
  await page.getByText("Thinking", { exact: true }).waitFor();
  assert.equal(await page.getByRole("article").filter({ hasText: "Accepted message after disconnect" }).count(), 1);
  assert.equal(await page.getByRole("article").filter({ hasText: activeRun.message }).count(), 1);
  await draftBox.fill("Queued follow-up one");
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/api/agent/messages/steer") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Send", exact: true }).click(),
  ]);
  await page.getByRole("region", { name: "Message queue", exact: true }).getByText("Queued follow-up one", { exact: true }).waitFor();
  assert.equal(queuedRequests[0].reasoningDepth, "QUICK");
  assert.equal(steerRequests.length, 1);
  assert.equal(steerRequests[0].interruptedRunId, "queued-active-run");
  assert.equal(await page.getByRole("button", { name: "Interrupt and handle now", exact: true }).count(), 0);
  await draftBox.fill("Queued follow-up two");
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).click();
  await page.getByRole("dialog", { name: "Model and thinking depth options" }).getByRole("button", { name: "Manage models", exact: true }).click();
  const deepDialog = page.getByRole("dialog", { name: "Manage models" });
  await deepDialog.getByLabel("Thinking level: deepseek-v4-flash").selectOption("DEEP");
  await deepDialog.getByRole("button", { name: "Save", exact: true }).click();
  await deepDialog.waitFor({ state: "hidden" });
  assert.equal(await page.getByRole("button", { name: "Model and thinking depth", exact: true }).getAttribute("data-reasoning-depth"), "DEEP");
  await draftBox.click();
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/api/agent/messages/steer") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Send", exact: true }).click(),
  ]);
  assert.equal(queuedRequests[1].reasoningDepth, "DEEP");
  assert.notEqual(queuedRequests[0].requestId, queuedRequests[1].requestId);
  await page.reload();
  await page.getByRole("region", { name: "Message queue", exact: true }).getByText("Queued follow-up two", { exact: true }).waitFor();
  assert.equal(steerRequests.length, 2, "Sending a correction steers directly; no second manual action");
  assert.equal(await page.getByRole("button", { name: "Remove from queue", exact: true }).count(), 0);
  await Promise.all([
    page.waitForResponse((response) => response.url().endsWith("/runs/queued-active-run/cancel") && response.request().method() === "POST"),
    page.getByRole("button", { name: "Stop", exact: true }).click(),
  ]);
  assert.equal(cancelCalls, 1);
  await page.reload();
  await page.getByText("Incomplete reply · saved before execution stopped", { exact: true }).click();
  await page.getByText("Saved incomplete answer", { exact: true }).waitFor();
  await page.getByText("Execution stopped", { exact: true }).click();
  await page.getByText("Recovered read plans", { exact: true }).waitFor();
  assert.equal(await page.getByRole("button", { name: "Model and thinking depth", exact: true }).isEnabled(), true);
  activeRun = null;
  queueItems = [];
  retryProbe = true;
  await page.reload();
  await draftBox.fill("Stop during retry backoff");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  const retryTrace = page.getByText("Waiting for model", { exact: true }).locator("xpath=ancestor::details[1]");
  if (await retryTrace.getAttribute("open") === null) await retryTrace.locator(":scope > summary").click();
  await page.getByText(/Reconnecting · retry 1\/5/).waitFor();
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  await page.getByText("Stopped. Any operation already started may still finish; check its result.", { exact: true }).waitFor();
  await page.waitForTimeout(2200);
  assert.equal(retryProbeCalls, 1, "Stop must prevent the next retry POST");
  assert.equal(cancelCalls, 1, "Unaccepted requests must not receive server cancellation");
  await page.waitForTimeout(3200);
  assert.equal(await page.getByText("Stopped. Any operation already started may still finish; check its result.", { exact: true }).count(), 0, "Stop feedback disappears rather than pinning an error above the composer");
  retryProbe = false;
  userId = "different-outbox-owner";
  await page.reload();
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).waitFor();
  assert.equal(await page.getByRole("article").filter({ hasText: "Accepted message after disconnect" }).count(), 0);
  learningPlans = [{ id: "delete-fixture", concept: "Synthetic JVM lesson", status: "COMPLETED", sourceType: "MANUAL", scheduledAt: new Date().toISOString(), lesson: { summary: "Fixture" }, exercises: [], lastScore: null, attemptCount: 0 }];
  await page.goto(`${origin}/app/learning`);
  await page.getByRole("button", { name: "Delete plan", exact: true }).click();
  assert.equal(learningDeleteCalls, 0);
  await page.getByRole("dialog", { name: "Delete learning plan?" }).getByRole("button", { name: "Cancel", exact: true }).click();
  assert.equal(learningDeleteCalls, 0);
  await page.getByRole("button", { name: "Delete plan", exact: true }).click();
  learningDeleteFails = true;
  await page.getByRole("button", { name: "Delete permanently", exact: true }).click();
  await page.getByRole("dialog").getByRole("alert").waitFor();
  learningDeleteFails = false;
  await page.getByRole("button", { name: "Delete permanently", exact: true }).click();
  await page.getByText("Start with one concept", { exact: true }).waitFor();
  assert.equal(learningDeleteCalls, 2);
  await page.goto(`${origin}/app/recaps`);
  await page.getByRole("button", { name: "Organize a recap", exact: true }).waitFor();
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
  await page.getByRole("button", { name: "Model and thinking depth", exact: true }).waitFor();
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
  await page.getByRole("heading", { name: "Make today count.", exact: true }).waitFor();
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
  const savedDecision = page.waitForResponse(response => response.url().endsWith("/proposal-resolution") && response.request().method() === "PUT");
  await deletionDock.getByRole("button", { name: "Keep", exact: true }).click();
  await savedDecision;
  await deletionDock.waitFor({ state: "detached" });
  assert.equal(deletionCalls, 0);
  await page.waitForFunction(() => !document.querySelector('[aria-label="Deletion confirmation"]'));
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.reload();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).waitFor();
  assert.equal(await deletionDock.count(), 0, "Kept proposals must not revive on reload");
  displayedMessages[0].id = "new-deletion-proposal";
  displayedMessages[0].proposalResolutions = {};
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
  await page.getByText('Deleted “Synthetic career preference”.', { exact: true }).waitFor();
  await page.waitForTimeout(5200);
  assert.equal(await page.getByText('Deleted “Synthetic career preference”.', { exact: true }).count(), 0);
  assert.equal(await page.getByText('Deleted · Synthetic career preference', { exact: true }).isVisible(), false, "Result belongs inside the collapsed original trace");
  await page.reload();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).waitFor();
  assert.equal(await deletionDock.count(), 0, "Completed deletion must not revive on reload");
  displayedMessages[0] = { ...displayedMessages[0], id: "legacy-old-proposal", proposalResolutions: {}, createdAt: "2026-01-01T00:00:00Z" };
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.reload();
  await page.getByRole("textbox", { name: "Message Ardor", exact: true }).waitFor();
  assert.equal(await deletionDock.count(), 0, "Old legacy proposals without a recoverable decision must expire, not prompt again");
  displayedMessages[0] = { ...displayedMessages[0], id: "remote-decision-proposal", proposalResolutions: {}, createdAt: new Date().toISOString() };
  activeRun = { id: "remote-decision-run", conversationId: conversation.id, message: "Synthetic ongoing task", status: "RUNNING", label: "Waiting", createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
  const restoredProgress = page.waitForResponse(response => response.url().endsWith("/runs/remote-decision-run/progress"));
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.reload();
  await deletionDock.waitFor();
  await restoredProgress;
  displayedMessages[0].proposalResolutions = { "memory_item:/api/agent/memory/items/dock-fixture": "DISMISSED" };
  activeRun = { ...activeRun, status: "COMPLETED" };
  await deletionDock.waitFor({ state: "hidden" });
  assert.equal(await deletionDock.count(), 0, "A durable remote decision must take precedence over in-memory pending state");
  activeRun = null;
  displayedMessages = [];
  quotaProbe = true;
  await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
  await page.goto(`${origin}/app?conversation=${conversation.id}`);
  const quotaInput = page.getByRole("textbox", { name: "Message Ardor", exact: true });
  await quotaInput.fill("Synthetic quota request");
  const quotaResponse = page.waitForResponse(response => response.url().endsWith("/api/agent/messages/stream"));
  await page.getByRole("button", { name: "Send", exact: true }).click();
  assert.equal((await quotaResponse).status(), 429);
  try {
    await page.getByText("Synthetic quota limit reached", { exact: true }).waitFor({ timeout: 5000 });
  } catch (error) {
    console.error(JSON.stringify({ quotaProbeCalls, url: page.url(), alerts: await page.getByRole("alert").allTextContents(),
      content: (await page.locator("main").innerText()).slice(-4000) }));
    await page.screenshot({ path: ".next/quota-error-failure.png" });
    throw error;
  }
  await page.waitForTimeout(2200);
  assert.equal(quotaProbeCalls, 1, "Quota denial must not automatically resend the model request");
  assert.equal(await quotaInput.isEnabled(), true, "A rejected request must not lock the composer");
  await quotaInput.fill("Editable follow-up draft");
  assert.equal(await quotaInput.inputValue(), "Editable follow-up draft");
  assert.deepEqual(errors, []);
  console.log("Workspace UI: rename, retry, sidebar persistence/isolation, settings, drafts and visible non-retried quota rejection passed.");
} finally {
  await browser?.close();
  server.kill();
  if (server.exitCode === null) await new Promise((resolve) => server.once("close", resolve));
}
