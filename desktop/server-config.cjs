"use strict";

const DEFAULT_SERVER_URL = "http://127.0.0.1:3000";
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "[::1]"]);

// Validation failures carry a stable code; callers localize it instead of parsing messages.
class ServerUrlError extends Error {
  constructor(code) {
    super(code);
    this.name = "ServerUrlError";
    this.code = code;
  }
}

function normalizeServerUrl(value) {
  if (typeof value !== "string" || value.length > 2048) throw new ServerUrlError("invalidServer");
  let url;
  try { url = new URL(value.trim()); }
  catch { throw new ServerUrlError("invalidServer"); }
  const localHttp = url.protocol === "http:" && LOOPBACK_HOSTS.has(url.hostname);
  if (url.protocol !== "https:" && !localHttp) throw new ServerUrlError("httpsRequired");
  if (url.username || url.password || url.search || url.hash || url.pathname !== "/") {
    throw new ServerUrlError("rootOnly");
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

module.exports = { DEFAULT_SERVER_URL, ServerUrlError, normalizeServerUrl, isTrustedAppUrl, isHealthyBackendStatus };
