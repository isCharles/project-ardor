const test = require("node:test");
const assert = require("node:assert/strict");
const { navigationState, navigate, menuTemplate, shortcutAction } = require("../window-chrome.cjs");
test("shortcuts work independently of popup creation", () => {
  assert.equal(shortcutAction({ type: "keyDown", control: true, key: "N" }), "new-chat");
  assert.equal(shortcutAction({ type: "keyDown", meta: true, key: "," }), "settings");
  assert.equal(shortcutAction({ type: "keyDown", key: "n" }), null);
  assert.equal(shortcutAction({ type: "keyUp", control: true, key: "n" }), null);
});
test("history cannot navigate to a previous server or untrusted URL", () => {
  const entries = [{ url: "https://old.example/app" }, { url: "https://current.example/app" }, { url: "https://current.example/app/settings" }];
  const calls = [];
  const history = { getAllEntries: () => entries, getActiveIndex: () => 1, goToOffset: (offset) => calls.push(offset) };
  const trusted = (url) => url?.startsWith("https://current.example/") === true;
  assert.deepEqual(navigationState(history, trusted), { back: false, forward: true });
  navigate(history, "back", trusted); assert.deepEqual(calls, []);
  navigate(history, "forward", trusted); assert.deepEqual(calls, [1]);
  assert.throws(() => navigate(history, "javascript:evil", trusted));
});
test("empty history disables both directions", () => {
  assert.deepEqual(navigationState({ getAllEntries: () => [], getActiveIndex: () => -1 }, () => true), { back: false, forward: false });
});
test("only known menus and fixed actions can be requested", () => {
  assert.throws(() => menuTemplate("arbitrary", "en", () => {}, () => {}));
  const actions = [];
  const menu = menuTemplate("file", "zh-CN", (action) => actions.push(action), () => {});
  assert.equal(menu[0].label, "新对话"); menu[0].click(); menu[1].click();
  assert.deepEqual(actions, ["new-chat", "settings"]);
  assert.equal(menuTemplate("edit", "en", () => {}, () => {}).some((item) => item.role === "paste"), true);
});
