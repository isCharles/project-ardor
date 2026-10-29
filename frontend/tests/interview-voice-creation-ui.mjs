import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {chromium} from "playwright";

const origin = "http://127.0.0.1:3132";
const server = spawn(process.execPath, ["node_modules/next/dist/bin/next", "start", "-p", "3132"], {stdio: "pipe"});
let logs = "", browser;
server.stdout.on("data", (x) => logs += x);
server.stderr.on("data", (x) => logs += x);

const session = {
  id: "session-1", resumeAnalysisId: null, modality: "VOICE",
  targetCompany: "Ardor", targetRole: "Java backend engineer",
  status: "IN_PROGRESS", createdAt: "2026-10-29T08:00:00Z",
};
const progress = {
  sessionId: "session-1", status: "IN_PROGRESS", answeredCount: 0, totalQuestions: 5,
  readyToFinish: false,
  nextQuestion: {
    id: "question-1", sequenceNumber: 1, questionText: "请先做一个自我介绍。",
    questionType: "TECHNICAL", leetcode: null,
  },
};

try {
  for (let i = 0; i < 120; i++) {
    try { if ((await fetch(origin)).ok) break; } catch { /* starting */ }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  browser = await chromium.launch({headless: true, ...(process.env.ARDOR_UI_BROWSER_CHANNEL ? {channel: process.env.ARDOR_UI_BROWSER_CHANNEL} : {})});
  const context = await browser.newContext({viewport: {width: 1400, height: 950}});
  await context.addInitScript(() => {
    window.__voiceSockets = [];
    window.__voiceMessages = [];
    window.AudioContext = class {
      constructor() { this.state = "running"; this.currentTime = 0; this.sampleRate = 48_000; this.destination = {}; }
      createMediaStreamSource() { return {connect() {}, disconnect() {}}; }
      createScriptProcessor() { return {connect() {}, disconnect() {}, onaudioprocess: null}; }
      createGain() { return {gain: {value: 0}, connect() {}, disconnect() {}}; }
      createBuffer(channels, length, rate) {
        return {duration: length / rate, getChannelData: () => new Float32Array(length)};
      }
      createBufferSource() { return {buffer: null, connect() {}, start() {}, stop() {}, onended: null}; }
      resume() { return Promise.resolve(); }
      close() { return Promise.resolve(); }
    };
    Object.defineProperty(navigator, "mediaDevices", {
      value: {getUserMedia: async () => ({getTracks: () => []})},
    });
    class FakeWebSocket {
      static CONNECTING = 0;
      static OPEN = 1;
      static CLOSED = 3;
      constructor(url) {
        this.url = url;
        this.readyState = FakeWebSocket.CONNECTING;
        window.__voiceSockets.push(url);
        setTimeout(() => {
          this.readyState = FakeWebSocket.OPEN;
          this.onopen?.({});
        }, 10);
      }
      send(raw) {
        const message = JSON.parse(raw);
        window.__voiceMessages.push({url: this.url, message});
        if (message.type === "create_interview") {
          setTimeout(() => {
            this.emit({type: "tts_connecting", turn: "turn-1"});
            this.emit({type: "questions_generating"});
            this.emit({type: "tts_started", turn: "turn-1"});
            this.emit({type: "tts_done", turn: "turn-1"});
            this.emit({type: "interview_created", text: "session-1", turn: "turn-1"});
          }, 60);
        }
        if (message.type === "start_asr") {
          setTimeout(() => {
            this.emit({type: "asr_connecting"});
            this.emit({type: "asr_ready"});
          }, 30);
        }
      }
      emit(event) { this.onmessage?.({data: JSON.stringify(event)}); }
      close() { this.readyState = FakeWebSocket.CLOSED; }
    }
    window.WebSocket = FakeWebSocket;
  });
  await context.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    let body = [];
    if (path === "/api/auth/me") body = {id: "voice-fixture", email: "fixture@ardor.invalid", admin: false};
    else if (path === "/api/auth/csrf") body = {token: "fixture", headerName: "X-XSRF-TOKEN"};
    else if (path === "/api/profile/sidebar") body = {userId: "voice-fixture", hrefs: ["/app"], revision: 0, configured: true};
    else if (path === "/api/background-tasks/summary") body = {activeCount: 0, unreadByModule: {}};
    else if (path === "/api/agent/permissions") body = {globalLevel: 0, modules: []};
    else if (path === "/api/resumes") body = [];
    else if (path === "/api/interviews" && request.method() === "GET") body = [session];
    else if (path === "/api/interviews/session-1") body = session;
    else if (path === "/api/interviews/session-1/next-question") body = progress;
    await route.fulfill({json: body});
  });
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto(`${origin}/app/interviews`);
  await page.getByText("Set up manually", {exact: true}).click();
  await page.locator('input[name="modality"][value="VOICE"]').check({force: true});
  await page.locator('input[name="targetCompany"]').fill("Ardor");
  await page.locator('input[name="targetRole"]').fill("Java backend engineer");
  await page.getByRole("button", {name: "Create and start", exact: true}).click();

  const creator = page.locator("[data-voice-interview-creator]");
  await creator.waitFor({timeout: 5_000});
  await page.getByText("请先做一个自我介绍。").waitFor({timeout: 10_000});
  assert.equal(await creator.count(), 0, "the creator must hand over once the first question finished");
  await page.getByText(/Listening to your answer/).waitFor({timeout: 10_000});

  const sockets = await page.evaluate(() => window.__voiceSockets);
  const messages = await page.evaluate(() => window.__voiceMessages);
  assert.ok(sockets.some((url) => url.endsWith("/ws/interviews/voice/new")), `missing creation socket: ${sockets}`);
  assert.ok(sockets.some((url) => url.endsWith("/ws/interviews/session-1/voice")), `missing answer socket: ${sockets}`);
  const creation = messages.find((item) => item.message.type === "create_interview");
  assert.equal(creation.message.targetRole, "Java backend engineer");
  assert.equal(creation.message.targetCompany, "Ardor");
  assert.ok(messages.some((item) => item.message.type === "start_asr"), "the answer recorder must start listening by itself");
  assert.deepEqual(errors, []);
  console.log("Interview voice creation: creator hand-off and auto-listen verified");
} catch (reason) {
  console.error(logs.slice(-4_000));
  throw reason;
} finally {
  await browser?.close();
  server.kill();
}
