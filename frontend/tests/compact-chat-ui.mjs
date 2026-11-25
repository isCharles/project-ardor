import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { chromium } from "playwright";

// Synthetic account, history and API fixtures only.
const origin = "http://127.0.0.1:3121";
const deletionOrigin = "http://127.0.0.1:3122";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3121"], { stdio: "pipe" });
const deletionServer = createServer((request, response) => {
  response.setHeader("Access-Control-Allow-Origin", origin);
  response.setHeader("Access-Control-Allow-Credentials", "true");
  response.setHeader("Access-Control-Allow-Headers", "Content-Type, X-CSRF-TOKEN");
  response.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
  if (request.method === "OPTIONS") {
    response.writeHead(204).end();
    return;
  }
  let payload = "";
  request.setEncoding("utf8");
  request.on("data", chunk => payload += chunk);
  request.on("end", () => {
    if (request.url !== "/api/agent/messages/stream" || request.method !== "POST") {
      response.writeHead(404).end();
      return;
    }
    const body = JSON.parse(payload);
    streamRequests.push(body);
    response.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive",
    });
    const send = event => response.write(`data: ${JSON.stringify(event)}\n\n`);
    send({ type: "tool_start", toolName: "delete_memory_card", label: "Delete card", elapsedMs: 1 });
    send({ type: "confirm", confirmation: deletionProposal });
    releaseDeletion = () => {
      const content = "The fixture card was deleted. Continuing automatically.";
      send({ type: "delta", content });
      send({ type: "done", content, conversationId: body.conversationId, messageId: "deletion-answer", elapsedMs: 100 });
      response.end();
    };
  });
});
const answer = "北京晴 4~16℃，本周投递 30~40 份。~~旧安排~~";
const longUserMessage = `UnbrokenMessage${"x".repeat(240)}`.repeat(12);
const deletionProposal = { kind: "memory_card", targetId: "deletion-fixture", label: "Fixture card",
  detail: "Delete only this fixture card", endpoint: "/api/memory-cards/deletion-fixture", proposalId: "deletion-proposal" };
let logs = "", browser, empty = false, imageUploads = 0, deletionMode = false, releaseDeletion, restoredHistory = null;
const streamRequests = [], deletionRequests = [], legacyResolutionWrites = [];
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
await new Promise((resolve, reject) => {
  deletionServer.once("error", reject);
  deletionServer.listen(3122, "127.0.0.1", resolve);
});
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
    if (new URL(route.request().url()).origin === deletionOrigin) return route.continue();
    let body = [];
    if (path === "/api/agent/messages/stream" && route.request().method() === "POST" && deletionMode) {
      return route.continue({ url: `${deletionOrigin}${path}` });
    }
    if (path === "/api/memory-cards/deletion-fixture" && route.request().method() === "DELETE") {
      deletionRequests.push(route.request().method());
      return route.fulfill({ status: 204, body: "" });
    }
    if (path === "/api/agent/messages/legacy-answer/proposal-resolution" && route.request().method() === "PUT") {
      legacyResolutionWrites.push(route.request().postDataJSON());
      return route.fulfill({ status: 204, body: "" });
    }
    if (path === "/api/agent/images" && route.request().method() === "POST") {
      imageUploads++; body = { id: `pasted-image-${imageUploads}`, mediaType: "image/png" };
    }
    else if (path === "/api/auth/me") body = { id: "compact-owner", email: "compact@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path.endsWith("/pin") && route.request().method() === "PATCH") {
      const id = path.split("/").at(-2), item = conversations.find(value => value.id === id);
      item.pinned = route.request().postDataJSON().pinned; body = item;
    }
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/profile/sidebar") body = { userId: "compact-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/agent") body = restoredHistory ?? { conversationId: conversations[0].id, llmConfigured: true, memory: { content: "" }, conversations, archivedConversations: [], messages: empty ? [] : [{ id: "compact-user", role: "USER", content: longUserMessage, createdAt: date }, { id: "compact-answer", role: "ASSISTANT", content: answer, createdAt: date }] };
    else if (path === "/api/agent/runs/latest") return route.fulfill({ status: 204 });
    else if (path === "/api/agent/models") body = [{ id: "PRIMARY", label: `claude-opus-5-5-${"long-name-".repeat(12)}`, provider: "ANTHROPIC_COMPATIBLE", available: true, nativeControl: true, imageInput: true }];
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
  const markdown = page.locator(".ardor-markdown");
  await markdown.getByText("北京晴 4~16℃，本周投递 30~40 份。", { exact: false }).waitFor();
  assert.equal(await markdown.locator("del").count(), 1);
  assert.equal(await markdown.locator("del").innerText(), "旧安排");
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
    await input.fill("");
    const imageUpload = page.waitForResponse(response => new URL(response.url()).pathname === "/api/agent/images" && response.request().method() === "POST");
    await input.evaluate(element => {
      const bytes = Uint8Array.from(atob("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6xkAAAAASUVORK5CYII="), value => value.charCodeAt(0));
      const clipboard = new DataTransfer();
      clipboard.items.add(new File([bytes], "clipboard.png", { type: "image/png" }));
      clipboard.setData("text/plain", "Pasted text");
      element.dispatchEvent(new ClipboardEvent("paste", { bubbles: true, cancelable: true, clipboardData: clipboard }));
    });
    await imageUpload;
    await page.getByAltText("clipboard.png").waitFor();
    await page.locator("[data-chat-image-previews] figcaption").getByText("clipboard.png", { exact: true }).waitFor();
    await page.screenshot({ path: `.next/compact-chat-pasted-image-${isEmpty ? "new" : "existing"}.png`, fullPage: true });
    assert.equal(await input.inputValue(), "Pasted text", "Mixed image and text paste preserves both");
    assert.equal(await page.getByRole("button", { name: "Send", exact: true }).isDisabled(), false);
    await page.getByRole("button", { name: "Remove chat image", exact: true }).click();
    await input.fill("A draft that must survive responsive resizing.");
    for (const width of [1440, 1024, 820, 640, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await page.waitForFunction(() => {
        const footer = document.querySelector("[data-composer-footer]")?.getBoundingClientRect();
        const button = document.querySelector('button[aria-label="Add attachments"]')?.getBoundingClientRect();
        return footer && button && Math.abs(button.y + button.height / 2 - footer.y - footer.height / 2) < 3;
      }, undefined, { timeout: 3000 });
      const footer = page.locator("[data-composer-footer]");
      const footerBox = await footer.boundingBox();
      assert.ok(footerBox.height <= 44, `${isEmpty ? "New" : "Existing"} footer must stay one row at ${width}: ${footerBox.height}`);
      assert.equal(await input.getAttribute("rows"), "2");
      const controls = [page.getByRole("button", { name: "Add attachments", exact: true }), page.locator("[data-approval-trigger]"),
        page.getByRole("button", { name: "Context usage", exact: true }), page.getByRole("button", { name: "Model and thinking depth", exact: true }),
        page.getByRole("button", { name: "Voice input", exact: true }), page.getByRole("button", { name: "Send", exact: true })];
      const geometry = await page.evaluate(() => {
        const rect = element => element ? element.getBoundingClientRect().toJSON() : null;
        const footer = document.querySelector("[data-composer-footer]");
        const buttons = [
          document.querySelector('button[aria-label="Add attachments"]'),
          document.querySelector("[data-approval-trigger]"),
          document.querySelector('button[aria-label="Context usage"]'),
          document.querySelector('button[aria-label="Model and thinking depth"]'),
          document.querySelector('button[aria-label="Voice input"]'),
          document.querySelector('button[aria-label="Send"]'),
        ];
        return { footer: rect(footer), controls: buttons.map(rect) };
      });
      let edge = geometry.footer.x;
      for (const [index, box] of geometry.controls.entries()) {
        assert.ok(box && box.width >= 12 && box.x >= edge - 1 && box.x + box.width <= geometry.footer.x + geometry.footer.width + 1,
          `Control overlap/overflow at ${width}: ${await controls[index].getAttribute("aria-label")} ${JSON.stringify({box, footerBox: geometry.footer, edge})}`);
        assert.ok(Math.abs(box.y + box.height / 2 - geometry.footer.y - geometry.footer.height / 2) < 3, `Control wrapped at ${width}: ${await controls[index].getAttribute("aria-label")} ${JSON.stringify({ box, footerBox: geometry.footer })}`);
        edge = box.x + box.width;
      }
      assert.equal(await input.inputValue(), "A draft that must survive responsive resizing.");
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth), false);
      if (!isEmpty) {
        const thread = await page.locator(".ardor-chat-enter").boundingBox();
        const bubble = await page.locator("[data-user-message]").boundingBox();
        assert.ok(bubble.x >= thread.x && bubble.x + bubble.width <= thread.x + thread.width + 1,
          `Long user message must stay inside the thread at ${width}px`);
      }
      await page.screenshot({ path: `.next/compact-chat-${isEmpty ? "new" : "existing"}-${width}.png` });
      await controls[3].click();
      const popup = page.getByRole("dialog", { name: "Model and thinking depth options", exact: true });
      await popup.waitFor();
      const popupBox = await popup.boundingBox();
      assert.ok(popupBox.x >= 0 && popupBox.x + popupBox.width <= width && popupBox.y >= 0,
        `Model menu must stay in viewport at ${width}`);
      await popup.getByRole("button", { name: "Manage models", exact: true }).click();
      const settingsPopup = page.getByRole("dialog", { name: "Manage models", exact: true });
      await settingsPopup.waitFor();
      assert.ok(await settingsPopup.getByLabel(/^Thinking level: claude-opus-5-5/).count() === 1,
        "Per-model settings must remain interactive through the portal");
      await page.keyboard.press("Escape");
      await settingsPopup.waitFor({ state: "hidden" });
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
  await page.setViewportSize({ width: 1440, height: 1000 });
  deletionMode = true;
  const input = page.getByRole("textbox", { name: "Message Ardor", exact: true });
  await input.fill("Delete the fixture card");
  const streamResponse = page.waitForResponse(response =>
    new URL(response.url()).pathname === "/api/agent/messages/stream"
    && response.request().method() === "POST");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  assert.equal((await streamResponse).status(), 200);
  assert.equal(streamRequests.length, 1);
  await page.getByRole("button", { name: "Confirm deletion", exact: true }).waitFor();
  await page.screenshot({ path: ".next/compact-chat-deletion-blocked.png", fullPage: true });
  assert.equal(streamRequests.length, 1, "The run must still be on its original request while confirmation is pending");
  const deleted = page.waitForResponse(response => new URL(response.url()).pathname === "/api/memory-cards/deletion-fixture" && response.request().method() === "DELETE");
  await page.getByRole("button", { name: "Confirm deletion", exact: true }).click();
  await deleted;
  releaseDeletion();
  await page.getByText("The fixture card was deleted. Continuing automatically.", { exact: true }).waitFor();
  assert.equal(deletionRequests.length, 1);
  assert.equal(streamRequests.length, 1, "Confirmed deletion must continue the same stream without a synthetic follow-up message");
  assert.equal(streamRequests[0].message, "Delete the fixture card");
  await page.screenshot({ path: ".next/compact-chat-deletion-continued.png", fullPage: true });

  restoredHistory = { conversationId: conversations[0].id, llmConfigured: true, memory: { content: "" }, conversations,
    archivedConversations: [], messages: [
      { id: "legacy-user", role: "USER", content: "Delete the fixture card", createdAt: date },
      { id: "legacy-answer", role: "ASSISTANT", content: "Deleted on another device.", createdAt: date,
        runTrace: JSON.stringify({ proposals: [deletionProposal] }),
        proposalResolutions: { [`${deletionProposal.kind}:${deletionProposal.endpoint}`]: "DONE" } },
    ] };
  await page.evaluate(() => {
    for (let index = localStorage.length - 1; index >= 0; index--) {
      const key = localStorage.key(index);
      if (key?.startsWith("ardor:deletion-result:")) localStorage.removeItem(key);
    }
  });
  const migrated = page.waitForResponse(response => new URL(response.url()).pathname === "/api/agent/messages/legacy-answer/proposal-resolution"
    && response.request().method() === "PUT");
  await page.reload();
  await migrated;
  await page.getByText("Deleted on another device.", { exact: true }).waitFor();
  await page.getByRole("button", { name: "Confirm deletion", exact: true }).waitFor({ state: "hidden" });
  assert.deepEqual(legacyResolutionWrites.at(-1), { key: deletionProposal.proposalId, resolution: "DONE" });
  console.log("Compact chat UI: image/text paste, long-message bounds, blocked deletion with automatic same-run continuation, legacy resolution recovery, history density, row actions, new/existing composers and six controls passed at six widths.");
} finally {
  await browser?.close(); server.kill();
  deletionServer.closeAllConnections();
  await new Promise(resolve => deletionServer.close(resolve));
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
