"use strict";

const form = document.getElementById("server-form");
const input = document.getElementById("server-url");
const retry = document.getElementById("retry");
const status = document.getElementById("status");
const version = document.getElementById("version");
const updateStatus = document.getElementById("update-status");
const checkUpdate = document.getElementById("check-update");
const installUpdate = document.getElementById("install-update");
let packaged = false;

function renderUpdate(next) {
  updateStatus.textContent = next.message;
  installUpdate.hidden = next.state !== "ready";
  checkUpdate.hidden = next.state === "ready";
  checkUpdate.disabled = !packaged || next.state === "checking" || next.state === "downloading";
}

async function run(action) {
  retry.disabled = true;
  form.querySelector("button").disabled = true;
  status.textContent = "正在连接…";
  try {
    const result = await action();
    if (!result.connected && result.changed !== false) status.textContent = "还未连接成功，请确认服务器已启动。";
    if (result.changed === false) status.textContent = "已取消切换。";
  } catch (error) { status.textContent = error.message || "连接失败"; }
  finally { retry.disabled = false; form.querySelector("button").disabled = false; }
}

window.ardorDesktop.getInfo().then((info) => {
  input.value = info.serverUrl;
  version.textContent = `v${info.version}`;
  packaged = info.packaged;
  renderUpdate(info.updateStatus);
});
window.ardorDesktop.onUpdateStatus(renderUpdate);
form.addEventListener("submit", (event) => { event.preventDefault(); void run(() => window.ardorDesktop.setServer(input.value)); });
retry.addEventListener("click", () => { void run(() => window.ardorDesktop.retry()); });
checkUpdate.addEventListener("click", () => { void window.ardorDesktop.checkForUpdates().then(renderUpdate, (error) => { updateStatus.textContent = error.message; }); });
installUpdate.addEventListener("click", () => { void window.ardorDesktop.installUpdate(); });
