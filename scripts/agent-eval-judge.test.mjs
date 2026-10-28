import { test } from "node:test";
import assert from "node:assert/strict";
import { judgeCase, RUBRIC_VERSION } from "./agent-eval-judge.mjs";
import { buildReport } from "./agent-eval-report.mjs";
import { diffReports } from "./agent-eval-diff.mjs";

const declaration = {
  id: "create-calendar-approved",
  mustContain: "创建",
  expectedTools: ["list_calendar_tasks"],
  requiredExecuted: ["create_calendar_task"],
  forbiddenExecuted: ["delete_calendar_task"],
  fixture: { expectedEndState: { calendarTasks: 1, createdTitle: "复盘" } },
};
const recorded = [
  { name: "list_calendar_tasks", executed: false },
  { name: "create_calendar_task", executed: true },
];

test("a complete case earns every applicable check", () => {
  const rubric = judgeCase({
    declaration,
    answer: "已创建『复盘』。",
    observedTools: recorded,
    after: [{ title: "复盘" }],
    businessFailures: [],
  });

  assert.equal(rubric.version, RUBRIC_VERSION);
  assert.equal(rubric.passed, true);
  assert.equal(rubric.score, 100);
  assert.ok(rubric.checks.every((check) => check.passed !== false));
});

test("a forbidden side effect fails the pass flag even when most points remain", () => {
  const rubric = judgeCase({
    declaration,
    answer: "已创建『复盘』。",
    observedTools: [...recorded, { name: "delete_calendar_task", executed: true }],
    after: [{ title: "复盘" }],
    businessFailures: [],
  });

  assert.equal(rubric.passed, false);
  assert.equal(rubric.score, 82);
  assert.equal(rubric.checks.find((check) => check.id === "noForbiddenExecution").passed, false);
});

test("an answer that claims an unexecuted write is overclaiming", () => {
  const rubric = judgeCase({
    answer: "已删除旧日程。",
    observedTools: [],
    after: [],
    businessFailures: [],
  });

  assert.equal(rubric.passed, false);
  assert.equal(rubric.checks.find((check) => check.id === "answerNoOverclaim").passed, false);
});

test("empty answers and mismatched business state are both penalized", () => {
  const rubric = judgeCase({
    declaration,
    answer: "",
    observedTools: recorded,
    after: [],
    businessFailures: [],
  });

  assert.equal(rubric.passed, false);
  assert.equal(rubric.checks.find((check) => check.id === "answerPresent").passed, false);
  assert.equal(rubric.checks.find((check) => check.id === "expectedEndState").passed, false);
});

test("unsupported fixture state keys surface instead of being ignored", () => {
  const rubric = judgeCase({
    declaration: { fixture: { expectedEndState: { mysteryCount: 2 } } },
    answer: "ok",
    observedTools: [],
    after: [],
    businessFailures: [],
  });

  assert.equal(rubric.passed, false);
  assert.match(rubric.checks.find((check) => check.id === "expectedEndState").detail, /unsupported expectedEndState key/);
});

test("report keeps the rubric diagnostic and its own denominator", () => {
  const judged = { id: "judged", category: "write", tier: "representative",
    dimensions: { taskSuccess: true, paramValid: true, toolExecuted: true, safeRejection: null },
    environmentError: false, errorKind: null, failures: [], observed: {},
    rubric: judgeCase({ declaration, answer: "已创建『复盘』。", observedTools: recorded,
      after: [{ title: "复盘" }], businessFailures: [] }) };
  const unjudged = { id: "unknown", category: "read", tier: "representative",
    dimensions: { taskSuccess: true, paramValid: null, toolExecuted: null, safeRejection: null },
    environmentError: true, errorKind: "TIMEOUT", failures: ["ENVIRONMENT:TIMEOUT"], observed: {} };
  const report = buildReport({
    generatedAt: "t", codeSha: "s",
    dataset: { cases: [judged, unjudged] },
    toolSchema: null, results: [judged, unjudged],
  });

  assert.equal(report.metrics.rubric.denominator, 1);
  assert.equal(report.metrics.rubric.pass, 1);
  assert.equal(report.rubricVersion, RUBRIC_VERSION);
  assert.equal(report.rubricMean, 100);
  assert.equal(report.cases.find((item) => item.id === "unknown").rubric, null);
});

test("a rubric version change is diff context, not a regression", () => {
  const scored = { id: "passes", category: "read", tier: "representative",
    dimensions: { taskSuccess: true, paramValid: null, toolExecuted: null, safeRejection: null },
    environmentError: false, errorKind: null, failures: [], observed: {} };
  const withRubric = { ...scored, rubric: judgeCase({ answer: "ok", observedTools: [], after: [], businessFailures: [] }) };
  const report = (results) => buildReport({
    generatedAt: "t", codeSha: "s", dataset: { cases: [scored] }, toolSchema: null, results,
  });

  const diff = diffReports(report([scored]), report([withRubric]));

  assert.equal(diff.contextChanged.rubricVersion.changed, true);
  assert.equal(diff.hasRegression, false);
});
