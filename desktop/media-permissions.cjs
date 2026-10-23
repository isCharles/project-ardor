"use strict";

const { normalizeServerUrl } = require("./server-config.cjs");

function isMicrophoneOnly(details) {
  return Array.isArray(details?.mediaTypes) && details.mediaTypes.length > 0
    && details.mediaTypes.every(type => type === "audio");
}

function microphoneGrantFor(serverUrl) {
  return { allowed: true, origin: normalizeServerUrl(serverUrl), mediaType: "audio" };
}

function allowsRememberedMicrophone(grant, serverUrl) {
  try {
    return grant?.allowed === true && grant.mediaType === "audio"
      && grant.origin === normalizeServerUrl(serverUrl);
  } catch { return false; }
}

module.exports = { isMicrophoneOnly, microphoneGrantFor, allowsRememberedMicrophone };
