"use strict";

function updateErrorKind(error) {
  const detail = String(error?.message ?? error ?? "");
  // A release that is missing its update metadata is a publishing problem, not a
  // missing release: say so instead of falling back to "you're up to date".
  if (/Cannot find latest\.yml|invalid release feed|ERR_UPDATER_INVALID_RELEASE_FEED/i.test(detail)) return "incomplete";
  if (/\b404\b/.test(detail)) return "not-found";
  if (/\b(?:401|403)\b/.test(detail)) return "access";
  if (/ENOTFOUND|EAI_AGAIN|ECONNRESET|ETIMEDOUT|network|fetch failed/i.test(detail)) return "network";
  return "other";
}

// Default to English, matching desktopText, so callers that omit the locale
// do not silently fall back to Chinese copy.
function updateErrorMessage(error, locale = "en") {
  const english = locale === "en";
  const kind = error?.kind ?? updateErrorKind(error);
  if (kind === "incomplete") {
    return english ? "A newer desktop version is still being published. Try again later." : "更新的桌面版本还在发布中，请稍后重试。";
  }
  if (kind === "metadata") {
    return english ? "The update information is damaged. Try again later." : "更新信息不完整或损坏，请稍后重试。";
  }
  if (kind === "not-found") {
    return english ? "Update files are unavailable (404). Check that the public release and its assets have been published." : "更新文件不可访问（404）。请确认公开下载仓库和该版本附件已发布。";
  }
  if (kind === "access") {
    return english ? "The update source denied access. Check that the installer is published in the public release repository." : "更新源拒绝访问。请确认安装包发布在公开下载仓库。";
  }
  if (kind === "network") {
    return english ? "The update source is temporarily unreachable. Check your connection and try again." : "暂时无法连接更新源，请检查网络后重试。";
  }
  return english ? "The update could not be completed. Please try again later." : "暂时无法完成更新，请稍后重试。";
}

module.exports = { updateErrorKind, updateErrorMessage };
