import { test } from "node:test";
import assert from "node:assert/strict";
import { scoreCase } from "./agent-eval-assertions.mjs";
import { buildReport, hashOf, stableStringify } from "./agent-eval-report.mjs";

const tool = (name, status, attributes = {}, errorCode = null) => ({ kind: "TOOL", name, status, attributes, errorCode });
const executed = (name) => tool(name, "COMPLETED", { executed: true, approvalSource: "CONFIRMED" });
const trace = (status, spans, totals = { llmCalls: 1, inputTokens: 100, outputTokens: 20 }) => ({ status, spans, totals });

const cases = [
  { id: "ok-read", prompt: "read", expectedTools: ["list_calendar_tasks"] },
  { id: "needs-write", prompt: "write", requiredExecuted: ["create_calendar_task"] },
  { id: "bad-params", prompt: "bad", expectInvalidParams: true, expectToolFailure: true, allowToolFailure: true },
  { id: "held-out", prompt: "held", expectedTools: ["list_resumes"], heldOut: true },
];

function reports() {
  const results = [
    scoreCase(cases[0], trace("COMPLETED", [executed("list_calendar_tasks")]), "ok"),
    scoreCase(cases[1], trace("COMPLETED", [executed("create_calendar_task")]), "ok"),
    scoreCase(cases[2], trace("COMPLETED", [tool("create_calendar_task", "FAILED", {}, "INVALID_PARAM")]), "bad"),
    scoreCase(cases[3], { status: "COMPLETED", spans: [executed("list_resumes")], totals: { llmCalls: 1 } }, "ok"),
  ];
  return buildReport({
    generatedAt: "2026-10-27T00:00:00.000Z", codeSha: "abc123",
    dataset: { datasetVersion: "test", cases }, toolSchema: { list_resumes: { parameters: {} } },
    model: "fake", effectiveParams: { temperature: 0 }, results,
  });
}

test("taskSuccess denominator excludes environment errors", () => {
  const results = [
    scoreCase(cases[0], trace("COMPLETED", [executed("list_calendar_tasks")]), "ok"),
    scoreCase(cases[1], null, undefined, { environmentError: true, errorKind: "TIMEOUT" }),
  ];
  const report = buildReport({ generatedAt: "t", codeSha: "s", dataset: { cases }, toolSchema: null, results });
  assert.equal(report.metrics.taskSuccess.denominator, 1);
  assert.equal(report.metrics.taskSuccess.pass, 1);
  assert.equal(report.metrics.environmentError.denominator, 2);
  assert.equal(report.metrics.environmentError.pass, 1);
});

test("each dimension only counts cases where it is observable", () => {
  const report = reports();
  assert.equal(report.metrics.toolExecuted.denominator, 1);
  assert.equal(report.metrics.toolExecuted.pass, 1);
  assert.equal(report.metrics.paramValid.denominator, 4);
  assert.equal(report.metrics.paramValid.pass, 3);
  assert.equal(report.metrics.safeRejection.denominator, 0);
  assert.equal(report.metrics.safeRejection.rate, null);
});

test("unknown usage is reported as null, never zero", () => {
  const report = reports();
  const heldOut = report.cases.find((item) => item.id === "held-out");
  assert.equal(heldOut.usageMissing, true);
  assert.equal(heldOut.inputTokens, null);
  assert.equal(heldOut.outputTokens, null);
  assert.equal(report.usageMissingCount, 1);
});

test("held-out cases are listed separately", () => {
  const report = reports();
  assert.deepEqual(report.heldOut, [{ id: "held-out", status: "PASS" }]);
});

test("hashes are order-independent and change with the dataset", () => {
  assert.equal(stableStringify({ a: 1, b: 2 }), stableStringify({ b: 2, a: 1 }));
  assert.equal(hashOf({ a: 1, b: 2 }), hashOf({ b: 2, a: 1 }));
  const first = reports();
  const changed = buildReport({
    generatedAt: "t", codeSha: "abc123",
    dataset: { datasetVersion: "test", cases: cases.map((item) => item.id === "ok-read" ? { ...item, prompt: "read v2" } : item) },
    toolSchema: { list_resumes: { parameters: {} } },
    results: [],
  });
  assert.notEqual(first.hashes.dataset, changed.hashes.dataset);
  assert.notEqual(first.hashes.prompt, changed.hashes.prompt);
});
