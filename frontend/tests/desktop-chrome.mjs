import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, writeFile, cp, realpath } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
// Preserve native startup diagnostics in CI instead of a context-free launch error.
process.env.DEBUG = [process.env.DEBUG, "pw:browser*"].filter(Boolean).join(",");
const { _electron: electron } = await import("playwright");

const temporaryRoot = await realpath(tmpdir());
const profile = await mkdtemp(join(temporaryRoot, "ardor-chrome-test-"));
const runtime = await mkdtemp(join(temporaryRoot, "ardor-chrome-runtime-"));
// Checkout/job ACLs can prevent Windows LPAC from launching executable children.
// A clean copy models the installer without changing ACLs or disabling sandbox.
await cp(resolve("../desktop/node_modules/electron/dist"), runtime, { recursive: true });
await writeFile(join(profile, "server.json"), JSON.stringify({ serverUrl: "http://127.0.0.1:3101" }), "utf8");
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3101"], { stdio: "pipe" });
let app;
try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    try { if ((await fetch("http://127.0.0.1:3101")).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  assert.ok(ready);
  app = await electron.launch({ executablePath: join(runtime, "electron.exe"), args: [resolve("../desktop/tests/chrome-smoke-main.cjs"), `--user-data-dir=${profile}`], env: { ...process.env, ARDOR_CHROME_TEST_DIR: profile } });
  console.log("Native window state", await app.evaluate(({ app, BrowserWindow }) => ({ ready: app.isReady(), windows: BrowserWindow.getAllWindows().map((window) => ({ url: window.webContents.getURL(), destroyed: window.isDestroyed() })) })));
  const page = await app.firstWindow();
  await page.waitForFunction(() => location.pathname === "/login" || location.pathname.endsWith("offline.html"), null, { timeout: 20_000 });
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const body = path === "/api/auth/me" ? { id: "chrome-fixture", email: "chrome@ardor.invalid", admin: false }
      : path === "/api/agent" ? { conversationId: null, llmConfigured: true, displayName: "Fixture", conversations: [], archivedConversations: [], messages: [], memory: { content: "" } }
      : path === "/api/agent/reasoning" ? { nativeControl: true }
      : path === "/api/profile" ? { displayName: "Fixture", headline: "", targetRoles: [], timezone: "Asia/Shanghai" }
      : path === "/api/settings/llm" ? { configured: true, provider: "OPENAI_COMPATIBLE", baseUrl: "https://example.com", model: "fixture", configurationSource: "ADMIN", personalOverride: false }
      : path === "/api/settings/web-search" ? { configured: true, provider: "TAVILY", configurationSource: "ADMIN", personalOverride: false }
      : path === "/api/settings/integrations" ? []
      : path === "/api/usage" ? { tier: "MEMBER", resetsAt: new Date().toISOString(), features: [] }
      : path === "/api/background-tasks/summary" ? { activeCount: 0, unreadByModule: {} } : {};
    await route.fulfill({ json: body });
  });
  await page.goto("http://127.0.0.1:3101/app");
  const toolbar = page.getByRole("banner", { name: "Window toolbar" });
  await toolbar.waitFor();
  const toolbarBackground = await toolbar.evaluate(element => {
    const style = getComputedStyle(element);
    return { color: style.backgroundColor, size: style.backgroundSize, repeat: style.backgroundRepeat };
  });
  assert.deepEqual(toolbarBackground, { color: "rgb(250, 249, 246)", size: "calc(100% - 160px) 100%", repeat: "no-repeat" },
    "The gradient must finish before the native window controls, whose overlay is #faf9f6");
  assert.deepEqual(await toolbar.getByRole("button").allTextContents(), ["", "", "", "file", "edit", "view", "help"]);
  assert.equal(await page.locator(".ardor-chat-toggle").isVisible(), false);
  const sidebar = page.getByRole("complementary", { name: "Conversations", exact: true });
  await sidebar.waitFor();
  const preferences = await app.evaluate(({ BrowserWindow }) => {
    const value = BrowserWindow.getAllWindows()[0].webContents.getLastWebPreferences();
    return { sandbox: value.sandbox, nodeIntegration: value.nodeIntegration, contextIsolation: value.contextIsolation };
  });
  assert.deepEqual(preferences, { sandbox: true, nodeIntegration: false, contextIsolation: true });
  await page.goto("http://127.0.0.1:3101/app/settings");
  await toolbar.getByRole("button", { name: "Back", exact: true }).click();
  await page.waitForURL((url) => url.origin === "http://127.0.0.1:3101" && url.pathname === "/app");
  await toolbar.getByRole("button", { name: "Forward", exact: true }).click();
  await page.waitForURL("http://127.0.0.1:3101/app/settings");
  await toolbar.getByRole("button", { name: "Back", exact: true }).click();
  await page.waitForURL((url) => url.origin === "http://127.0.0.1:3101" && url.pathname === "/app");
  await toolbar.getByRole("button", { name: "Toggle conversations" }).click();
  assert.equal(await sidebar.isVisible(), false);
  await toolbar.getByRole("button", { name: "Toggle conversations" }).click();
  await sidebar.waitFor();
  assert.equal(await app.evaluate(({ Menu }) => Menu.getApplicationMenu() === null), true);
  // Real IPC validates both sender and enum before touching navigation/menu APIs.
  const invalidDenied = await page.evaluate(async () => {
    try { await window.ardorDesktop.navigate("invalid"); return false; } catch { return true; }
  });
  assert.equal(invalidDenied, true);
  await page.screenshot({ path: ".next/desktop-chrome.png", animations: "disabled" });
  // Works before ever opening File: native input handler, not a popup accelerator.
  await app.evaluate(({ BrowserWindow }) => { const window = BrowserWindow.getAllWindows()[0]; window.restore(); window.focus(); window.webContents.focus(); });
  await app.evaluate(({ BrowserWindow }) => { const contents = BrowserWindow.getAllWindows()[0].webContents; contents.sendInputEvent({ type: "keyDown", keyCode: ",", modifiers: ["control"] }); contents.sendInputEvent({ type: "keyUp", keyCode: ",", modifiers: ["control"] }); });
  await page.waitForURL("http://127.0.0.1:3101/app/settings");
  await page.getByRole("heading", { name: "General & sidebar", exact: true }).waitFor();
  await page.screenshot({ path: ".next/desktop-settings.png", animations: "disabled" });
  console.log("Real Electron: production main/preload, custom toolbar, single sidebar toggle, no duplicate native menu, validated IPC passed.");
} finally {
  await app?.close();
  server.kill();
  if (server.exitCode === null) await new Promise((resolve) => server.once("close", resolve));
}
