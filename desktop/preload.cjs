"use strict";

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("ardorDesktop", Object.freeze({
  getInfo: () => ipcRenderer.invoke("desktop:get-info"),
  setLocale: (locale) => ipcRenderer.invoke("desktop:set-locale", locale),
  retry: () => ipcRenderer.invoke("desktop:retry"),
  // Structured result: { changed, connected?, errorCode?, message? }.
  setServerResult: (url) => ipcRenderer.invoke("desktop:set-server", url),
  // Kept for pages served by older Ardor servers, which expect a rejection on invalid input.
  setServer: async (url) => {
    const result = await ipcRenderer.invoke("desktop:set-server", url);
    if (result?.errorCode) throw new Error(result.message);
    return result;
  },
  checkForUpdates: () => ipcRenderer.invoke("desktop:check-update"),
  installUpdate: () => ipcRenderer.invoke("desktop:install-update"),
  onUpdateStatus: (callback) => {
    if (typeof callback !== "function") return () => {};
    const listener = (_event, status) => callback(status);
    ipcRenderer.on("desktop:update-status", listener);
    return () => ipcRenderer.removeListener("desktop:update-status", listener);
  },
}));
