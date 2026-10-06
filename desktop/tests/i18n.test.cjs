"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { normalizeLocale, desktopText, updateStatusMessage, serverErrorKey, serverErrorMessage } = require("../i18n.cjs");
const { ServerUrlError } = require("../server-config.cjs");

test("desktop locale is limited to supported languages and defaults to English", () => {
  assert.equal(normalizeLocale("en"), "en");
  assert.equal(normalizeLocale("zh-CN"), "zh-CN");
  assert.equal(normalizeLocale("fr"), null);
  assert.equal(desktopText(null, "idle"), "Ready to check for updates");
});

test("native prompts and update status follow the selected locale", () => {
  assert.equal(desktopText("en", "switchMessage", { server: "https://ardor.example" }), "Connect to https://ardor.example?");
  assert.equal(desktopText("zh-CN", "microphoneTitle"), "麦克风权限");
  assert.match(desktopText("en", "operationDenied"), /not allowed/);
  assert.match(desktopText("zh-CN", "operationDenied"), /不允许/);
  assert.match(updateStatusMessage("en", { state: "downloading", percent: 42 }), /42%/);
  assert.match(updateStatusMessage("zh-CN", { state: "ready", version: "0.5.1" }), /版本 0\.5\.1/);
});

test("update failures remain short and do not expose provider headers", () => {
  const status = { state: "error", errorKind: "not-found" };
  assert.match(updateStatusMessage("en", status), /404/);
  assert.match(updateStatusMessage("zh-CN", status), /404/);
  assert.doesNotMatch(JSON.stringify(status), /set-cookie|session/);
});

test("server URL validation failures are localized from their code", () => {
  const error = new ServerUrlError("httpsRequired");
  assert.match(serverErrorMessage("en", error), /HTTPS/);
  assert.match(serverErrorMessage("zh-CN", error), /公网地址/);
  assert.equal(serverErrorKey(new ServerUrlError("rootOnly")), "rootOnly");
  // Anything without a known code, including plain errors, falls back to the generic key.
  assert.equal(serverErrorKey(new Error("公网地址必须使用 HTTPS")), "invalidServer");
  assert.equal(serverErrorKey(undefined), "invalidServer");
});
