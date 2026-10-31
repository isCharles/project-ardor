import assert from "node:assert/strict";
import { test } from "node:test";
import { oneLine, runIntents, stripMarkdown, toolSteps } from "../src/lib/run-intents.ts";

test("the first sentence of an intermediate call becomes the visible next step", () => {
  assert.equal(oneLine("我先查一下 11 月 3 日的日历。然后把你说的组会排进去。"), "我先查一下 11 月 3 日的日历。");
  assert.equal(oneLine("Let me check your calendar first. Then I will add the meeting."), "Let me check your calendar first.");
});

test("a bare acknowledgement falls back to the whole comment instead of showing nothing useful", () => {
  assert.equal(oneLine("好的。接下来我会读取日历并确认 11 月 3 日是否空闲。"), "好的。接下来我会读取日历并确认 11 月 3 日是否空闲。");
});

test("long or multi-clause comments are cut to one readable line", () => {
  const line = oneLine("我"); // shorter than the minimum sentence, still returned as-is
  assert.equal(line, "我");
  const long = oneLine(`${"我会先核对日历里的空闲时段".repeat(10)}`);
  assert.ok(Array.from(long).length <= 80, long);
  assert.ok(long.endsWith("…"));
});

test("truncation never splits a surrogate pair", () => {
  const text = "🎯".repeat(60);
  const line = oneLine(text, 10);
  assert.equal(Array.from(line).length, 10);
  assert.ok(!/[\uD800-\uDBFF]$/.test(line.slice(0, -1)));
});

test("markdown noise is stripped from the single line", () => {
  assert.equal(stripMarkdown("**先看简历**：[打开](https://example.com)\n- 然后分析"), "先看简历：打开 然后分析");
});

test("only commentary steps become intent lines and duplicates collapse", () => {
  const steps = [
    { label: "我先读取日历。", status: "COMMENTARY" },
    { label: "读取日历", status: "COMPLETED" },
    { label: "我先读取日历。", status: "COMMENTARY" },
    { label: "   ", status: "COMMENTARY" },
    { label: "接下来整理面试记录。", status: "COMMENTARY" },
  ];
  assert.deepEqual(runIntents(steps), ["我先读取日历。", "接下来整理面试记录。"]);
  assert.deepEqual(toolSteps(steps).map(step => step.label), ["读取日历"]);
});

test("an empty or missing step list renders nothing", () => {
  assert.deepEqual(runIntents([]), []);
  assert.deepEqual(runIntents(null), []);
  assert.deepEqual(runIntents(undefined), []);
  assert.equal(oneLine("   "), "");
});
