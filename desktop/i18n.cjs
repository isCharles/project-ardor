"use strict";

const LOCALES = new Set(["en", "zh-CN"]);

function normalizeLocale(value) { return LOCALES.has(value) ? value : null; }

const COPY = {
  en: {
    idle: "Ready to check for updates",
    checking: "Checking for updates…",
    downloading: "Downloading version {version}…",
    progress: "Downloading update {percent}%…",
    current: "You're up to date",
    ready: "Version {version} is ready to install",
    unavailable: "Updates are unavailable in development mode",
    switchTitle: "Switch server",
    switchMessage: "Connect to {server}?",
    switchDetail: "Sign-in and data stay on their respective servers. Connect only to an Ardor server you trust.",
    cancel: "Cancel",
    switch: "Switch",
    microphoneTitle: "Microphone access",
    microphoneMessage: "Allow Ardor to use your microphone for voice interviews?",
    deny: "Don't allow",
    allow: "Allow",
    invalidServer: "Enter a valid server address.",
    httpsRequired: "Public servers must use HTTPS; HTTP is allowed only for this device.",
    rootOnly: "Enter only the server's root address, without an account, path, or parameters.",
  },
  "zh-CN": {
    idle: "可检查更新",
    checking: "正在检查更新…",
    downloading: "发现 {version}，正在下载…",
    progress: "正在下载更新 {percent}%…",
    current: "已经是最新版本",
    ready: "版本 {version} 已下载，可安装并重启",
    unavailable: "开发模式不能检查安装包更新",
    switchTitle: "切换服务器",
    switchMessage: "要连接到 {server} 吗？",
    switchDetail: "登录信息与数据留在各自服务器。请只连接你信任的 Ardor 服务。",
    cancel: "取消",
    switch: "切换",
    microphoneTitle: "麦克风权限",
    microphoneMessage: "允许 Ardor 使用麦克风进行语音面试吗？",
    deny: "不允许",
    allow: "允许",
    invalidServer: "请输入有效的服务器地址",
    httpsRequired: "公网地址必须使用 HTTPS；HTTP 仅支持本机",
    rootOnly: "只填写服务器根地址，不要包含账号、路径或参数",
  },
};

function desktopText(locale, key, values = {}) {
  const template = COPY[normalizeLocale(locale) ?? "en"][key];
  if (!template) throw new Error(`Unknown desktop text key: ${key}`);
  return template.replace(/\{(\w+)\}/g, (_match, name) => String(values[name] ?? ""));
}

function updateStatusMessage(locale, status) {
  if (status.state === "error") {
    const { updateErrorMessage } = require("./update-errors.cjs");
    return updateErrorMessage({ kind: status.errorKind }, locale);
  }
  const key = status.state === "downloading" && status.percent != null ? "progress" : status.state;
  return desktopText(locale, key, status);
}

function serverErrorMessage(locale, error) {
  const message = String(error?.message ?? error ?? "");
  const key = message.includes("公网地址必须使用 HTTPS") ? "httpsRequired"
    : message.includes("只填写服务器根地址") ? "rootOnly" : "invalidServer";
  return desktopText(locale, key);
}

module.exports = { normalizeLocale, desktopText, updateStatusMessage, serverErrorMessage };
