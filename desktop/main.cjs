"use strict";

const { app, BrowserWindow, dialog, ipcMain, net, session, shell } = require("electron");
const { autoUpdater } = require("electron-updater");
const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { DEFAULT_SERVER_URL, normalizeServerUrl, isTrustedAppUrl, isHealthyBackendStatus } = require("./server-config.cjs");
const { updateErrorKind } = require("./update-errors.cjs");
const { normalizeLocale, desktopText, updateStatusMessage, serverErrorMessage } = require("./i18n.cjs");

let window;
let serverUrl = DEFAULT_SERVER_URL;
let desktopLocale = null;
let updateStatus = { state: "idle", message: desktopText("en", "idle") };
let updateCheckRunning = false;
let offline = false;
const offlineFile = path.join(__dirname, "offline.html");
const UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

function configPath() { return path.join(app.getPath("userData"), "server.json"); }
function localePath() { return path.join(app.getPath("userData"), "locale.json"); }
function currentLocale() { return desktopLocale ?? "en"; }

function readDesktopLocale() {
  try { return normalizeLocale(JSON.parse(fs.readFileSync(localePath(), "utf8")).locale); }
  catch { return null; }
}

function saveDesktopLocale(value) {
  const locale = normalizeLocale(value);
  if (!locale) throw new Error("Invalid locale");
  const target = localePath();
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.tmp`;
  fs.writeFileSync(temporary, JSON.stringify({ locale }), { encoding: "utf8", mode: 0o600 });
  fs.renameSync(temporary, target);
  desktopLocale = locale;
  updateStatus = { ...updateStatus, message: updateStatusMessage(locale, updateStatus) };
  if (window && !window.isDestroyed()) window.webContents.send("desktop:update-status", updateStatus);
}

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
  if (!trustedSender(event) || event.sender !== window?.webContents) throw new Error(desktopText(currentLocale(), "operationDenied"));
}

function sendUpdateStatus(state, details = {}) {
  updateStatus = { state, ...details };
  updateStatus.message = updateStatusMessage(currentLocale(), updateStatus);
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
    // Keep the landing page for the website; the desktop opens login or the
    // workspace directly according to the current server session.
    await window.loadURL(`${serverUrl}${backend.status === 401 ? "/login" : "/app"}`);
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
  autoUpdater.on("checking-for-update", () => sendUpdateStatus("checking"));
  autoUpdater.on("update-available", (info) => {
    sendUpdateStatus("downloading", { version: info.version });
    void autoUpdater.downloadUpdate().catch((error) => sendUpdateStatus("error", { errorKind: updateErrorKind(error) }));
  });
  autoUpdater.on("update-not-available", () => sendUpdateStatus("current"));
  autoUpdater.on("download-progress", (progress) => {
    sendUpdateStatus("downloading", { percent: Math.round(progress.percent) });
  });
  autoUpdater.on("update-downloaded", (info) => sendUpdateStatus("ready", { version: info.version }));
  autoUpdater.on("error", (error) => sendUpdateStatus("error", { errorKind: updateErrorKind(error) }));
}

async function checkForUpdates() {
  if (!app.isPackaged) return { state: "unavailable", message: desktopText(currentLocale(), "unavailable") };
  if (updateCheckRunning || ["downloading", "ready"].includes(updateStatus.state)) return updateStatus;
  updateCheckRunning = true;
  try { await autoUpdater.checkForUpdates(); return updateStatus; }
  catch (error) { sendUpdateStatus("error", { errorKind: updateErrorKind(error) }); return updateStatus; }
  finally { updateCheckRunning = false; }
}

function scheduleUpdateChecks() {
  if (!app.isPackaged) return;
  setTimeout(() => { void checkForUpdates(); }, 20_000);
  setInterval(() => { void checkForUpdates(); }, UPDATE_CHECK_INTERVAL_MS);
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
    return { version: app.getVersion(), serverUrl, locale: desktopLocale, updateStatus, packaged: app.isPackaged };
  });
  ipcMain.handle("desktop:set-locale", (event, value) => {
    assertTrusted(event);
    saveDesktopLocale(value);
    return { locale: desktopLocale };
  });
  ipcMain.handle("desktop:retry", async (event) => { assertTrusted(event); return connect(); });
  ipcMain.handle("desktop:set-server", async (event, value) => {
    assertTrusted(event);
    let next;
    try { next = normalizeServerUrl(value); }
    catch (error) { throw new Error(serverErrorMessage(currentLocale(), error)); }
    if (next !== serverUrl) {
      const response = await dialog.showMessageBox(window, {
        type: "question", buttons: [desktopText(currentLocale(), "cancel"), desktopText(currentLocale(), "switch")], defaultId: 0, cancelId: 0,
        title: desktopText(currentLocale(), "switchTitle"), message: desktopText(currentLocale(), "switchMessage", { server: next }),
        detail: desktopText(currentLocale(), "switchDetail"),
      });
      if (response.response !== 1) return { changed: false };
      saveServerUrl(next);
    }
    return { changed: true, ...(await connect()) };
  });
  ipcMain.handle("desktop:check-update", async (event) => {
    assertTrusted(event);
    return checkForUpdates();
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
    desktopLocale = readDesktopLocale();
    updateStatus.message = updateStatusMessage(currentLocale(), updateStatus);
    session.defaultSession.setPermissionRequestHandler((webContents, permission, callback, details) => {
      if (permission !== "media" || !isTrustedAppUrl(details.requestingUrl, serverUrl) || webContents !== window?.webContents) return callback(false);
      void dialog.showMessageBox(window, {
        type: "question", buttons: [desktopText(currentLocale(), "deny"), desktopText(currentLocale(), "allow")], defaultId: 0, cancelId: 0,
        title: desktopText(currentLocale(), "microphoneTitle"), message: desktopText(currentLocale(), "microphoneMessage"),
      }).then(({ response }) => callback(response === 1), () => callback(false));
    });
    configureUpdater();
    registerIpc();
    createWindow();
    scheduleUpdateChecks();
  });
  app.on("window-all-closed", () => app.quit());
} else app.quit();
