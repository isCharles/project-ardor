import assert from "node:assert/strict";
import { test } from "node:test";
import { runTimeline } from "../src/lib/run-timeline.ts";

const shape = timeline => timeline.rows.map(row => row.kind === "say"
  ? `say:${row.text}`
  : `tool:${row.step.label}`);

test("spoken lines and tools keep their recorded order", () => {
  const timeline = runTimeline([
    { label: "模型输出与上游等待（非纯思考时间）", status: "MODEL_WAIT", elapsedMs: 12400 },
    { label: "我先查看今天的日历。", status: "COMMENTARY", elapsedMs: 0 },
    { label: "读取日历", status: "COMPLETED", elapsedMs: 19 },
    { label: "读取学习计划", status: "COMPLETED", elapsedMs: 27 },
    { label: "日历是空的，我再核对学习计划。", status: "COMMENTARY", elapsedMs: 0 },
    { label: "更新学习计划", status: "COMPLETED", elapsedMs: 18 },
  ]);
  assert.deepEqual(shape(timeline), [
    "say:我先查看今天的日历。",
    "tool:读取日历",
    "tool:读取学习计划",
    "say:日历是空的，我再核对学习计划。",
    "tool:更新学习计划",
  ]);
  assert.deepEqual(timeline.tools.map(step => step.label), ["读取日历", "读取学习计划", "更新学习计划"]);
});

test("the model-wait accounting row never becomes a tool", () => {
  const timeline = runTimeline([
    { label: "模型输出与上游等待（非纯思考时间）", status: "MODEL_WAIT", elapsedMs: 12000 },
    { key: "status", label: "等待模型输出", elapsedMs: 0 },
    { label: "读取日历", status: "COMPLETED", elapsedMs: 19 },
  ]);
  assert.deepEqual(shape(timeline), ["tool:读取日历"]);
  assert.equal(timeline.tools.length, 1);
});

test("older traces identify the first model-wait row by its fixed label", () => {
  const timeline = runTimeline([
    { label: "模型输出与上游等待（非纯思考时间）", status: "COMPLETED", elapsedMs: 1200 },
    { label: "读取日历", status: "COMPLETED", elapsedMs: 19 },
  ]);
  assert.deepEqual(shape(timeline), ["tool:读取日历"]);
});

test("a running tool marks the timeline as in progress", () => {
  const timeline = runTimeline([{ label: "读取日历", done: false }]);
  assert.equal(timeline.running, true);
  assert.equal(timeline.tools.length, 1);
});

test("blank commentary is skipped and empty runs produce nothing", () => {
  assert.deepEqual(runTimeline([{ label: "   ", status: "COMMENTARY" }]).rows, []);
  assert.deepEqual(runTimeline([]).rows, []);
  assert.deepEqual(runTimeline(null).rows, []);
  assert.deepEqual(runTimeline(undefined).rows, []);
});

test("consecutive commentary keeps its order", () => {
  assert.deepEqual(shape(runTimeline([
    { label: "第一句。", status: "COMMENTARY" },
    { label: "第二句。", status: "COMMENTARY" },
  ])), ["say:第一句。", "say:第二句。"]);
});
