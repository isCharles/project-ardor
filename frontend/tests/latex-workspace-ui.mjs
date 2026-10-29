import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic workspace: no model call, no real account, no real LaTeX compile.
const origin = "http://127.0.0.1:3125";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3125"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const projectId = "30000000-0000-4000-8000-000000000001";
const conversationId = "30000000-0000-4000-8000-0000000000c1";
let frozenRevision = null;
const freezes = [], previews = [], uploads = [], streams = [];
let conversations = 0;
const resumes = [];
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const project = () => ({ id: projectId, title: "我的简历", mainFile: "main.tex", currentRevision: 1, frozenRevision, updatedAt: new Date().toISOString() });
const snapshot = () => ({ projectId, title: "我的简历", mainFile: "main.tex", revisionNo: 1, files: { "main.tex": "\\documentclass{article}\n\\begin{document}\n第一版\n\\end{document}\n" }, sha256: "x", frozenRevision });

try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await delay(100);
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const consoleErrors = [];
  context.on("page", page => page.on("pageerror", error => consoleErrors.push(String(error))));
  await context.route("**/latex-api/**", async route => {
    previews.push(route.request().url());
    return route.fulfill({ contentType: "application/pdf", body: "%PDF-1.4\nsynthetic\n%%EOF\n" });
  });
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const method = route.request().method();
    if (method === "POST" && path === "/api/agent/conversations") {
      conversations += 1;
      return route.fulfill({ status: 201, json: { id: conversationId, title: "新对话", pinned: false, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() } });
    }
    if (method === "POST" && path === "/api/agent/messages/stream") {
      const body = JSON.parse(route.request().postData() ?? "{}");
      streams.push(body);
      const events = [
        { type: "delta", content: "我会先把项目经历改得更具体。" },
        { type: "tool_start", toolName: "read_latex_project", label: "读取当前修订" },
        { type: "tool_end", toolName: "read_latex_project", label: "读取当前修订", elapsedMs: 12 },
        { type: "delta", content: "已按你的要求调整。" },
        { type: "done", messageId: "assistant-1", conversationId, content: "已按你的要求调整。" },
      ];
      return route.fulfill({ status: 200, headers: { "content-type": "text/event-stream" }, body: events.map(event => `data: ${JSON.stringify(event)}\n\n`).join("") });
    }
    if (method === "POST" && path === `/api/latex/projects/${projectId}/freeze`) {
      const body = JSON.parse(route.request().postData() ?? "{}");
      freezes.push(body);
      frozenRevision = body.revisionNo;
      return route.fulfill({ json: { ...project(), frozenRevision } });
    }
    if (method === "POST" && path === "/api/resumes") {
      const buffer = route.request().postDataBuffer()?.toString("utf8") ?? "";
      const name = buffer.match(/filename="([^"]+)"/)?.[1] ?? "unknown.pdf";
      uploads.push(name);
      const item = { id: `resume-${uploads.length}`, originalFilename: name, primary: false, displayAnalysisId: null };
      resumes.push(item);
      return route.fulfill({ status: 201, json: item });
    }
    if (method !== "GET") return route.fulfill({ json: {} });
    let body = [];
    if (path === "/api/auth/me") body = { id: "latex-owner", email: "latex@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "latex-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path.startsWith("/api/background-tasks/")) body = { activeCount: 0, items: [] };
    else if (path === "/api/latex/projects") body = [project()];
    else if (path === `/api/latex/projects/${projectId}`) body = snapshot();
    else if (path === "/api/resumes") body = resumes;
    else if (path === "/api/agent") body = { conversationId: url.searchParams.get("conversationId") ? conversationId : null, llmConfigured: true, displayName: "Synthetic owner", conversations: [], archivedConversations: [], memory: { content: "", updatedAt: null }, messages: streams.length ? [{ id: "user-1", role: "USER", content: streams[0].message, createdAt: new Date().toISOString() }, { id: "assistant-1", role: "ASSISTANT", content: "已按你的要求调整。", createdAt: new Date().toISOString() }] : [] };
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    return route.fulfill({ json: body });
  });

  const page = await context.newPage();
  const bodyText = async () => (await page.locator("main").innerText().catch(() => "")).replace(/\s+/g, " ");
  const waitFor = async (probe, label, timeout = 20000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (await probe()) return; await delay(120); }
    throw new Error(`timed out waiting for ${label}; text=${await bodyText()} previews=${JSON.stringify(previews)} uploads=${JSON.stringify(uploads)} errors=${JSON.stringify(consoleErrors)}`);
  };

  await page.goto(`${origin}/app/latex`);
  await waitFor(async () => (await bodyText()).includes("我的简历"), "the workspace title");
  await waitFor(() => previews.some(entry => entry.includes(`/latex-api/${projectId}?revision=1`)), "the current version compile");
  await waitFor(async () => (await page.locator("iframe").count()) >= 1, "the version preview iframe");
  assert.match(await bodyText(), /当前版本|Current version/i, "the sidebar names the current version");

  // The chat is the editing surface and must scope the turn to this workspace.
  await page.getByRole("textbox", { name: /Message Ardor|给 Ardor 的消息/ }).fill("把项目经历写得更具体");
  await page.getByRole("textbox", { name: /Message Ardor|给 Ardor 的消息/ }).press("Enter");
  await waitFor(() => streams.length === 1, "the Ardor stream request");
  assert.equal(streams[0].message, "把项目经历写得更具体");
  assert.deepEqual(streams[0].contextReferences, [{ type: "LATEX", id: projectId }], "the turn is scoped to this workspace");
  assert.equal(conversations, 1, "one conversation is created and reused");
  await waitFor(async () => (await bodyText()).includes("已按你的要求调整。"), "the assistant reply");
  await waitFor(async () => (await bodyText()).includes("读取当前修订"), "the tool step chip");

  // Freezing compiles the revision and files the PDF as a resume, so analysis can use it.
  await page.getByRole("button", { name: /^Freeze$|^定稿$/ }).click();
  await waitFor(() => freezes.length === 1, "the freeze request");
  assert.equal(freezes[0].revisionNo, 1);
  await waitFor(() => uploads.length === 1, "the frozen PDF upload");
  assert.match(uploads[0], /v1\.pdf$/, "the uploaded resume is named after the frozen version");
  await waitFor(async () => /已在简历分析|In Resume analysis/.test(await bodyText()), "the resume analysis link");

  // Reloading finds the same frozen PDF and does not upload a duplicate.
  await page.reload();
  await waitFor(async () => /已在简历分析|In Resume analysis/.test(await bodyText()), "the frozen resume on reload");
  await waitFor(async () => (await bodyText()).includes("把项目经历写得更具体"), "the saved conversation");
  assert.equal(uploads.length, 1, "a frozen version is only uploaded once");

  assert.deepEqual(consoleErrors, []);
  console.log("Resume workspace UI: Ardor chat, current-version preview, freeze-to-analysis upload and reload dedupe passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
