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
let projectTitle = "我的简历";
let archived = false;
const archivedProjects = [];
const freezes = [], previews = [], uploads = [], streams = [], renames = [], archives = [], restores = [];
let conversations = 0;
const resumes = [];
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const project = () => ({ id: projectId, title: projectTitle, mainFile: "main.tex", currentRevision: 1, frozenRevision, updatedAt: new Date().toISOString() });
const snapshot = () => ({ projectId, title: projectTitle, mainFile: "main.tex", revisionNo: 1, files: { "main.tex": "\\documentclass{article}\n\\begin{document}\n第一版\n\\end{document}\n" }, sha256: "x", frozenRevision });
const png = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Zl1sAAAAASUVORK5CYII=", "base64");

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
    const url = new URL(route.request().url());
    previews.push(url.toString());
    if (url.searchParams.has("page")) {
      return route.fulfill({ contentType: "image/png", body: png, headers: { "X-Page-Count": "1" } });
    }
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
    if (method === "DELETE" && path === `/api/latex/projects/${projectId}`) {
      archives.push(path);
      archived = true;
      archivedProjects.push(project());
      return route.fulfill({ status: 204, body: "" });
    }
    if (method === "POST" && path === `/api/latex/projects/${projectId}/restore`) {
      restores.push(path);
      archived = false;
      archivedProjects.length = 0;
      return route.fulfill({ json: project() });
    }
    if (method === "PATCH" && path === `/api/latex/projects/${projectId}`) {
      const body = JSON.parse(route.request().postData() ?? "{}");
      renames.push(body);
      projectTitle = body.title;
      return route.fulfill({ json: project() });
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
    else if (path === "/api/agent/models") body = [{ id: "PRIMARY", label: "Primary", provider: "synthetic", available: true, nativeControl: false, visible: true }];
    else if (path === "/api/latex/projects") body = archived ? [] : [project()];
    else if (path === "/api/latex/projects/archived") body = archivedProjects;
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
  await waitFor(async () => (await bodyText()).includes("我的简历"), "the resume title");

  // Renaming is available in the header, so duplicate titles can be told apart.
  await page.getByRole("button", { name: /Rename resume|重命名简历/ }).click();
  await page.getByRole("textbox", { name: /Resume name|简历名称/ }).fill("后端实习简历");
  await page.getByRole("button", { name: /^Save$|^保存$/ }).click();
  await waitFor(() => renames.length === 1, "the rename request");
  assert.equal(renames[0].title, "后端实习简历");
  await waitFor(async () => (await bodyText()).includes("后端实习简历"), "the renamed title");

  // The sidebar reuses the rasterized page preview, not the dark PDF viewer.
  await waitFor(() => previews.some(entry => {
    const url = new URL(entry);
    return url.pathname === `/latex-api/${projectId}` && url.searchParams.get("revision") === "1" && url.searchParams.get("page") === "1";
  }), "the page preview request");
  await waitFor(async () => (await page.getByAltText(/Current resume|当前简历/).count()) >= 1, "the page preview image");
  assert.equal(await page.locator("iframe").count(), 0, "the preview is not a PDF iframe");
  const asideText = async () => (await page.getByRole("complementary", { name: /Resume preview|简历预览/ }).innerText()).replace(/\s+/g, " ");
  assert.match(await asideText(), /编辑中|Editing/i, "an editing resume has no version number");
  assert.doesNotMatch(await asideText(), /\bv1\b/);

  // The composer offers the same model picker as the main Ardor chat.
  const modelButton = page.getByRole("button", { name: /Model and thinking depth|模型与思考深度/ });
  await modelButton.waitFor();
  await waitFor(() => modelButton.isEnabled(), "the model picker");

  // The chat is the editing surface and must scope the turn to this resume.
  await page.getByRole("textbox", { name: /Message Ardor|给 Ardor 的消息/ }).fill("把项目经历写得更具体");
  await page.getByRole("textbox", { name: /Message Ardor|给 Ardor 的消息/ }).press("Enter");
  await waitFor(() => streams.length === 1, "the Ardor stream request");
  assert.equal(streams[0].message, "把项目经历写得更具体");
  assert.equal(streams[0].modelChoice, "PRIMARY", "the selected model is sent with the turn");
  assert.equal(streams[0].reasoningDepth, "AUTO");
  assert.deepEqual(streams[0].contextReferences, [{ type: "LATEX", id: projectId }], "the turn is scoped to this resume");
  assert.equal(conversations, 1, "one conversation is created and reused");
  await waitFor(async () => (await bodyText()).includes("已按你的要求调整。"), "the assistant reply");
  await waitFor(async () => (await bodyText()).includes("读取当前修订"), "the tool step chip");

  // Freezing compiles the revision and files the PDF as a resume, so analysis can use it.
  await page.getByRole("button", { name: /^Freeze$|^定稿$/ }).click();
  await waitFor(() => freezes.length === 1, "the freeze request");
  assert.equal(freezes[0].revisionNo, 1);
  await waitFor(() => uploads.length === 1, "the frozen PDF upload");
  assert.match(uploads[0], /后端实习简历 v1\.pdf$/, "the uploaded resume is named after the frozen version");
  await waitFor(async () => /已在简历分析|In Resume analysis/.test(await bodyText()), "the resume analysis link");
  await waitFor(async () => /定稿版本|Final version/i.test(await asideText()), "the frozen version label");
  assert.match(await asideText(), /v1\b/, "a frozen resume shows its version number");

  // Reloading finds the same frozen PDF and does not upload a duplicate.
  await page.reload();
  await waitFor(async () => /已在简历分析|In Resume analysis/.test(await bodyText()), "the frozen resume on reload");
  await waitFor(async () => (await bodyText()).includes("把项目经历写得更具体"), "the saved conversation");
  assert.equal(uploads.length, 1, "a frozen version is only uploaded once");

  // Archiving removes the workspace from the list without deleting its revisions.
  page.on("dialog", dialog => dialog.accept());
  await page.getByRole("button", { name: /Archive resume|归档简历/ }).click();
  await waitFor(() => archives.length === 1, "the archive request");
  assert.equal(archives[0], `/api/latex/projects/${projectId}`);
  await waitFor(async () => /No resume yet|还没有简历/.test(await bodyText()), "the empty workspace state");
  assert.equal(await page.getByRole("combobox", { name: /Switch resume|切换简历/ }).count(), 0, "an archived workspace leaves the switch list");

  // The archived list is the recovery path: a mistaken archive is reversible from the UI.
  await page.getByRole("button", { name: /Archived resumes|已归档简历/ }).click();
  await waitFor(async () => (await page.locator("dialog").innerText()).includes("后端实习简历"), "the archived resume in the dialog");
  await page.getByRole("button", { name: /^Restore$|^恢复$/ }).click();
  await waitFor(() => restores.length === 1, "the restore request");
  assert.equal(restores[0], `/api/latex/projects/${projectId}/restore`);
  await waitFor(async () => (await bodyText()).includes("后端实习简历"), "the restored workspace");
  assert.equal(await page.locator("dialog").count(), 0, "restoring closes the archived list");
  await waitFor(() => previews.some(entry => new URL(entry).pathname === `/latex-api/${projectId}`), "the restored workspace preview");

  assert.deepEqual(consoleErrors, []);
  console.log("Resume workspace UI: Ardor chat, current-version preview, freeze-to-analysis upload, archive and restore passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
