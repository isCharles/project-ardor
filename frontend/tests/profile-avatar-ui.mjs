import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

const origin = "http://127.0.0.1:3123";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3123"], { stdio: "pipe" });
let logs = "", browser, owner = "avatar-a", avatar = null, failUpload = false, uploads = 0, deletes = 0, uploadedPng;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
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
        const data = request.postDataBuffer(), fileHeader = data.indexOf(Buffer.from('filename="avatar.png"'));
        assert.ok(fileHeader >= 0);
        const start = data.indexOf(Buffer.from("\r\n\r\n"), fileHeader) + 4;
        uploadedPng = data.subarray(start, data.indexOf(Buffer.from("\r\n--"), start));
        avatar = `data:image/png;base64,${uploadedPng.toString("base64")}`;
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
  const pattern = Buffer.from(await page.evaluate(() => {
    const canvas = document.createElement("canvas"); canvas.width = 600; canvas.height = 300;
    const context = canvas.getContext("2d");
    for (const [index, color] of ["red", "lime", "blue"].entries()) { context.fillStyle = color; context.fillRect(index * 200, 0, 200, 300); }
    return canvas.toDataURL().split(",")[1];
  }), "base64");
  const choose = async () => {
    await input.setInputFiles({ name: "avatar.png", mimeType: "image/png", buffer: pattern });
    await page.getByRole("dialog", { name: "Adjust avatar", exact: true }).waitFor();
  };
  await choose(); assert.equal(uploads, 0, "Selecting a file does not save it");
  const zoom = page.getByRole("slider", { name: "Avatar zoom", exact: true });
  const horizontal = page.getByRole("slider", { name: "Avatar horizontal position", exact: true });
  await zoom.fill("2"); await horizontal.fill("100");
  const preview = page.getByLabel("Avatar crop preview", { exact: true });
  await page.waitForFunction(() => document.querySelector('canvas[aria-label="Avatar crop preview"]').getContext("2d").getImageData(128, 128, 1, 1).data[2] === 255);
  const bounds = await preview.boundingBox();
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await page.mouse.down(); await page.mouse.move(bounds.x + bounds.width - 4, bounds.y + bounds.height / 2); await page.mouse.up();
  assert.ok(Number(await horizontal.inputValue()) < 100, "Dragging changes the crop position");
  await horizontal.fill("-100");
  await page.getByRole("button", { name: "Save avatar", exact: true }).click();
  await image.waitFor(); assert.equal(uploads, 1);
  assert.deepEqual(await page.evaluate(async base64 => {
    const bitmap = await createImageBitmap(await (await fetch(`data:image/png;base64,${base64}`)).blob());
    const canvas = document.createElement("canvas"); canvas.width = bitmap.width; canvas.height = bitmap.height;
    const context = canvas.getContext("2d"); context.drawImage(bitmap, 0, 0);
    const pixel = [...context.getImageData(128, 128, 1, 1).data]; bitmap.close();
    return { width: canvas.width, height: canvas.height, pixel };
  }, uploadedPng.toString("base64")), { width: 256, height: 256, pixel: [255, 0, 0, 255] }, "Uploaded pixels match the selected crop");
  await open(); await image.waitFor();
  await choose(); await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
  assert.equal(uploads, 1, "Cancel preserves the previous avatar without a request");
  await input.setInputFiles({ name: "bad.png", mimeType: "image/png", buffer: Buffer.from("broken") });
  await page.getByRole("alert").filter({ hasText: "Choose a valid PNG or JPEG" }).waitFor();
  assert.equal(uploads, 1, "Broken images are rejected before upload");
  failUpload = true; await choose();
  await page.getByRole("button", { name: "Save avatar", exact: true }).click();
  await page.getByRole("alert").filter({ hasText: "Synthetic invalid image" }).waitFor();
  assert.equal(await image.count(), 1, "Rejected upload preserves the old avatar");
  failUpload = false;
  await page.getByRole("button", { name: "Save avatar", exact: true }).click();
  await page.getByRole("dialog").waitFor({ state: "hidden" });
  assert.equal(uploads, 3);
  const jpeg = Buffer.from(await page.evaluate(() => {
    const canvas = document.createElement("canvas"); canvas.width = 600; canvas.height = 300;
    const context = canvas.getContext("2d");
    for (const [index, color] of ["red", "lime", "blue"].entries()) { context.fillStyle = color; context.fillRect(index * 200, 0, 200, 300); }
    return canvas.toDataURL("image/jpeg", 0.95).split(",")[1];
  }), "base64");
  // JPEG EXIF orientation 6 rotates the stripes into top/middle/bottom bands.
  const exif = Buffer.from("45786966000049492a0008000000010012010300010000000600000000000000", "hex");
  const app1 = Buffer.alloc(4); app1.writeUInt16BE(0xffe1); app1.writeUInt16BE(exif.length + 2, 2);
  await input.setInputFiles({ name: "phone.jpg", mimeType: "image/jpeg", buffer: Buffer.concat([jpeg.subarray(0, 2), app1, exif, jpeg.subarray(2)]) });
  await page.getByRole("dialog", { name: "Adjust avatar", exact: true }).waitFor();
  assert.equal(await horizontal.isDisabled(), true);
  const vertical = page.getByRole("slider", { name: "Avatar vertical position", exact: true });
  assert.equal(await vertical.isEnabled(), true, "Phone EXIF rotation is applied before cropping");
  await zoom.fill("2"); await vertical.fill("100");
  await page.waitForFunction(() => {
    const pixel = document.querySelector('canvas[aria-label="Avatar crop preview"]').getContext("2d").getImageData(128, 128, 1, 1).data;
    return pixel[2] > 240 && pixel[0] < 10 && pixel[1] < 10;
  });
  await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
  assert.equal(uploads, 3, "Cancelling an oriented photo also leaves the saved image unchanged");
  await page.getByRole("button", { name: "Remove", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click(); assert.equal(deletes, 0);
  await page.getByRole("button", { name: "Remove", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Remove avatar", exact: true }).click();
  await page.waitForFunction(() => !document.querySelector('button[aria-label="Account"] img')); assert.equal(deletes, 1);
  owner = "avatar-b"; await open(); assert.equal(await image.count(), 0);
  await page.setViewportSize({ width: 390, height: 900 });
  await choose();
  const dialog = await page.getByRole("dialog").boundingBox();
  assert.ok(dialog.x >= 0 && dialog.x + dialog.width <= 391);
  await page.screenshot({ path: ".next/avatar-crop-mobile.png" });
  await page.getByRole("dialog").getByRole("button", { name: "Cancel", exact: true }).click();
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1));
  assert.deepEqual(errors, []);
  await context.close(); console.log("Profile avatar UI: crop pixels, drag/zoom, EXIF rotation, cancel, retry, reload, removal and account isolation passed.");
} finally { await browser?.close(); server.kill(); }
