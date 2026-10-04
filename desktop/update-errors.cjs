"use strict";

function updateErrorMessage(error) {
  const detail = String(error?.message ?? error ?? "");
  if (/\b404\b/.test(detail)) {
    return "更新文件不可访问（404）。请确认公开下载仓库和该版本附件已发布。";
  }
  if (/\b(?:401|403)\b/.test(detail)) {
    return "更新源拒绝访问。请确认安装包发布在公开下载仓库。";
  }
  if (/ENOTFOUND|EAI_AGAIN|ECONNRESET|ETIMEDOUT|network|fetch failed/i.test(detail)) {
    return "暂时无法连接更新源，请检查网络后重试。";
  }
  return "暂时无法完成更新，请稍后重试。";
}

module.exports = { updateErrorMessage };
