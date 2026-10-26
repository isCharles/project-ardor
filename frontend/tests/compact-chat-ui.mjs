import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic account, history and API fixtures only.
const origin = "http://127.0.0.1:3121";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3121"], { stdio: "pipe" });
let logs = "", browser, empty = false;
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
try {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) break; } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: "reduce" });
  const date = new Date().toISOString();
  const conversations = Array.from({ length: 40 }, (_, i) => ({ id: `compact-${i}`, title: `Conversation ${i} ${"Long title ".repeat(8)}`, pinned: i === 0, createdAt: date, updatedAt: date }));
  await context.route("**/api/**", async route => {
    const path = new URL(route.request().url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = { id: "compact-owner", email: "compact@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path.endsWith("/pin") && route.request().method() === "PATCH") {
      const id = path.split("/").at(-2), item = conversations.find(value => value.id === id);
      item.pinned = route.request().postDataJSON().pinned; body = item;
    }
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/profile/sidebar") body = { userId: "compact-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/agent") body = { conversationId: conversations[0].id, llmConfigured: true, memory: { content: "" }, conversations, archivedConversations: [], messages: empty ? [] : [{ id: "compact-answer", role: "ASSISTANT", content: "Synthetic answer", createdAt: date }] };
    else if (path === "/api/agent/runs/latest") return route.fulfill({ status: 204 });
    else if (path === "/api/agent/models") body = [{ id: "PRIMARY", label: `claude-opus-5-5-${"long-name-".repeat(12)}`, provider: "ANTHROPIC_COMPATIBLE", available: true, nativeControl: true }];
    else if (path === "/api/agent/permissions") body = { mode: "AUTO", globalLevel: 2, modules: [] };
    else if (path === "/api/settings/llm") body = { configured: true };
    else if (path === "/api/usage") body = null;
    await route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app`);
  await page.locator("[data-conversation-row]").first().waitFor();
  const sidebar = page.getByRole("complementary", { name: "Conversations", exact: true });
  await sidebar.getByText("Ardor", { exact: true }).waitFor();
  assert.equal(await sidebar.getByText("Project Ardor", { exact: true }).count(), 0);
  const pinned = sidebar.getByRole("region", { name: "Pinned chats", exact: true });
  await pinned.waitFor();
  assert.equal(await pinned.locator("[data-conversation-row]").count(), 1);
  assert.equal(await pinned.locator("[data-conversation-row] svg").count(), 0);
  assert.equal(await sidebar.locator('#recent-conversations [data-conversation-row]').count(), 39);
  await sidebar.getByRole("button", { name: "Recent chats", exact: true }).click();
  await page.locator("#recent-conversations").waitFor({ state: "hidden" });
  assert.equal(await pinned.isVisible(), true, "Pinned chats remain available when recent history is collapsed");
  await sidebar.getByRole("button", { name: "Recent chats", exact: true }).click();
  const title = pinned.locator("[data-conversation-title]");
  await page.waitForFunction(() => document.querySelector('[data-conversation-title]')?.dataset.overflow === 'true');
  assert.equal(await title.evaluate(element => getComputedStyle(element).textOverflow), "clip");
  assert.notEqual(await title.evaluate(element => getComputedStyle(element).maskImage), "none");
  assert.equal(await title.locator("span").evaluate(element => getComputedStyle(element).animationName), "none", "Respect reduced motion");
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await pinned.locator("[data-conversation-row]").hover();
  await page.waitForFunction(() => document.querySelector('[data-conversation-title] > span')?.getAnimations().length > 0);
  const animatedShift = await title.locator("span").evaluate(element => {
    const animation = element.getAnimations()[0]; animation.pause(); animation.currentTime = 5000;
    return new DOMMatrix(getComputedStyle(element).transform).m41;
  });
  assert.ok(animatedShift < 0, "Hover reveals the clipped title by moving it left");
  await page.emulateMedia({ reducedMotion: "reduce" });
  assert.ok((await page.getByRole("complementary", { name: "Conversations", exact: true }).boundingBox()).width <= 256);
  const visibleRows = await page.locator("[data-conversation-row]").evaluateAll(rows => rows.filter(row => {
    const box = row.getBoundingClientRect();
    const clip = row.closest(".overflow-y-auto").getBoundingClientRect();
    return box.top >= clip.top && box.bottom <= clip.bottom;
  }).length);
  assert.ok(visibleRows >= 22, `Expected denser history, got ${visibleRows} visible rows`);
  await page.locator("[data-conversation-row]").first().hover();
  await page.getByRole("button", { name: "Chat actions", exact: true }).first().click();
  await page.getByRole("button", { name: "Rename", exact: true }).waitFor();
  await page.keyboard.press("Escape");
  await page.getByRole("button", { name: "Rename", exact: true }).waitFor({ state: "hidden" });
  await pinned.locator("[data-conversation-item]").hover();
  await pinned.getByRole("button", { name: "Chat actions", exact: true }).click();
  await pinned.getByRole("button", { name: "Unpin", exact: true }).click();
  await pinned.waitFor({ state: "hidden" });
  assert.equal(await sidebar.locator('#recent-conversations [data-conversation-row]').count(), 40);
  await sidebar.locator("[data-conversation-item]").first().hover();
  await sidebar.getByRole("button", { name: "Chat actions", exact: true }).first().click();
  await sidebar.getByRole("button", { name: "Pin", exact: true }).click();
  await pinned.waitFor();

  for (const isEmpty of [false, true]) {
    empty = isEmpty;
    await page.reload();
    const input = page.getByRole("textbox", { name: "Message Ardor", exact: true });
    await input.waitFor();
    await input.fill("A draft that must survive responsive resizing.");
    for (const width of [1440, 1024, 820, 640, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const footer = page.locator("[data-composer-footer]");
      const footerBox = await footer.boundingBox();
      await page.screenshot({ path: `.next/compact-chat-${isEmpty ? "new" : "existing"}-${width}.png` });
      assert.ok(footerBox.height <= 44, `${isEmpty ? "New" : "Existing"} footer must stay one row at ${width}: ${footerBox.height}`);
      assert.equal(await input.getAttribute("rows"), "2");
      const controls = [page.getByRole("button", { name: "Add attachments", exact: true }), page.locator("[data-approval-trigger]"),
        page.getByRole("button", { name: "Context usage", exact: true }), page.getByRole("button", { name: "Model and thinking depth", exact: true }),
        page.getByRole("button", { name: "Voice input", exact: true }), page.getByRole("button", { name: "Send", exact: true })];
      let edge = footerBox.x;
      for (const control of controls) {
        const box = await control.boundingBox();
        assert.ok(box && box.width >= 12 && box.x >= edge - 1 && box.x + box.width <= footerBox.x + footerBox.width + 1,
          `Control overlap/overflow at ${width}: ${await control.getAttribute("aria-label")} ${JSON.stringify({box, footerBox, edge})}`);
        assert.ok(Math.abs(box.y + box.height / 2 - footerBox.y - footerBox.height / 2) < 3, `Control wrapped at ${width}: ${await control.getAttribute("aria-label")} ${JSON.stringify({ box, footerBox })}`);
        edge = box.x + box.width;
      }
      assert.equal(await input.inputValue(), "A draft that must survive responsive resizing.");
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
      await controls[3].click();
      const popup = page.getByRole("dialog", { name: "Model and thinking depth options", exact: true });
      await popup.waitFor();
      const popupBox = await popup.boundingBox();
      assert.ok(popupBox.x >= 0 && popupBox.x + popupBox.width <= width && popupBox.y >= 0,
        `Model menu must stay in viewport at ${width}`);
      await popup.getByRole("button", { name: "Max", exact: true }).click();
      assert.equal(await controls[3].getAttribute("data-reasoning-depth"), "DEEP", "Portal controls remain interactive");
      await page.keyboard.press("Escape");
      await popup.waitFor({ state: "hidden" });
      if (width === 320) {
        for (const length of [40000, 50000, 50001]) {
          await input.fill("x".repeat(length));
          const badge = await page.locator("[data-draft-counter]").boundingBox();
          const currentFooter = await footer.boundingBox();
          assert.ok(badge.x >= 0 && badge.x + badge.width <= width && badge.y + badge.height <= currentFooter.y,
            "Long-draft counter must stay outside the toolbar");
          let previousRight = currentFooter.x;
          for (const control of controls) {
            const box = await control.boundingBox();
            assert.ok(box.x >= previousRight - 1 && box.x + box.width <= currentFooter.x + currentFooter.width + 1,
              `Long-draft controls overlap at ${length}`);
            previousRight = box.x + box.width;
          }
          assert.equal(await controls.at(-1).isDisabled(), length > 50000);
        }
        await input.fill("A draft that must survive responsive resizing.");
      }
      await page.screenshot({ path: `.next/compact-chat-${isEmpty ? "new" : "existing"}-${width}.png` });
    }
  }
  console.log("Compact chat UI: history density, row actions, new/existing two-row composers and six controls passed at six widths.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
