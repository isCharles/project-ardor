import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic workspace: no model call, no real account, no real LaTeX compile.
const origin = "http://127.0.0.1:3125";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3125"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const projectId = "30000000-0000-4000-8000-000000000001";
const template = "\\documentclass{article}\n\\begin{document}\n第一版\n\\end{document}\n";
const saved = "\\documentclass{article}\n\\begin{document}\n第二版：补充项目结果\n\\end{document}\n";
let files = { "main.tex": template };
let revisionNo = 1;
let frozenRevision = null;
let conflictOnce = false;
const saves = [], freezes = [], previews = [];
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));

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
    if (method === "POST" && path === "/api/latex/projects") {
      return route.fulfill({ status: 201, json: { projectId, title: "我的 LaTeX 简历", mainFile: "main.tex", revisionNo, files, sha256: "x", frozenRevision } });
    }
    if (method === "POST" && path.endsWith("/revisions")) {
      const body = JSON.parse(route.request().postData() ?? "{}");
      if (conflictOnce) { conflictOnce = false; return route.fulfill({ status: 409, json: { message: "工作区已被更新，请基于最新修订重新提交" } }); }
      saves.push(body);
      assert.equal(body.baseRevision, revisionNo, "a save must name the revision it started from");
      revisionNo += 1;
      files = body.files;
      return route.fulfill({ status: 201, json: { projectId, title: "我的 LaTeX 简历", mainFile: "main.tex", revisionNo, files, sha256: "y", frozenRevision } });
    }
    if (method === "POST" && path.endsWith("/freeze")) {
      const body = JSON.parse(route.request().postData() ?? "{}");
      freezes.push(body);
      frozenRevision = body.revisionNo;
      return route.fulfill({ json: { id: projectId, title: "我的 LaTeX 简历", mainFile: "main.tex", currentRevision: revisionNo, frozenRevision, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() } });
    }
    if (method !== "GET") return route.fulfill({ json: {} });
    let body = [];
    if (path === "/api/auth/me") body = { id: "latex-owner", email: "latex@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "latex-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path.startsWith("/api/background-tasks/")) body = { activeCount: 0, items: [] };
    else if (path === "/api/latex/projects") body = [{ id: projectId, title: "我的 LaTeX 简历", mainFile: "main.tex", currentRevision: revisionNo, frozenRevision, updatedAt: new Date().toISOString() }];
    else if (path === `/api/latex/projects/${projectId}/revisions`) {
      body = Array.from({ length: revisionNo }, (_, index) => ({ revisionNo: revisionNo - index, sha256: "s", message: null, createdAt: new Date(Date.now() - index * 60_000).toISOString() }));
    }
    else if (path === `/api/latex/projects/${projectId}`) {
      const requested = Number(url.searchParams.get("revision") ?? revisionNo);
      body = { projectId, title: "我的 LaTeX 简历", mainFile: "main.tex", revisionNo: requested, files: requested === revisionNo ? files : { "main.tex": template }, sha256: "z", frozenRevision };
    }
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    return route.fulfill({ json: body });
  });

  const page = await context.newPage();
  const bodyText = async () => (await page.locator("main").innerText().catch(() => "")).replace(/\s+/g, " ");
  const waitFor = async (probe, label, timeout = 15000) => {
    const end = Date.now() + timeout;
    while (Date.now() < end) { if (await probe()) return; await delay(120); }
    throw new Error(`timed out waiting for ${label}; text=${await bodyText()} previews=${JSON.stringify(previews)} errors=${JSON.stringify(consoleErrors)}`);
  };
  const editor = () => page.getByRole("textbox", { name: /LaTeX source|LaTeX 源文件/ });

  await page.goto(`${origin}/app/latex`);
  await waitFor(async () => (await bodyText()).includes("我的 LaTeX 简历"), "the existing workspace");
  assert.match(await editor().inputValue(), /第一版/, "the saved source is what the editor shows");

  // Editing and saving appends a revision instead of overwriting the first one.
  await editor().fill(saved);
  await page.getByRole("button", { name: /^Save$|^保存$/ }).click();
  await waitFor(() => saves.length === 1, "the save request");
  assert.equal(saves[0].files["main.tex"], saved);
  await waitFor(async () => (await bodyText()).includes("v2"), "the new revision in the history list");

  // Preview compiles the saved revision through the server route.
  await page.getByRole("button", { name: /Preview|预览 PDF/ }).click();
  await waitFor(() => previews.some(entry => entry.includes(`/latex-api/${projectId}?revision=2`)), "the preview compile call");
  await waitFor(async () => (await page.locator("iframe").count()) >= 1, "the PDF iframe");

  // A stale base revision must surface the conflict instead of silently overwriting.
  conflictOnce = true;
  await editor().fill(saved.replace("第二版", "第三版"));
  await page.getByRole("button", { name: /^Save$|^保存$/ }).click();
  await waitFor(async () => /请先刷新再修改|Reload before editing/.test(await bodyText()), "the conflict message");

  // Retrying after a conflict succeeds and appends the retried revision.
  await page.getByRole("button", { name: /^Save$|^保存$/ }).click();
  await waitFor(() => saves.length === 2, "the retried save");

  // Freezing needs an explicit confirmation and records the shown revision.
  await page.getByRole("button", { name: /^Freeze$|^定稿$/ }).click();
  const dialog = page.getByRole("dialog");
  await dialog.waitFor();
  await dialog.getByRole("button", { name: /^Freeze$|^冻结$/ }).click();
  await waitFor(() => freezes.length === 1, "the freeze request");
  assert.equal(freezes[0].revisionNo, revisionNo);

  // Restoring an older revision loads its content as an unsaved draft.
  await page.getByRole("button", { name: /^Load$|^载入$/ }).first().click();
  await waitFor(async () => /已载入第 \d+ 版|Revision \d+ loaded/.test(await bodyText()), "the restore notice");
  assert.match(await editor().inputValue(), /第一版/);
  await waitFor(async () => /未保存|unsaved/.test(await bodyText()), "the unsaved marker");

  assert.deepEqual(consoleErrors, []);
  console.log("LaTeX workspace UI: template, append revision, preview compile, conflict, freeze confirmation and revision restore passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
