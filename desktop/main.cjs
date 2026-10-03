"use strict";

const { app, BrowserWindow, dialog, ipcMain, net, session, shell } = require("electron");
const { autoUpdater } = require("electron-updater");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { DEFAULT_SERVER_URL, normalizeServerUrl, isTrustedAppUrl, isHealthyBackendStatus } = require("./server-config.cjs");

let window;
let serverUrl = DEFAULT_SERVER_URL;
let updateStatus = { state: "idle", message: "可检查更新" };
let updateCheckRunning = false;
let offline = false;
const offlineFile = path.join(__dirname, "offline.html");

function configPath() { return path.join(app.getPath("userData"), "server.json"); }

function readServerUrl() {
  try { return normalizeServerUrl(JSON.parse(fs.readFileSync(configPath(), "utf8")).serverUrl); }
  catch { return DEFAULT_SERVER_URL; }
}

function saveServerUrl(url) {
  const normalized = normalizeServerUrl(url);
  const target = configPath();
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.tmp`;
  fs.writeFileSync(temporary, JSON.stringify({ serverUrl: normalized }), { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, target);
  serverUrl = normalized;
}

function trustedSender(event) {
  const source = event.senderFrame?.url ?? "";
  return source === pathToFileURL(offlineFile).href || isTrustedAppUrl(source, serverUrl);
}

function assertTrusted(event) {
  if (!trustedSender(event) || event.sender !== window?.webContents) throw new Error("不允许从当前页面执行桌面操作");
}

function sendUpdateStatus(state, message, details = {}) {
  updateStatus = { state, message, ...details };
  if (window && !window.isDestroyed()) window.webContents.send("desktop:update-status", updateStatus);
}

async function showOffline() {
  if (!window || window.isDestroyed()) return;
  offline = true;
  await window.loadFile(offlineFile);
}

async function connect() {
  if (!window || window.isDestroyed()) return { connected: false };
  try {
    const response = await net.fetch(`${serverUrl}/`, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const backend = await net.fetch(`${serverUrl}/api/auth/me`, { signal: AbortSignal.timeout(8000), cache: "no-store" });
    if (!isHealthyBackendStatus(backend.status)) throw new Error(`Backend HTTP ${backend.status}`);
    offline = false;
    await window.loadURL(`${serverUrl}/`);
    return { connected: true };
  } catch {
    await showOffline();
    return { connected: false };
  }
}

function openExternalSafe(url) {
  try {
    const parsed = new URL(url);
    if (parsed.protocol === "https:") void shell.openExternal(parsed.toString());
  } catch { /* Ignore malformed links. */ }
}

function configureUpdater() {
  autoUpdater.autoDownload = false;
  autoUpdater.autoInstallOnAppQuit = false;
  autoUpdater.on("checking-for-update", () => sendUpdateStatus("checking", "正在检查更新…"));
  autoUpdater.on("update-available", (info) => {
    sendUpdateStatus("downloading", `发现 ${info.version}，正在下载…`, { version: info.version });
    void autoUpdater.downloadUpdate().catch((error) => sendUpdateStatus("error", `下载失败：${error.message}`));
  });
  autoUpdater.on("update-not-available", () => sendUpdateStatus("current", "已经是最新版本"));
  autoUpdater.on("download-progress", (progress) => {
    sendUpdateStatus("downloading", `正在下载更新 ${Math.round(progress.percent)}%…`, { percent: Math.round(progress.percent) });
  });
  autoUpdater.on("update-downloaded", (info) => sendUpdateStatus("ready", `版本 ${info.version} 已下载，可安装并重启`, { version: info.version }));
  autoUpdater.on("error", (error) => sendUpdateStatus("error", `更新失败：${error.message}`));
}

function createWindow() {
  window = new BrowserWindow({
    width: 1280, height: 820, minWidth: 860, minHeight: 600,
    title: "Project Ardor", icon: path.join(__dirname, "assets", "icon.png"),
    backgroundColor: "#fffaf8",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
    },
  });
  window.webContents.setWindowOpenHandler(({ url }) => { openExternalSafe(url); return { action: "deny" }; });
  window.webContents.on("will-navigate", (event, url) => {
    if (isTrustedAppUrl(url, serverUrl) || (offline && url === pathToFileURL(offlineFile).href)) return;
    event.preventDefault();
    openExternalSafe(url);
  });
  window.webContents.on("did-fail-load", (_event, code, _description, validatedUrl, isMainFrame) => {
    if (isMainFrame && code !== -3 && isTrustedAppUrl(validatedUrl, serverUrl)) void showOffline();
  });
  window.on("closed", () => { window = undefined; });
  void connect();
}

function registerIpc() {
  ipcMain.handle("desktop:get-info", (event) => {
    assertTrusted(event);
    return { version: app.getVersion(), serverUrl, updateStatus, packaged: app.isPackaged };
  });
  ipcMain.handle("desktop:retry", async (event) => { assertTrusted(event); return connect(); });
  ipcMain.handle("desktop:set-server", async (event, value) => {
    assertTrusted(event);
    const next = normalizeServerUrl(value);
    if (next !== serverUrl) {
      const response = await dialog.showMessageBox(window, {
        type: "question", buttons: ["取消", "切换"], defaultId: 0, cancelId: 0,
        title: "切换服务器", message: `要连接到 ${next} 吗？`,
        detail: "登录信息与数据留在各自服务器。请只连接你信任的 Ardor 服务。",
      });
      if (response.response !== 1) return { changed: false };
      saveServerUrl(next);
    }
    return { changed: true, ...(await connect()) };
  });
  ipcMain.handle("desktop:check-update", async (event) => {
    assertTrusted(event);
    if (!app.isPackaged) return { state: "unavailable", message: "开发模式不能检查安装包更新" };
    if (updateCheckRunning) return updateStatus;
    updateCheckRunning = true;
    try { await autoUpdater.checkForUpdates(); return updateStatus; }
    catch (error) { sendUpdateStatus("error", `检查失败：${error.message}`); return updateStatus; }
    finally { updateCheckRunning = false; }
  });
  ipcMain.handle("desktop:install-update", (event) => {
    assertTrusted(event);
    if (updateStatus.state !== "ready") return { installed: false };
    setImmediate(() => autoUpdater.quitAndInstall(false, true));
    return { installed: true };
  });
}

if (app.requestSingleInstanceLock()) {
  app.on("second-instance", () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
  app.whenReady().then(() => {
    serverUrl = readServerUrl();
    session.defaultSession.setPermissionRequestHandler((webContents, permission, callback, details) => {
      if (permission !== "media" || !isTrustedAppUrl(details.requestingUrl, serverUrl) || webContents !== window?.webContents) return callback(false);
      void dialog.showMessageBox(window, {
        type: "question", buttons: ["不允许", "允许"], defaultId: 0, cancelId: 0,
        title: "麦克风权限", message: "允许 Ardor 使用麦克风进行语音面试吗？",
      }).then(({ response }) => callback(response === 1), () => callback(false));
    });
    configureUpdater();
    registerIpc();
    createWindow();
  });
  app.on("window-all-closed", () => app.quit());
} else app.quit();
