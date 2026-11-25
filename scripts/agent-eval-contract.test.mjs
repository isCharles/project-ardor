import { test } from "node:test";
import assert from "node:assert/strict";
import { buildReport } from "./agent-eval-report.mjs";
import { validateReport } from "./agent-eval-contract.mjs";

function validReport() {
  const declaration = { id: "read", prompt: "read", tier: "representative", expectedTools: ["list_calendar_tasks"] };
  const result = {
    id: "read", category: "read", tier: "representative", heldOut: false,
    dimensions: { taskSuccess: true, trajectorySuccess: true, paramValid: true, toolExecuted: null, safeRejection: null },
    environmentError: false, errorKind: null, failures: [],
    observed: { runStatus: "COMPLETED", durationMs: 40, inputTokens: 100, outputTokens: 20,
      tools: [{ name: "list_calendar_tasks", status: "COMPLETED", executed: true }] },
  };
  return buildReport({
    generatedAt: "2026-11-25T00:00:00.000Z", codeSha: "a".repeat(40),
    dataset: { datasetVersion: "2026-11-25.test.v1", cases: [declaration] },
    split: { manifestVersion: 1, datasetVersion: "2026-11-25.test.v1", splitHash: "b".repeat(64),
      heldOutCaseIds: ["h1", "h2", "h3", "h4"] },
    toolSchema: null, model: "fake", cost: { currency: "USD", source: "test-v1", inputPricePerM: 1, outputPricePerM: 2 },
    results: [result],
  });
}

test("a complete evidence report passes the CI contract", () => {
  assert.doesNotThrow(() => validateReport(validReport()));
});

test("missing split evidence is rejected", () => {
  const report = validReport();
  report.split = null;
  report.hashes.split = null;
  assert.throws(() => validateReport(report), /split/);
});

test("tampered metric denominators are rejected", () => {
  const report = validReport();
  report.metrics.taskSuccess.denominator = 2;
  assert.throws(() => validateReport(report), /taskSuccess rate/);
});

test("incomplete cost cannot publish a zero total or per-success cost", () => {
  const report = validReport();
  report.cases[0].costUsd = null;
  report.cases[0].costMissing = true;
  report.cost.missingCaseCount = 1;
  report.cost.knownCaseCount = 0;
  report.cost.totalCostUsd = 0;
  report.cost.costPerSuccessfulTaskUsd = 0;
  assert.throws(() => validateReport(report), /Incomplete cost/);
});

test("negative latency and inconsistent execution success are rejected", () => {
  const report = validReport();
  report.cases[0].durationMs = -1;
  assert.throws(() => validateReport(report), /invalid durationMs/);
  report.cases[0].durationMs = 40;
  report.cases[0].executionSuccess = false;
  assert.throws(() => validateReport(report), /inconsistent executionSuccess/);
});
