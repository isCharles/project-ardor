import assert from "node:assert/strict";
import { test } from "node:test";
import { runSegments } from "../src/lib/run-timeline.ts";

const shape = segments => segments.map(segment => segment.kind === "say"
  ? `say:${segment.text}`
  : `tools:${segment.steps.length}${segment.running ? "!" : ""}`);

test("each spoken line is followed by the tools it announced", () => {
  const segments = runSegments([
    { label: "模型输出与上游等待（非纯思考时间）", status: "MODEL_WAIT", elapsedMs: 12400 },
    { label: "我先查看今天的日历。", status: "COMMENTARY", elapsedMs: 0 },
    { label: "读取日历", status: "COMPLETED", elapsedMs: 19 },
    { label: "读取学习计划", status: "COMPLETED", elapsedMs: 27 },
    { label: "日历是空的，我再核对学习计划。", status: "COMMENTARY", elapsedMs: 0 },
    { label: "更新学习计划", status: "COMPLETED", elapsedMs: 18 },
  ]);
  assert.deepEqual(shape(segments), [
    "say:我先查看今天的日历。",
    "tools:2",
    "say:日历是空的，我再核对学习计划。",
    "tools:1",
  ]);
});

test("the model-wait accounting row never becomes a tool", () => {
  const segments = runSegments([
    { label: "模型输出与上游等待（非纯思考时间）", status: "MODEL_WAIT", elapsedMs: 12000 },
    { key: "status", label: "等待模型输出", elapsedMs: 0 },
    { label: "读取日历", status: "COMPLETED", elapsedMs: 19 },
  ]);
  assert.deepEqual(shape(segments), ["tools:1"]);
});

test("older traces identify the first model-wait row by its fixed label", () => {
  const segments = runSegments([
    { label: "模型输出与上游等待（非纯思考时间）", status: "COMPLETED", elapsedMs: 1200 },
    { label: "读取日历", status: "COMPLETED", elapsedMs: 19 },
  ]);
  assert.deepEqual(shape(segments), ["tools:1"]);
});

test("a running tool marks its group as in progress", () => {
  const segments = runSegments([{ label: "读取日历", done: false }]);
  assert.equal(segments[0].running, true);
  assert.equal(shape(segments)[0], "tools:1!");
});

test("blank commentary is skipped and empty runs produce nothing", () => {
  assert.deepEqual(runSegments([{ label: "   ", status: "COMMENTARY" }]), []);
  assert.deepEqual(runSegments([]), []);
  assert.deepEqual(runSegments(null), []);
  assert.deepEqual(runSegments(undefined), []);
});

test("consecutive commentary keeps its order without inventing a tool group", () => {
  assert.deepEqual(shape(runSegments([
    { label: "第一句。", status: "COMMENTARY" },
    { label: "第二句。", status: "COMMENTARY" },
  ])), ["say:第一句。", "say:第二句。"]);
});
