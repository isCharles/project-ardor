"use strict";

const form = document.getElementById("server-form");
const input = document.getElementById("server-url");
const retry = document.getElementById("retry");
const status = document.getElementById("status");
const version = document.getElementById("version");
const updateStatus = document.getElementById("update-status");
const checkUpdate = document.getElementById("check-update");
const installUpdate = document.getElementById("install-update");
const languageEn = document.getElementById("language-en");
const languageZh = document.getElementById("language-zh");
let packaged = false;
let locale = "en";
let localeRevision = 0;
let statusKey = "";

const copy = {
  en: {
    title: "Project Ardor · Connect to server",
    headline: "Wait for your server.\nThen let's continue.",
    detail: "Ardor can't reach the server right now. Start your local service or enter the address of your hosted server.",
    label: "Server address",
    connect: "Connect",
    hint: "Local default: http://127.0.0.1:3000 · Public servers require HTTPS",
    retry: "Try again",
    check: "Check for updates",
    install: "Install and restart",
    footer: "The desktop app only connects you. Your resumes, models, and chats stay on the selected server.",
    connecting: "Connecting…",
    unavailable: "Still unable to connect. Make sure the server is running.",
    canceled: "Server switch canceled.",
    failed: "Connection failed.",
    invalidServer: "Enter a valid server address.",
    httpsRequired: "Public servers must use HTTPS; HTTP is allowed only for this device.",
    rootOnly: "Enter only the server's root address, without an account, path, or parameters.",
    updateFailed: "Could not check for updates. Try again later.",
  },
  "zh-CN": {
    title: "Project Ardor · 连接服务器",
    headline: "等服务器就绪，\n我们继续。",
    detail: "当前无法连接 Ardor。本机使用时请先启动服务；连接公网部署时，填入服务器地址即可。",
    label: "服务器地址",
    connect: "连接",
    hint: "本机默认 http://127.0.0.1:3000 · 公网地址须使用 HTTPS",
    retry: "重新连接",
    check: "检查更新",
    install: "安装并重启",
    footer: "桌面程序只负责连接。你的简历、模型与对话仍在所选服务器上。",
    connecting: "正在连接…",
    unavailable: "还未连接成功，请确认服务器已启动。",
    canceled: "已取消切换。",
    failed: "连接失败。",
    invalidServer: "请输入有效的服务器地址。",
    httpsRequired: "公网地址必须使用 HTTPS；HTTP 仅支持本机。",
    rootOnly: "只填写服务器根地址，不要包含账号、路径或参数。",
    updateFailed: "检查更新失败，请稍后重试。",
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
  retry.textContent = words.retry;
  checkUpdate.textContent = words.check;
  installUpdate.textContent = words.install;
  document.getElementById("footer").textContent = words.footer;
  languageEn.setAttribute("aria-pressed", String(locale === "en"));
  languageZh.setAttribute("aria-pressed", String(locale === "zh-CN"));
  if (statusKey) status.textContent = words[statusKey];
}

async function changeLocale(next) {
  const previous = locale;
  localeRevision += 1;
  renderLocale(next);
  try { await window.ardorDesktop.setLocale(locale); }
  catch { renderLocale(previous); }
}

function renderUpdate(next) {
  updateStatus.textContent = next.message;
  installUpdate.hidden = next.state !== "ready";
  checkUpdate.hidden = next.state === "ready";
  checkUpdate.disabled = !packaged || next.state === "checking" || next.state === "downloading";
}

function connectionErrorKey(error) {
  const message = String(error?.message ?? "");
  if (message.includes("HTTPS")) return "httpsRequired";
  if (/root address|根地址/.test(message)) return "rootOnly";
  if (/valid server address|有效的服务器地址/.test(message)) return "invalidServer";
  return "failed";
}

async function run(action) {
  retry.disabled = true;
  form.querySelector("button").disabled = true;
  statusKey = "connecting";
  status.textContent = copy[locale][statusKey];
  try {
    const result = await action();
    if (!result.connected && result.changed !== false) statusKey = "unavailable";
    if (result.changed === false) statusKey = "canceled";
    status.textContent = copy[locale][statusKey] ?? "";
  } catch (error) { statusKey = connectionErrorKey(error); status.textContent = copy[locale][statusKey]; }
  finally { retry.disabled = false; form.querySelector("button").disabled = false; }
}

const initialLocaleRevision = localeRevision;
window.ardorDesktop.getInfo().then((info) => {
  input.value = info.serverUrl;
  version.textContent = `v${info.version}`;
  packaged = info.packaged;
  if (initialLocaleRevision === localeRevision) renderLocale(info.locale);
  renderUpdate(info.updateStatus);
}).catch(() => { statusKey = "failed"; status.textContent = copy[locale].failed; });
window.ardorDesktop.onUpdateStatus(renderUpdate);
languageEn.addEventListener("click", () => { void changeLocale("en"); });
languageZh.addEventListener("click", () => { void changeLocale("zh-CN"); });
form.addEventListener("submit", (event) => { event.preventDefault(); void run(() => window.ardorDesktop.setServer(input.value)); });
retry.addEventListener("click", () => { void run(() => window.ardorDesktop.retry()); });
checkUpdate.addEventListener("click", () => { void window.ardorDesktop.checkForUpdates().then(renderUpdate, () => { updateStatus.textContent = copy[locale].updateFailed; }); });
installUpdate.addEventListener("click", () => { void window.ardorDesktop.installUpdate(); });
