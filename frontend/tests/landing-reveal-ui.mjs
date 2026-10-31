import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { chromium } from "playwright";

// The landing page is public and needs no API fixtures; only its own scroll
// container and canvas animation are exercised.
const origin = "http://127.0.0.1:3134";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3134"], { stdio: "pipe" });
let logs = "", browser;

async function scrollToDemo(page) {
  await page.evaluate(async () => {
    const demo = document.querySelector("#career-agent");
    let scroller = demo.parentElement;
    while (scroller) {
      const style = getComputedStyle(scroller);
      if (/(auto|scroll|overlay)/.test(style.overflowY) && scroller.scrollHeight > scroller.clientHeight) break;
      scroller = scroller.parentElement;
    }
    if (scroller) scroller.scrollTop = scroller.scrollTop + demo.getBoundingClientRect().top - scroller.getBoundingClientRect().top;
    else window.scrollTo(0, window.scrollY + demo.getBoundingClientRect().top);
    await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
}

try {
  let ready = false;
  for (let attempt = 0; attempt < 100; attempt++) {
    if (server.exitCode !== null) throw new Error(logs);
    try { if ((await fetch(origin)).ok) { ready = true; break; } } catch { /* startup */ }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  assert.ok(ready, logs);
  browser = await chromium.launch({ headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? { channel: process.env.ARDOR_UI_BROWSER_CHANNEL } : {}) });

  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on("pageerror", error => errors.push(String(error)));
  await page.goto(origin, { waitUntil: "load" });
  await page.waitForFunction(() => {
    const demo = document.querySelector("#career-agent");
    return demo && Number(getComputedStyle(demo.querySelector(":scope > div > div")).opacity) === 0;
  });
  await scrollToDemo(page);
  await page.waitForFunction(() => {
    const demo = document.querySelector("#career-agent");
    return Number(getComputedStyle(demo.querySelector(":scope > div > div")).opacity) === 1;
  }, undefined, { timeout: 5000 });

  const reveal = await page.evaluate(() => {
    const demo = document.querySelector("#career-agent");
    const rect = demo.getBoundingClientRect();
    return { opacity: Number(getComputedStyle(demo.querySelector(":scope > div > div")).opacity), top: Math.round(rect.top), height: Math.round(rect.height), viewport: window.innerHeight };
  });
  assert.equal(reveal.opacity, 1);
  assert.ok(reveal.top >= 0 && reveal.top < reveal.viewport * 0.1, `demo did not scroll into view: ${JSON.stringify(reveal)}`);
  assert.ok(reveal.height < reveal.viewport * 1.2, `demo still occupies an oversized scroll track: ${JSON.stringify(reveal)}`);
  assert.deepEqual(errors, []);
  await page.close();

  const reduced = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: "reduce" });
  const reducedPage = await reduced.newPage();
  await reducedPage.goto(origin, { waitUntil: "load" });
  assert.equal(await reducedPage.evaluate(() => {
    const demo = document.querySelector("#career-agent");
    return Number(getComputedStyle(demo.querySelector(":scope > div > div")).opacity);
  }), 1);
  await reduced.close();

  // The tunnel card wraps ring progress with a negative modulo bug unless the
  // value is normalized; keep it drawing after the original failure window.
  const canvasPage = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await canvasPage.clock.install({ time: new Date("2026-01-01T00:00:00Z") });
  await canvasPage.goto(origin, { waitUntil: "load" });
  await canvasPage.waitForSelector("article canvas");
  await canvasPage.waitForTimeout(300);
  const litPixels = () => canvasPage.evaluate(() => {
    const canvas = document.querySelector("article canvas");
    const data = canvas.getContext("2d").getImageData(0, 0, canvas.width, canvas.height).data;
    let lit = 0;
    for (let index = 0; index < data.length; index += 4) {
      if (data[index] + data[index + 1] + data[index + 2] > 120) lit += 1;
    }
    return lit;
  });
  const early = await litPixels();
  assert.ok(early > 1000, `tunnel canvas did not render: ${early}`);
  await canvasPage.clock.fastForward(45_000);
  await canvasPage.waitForTimeout(100);
  const late = await litPixels();
  assert.ok(late > 1000, `tunnel canvas lost its lines after 45s: ${late}`);
  await canvasPage.close();
} finally {
  await browser?.close();
  server.kill();
}
