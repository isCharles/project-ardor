import { test } from "node:test";
import assert from "node:assert/strict";
import { buildReport, hashOf } from "./agent-eval-report.mjs";
import { validateReport } from "./agent-eval-contract.mjs";

function validReport() {
  const declaration = { id: "read", prompt: "read", tier: "representative", expectedTools: ["list_calendar_tasks"] };
  const result = {
    id: "read", category: "read", tier: "representative", heldOut: false,
    dimensions: { taskSuccess: true, trajectorySuccess: true, paramValid: true, toolExecuted: null, safeRejection: null },
    environmentError: false, errorKind: null, failures: [],
    observed: { runStatus: "COMPLETED", durationMs: 40, inputTokens: 100, outputTokens: 20,
      cacheReadInputTokens: 0, cacheCreationInputTokens: 0,
      tools: [{ name: "list_calendar_tasks", status: "COMPLETED", executed: true }] },
  };
  const splitEvidence = { manifestVersion: 1, datasetVersion: "2026-11-25.test.v1",
    heldOutCaseIds: ["h1", "h2", "h3", "h4"] };
  const split = { ...splitEvidence, splitHash: hashOf(splitEvidence) };
  return buildReport({
    generatedAt: "2026-11-25T00:00:00.000Z", codeSha: "a".repeat(40),
    dataset: { datasetVersion: "2026-11-25.test.v1", cases: [declaration] },
    split, toolSchema: null, model: "fake", cost: { currency: "USD", source: "test-v1", inputPricePerM: 1, outputPricePerM: 2 },
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
  assert.throws(() => validateReport(report), /taskSuccess/);
});

test("fabricated dataset hashes and metric pass counts are rejected", () => {
  const report = validReport();
  report.hashes.dataset = "c".repeat(64);
  assert.throws(() => validateReport(report, { expectedDataset: {
    datasetVersion: report.datasetVersion,
    cases: [{ id: "read", prompt: "read" }],
    split: { datasetHash: "d".repeat(64) },
  } }), /dataset hash/);
  const fabricated = validReport();
  fabricated.metrics.paramValid.pass = 0;
  fabricated.metrics.paramValid.rate = 0;
  assert.throws(() => validateReport(fabricated), /paramValid/);
});

test("incomplete cost cannot publish a zero total or per-success cost", () => {
  const report = validReport();
  report.cases[0].costUsd = null;
  report.cases[0].costMissing = true;
  report.cost.missingCaseCount = 1;
  report.cost.knownCaseCount = 0;
  report.cost.totalCostUsd = 0;
  report.cost.costPerSuccessfulTaskUsd = 0;
  assert.throws(() => validateReport(report), /cost|Cost/);
});

test("per-case cost is recomputed from usage and versioned pricing", () => {
  const report = validReport();
  report.cases[0].costUsd = 123;
  assert.throws(() => validateReport(report), /costUsd does not match/);
});

test("split evidence must match the pinned dataset", () => {
  const report = validReport();
  const { splitHash, ...splitEvidence } = report.split;
  const expectedDataset = {
    datasetVersion: report.datasetVersion,
    cases: [{ id: "read", prompt: "read" }],
    split: { ...splitEvidence, datasetHash: report.hashes.dataset },
  };
  report.split.heldOutCaseIds[0] = "forged";
  const { splitHash: ignored, ...forgedEvidence } = report.split;
  report.split.splitHash = hashOf(forgedEvidence);
  report.hashes.split = report.split.splitHash;
  assert.throws(() => validateReport(report, { expectedDataset }), /split evidence differs/);
});

test("a tool-schema hash must match the expected snapshot", () => {
  const report = validReport();
  const schema = { list_calendar_tasks: { parameters: {} } };
  report.hashes.toolSchema = hashOf(schema);
  assert.throws(() => validateReport(report), /requires the expected schema snapshot/);
  assert.throws(() => validateReport(report, { expectedToolSchema: { other: {} } }), /tool-schema hash differs/);
  assert.doesNotThrow(() => validateReport(report, { expectedToolSchema: schema }));
});

test("negative latency and inconsistent execution success are rejected", () => {
  const report = validReport();
  report.cases[0].durationMs = -1;
  assert.throws(() => validateReport(report), /invalid durationMs/);
  report.cases[0].durationMs = 40;
  report.cases[0].executionSuccess = false;
  assert.throws(() => validateReport(report), /inconsistent executionSuccess/);
});
