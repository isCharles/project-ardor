import { test } from "node:test";
import assert from "node:assert/strict";
import { scoreCase } from "./agent-eval-assertions.mjs";

const tool = (name, status, attributes = {}, errorCode = null) => ({ kind: "TOOL", name, status, attributes, errorCode });
const executed = (name) => tool(name, "COMPLETED", { executed: true, approvalSource: "CONFIRMED" });
const proposal = (name) => tool(name, "COMPLETED", { proposal: true, approvalSource: "PROPOSAL", executed: false });
const trace = (status, spans, totals = { llmCalls: 1, inputTokens: 100, outputTokens: 20 }) => ({ status, spans, totals });

test("a required write that only produced a proposal is not task success", () => {
  const item = { id: "create", requiredExecuted: ["create_calendar_task"] };
  const result = scoreCase(item, trace("COMPLETED", [proposal("create_calendar_task")]), "已创建");
  assert.equal(result.dimensions.taskSuccess, false);
  assert.equal(result.dimensions.toolExecuted, false);
  assert.match(result.failures.join(" "), /only produced a proposal/);
});

test("a required write that actually executed passes", () => {
  const item = { id: "create", requiredExecuted: ["create_calendar_task"] };
  const result = scoreCase(item, trace("COMPLETED", [executed("create_calendar_task")]), "已创建");
  assert.equal(result.dimensions.taskSuccess, true);
  assert.equal(result.dimensions.toolExecuted, true);
});

test("a completed run with a missing required tool is still a failure", () => {
  const result = scoreCase({ id: "create", requiredExecuted: ["create_calendar_task"] }, trace("COMPLETED", []), "ok");
  assert.equal(result.dimensions.taskSuccess, false);
  assert.match(result.failures.join(" "), /never recorded/);
});

test("an executed forbidden side effect fails even if the run completed", () => {
  const item = { id: "read-only", expectedTools: ["list_calendar_tasks"], forbiddenExecuted: ["create_calendar_task"] };
  const result = scoreCase(item, trace("COMPLETED", [executed("list_calendar_tasks"), executed("create_calendar_task")]), "ok");
  assert.equal(result.dimensions.taskSuccess, false);
  assert.match(result.failures.join(" "), /forbidden side effect: create_calendar_task actually executed/);
});

test("a business error masked as success fails and does not leak expected text into the log", () => {
  const item = { id: "masked", expectToolFailure: true, allowToolFailure: true, mustNotContain: "已成功创建" };
  const result = scoreCase(item, trace("COMPLETED", [executed("create_calendar_task")]), "已成功创建日程");
  assert.equal(result.dimensions.taskSuccess, false);
  assert.match(result.failures.join(" "), /expected a tool failure/);
  assert.match(result.failures.join(" "), /forbidden content present in answer/);
});

test("an expected tool failure must actually be recorded", () => {
  const item = { id: "fails", expectToolFailure: true, allowToolFailure: true };
  assert.equal(scoreCase(item, trace("COMPLETED", []), "ok").dimensions.taskSuccess, false);
  const recorded = scoreCase(item, trace("COMPLETED", [tool("search_web", "FAILED", {}, "TOOL_FAILED")]), "搜索失败");
  assert.equal(recorded.dimensions.taskSuccess, true);
});

test("intentional invalid params are a pass with paramValid=false", () => {
  const item = { id: "bad-params", expectInvalidParams: true, expectToolFailure: true, allowToolFailure: true, forbiddenExecuted: ["create_calendar_task"] };
  const result = scoreCase(item, trace("COMPLETED", [tool("create_calendar_task", "FAILED", {}, "INVALID_PARAM")]), "参数不合法");
  assert.equal(result.dimensions.taskSuccess, true);
  assert.equal(result.dimensions.paramValid, false);
});

test("an invalid-params case that silently accepts the params fails", () => {
  const item = { id: "bad-params", expectInvalidParams: true, expectToolFailure: true, allowToolFailure: true, forbiddenExecuted: ["create_calendar_task"] };
  const result = scoreCase(item, trace("COMPLETED", [executed("create_calendar_task")]), "已创建");
  assert.equal(result.dimensions.taskSuccess, false);
  assert.match(result.failures.join(" "), /expected invalid parameters to be rejected/);
});

test("write-then-response-lost must show exactly one executed write", () => {
  const item = { id: "lost", requiredExecuted: ["create_calendar_task"], expectSingleExecutedWrite: true };
  assert.equal(scoreCase(item, trace("COMPLETED", [executed("create_calendar_task")]), "").dimensions.taskSuccess, true);
  const duplicated = scoreCase(item, trace("COMPLETED", [executed("create_calendar_task"), executed("create_calendar_task")]), "");
  assert.equal(duplicated.dimensions.taskSuccess, false);
  assert.match(duplicated.failures.join(" "), /exactly one executed write/);
});

test("proposal-only cases require a proposal and reject execution", () => {
  const item = { id: "propose", allowProposalOnly: true, expectProposed: ["delete_memory"], forbiddenExecuted: ["delete_memory"] };
  assert.equal(scoreCase(item, trace("COMPLETED", [proposal("delete_memory")]), "请确认").dimensions.taskSuccess, true);
  const executedAnyway = scoreCase(item, trace("COMPLETED", [executed("delete_memory")]), "已删除");
  assert.equal(executedAnyway.dimensions.taskSuccess, false);
  assert.match(executedAnyway.failures.join(" "), /executed without confirmation/);
});

test("safe rejection allows read tools but forbids the mutating tool", () => {
  const item = { id: "reject", expectSafeRejection: true, forbiddenExecuted: ["update_calendar_task"] };
  assert.equal(scoreCase(item, trace("COMPLETED", [executed("list_calendar_tasks")]), "无权修改").dimensions.safeRejection, true);
  const violated = scoreCase(item, trace("COMPLETED", [executed("update_calendar_task")]), "已修改");
  assert.equal(violated.dimensions.safeRejection, false);
});

test("clarification requires a completed run with no mutating write", () => {
  const item = { id: "clarify", expectClarification: true, forbiddenExecuted: ["create_calendar_task"] };
  assert.equal(scoreCase(item, trace("COMPLETED", []), "请告诉我时间").dimensions.safeRejection, true);
  const acted = scoreCase(item, trace("COMPLETED", [executed("create_calendar_task")]), "已创建");
  assert.equal(acted.dimensions.taskSuccess, false);
});

test("a null trace is an environment error, not a scored business failure", () => {
  const result = scoreCase({ id: "env" }, null, undefined, { environmentError: true, errorKind: "TIMEOUT" });
  assert.equal(result.environmentError, true);
  assert.equal(result.dimensions.taskSuccess, false);
  assert.equal(result.dimensions.paramValid, null);
  assert.equal(result.dimensions.toolExecuted, null);
  assert.equal(result.errorKind, "TIMEOUT");
});

test("unknown usage stays unknown instead of becoming zero", () => {
  const result = scoreCase({ id: "u" }, { status: "COMPLETED", spans: [], totals: { llmCalls: 1 } }, "");
  assert.equal(result.observed.usageMissing, true);
  assert.equal(result.observed.inputTokens, null);
  assert.equal(result.observed.outputTokens, null);
  const known = scoreCase({ id: "u" }, trace("COMPLETED", []), "");
  assert.equal(known.observed.usageMissing, false);
  assert.equal(known.observed.inputTokens, 100);
});

test("answer assertion does not expose expected private text", () => {
  const result = scoreCase({ id: "private", mustContain: "private text" }, trace("COMPLETED", []), "other");
  assert.deepEqual(result.failures, ["answer assertion failed"]);
});
