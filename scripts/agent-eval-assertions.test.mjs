import { test } from "node:test";
import assert from "node:assert/strict";
import { evaluateCase } from "./agent-eval-assertions.mjs";

const span = (name, status) => ({ kind: "TOOL", name, status });
test("expected tools must actually complete", () => {
  const item = { expectedTools: ["list_calendar_tasks"] };
  for (const status of ["FAILED", "NOT_EXECUTED", "RUNNING"]) {
    assert.equal(evaluateCase(item, { status: "COMPLETED", spans: [span("list_calendar_tasks", status)] }, "").failures.length, 1);
  }
  assert.deepEqual(evaluateCase(item, { status: "COMPLETED", spans: [span("list_calendar_tasks", "COMPLETED")] }, "").failures, []);
});
test("forbidden attempts fail even when blocked and missing records do not pass", () => {
  assert.equal(evaluateCase({ forbiddenTools: ["delete_memory"] }, { status: "COMPLETED", spans: [span("delete_memory", "NOT_EXECUTED")] }, "").failures.length, 1);
  assert.equal(evaluateCase({ expectedTools: ["read"] }, { status: "COMPLETED" }, "").failures.length, 1);
  assert.equal(evaluateCase({}, { status: "FAILED", spans: [] }, "").failures.length, 1);
});
test("answer assertion does not expose expected private text", () => {
  assert.deepEqual(evaluateCase({ mustContain: "private text" }, { status: "COMPLETED", spans: [] }, "other").failures, ["answer assertion failed"]);
});
