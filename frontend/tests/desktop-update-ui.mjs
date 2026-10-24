import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Exercise the production sidebar against a synthetic existing desktop bridge.
// No installer is downloaded or launched by this test.
const origin = "http://127.0.0.1:3118";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3118"], { stdio: "pipe" });
let logs = "", browser;
server.stdout.on("data", value => { logs += value; }); server.stderr.on("data", value => { logs += value; });
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext();
  await context.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    const body = path === "/api/auth/me" ? { id: "update-owner", email: "update@ardor.invalid", admin: false }
      : path === "/api/profile" ? { displayName: "Synthetic owner", timezone: "UTC", targetRoles: [] }
      : path === "/api/profile/sidebar" ? { userId: "update-owner", hrefs: [], destinationIds: ["learning"], revision: 1, configured: true } : [];
    return route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const errors = []; page.on("pageerror", error => errors.push(error.message));
  await page.addInitScript(() => {
    let listener;
    window.updateProbe = { installs: 0, checks: 0, installResult: true, failCheck: false, status: { state: "idle", message: "" } };
    window.emitUpdate = status => { window.updateProbe.status = status; listener?.(status); };
    window.ardorDesktop = {
      getInfo: async () => ({ packaged: true, version: "0.0.1", locale: null, serverUrl: location.origin, updateStatus: window.updateProbe.status }),
      onUpdateStatus: callback => { listener = callback; return () => { listener = null; }; },
      checkForUpdates: async () => {
        window.updateProbe.checks++;
        if (window.updateProbe.failCheck) throw new Error("authorization: synthetic-secret-must-not-render");
        return window.updateProbe.status;
      },
      installUpdate: async () => { window.updateProbe.installs++; return { installed: window.updateProbe.installResult }; },
    };
  });
  await page.goto(`${origin}/app/learning`);
  const update = page.locator("[data-desktop-update]");
  await update.waitFor();
  const account = page.getByRole("button", { name: "Account", exact: true });
  assert.ok((await update.boundingBox()).y < (await account.boundingBox()).y, "Update belongs above the account avatar");
  await update.click();
  assert.equal(await page.evaluate(() => window.updateProbe.checks), 1);
  await page.evaluate(() => window.emitUpdate({ state: "downloading", percent: 42, message: "" }));
  await page.getByRole("progressbar", { name: "Update download" }).waitFor();
  assert.equal(await update.isDisabled(), true);
  assert.equal(await page.getByRole("progressbar").getAttribute("aria-valuenow"), "42");
  assert.equal(await update.innerText(), "42%");
  await page.evaluate(() => window.emitUpdate({ state: "downloading", percent: Number.NaN, message: "" }));
  await page.getByRole("button", { name: "Downloading update…", exact: true }).waitFor();
  assert.equal(await page.getByRole("progressbar").count(), 0);
  await page.evaluate(() => window.emitUpdate({ state: "ready", version: "9.9.9", message: "" }));
  await page.getByRole("button", { name: "Install and restart", exact: true }).click();
  assert.equal(await page.evaluate(() => window.updateProbe.installs), 1);
  // A stale ready state must not claim that installation happened.
  await page.evaluate(() => { window.updateProbe.installResult = false; window.updateProbe.status = { state: "idle", message: "" }; });
  await update.click();
  await page.getByText("Update is not ready yet. Check again.", { exact: true }).waitFor();
  await page.evaluate(() => { window.updateProbe.failCheck = true; });
  await update.click();
  await page.getByText("Could not check for updates. Try again.", { exact: true }).waitFor();
  assert.equal(await page.getByText("synthetic-secret-must-not-render", { exact: false }).count(), 0);
  assert.deepEqual(errors, []);
  await context.close();
  const web = await browser.newContext();
  await web.route("**/api/**", route => route.fulfill({ json: new URL(route.request().url()).pathname === "/api/auth/me"
    ? { id: "web-owner", email: "web@ardor.invalid", admin: false } : [] }));
  const webPage = await web.newPage();
  await webPage.goto(`${origin}/app/learning`);
  await webPage.getByRole("button", { name: "Account", exact: true }).waitFor();
  assert.equal(await webPage.locator("[data-desktop-update]").count(), 0);
  await web.close();
  console.log("Desktop update UI: sidebar location, progress, indeterminate download, install/restart bridge, stale readiness and safe failure feedback passed; no real installer launched.");
} finally {
  await browser?.close(); server.kill();
}
