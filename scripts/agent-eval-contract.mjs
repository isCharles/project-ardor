import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { isDeepStrictEqual } from "node:util";
import { calculateCost, hashOf, sha256 } from "./agent-eval-report.mjs";
import { loadEvaluationDataset } from "./agent-eval-dataset.mjs";

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function requireHash(value, label, allowNull = false) {
  if (allowNull && value == null) return;
  requireValue(typeof value === "string" && /^[a-f0-9]{64}$/.test(value), `${label} must be a SHA-256 hash`);
}

function metric(cases, inDenominator, isPass) {
  const denominator = cases.filter(inDenominator);
  const pass = denominator.filter(isPass).length;
  return { pass, denominator: denominator.length,
    rate: denominator.length === 0 ? null : Number((pass / denominator.length).toFixed(4)) };
}

function requireMetric(name, actual, expected) {
  requireValue(isDeepStrictEqual(actual, expected), `${name} does not match case evidence: expected ${JSON.stringify(expected)}`);
}

function roundedSum(cases) {
  return Number(cases.reduce((total, item) => total + item.costUsd, 0).toFixed(6));
}

function categoryCost(cases) {
  if (cases.length === 0) return 0;
  return cases.every((item) => item.costUsd != null) ? roundedSum(cases) : null;
}

function percentile(values, fraction) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(fraction * sorted.length) - 1)];
}

export function validateReport(report, { expectedDataset = null, expectedToolSchema = null } = {}) {
  requireValue(report && typeof report === "object", "Report must be an object");
  requireValue(typeof report.codeSha === "string" && /^[a-f0-9]{40}$/.test(report.codeSha), "codeSha must be a full Git SHA");
  requireValue(Array.isArray(report.cases), "Report cases must be an array");
  requireValue(report.samples === report.cases.length, "Report sample count does not match cases");
  requireValue(new Set(report.cases.map((item) => item.id)).size === report.cases.length, "Report case ids must be unique");
  requireValue(Array.isArray(report.evaluatedCaseIds) && report.evaluatedCaseIds.length === report.samples,
    "Evaluated case ids must match the sample count");
  requireValue(isDeepStrictEqual([...report.evaluatedCaseIds].sort(), report.cases.map((item) => item.id).sort()),
    "Evaluated case ids do not match case records");
  requireHash(report.hashes?.dataset, "hashes.dataset");
  requireHash(report.hashes?.split, "hashes.split");
  requireHash(report.hashes?.prompt, "hashes.prompt");
  requireHash(report.hashes?.toolSchema, "hashes.toolSchema", true);
  if (report.hashes?.toolSchema != null) {
    requireValue(expectedToolSchema != null, "A tool-schema hash requires the expected schema snapshot");
    requireValue(hashOf(expectedToolSchema) === report.hashes.toolSchema, "Report tool-schema hash differs from the expected snapshot");
  }
  requireValue(report.split?.manifestVersion === 1, "Report needs split manifest v1");
  requireValue(report.split?.datasetVersion === report.datasetVersion, "Report dataset and split versions differ");
  const { splitHash, ...splitEvidence } = report.split;
  requireValue(hashOf(splitEvidence) === splitHash, "Report split hash does not match split evidence");
  requireValue(report.hashes.split === splitHash, "Report split hash differs from split evidence");
  requireValue(Array.isArray(report.split?.heldOutCaseIds) && report.split.heldOutCaseIds.length === 4,
    "AEBB-001 reports must pin four held-out cases");

  if (expectedDataset) {
    requireValue(report.datasetVersion === expectedDataset.datasetVersion, "Report dataset version differs from the pinned dataset");
    requireValue(report.hashes.dataset === expectedDataset.split.datasetHash, "Report dataset hash differs from the pinned dataset");
    requireValue(report.hashes.prompt === sha256(expectedDataset.cases.map((item) => item.prompt ?? "").join("\n")),
      "Report prompt hash differs from the pinned dataset");
    const knownIds = new Set(expectedDataset.cases.map((item) => item.id));
    requireValue(report.evaluatedCaseIds.every((id) => knownIds.has(id)), "Report evaluated an unknown case id");
    requireValue(isDeepStrictEqual(report.split, expectedDataset.split), "Report split evidence differs from the pinned dataset");
  }

  for (const item of report.cases) {
    requireValue(item.durationMs == null || (Number.isFinite(item.durationMs) && item.durationMs >= 0), `${item.id} has invalid durationMs`);
    requireValue(item.costUsd == null || (Number.isFinite(item.costUsd) && item.costUsd >= 0), `${item.id} has invalid costUsd`);
    requireValue(item.costMissing === (item.costUsd == null), `${item.id} has inconsistent costMissing`);
    requireValue(item.costUsd === calculateCost(item, report.cost?.pricing), `${item.id} costUsd does not match its usage and pricing`);
    requireValue(item.executionSuccess === (!item.environmentError && item.runStatus !== "UNKNOWN"), `${item.id} has inconsistent executionSuccess`);
    requireValue(item.runCompleted === (item.runStatus === "COMPLETED"), `${item.id} has inconsistent runCompleted`);
    if (item.usageMissing) requireValue(item.inputTokens == null && item.outputTokens == null, `${item.id} publishes partial usage as complete`);
    if (item.cacheUsageMissing) {
      requireValue(item.cacheReadInputTokens == null && item.cacheCreationInputTokens == null, `${item.id} publishes partial cache usage as complete`);
    }
    if (item.costUsd != null) {
      requireValue(report.cost?.pricing?.source && report.cost.pricing.currency === "USD", `${item.id} has cost without versioned USD pricing`);
      if (item.cacheReadInputTokens > 0) requireValue(report.cost.pricing.cacheReadInputPricePerM != null, `${item.id} has unpriced cache reads`);
      if (item.cacheCreationInputTokens > 0) requireValue(report.cost.pricing.cacheCreationInputPricePerM != null, `${item.id} has unpriced cache creation`);
      if (item.cacheUsageMissing) {
        requireValue(report.cost.pricing.cacheReadInputPricePerM != null && report.cost.pricing.cacheCreationInputPricePerM != null,
          `${item.id} has known cost with unknown cache pricing`);
      }
    }
  }
  requireValue(Array.isArray(report.heldOut), "Report heldOut results must be an array");
  const heldOut = report.cases.filter((item) => item.heldOut).map((item) => ({ id: item.id, status: item.status }));
  requireValue(isDeepStrictEqual([...heldOut].sort((a, b) => a.id.localeCompare(b.id)),
    [...report.heldOut].sort((a, b) => a.id.localeCompare(b.id))), "Held-out results do not match case flags");

  const cases = report.cases;
  requireMetric("executionSuccess", report.metrics?.executionSuccess, metric(cases, () => true, (item) => item.executionSuccess));
  requireMetric("runCompleted", report.metrics?.runCompleted, metric(cases, () => true, (item) => item.runCompleted));
  requireMetric("taskSuccess", report.metrics?.taskSuccess, metric(cases, (item) => !item.environmentError, (item) => item.taskSuccess === true));
  requireMetric("outcome", report.metrics?.outcome, report.metrics?.taskSuccess);
  requireMetric("trajectory", report.metrics?.trajectory, metric(cases, (item) => !item.environmentError, (item) => item.trajectorySuccess === true));
  requireMetric("paramValid", report.metrics?.paramValid, metric(cases, (item) => item.paramValid !== null, (item) => item.paramValid === true));
  requireMetric("toolExecuted", report.metrics?.toolExecuted, metric(cases, (item) => item.toolExecuted !== null, (item) => item.toolExecuted === true));
  requireMetric("safeRejection", report.metrics?.safeRejection, metric(cases, (item) => item.safeRejection !== null, (item) => item.safeRejection === true));
  requireMetric("environmentError", report.metrics?.environmentError, metric(cases, () => true, (item) => item.environmentError === true));
  requireMetric("output", report.metrics?.output, metric(cases, (item) => item.rubric != null, (item) => item.rubric.passed === true));
  requireMetric("rubric", report.metrics?.rubric, report.metrics?.output);

  const durations = cases.map((item) => item.durationMs).filter((value) => value != null);
  const expectedLatency = { known: durations.length, missing: cases.length - durations.length,
    p50Ms: percentile(durations, 0.5), p95Ms: percentile(durations, 0.95) };
  requireValue(isDeepStrictEqual(report.latency, expectedLatency), "Latency summary does not match case durations");
  requireValue(report.usageMissingCount === cases.filter((item) => item.usageMissing).length, "Usage missing count is inconsistent");
  requireValue(report.cacheUsageMissingCount === cases.filter((item) => item.cacheUsageMissing).length, "Cache usage missing count is inconsistent");
  const cacheKnown = cases.length > 0 && cases.every((item) => !item.cacheUsageMissing);
  requireValue(isDeepStrictEqual(report.cacheUsage, {
    readInputTokens: cacheKnown ? cases.reduce((sum, item) => sum + (item.cacheReadInputTokens ?? 0), 0) : null,
    creationInputTokens: cacheKnown ? cases.reduce((sum, item) => sum + (item.cacheCreationInputTokens ?? 0), 0) : null,
  }), "Cache usage aggregate does not match case evidence");

  const priced = cases.filter((item) => item.costUsd != null);
  const missing = cases.length - priced.length;
  const successful = cases.filter((item) => item.status === "PASS");
  const expectedCost = {
    currency: report.cost?.pricing?.currency ?? (priced.length > 0 ? "USD" : null),
    pricing: report.cost?.pricing ?? null,
    knownCaseCount: priced.length,
    missingCaseCount: missing,
    knownTotalUsd: priced.length > 0 ? roundedSum(priced) : null,
    totalCostUsd: missing === 0 && cases.length > 0 ? roundedSum(cases) : null,
    successfulTaskCostUsd: categoryCost(successful),
    failedTaskCostUsd: categoryCost(cases.filter((item) => item.status === "FAIL")),
    environmentErrorCostUsd: categoryCost(cases.filter((item) => item.status === "ERROR")),
    costPerSuccessfulTaskUsd: missing === 0 && cases.length > 0 && successful.length > 0
      ? Number((roundedSum(cases) / successful.length).toFixed(6)) : null,
  };
  requireValue(isDeepStrictEqual(report.cost, expectedCost), "Cost summary does not match case evidence");
  return report;
}

function main() {
  const files = process.argv.slice(2);
  requireValue(files.length > 0, "Usage: node scripts/agent-eval-contract.mjs <report.json> [...]");
  const expectedDataset = loadEvaluationDataset();
  const schemaFile = process.env.ARDOR_EVAL_TOOL_SCHEMA_FILE;
  const expectedToolSchema = schemaFile ? JSON.parse(fs.readFileSync(schemaFile, "utf8")) : null;
  for (const file of files) {
    const report = validateReport(JSON.parse(fs.readFileSync(file, "utf8")), { expectedDataset, expectedToolSchema });
    console.log(`OK ${file}: ${report.samples} cases, split=${report.hashes.split}, costMissing=${report.cost.missingCaseCount}`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); }
  catch (error) {
    console.error(`EVALUATION CONTRACT FAILED: ${error.message}`);
    process.exit(1);
  }
}
