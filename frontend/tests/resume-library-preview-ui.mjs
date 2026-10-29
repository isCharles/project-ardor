import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// A parsed resume that has never been analyzed must still preview and download.
const origin = "http://127.0.0.1:3131";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3131"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const resumeId = "41000000-0000-4000-8000-000000000001";
const pageRequests = [], analysisRequests = [];
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const resume = {
  id: resumeId, originalFilename: "library-only.pdf", sizeBytes: 2048, parseStatus: "PARSED",
  createdAt: "2026-10-20T08:00:00Z", analysisId: null, analysisStatus: null, analysisError: null,
  overallScore: null, primary: true, displayAnalysisId: null,
};
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
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
  const consoleErrors = [];
  context.on("page", page => page.on("pageerror", error => consoleErrors.push(String(error))));
  await context.route("**/api/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (path === `/api/resumes/${resumeId}/preview/pages/1`) {
      pageRequests.push(path);
      return route.fulfill({ contentType: "image/png", body: png, headers: { "X-Resume-Page-Count": "1" } });
    }
    if (path === `/api/resumes/${resumeId}/file`) {
      fileRequests.push(path);
      return route.fulfill({ contentType: "application/pdf", headers: { "Content-Disposition": 'attachment; filename="library-only.pdf"' }, body: "%PDF-1.4\nsynthetic\n%%EOF\n" });
    }
    if (path.endsWith("/analysis")) analysisRequests.push(path);
    let body = [];
    if (path === "/api/auth/me") body = { id: "library-owner", email: "library@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "library-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path.startsWith("/api/background-tasks/")) body = { activeCount: 0, items: [] };
    else if (path === "/api/resumes") body = [resume];
    else if (path.endsWith("/analysis/history")) body = [];
    else if (path === "/api/opportunities") body = [];
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    return route.fulfill({ json: body });
  });

  const page = await context.newPage();
  await page.goto(`${origin}/app/resumes`);
  await page.getByText("library-only.pdf").first().waitFor();
  const card = page.getByRole("button", { name: /library-only\.pdf/ }).first();
  await card.hover();
  await page.getByRole("button", { name: /Preview resume|查看简历/ }).first().click();
  await page.getByRole("complementary", { name: /Original resume|原简历/ }).waitFor();
  await page.getByAltText(/Original resume page|原简历页面/).waitFor();
  assert.ok(pageRequests.length >= 1, "the sidebar renders the stored PDF page");
  assert.deepEqual(analysisRequests, [], "opening the preview must not require an analysis");

  const downloadLink = page.getByRole("complementary", { name: /Original resume|原简历/ }).getByRole("link", { name: /Download original|下载原文件/ });
  assert.equal(await downloadLink.getAttribute("href"), `/api/resumes/${resumeId}/file`);
  assert.equal(await downloadLink.getAttribute("download"), "library-only.pdf");

  await page.getByRole("button", { name: /Close preview|关闭预览/ }).click();
  await page.getByRole("button", { name: /^View original$|^查看原简历$/ }).click();
  await page.getByRole("complementary", { name: /Original resume|原简历/ }).waitFor();
  await page.getByRole("button", { name: /Close preview|关闭预览/ }).click();
  const plainDownload = page.getByRole("link", { name: /Download original|下载原文件/ }).first();
  assert.equal(await plainDownload.getAttribute("href"), `/api/resumes/${resumeId}/file`);

  assert.deepEqual(consoleErrors, []);
  console.log("Library preview: a never-analyzed resume opens in the sidebar, downloads its original file and offers both actions on the report-less view.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
