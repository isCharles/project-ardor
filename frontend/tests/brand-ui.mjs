import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { chromium } from "playwright";

// Every brand entrance uses the supplied Ardor mark; no legacy flame or rule mark remains.
const origin = "http://127.0.0.1:3132";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3132"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);

const resume = {
  id: "brand-resume", originalFilename: "brand-resume.pdf", sizeBytes: 2048, parseStatus: "PARSED",
  createdAt: "2026-10-30T08:00:00Z", analysisId: null, analysisStatus: null, analysisError: null,
  overallScore: null, primary: true, displayAnalysisId: null,
};
const serviceTypes = ["PRIMARY_LLM", "EMBEDDING", "ASR", "TTS", "FALLBACK_LLM", "WEB_SEARCH"];
const apiConfigs = serviceTypes.map((serviceType) => ({
  serviceType, configured: false, provider: "", baseUrl: null, model: null, keyHint: null, updatedAt: null,
}));
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
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "brand-owner", email: "brand@ardor.invalid", admin: true };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "brand-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path.startsWith("/api/background-tasks/")) body = { activeCount: 0, items: [] };
    else if (path === "/api/resumes") body = [resume];
    else if (path.endsWith("/analysis/history")) body = [];
    else if (path === "/api/opportunities") body = [];
    else if (path === "/api/admin/overview") body = { counts: {}, databaseAvailable: true, apiConfigs };
    else if (path.startsWith("/api/admin/")) body = [];
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    return route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const consoleErrors = [];
  // The offline page is a desktop surface and expects the Electron preload; only
  // the web pages must be free of page errors in this browser test.
  page.on("pageerror", error => consoleErrors.push(String(error)));
  const brandMarks = () => page.locator('[data-brand-mark="ardor"]');
  const legacyMarks = async () => [
    await page.locator(".lucide-flame").count(),
    await page.locator('svg[viewBox="0 0 14 18"]').count(),
    (await page.locator("main").innerText().catch(() => "")).includes("✦") ? 1 : 0,
  ];

  await page.goto(`${origin}/login`);
  await page.getByRole("heading", { name: /Welcome back|欢迎回来/ }).waitFor();
  assert.equal(await brandMarks().count(), 1, "login shows the official mark");
  assert.deepEqual(await legacyMarks(), [0, 0, 0], "login carries no legacy flame or rule mark");
  await page.screenshot({ path: ".next/brand-login.png", fullPage: true });

  const favicon = await context.request.get(`${origin}/favicon.ico`);
  assert.equal(favicon.status(), 200);
  assert.match(favicon.headers()["content-type"], /image\/(x-icon|vnd\.microsoft\.icon)/);
  const ico = await favicon.body();
  assert.equal(ico.readUInt16LE(2), 1, "favicon.ico is an icon file");
  const sizes = Array.from({ length: ico.readUInt16LE(4) }, (_, index) => {
    const width = ico.readUInt8(6 + index * 16);
    return width === 0 ? 256 : width;
  });
  assert.deepEqual(sizes, [16, 24, 32, 48], "favicon carries the four required sizes");

  const icon = await context.request.get(`${origin}/icon.svg`);
  assert.equal(icon.status(), 200);
  assert.match(icon.headers()["content-type"], /image\/svg\+xml/);
  const apple = await context.request.get(`${origin}/apple-icon.png`);
  assert.equal(apple.status(), 200);
  assert.match(apple.headers()["content-type"], /image\/png/);
  const appleBody = await apple.body();
  assert.equal(appleBody.readUInt32BE(16), 180, "apple-icon.png is 180 px wide");
  const links = await page.locator('link[rel~="icon"], link[rel="apple-touch-icon"]').evaluateAll(nodes => nodes.map(node => node.getAttribute("href")));
  assert.ok(links.some(href => href?.includes("favicon.ico")), `favicon link present: ${JSON.stringify(links)}`);
  assert.ok(links.some(href => href?.includes("icon.svg")), `svg icon link present: ${JSON.stringify(links)}`);
  assert.ok(links.some(href => href?.includes("apple-icon")), `apple icon link present: ${JSON.stringify(links)}`);

  const asset = (path) => readFile(new URL(path, import.meta.url));
  assert.deepEqual(await asset("../src/app/icon.svg"), await asset("../../brand/ardor-mark-favicon.svg"),
    "the served icon.svg is the generated favicon cut, not a hand export");
  assert.deepEqual(await asset("../../desktop/assets/icon.svg"), await asset("../../brand/ardor-app-light.svg"),
    "the desktop window and installer icon.svg is the official light app icon");

  await page.goto(`${origin}/app/resumes`);
  await page.getByText("brand-resume.pdf").first().waitFor();
  assert.equal(await page.getByRole("complementary", { name: /Main navigation|主导航/ }).locator('[data-brand-mark="ardor"]').count(), 1,
    "workbench rail shows the official mark");
  assert.deepEqual(await legacyMarks(), [0, 0, 0], "workbench carries no legacy flame or rule mark");
  await page.screenshot({ path: ".next/brand-workbench.png", fullPage: true });

  await page.goto(`${origin}/app/admin`);
  await page.getByText(/Ardor Admin|Ardor 管理/).first().waitFor();
  assert.equal(await page.locator("aside").filter({ hasText: /Ardor Admin|Ardor 管理/ }).locator('[data-brand-mark="ardor"]').count(), 1,
    "admin shell shows the official mark");
  assert.deepEqual(await legacyMarks(), [0, 0, 0], "admin carries no legacy flame or rule mark");
  await page.screenshot({ path: ".next/brand-admin.png", fullPage: true });

  const offline = await context.newPage();
  await offline.goto(new URL("../../desktop/offline.html", import.meta.url).href);
  await offline.locator(".card").waitFor();
  const mark = await offline.locator("img.mark").evaluate(image => ({ complete: image.complete, width: image.naturalWidth }));
  assert.ok(mark.complete && mark.width > 0, "the offline page loads the supplied mark under its CSP");
  assert.equal(await offline.locator(".lucide-flame").count(), 0);
  assert.equal((await offline.locator("main").innerText()).includes("✦"), false);
  await offline.screenshot({ path: ".next/brand-offline.png", fullPage: true });

  assert.deepEqual(consoleErrors, []);
  console.log("Brand UI: login, workbench, admin and offline use the supplied mark; favicon carries 16/24/32/48 and no legacy mark remains.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
