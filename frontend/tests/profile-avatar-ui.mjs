import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

const origin = "http://127.0.0.1:3123";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3123"], { stdio: "pipe" });
let logs = "", browser, owner = "avatar-a", avatar = null, failUpload = false, uploads = 0, deletes = 0;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
const png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j/1cAAAAASUVORK5CYII=";
try {
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* Starting. */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1200, height: 900 } });
  await context.route("**/api/**", async route => {
    const request = route.request(), path = new URL(request.url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: owner, email: `${owner}@ardor.invalid`, admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", targetRoles: [], timezone: "UTC" };
    else if (path === "/api/profile/sidebar") body = { userId: owner, hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/agent/permissions") body = { mode: "CUSTOM", globalLevel: 1, modules: [] };
    else if (path === "/api/settings/llm" || path === "/api/settings/web-search") body = { configured: false };
    else if (path === "/api/usage") body = null;
    else if (path === "/api/profile/avatar") {
      if (request.method() === "PUT") {
        uploads++; assert.equal(request.headers()["x-csrf-token"], "fixture");
        assert.match(request.postDataBuffer().toString(), new RegExp(owner));
        if (failUpload) return route.fulfill({ status: 400, json: { message: "Synthetic invalid image" } });
        avatar = `data:image/png;base64,${png}`;
      } else if (request.method() === "DELETE") {
        deletes++; assert.equal(request.headers()["x-csrf-token"], "fixture");
        assert.equal(new URL(request.url()).searchParams.get("expectedUserId"), owner); avatar = null;
      }
      body = { userId: owner, imageDataUrl: avatar, updatedAt: null };
    }
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  const errors = []; page.on("pageerror", error => errors.push(error.message));
  const open = async () => {
    await page.goto(`${origin}/app/settings`);
    await page.getByRole("button", { name: "Profile", exact: true }).click().catch(async error => {
      console.error({ errors, body: await page.locator("body").innerText() }); throw error;
    });
  };
  const image = page.getByRole("button", { name: "Account", exact: true }).locator("img");
  await open(); assert.equal(await image.count(), 0);
  const input = page.getByLabel("Avatar image", { exact: true });
  await input.setInputFiles({ name: "avatar.png", mimeType: "image/png", buffer: Buffer.from(png, "base64") });
  await image.waitFor(); assert.equal(uploads, 1);
  await open(); await image.waitFor();
  failUpload = true;
  await input.setInputFiles({ name: "bad.png", mimeType: "image/png", buffer: Buffer.from("broken") });
  await page.getByRole("alert").filter({ hasText: "Synthetic invalid image" }).waitFor();
  assert.equal(await image.count(), 1, "Rejected upload preserves the old avatar");
  failUpload = false;
  await input.setInputFiles({ name: "replacement.png", mimeType: "image/png", buffer: Buffer.from(png, "base64") });
  await page.getByRole("button", { name: "Upload avatar", exact: true }).waitFor();
  assert.equal(uploads, 3);
  await page.getByRole("button", { name: "Remove", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click(); assert.equal(deletes, 0);
  await page.getByRole("button", { name: "Remove", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Remove avatar", exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('button[aria-label="Account"] img')); assert.equal(deletes, 1);
  owner = "avatar-b"; await open(); assert.equal(await image.count(), 0);
  await page.setViewportSize({ width: 390, height: 900 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  assert.deepEqual(errors, []);
  await context.close(); console.log("Profile avatar UI: upload, replace, reload, failed-upload preservation, confirmed removal and account isolation passed.");
} finally { await browser?.close(); server.kill(); }
