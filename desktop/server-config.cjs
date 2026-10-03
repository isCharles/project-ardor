"use strict";

const DEFAULT_SERVER_URL = "http://127.0.0.1:3000";
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]"]);

function normalizeServerUrl(value) {
  if (typeof value !== "string" || value.length > 2048) throw new Error("请输入有效的服务器地址");
  let url;
  try { url = new URL(value.trim()); }
  catch { throw new Error("请输入有效的服务器地址"); }
  const localHttp = url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname);
  if (url.protocol !== "https:" && !localHttp) throw new Error("公网地址必须使用 HTTPS；HTTP 仅支持本机");
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/") {
    throw new Error("只填写服务器根地址，不要包含账号、路径或参数");
  }
  return url.origin;
}

function isTrustedAppUrl(value, serverUrl) {
  try {
    const url = new URL(value);
    return (url.protocol === "http:" || url.protocol === "https:") && url.origin === normalizeServerUrl(serverUrl);
  } catch { return false; }
}

function isHealthyBackendStatus(status) { return status === 200 || status === 401; }

module.exports = { DEFAULT_SERVER_URL, normalizeServerUrl, isTrustedAppUrl, isHealthyBackendStatus };
