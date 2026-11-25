import { test } from "node:test";
import assert from "node:assert/strict";
import { scoreCase } from "./agent-eval-assertions.mjs";
import { buildReport, calculateCost, hashOf, stableStringify } from "./agent-eval-report.mjs";

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

test("execution, latency and split evidence are independent of task success", () => {
  const split = { manifestVersion: 1, datasetVersion: "test", splitHash: "a".repeat(64), heldOutCaseIds: ["held-out"] };
  const success = scoreCase(cases[0], { ...trace("COMPLETED", [executed("list_calendar_tasks")]), durationMs: 100 }, "ok");
  const failed = scoreCase(cases[1], { ...trace("FAILED", []), durationMs: 300 }, "not done");
  const environment = { ...scoreCase(cases[3], null, undefined, { environmentError: true, errorKind: "TIMEOUT" }), costUsd: 0.5 };
  const report = buildReport({
    generatedAt: "t", codeSha: "abc123", dataset: { cases }, split, toolSchema: null,
    cost: { currency: "USD", source: "test-price-v1", inputPricePerM: 1, outputPricePerM: 2 },
    results: [success, failed, environment],
  });
  assert.deepEqual(report.metrics.executionSuccess, { pass: 2, denominator: 3, rate: 0.6667 });
  assert.deepEqual(report.metrics.runCompleted, { pass: 1, denominator: 3, rate: 0.3333 });
  assert.deepEqual(report.latency, { known: 2, missing: 1, p50Ms: 100, p95Ms: 300 });
  assert.equal(report.hashes.split, split.splitHash);
  assert.deepEqual(report.split, split);
  assert.equal(report.cases[0].durationMs, 100);
  assert.equal(report.cases[2].durationMs, null);
  assert.equal(report.cases[2].costUsd, 0.5);
  assert.deepEqual(report.cost, {
    currency: "USD",
    pricing: { currency: "USD", source: "test-price-v1", inputPricePerM: 1, outputPricePerM: 2 },
    knownCaseCount: 3, missingCaseCount: 0, knownTotalUsd: 0.50028, totalCostUsd: 0.50028,
    successfulTaskCostUsd: 0.00014, failedTaskCostUsd: 0.00014, environmentErrorCostUsd: 0.5,
    costPerSuccessfulTaskUsd: 0.50028,
  });
});

test("missing prices or cache usage keep cost unknown instead of inventing zero", () => {
  const result = scoreCase(cases[0], trace("COMPLETED", [executed("list_calendar_tasks")]), "ok");
  const report = buildReport({
    generatedAt: "t", codeSha: "abc123", dataset: { cases }, toolSchema: null,
    cost: { currency: "USD", source: "test-price-v1", inputPricePerM: 1, outputPricePerM: 2, cacheReadInputPricePerM: 0.1 },
    results: [result],
  });
  assert.equal(report.cases[0].costUsd, null);
  assert.equal(report.cases[0].costMissing, true);
  assert.equal(report.cost.missingCaseCount, 1);
  assert.equal(report.cost.totalCostUsd, null);
  assert.equal(report.cost.costPerSuccessfulTaskUsd, null);
  assert.equal(calculateCost({ inputTokens: 10, outputTokens: 5 }, null), null);
});

test("zero samples and zero successful tasks have null rates and per-success cost", () => {
  const empty = buildReport({ generatedAt: "t", codeSha: "abc123", dataset: { cases }, results: [] });
  assert.equal(empty.samples, 0);
  assert.equal(empty.metrics.taskSuccess.rate, null);
  assert.equal(empty.latency.p50Ms, null);
  assert.equal(empty.cost.costPerSuccessfulTaskUsd, null);
  const failed = buildReport({
    generatedAt: "t", codeSha: "abc123", dataset: { cases }, results: [scoreCase(cases[1], trace("COMPLETED", []), "no")],
    cost: { currency: "USD", source: "test-price-v1", inputPricePerM: 1, outputPricePerM: 2 },
  });
  assert.equal(failed.cost.totalCostUsd, 0.00014);
  assert.equal(failed.cost.costPerSuccessfulTaskUsd, null);
});

test("answer failures do not rewrite a compliant trajectory", () => {
  const item = { ...cases[0], mustContain: "required phrase" };
  const result = scoreCase(item, trace("COMPLETED", [executed("list_calendar_tasks")]), "missing");
  assert.equal(result.dimensions.taskSuccess, false);
  assert.equal(result.dimensions.trajectorySuccess, true);
});
