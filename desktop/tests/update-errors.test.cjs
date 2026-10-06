"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const { updateErrorMessage } = require("../update-errors.cjs");

test("private GitHub feed errors never expose response headers or cookies", () => {
  const message = updateErrorMessage(new Error('404 GET releases.atom Headers: { "set-cookie": "secret-session" }'));
  assert.match(message, /404/);
  assert.doesNotMatch(message, /secret-session|set-cookie|releases\.atom/);
});

test("network failures have an actionable, short message", () => {
  assert.match(updateErrorMessage(new Error("getaddrinfo ENOTFOUND github.com"), "zh-CN"), /网络/);
});

test("update errors default to English like the rest of the desktop copy", () => {
  assert.match(updateErrorMessage(new Error("getaddrinfo ENOTFOUND github.com")), /unreachable/);
});
