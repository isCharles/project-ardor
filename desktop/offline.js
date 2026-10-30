"use strict";

const form = document.getElementById("server-form");
const input = document.getElementById("server-url");
const status = document.getElementById("status");
const version = document.getElementById("version");
const updateStatus = document.getElementById("update-status");
const installUpdate = document.getElementById("install-update");
const RETRY_INTERVAL_MS = 5_000;
let locale = "en";
let statusKey = "waiting";
let autoRetryTimer = null;
let autoRetryRunning = false;

const copy = {
  en: {
    title: "Ardor · Connect to server",
    headline: "Wait for your server.\nThen let's continue.",
    detail: "Ardor can't reach the server right now. Start your local service or enter the address of your hosted server.",
    label: "Server address",
    connect: "Connect",
    hint: "Local default: http://127.0.0.1:3000 · Public servers require HTTPS",
    install: "Install and restart",
    footer: "The desktop app only connects you. Your resumes, models, and chats stay on the selected server.",
    waiting: "Waiting for the server. Retrying automatically every few seconds…",
    connecting: "Connecting…",
    unavailable: "Still unable to connect. Make sure the server is running.",
    canceled: "Server switch canceled.",
    failed: "Connection failed.",
    invalidServer: "Enter a valid server address.",
    httpsRequired: "Public servers must use HTTPS; HTTP is allowed only for this device.",
    rootOnly: "Enter only the server's root address, without an account, path, or parameters.",
  },
  "zh-CN": {
    title: "Ardor · 连接服务器",
    headline: "等服务器就绪，\n我们继续。",
    detail: "当前无法连接 Ardor。本机使用时请先启动服务；连接公网部署时，填入服务器地址即可。",
    label: "服务器地址",
    connect: "连接",
    hint: "本机默认 http://127.0.0.1:3000 · 公网地址须使用 HTTPS",
    install: "安装并重启",
    footer: "桌面程序只负责连接。你的简历、模型与对话仍在所选服务器上。",
    waiting: "正在等待服务器启动，每隔几秒自动重试…",
    connecting: "正在连接…",
    unavailable: "还未连接成功，请确认服务器已启动。",
    canceled: "已取消切换。",
    failed: "连接失败。",
    invalidServer: "请输入有效的服务器地址。",
    httpsRequired: "公网地址必须使用 HTTPS；HTTP 仅支持本机。",
    rootOnly: "只填写服务器根地址，不要包含账号、路径或参数。",
  },
};

function renderLocale(next) {
  locale = next === "zh-CN" ? "zh-CN" : "en";
  const words = copy[locale];
  document.documentElement.lang = locale;
  document.title = words.title;
  document.getElementById("headline").textContent = words.headline;
  document.getElementById("detail").textContent = words.detail;
  document.getElementById("server-label").textContent = words.label;
  document.getElementById("connect").textContent = words.connect;
  document.getElementById("hint").textContent = words.hint;
  installUpdate.textContent = words.install;
  document.getElementById("footer").textContent = words.footer;
  status.textContent = words[statusKey] ?? "";
}

function renderUpdate(next) {
  updateStatus.textContent = next.message;
  installUpdate.hidden = next.state !== "ready";
}

function showStatus(key) {
  statusKey = key;
  status.textContent = copy[locale][key] ?? "";
}

async function autoRetry() {
  if (autoRetryRunning || document.hidden) return;
  autoRetryRunning = true;
  try {
    const result = await window.ardorDesktop.retry();
    // A successful retry navigates the window to the app, so only failures
    // need a visible status here.
    if (!result?.connected) showStatus("waiting");
  } catch {
    showStatus("failed");
  } finally {
    autoRetryRunning = false;
  }
}

function startAutoRetry() {
  if (autoRetryTimer) return;
  autoRetryTimer = setInterval(() => { void autoRetry(); }, RETRY_INTERVAL_MS);
}

async function submitServer() {
  const connect = form.querySelector("button");
  connect.disabled = true;
  showStatus("connecting");
  try {
    const result = await window.ardorDesktop.setServerResult(input.value);
    if (result.errorCode && copy[locale][result.errorCode]) showStatus(result.errorCode);
    else if (result.changed === false) showStatus("canceled");
    else if (!result.connected) showStatus("unavailable");
  } catch {
    showStatus("failed");
  } finally {
    connect.disabled = false;
  }
}

window.ardorDesktop.getInfo().then((info) => {
  input.value = info.serverUrl;
  version.textContent = `v${info.version}`;
  renderLocale(info.locale);
  renderUpdate(info.updateStatus);
  startAutoRetry();
}).catch(() => showStatus("failed"));
window.ardorDesktop.onUpdateStatus(renderUpdate);
form.addEventListener("submit", (event) => { event.preventDefault(); void submitServer(); });
installUpdate.addEventListener("click", () => { void window.ardorDesktop.installUpdate(); });
