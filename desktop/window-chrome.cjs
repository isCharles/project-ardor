"use strict";

const MENU_NAMES = new Set(["file", "edit", "view", "help"]);
function shortcutAction(input) {
  if (input.type !== "keyDown" || input.isAutoRepeat || input.alt || input.shift || !(input.control || input.meta)) return null;
  if (input.key?.toLowerCase() === "n") return "new-chat";
  if (input.key === ",") return "settings";
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
    { type: "separator" }, { role: "close" },
  ];
  if (name === "edit") return [{ role: "undo" }, { role: "redo" }, { type: "separator" }, { role: "cut" }, { role: "copy" }, { role: "paste" }, { role: "selectAll" }];
  if (name === "view") return [{ label: text("Toggle conversations", "折叠对话栏"), click: () => action("toggle-sidebar") }, { type: "separator" }, { role: "reload" }, { role: "resetZoom" }, { role: "zoomIn" }, { role: "zoomOut" }, { role: "togglefullscreen" }];
  return [{ label: text("About Ardor", "关于 Ardor"), click: about }];
}
module.exports = { navigationState, navigate, menuTemplate, shortcutAction };
