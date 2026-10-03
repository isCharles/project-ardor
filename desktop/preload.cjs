"use strict";

const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("ardorDesktop", Object.freeze({
  getInfo: () => ipcRenderer.invoke("desktop:get-info"),
  retry: () => ipcRenderer.invoke("desktop:retry"),
  setServer: (url) => ipcRenderer.invoke("desktop:set-server", url),
  checkForUpdates: () => ipcRenderer.invoke("desktop:check-update"),
  installUpdate: () => ipcRenderer.invoke("desktop:install-update"),
  onUpdateStatus: (callback) => {
    if (typeof callback !== "function") return () => {};
    const listener = (_event, status) => callback(status);
    ipcRenderer.on("desktop:update-status", listener);
    return () => ipcRenderer.removeListener("desktop:update-status", listener);
  },
}));
