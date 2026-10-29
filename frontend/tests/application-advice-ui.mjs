import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// Synthetic rhythm and persisted advice only; never calls a model or a real account.
const origin = "http://127.0.0.1:3121";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3121"], { stdio: "pipe" });
let logs = "", browser;
let advice = "**维持投递**：本周 5 份简历，先把手上的面试准备好。\n\n- 本周再投 2–3 家匹配岗位\n- 优先准备已约面的公司\n- 暂不扩大打招呼量";
const rhythm = () => ({
  today: "2026-10-29", weekStart: "2026-10-26",
  todayGreetingCount: 12, todayResumeCount: 3,
  weeklyGreetingCount: 89, weeklyResumeCount: 5,
  weeklyGreetingRecordedDays: 4, weeklyResumeRecordedDays: 2,
  weeklyResumeGoal: 10, weeklyGreetingGoal: 60,
  reminderEnabled: true, reminderTime: "19:00", weekdaysOnly: true, reminderDue: false,
  days: [
    { date: "2026-10-26", greetingCount: 20, resumeCount: 2 },
    { date: "2026-10-27", greetingCount: 25, resumeCount: 0 },
    { date: "2026-10-28", greetingCount: 32, resumeCount: null },
    { date: "2026-10-29", greetingCount: 12, resumeCount: 3 },
    { date: "2026-10-30", greetingCount: null, resumeCount: null },
    { date: "2026-10-31", greetingCount: null, resumeCount: null },
    { date: "2026-11-01", greetingCount: null, resumeCount: null },
  ],
  advice,
});
server.stdout.on("data", chunk => logs += chunk); server.stderr.on("data", chunk => logs += chunk);
try {
  let ready = false;
  for (let i = 0; i < 100; i++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* starting */ }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });
  const context = await browser.newContext({ viewport: { width: 1280, height: 1000 } });
  await context.addInitScript(() => localStorage.setItem("ardor:locale", "zh-CN"));
  await context.route("**/api/**", route => {
    const path = new URL(route.request().url()).pathname;
    let body = {};
    if (path === "/api/auth/me") body = { id: "advice-owner", email: "advice@ardor.invalid", admin: false };
    else if (path === "/api/auth/csrf") body = { token: "fixture", headerName: "X-CSRF-TOKEN" };
    else if (path === "/api/profile/sidebar") body = { userId: "advice-owner", hrefs: ["/app"], configured: true, revision: 0 };
    else if (path === "/api/profile") body = { displayName: "Synthetic owner", timezone: "Asia/Shanghai", targetRoles: [] };
    else if (path === "/api/background-tasks/summary") body = { activeCount: 0, unreadByModule: {} };
    else if (path === "/api/applications/rhythm") body = rhythm();
    return route.fulfill({ json: body });
  });
  const page = await context.newPage();
  await page.goto(`${origin}/app/applications`);
  const card = page.locator("section").filter({ hasText: "Ardor 的建议" });
  await card.getByRole("heading", { name: "Ardor 的建议" }).waitFor();
  await card.locator("strong").getByText("维持投递", { exact: true }).waitFor();
  assert.equal(await card.locator("li").count(), 3, "Markdown actions render as a list");
  assert.doesNotMatch(await card.innerText(), /\*\*/);
  const ask = page.getByRole("link", { name: /让 Ardor 判断/ });
  assert.match(decodeURIComponent(await ask.getAttribute("href")), /Markdown/);
  // Legacy saved advice with plain line breaks must not collapse into one paragraph.
  advice = "第一行结论\n第二行依据\n第三行行动";
  await page.reload();
  await card.getByText(/第一行结论/).waitFor();
  assert.match(await card.innerText(), /第一行结论\n第二行依据\n第三行行动/);
  console.log("Application advice UI: Markdown list/emphasis and legacy line breaks passed.");
} finally {
  await browser?.close(); server.kill();
  if (server.exitCode === null) await new Promise(resolve => server.once("close", resolve));
}
