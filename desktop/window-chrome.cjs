"use strict";

const MENU_NAMES = new Set(["file", "edit", "view", "help"]);
function shortcutAction(input) {
  if (input.type !== "keyDown" || input.isAutoRepeat) return null;
  if (input.alt && !input.control && !input.meta && !input.shift) {
    if (input.key === "ArrowLeft") return "back";
    if (input.key === "ArrowRight") return "forward";
    return null;
  }
  if (input.alt || input.shift || !(input.control || input.meta)) return null;
  if (input.key?.toLowerCase() === "n") return "new-chat";
  if (input.key === ",") return "settings";
  if (input.key?.toLowerCase() === "b") return "toggle-sidebar";
  return null;
}
function navigationState(history, trusted) {
  const entries = history.getAllEntries();
  const index = history.getActiveIndex();
  return { back: index > 0 && trusted(entries[index - 1]?.url), forward: index + 1 < entries.length && trusted(entries[index + 1]?.url) };
}
function navigate(history, direction, trusted) {
  if (!["back", "forward"].includes(direction)) throw new Error("Invalid navigation command");
  if (navigationState(history, trusted)[direction]) history.goToOffset(direction === "back" ? -1 : 1);
  return navigationState(history, trusted);
}
function menuTemplate(name, locale, action, about) {
  if (!MENU_NAMES.has(name)) throw new Error("Invalid menu");
  const text = (en, zh) => locale === "zh-CN" ? zh : en;
  if (name === "file") return [
    { label: text("New chat", "新对话"), accelerator: "CmdOrCtrl+N", click: () => action("new-chat") },
    { label: text("Settings", "设置"), accelerator: "CmdOrCtrl+,", click: () => action("settings") },
    { type: "separator" },
    { label: text("Log out", "退出登录"), click: () => action("logout") },
    { type: "separator" },
    { role: "close" },
    { role: "quit" },
  ];
  if (name === "edit") return [{ role: "undo" }, { role: "redo" }, { type: "separator" }, { role: "cut" }, { role: "copy" }, { role: "paste" }, { role: "selectAll" }];
  if (name === "view") return [
    { label: text("Toggle conversations", "折叠对话栏"), accelerator: "CmdOrCtrl+B", click: () => action("toggle-sidebar") },
    { label: text("Back", "后退"), accelerator: "Alt+Left", click: () => action("back") },
    { label: text("Forward", "前进"), accelerator: "Alt+Right", click: () => action("forward") },
    { type: "separator" },
    { role: "reload" }, { role: "resetZoom" }, { role: "zoomIn" }, { role: "zoomOut" }, { role: "togglefullscreen" },
  ];
  return [
    { label: text("Keyboard shortcuts", "键盘快捷键"), click: () => action("shortcuts") },
    { label: text("About Ardor", "关于 Ardor"), click: about },
  ];
}
module.exports = { navigationState, navigate, menuTemplate, shortcutAction };
