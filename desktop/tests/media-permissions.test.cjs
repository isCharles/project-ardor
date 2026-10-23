"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { isMicrophoneOnly, microphoneGrantFor, allowsRememberedMicrophone } = require("../media-permissions.cjs");

test("installer includes the media permission policy module", () => {
  assert.match(fs.readFileSync(path.join(__dirname, "../electron-builder.yml"), "utf8"),
    /^  - media-permissions\.cjs$/m);
});

test("only explicit audio-only capture is a microphone request", () => {
  assert.equal(isMicrophoneOnly({ mediaTypes: ["audio"] }), true);
  for (const details of [undefined, {}, { mediaTypes: [] }, { mediaTypes: ["video"] },
    { mediaTypes: ["audio", "video"] }, { mediaTypes: ["unknown"] }]) {
    assert.equal(isMicrophoneOnly(details), false);
  }
});

test("remembered permission is bound to the exact normalized server origin", () => {
  const grant = microphoneGrantFor("https://ardor.example/");
  assert.equal(allowsRememberedMicrophone(grant, "https://ardor.example"), true);
  assert.equal(allowsRememberedMicrophone(grant, "https://other.example"), false);
  assert.equal(allowsRememberedMicrophone(grant, "https://ardor.example:8443"), false);
  assert.equal(allowsRememberedMicrophone(grant, "http://127.0.0.1:3000"), false);
});

test("legacy unscoped grants and non-audio grants require new consent", () => {
  for (const grant of [true, { allowed: true }, { allowed: true, origin: "https://ardor.example" },
    { allowed: true, origin: "https://ardor.example", mediaType: "video" },
    { allowed: false, origin: "https://ardor.example", mediaType: "audio" }]) {
    assert.equal(allowsRememberedMicrophone(grant, "https://ardor.example"), false);
  }
});

test("remembered audio permission cannot authorize a camera request", () => {
  const grant = microphoneGrantFor("https://ardor.example");
  assert.equal(isMicrophoneOnly({ mediaTypes: ["video"] })
    && allowsRememberedMicrophone(grant, "https://ardor.example"), false);
});
