"use strict";

function updateErrorKind(error) {
  const detail = String(error?.message ?? error ?? "");
  if (/\b404\b/.test(detail)) return "not-found";
  if (/\b(?:401|403)\b/.test(detail)) return "access";
  if (/ENOTFOUND|EAI_AGAIN|ECONNRESET|ETIMEDOUT|network|fetch failed/i.test(detail)) return "network";
  return "other";
}

function updateErrorMessage(error, locale = "zh-CN") {
  const english = locale === "en";
  const kind = error?.kind ?? updateErrorKind(error);
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
