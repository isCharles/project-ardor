"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { DEFAULT_SERVER_URL, normalizeServerUrl, isTrustedAppUrl, isHealthyBackendStatus } = require("../server-config.cjs");

test("local default and HTTPS deployments are accepted", () => {
  assert.equal(normalizeServerUrl(DEFAULT_SERVER_URL), DEFAULT_SERVER_URL);
  assert.equal(normalizeServerUrl(" https://ardor.example.com/ "), "https://ardor.example.com");
  assert.equal(normalizeServerUrl("http://localhost:3000"), "http://localhost:3000");
});

test("insecure remote origins and URL decorations are rejected", () => {
  for (const value of [
    "http://example.com", "http://192.168.1.2:3000", "file:///C:/secret", "javascript:alert(1)",
    "https://user:pass@example.com", "https://example.com/app", "https://example.com/?token=abc",
  ]) assert.throws(() => normalizeServerUrl(value));
});

test("navigation stays on the configured origin", () => {
  assert.equal(isTrustedAppUrl("https://ardor.example.com/app/settings", "https://ardor.example.com"), true);
  assert.equal(isTrustedAppUrl("https://evil.example.com", "https://ardor.example.com"), false);
  assert.equal(isTrustedAppUrl("file:///C:/secret", "https://ardor.example.com"), false);
});

test("the connection probe accepts both authenticated and unauthenticated backend responses", () => {
  assert.equal(isHealthyBackendStatus(200), true);
  assert.equal(isHealthyBackendStatus(401), true);
  assert.equal(isHealthyBackendStatus(502), false);
});
