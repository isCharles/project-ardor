import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

function requireValue(condition, message) {
  if (!condition) throw new Error(message);
}

function requireHash(value, label, allowNull = false) {
  if (allowNull && value == null) return;
  requireValue(typeof value === "string" && /^[a-f0-9]{64}$/.test(value), `${label} must be a SHA-256 hash`);
}

function validateMetric(name, metric) {
  requireValue(metric && Number.isInteger(metric.pass) && Number.isInteger(metric.denominator), `${name} needs integer counts`);
  requireValue(metric.pass >= 0 && metric.pass <= metric.denominator, `${name} has invalid counts`);
  const expected = metric.denominator === 0 ? null : Number((metric.pass / metric.denominator).toFixed(4));
  requireValue(metric.rate === expected, `${name} rate does not match its numerator and denominator`);
}

export function validateReport(report) {
  requireValue(report && typeof report === "object", "Report must be an object");
  requireValue(typeof report.codeSha === "string" && /^[a-f0-9]{40}$/.test(report.codeSha), "codeSha must be a full Git SHA");
  requireValue(Array.isArray(report.cases), "Report cases must be an array");
  requireValue(report.samples === report.cases.length, "Report sample count does not match cases");
  requireValue(new Set(report.cases.map((item) => item.id)).size === report.cases.length, "Report case ids must be unique");
  requireHash(report.hashes?.dataset, "hashes.dataset");
  requireHash(report.hashes?.split, "hashes.split");
  requireHash(report.hashes?.prompt, "hashes.prompt");
  requireHash(report.hashes?.toolSchema, "hashes.toolSchema", true);
  requireValue(report.split?.manifestVersion === 1, "Report needs split manifest v1");
  requireValue(report.split?.datasetVersion === report.datasetVersion, "Report dataset and split versions differ");
  requireValue(report.hashes.split === report.split?.splitHash, "Report split hash differs from split evidence");
  requireValue(Array.isArray(report.split?.heldOutCaseIds) && report.split.heldOutCaseIds.length === 4,
    "AEBB-001 reports must pin four held-out cases");

  for (const item of report.cases) {
    requireValue(item.durationMs == null || (Number.isFinite(item.durationMs) && item.durationMs >= 0), `${item.id} has invalid durationMs`);
    requireValue(item.costUsd == null || (Number.isFinite(item.costUsd) && item.costUsd >= 0), `${item.id} has invalid costUsd`);
    requireValue(item.costMissing === (item.costUsd == null), `${item.id} has inconsistent costMissing`);
    requireValue(item.executionSuccess === (!item.environmentError && item.runStatus !== "UNKNOWN"), `${item.id} has inconsistent executionSuccess`);
  }
  const heldOut = report.cases.filter((item) => item.heldOut).map((item) => item.id).sort();
  requireValue(JSON.stringify(heldOut) === JSON.stringify([...report.heldOut].map((item) => item.id).sort()), "Held-out results do not match case flags");

  for (const [name, metric] of Object.entries(report.metrics ?? {})) validateMetric(name, metric);
  const scorable = report.cases.filter((item) => !item.environmentError).length;
  requireValue(report.metrics?.taskSuccess?.denominator === scorable, "taskSuccess denominator includes environment errors");
  requireValue(report.metrics?.executionSuccess?.denominator === report.samples, "executionSuccess denominator must include every attempt");
  requireValue(report.metrics?.environmentError?.denominator === report.samples, "environmentError denominator must include every attempt");
  requireValue(JSON.stringify(report.metrics?.outcome) === JSON.stringify(report.metrics?.taskSuccess), "Outcome and taskSuccess contracts differ");

  const latency = report.latency;
  requireValue(latency && latency.known + latency.missing === report.samples, "Latency completeness counts do not match samples");
  requireValue(latency.known === report.cases.filter((item) => item.durationMs != null).length, "Latency known count is inconsistent");
  if (latency.known > 0) {
    requireValue(Number.isFinite(latency.p50Ms) && latency.p50Ms >= 0, "Latency p50 is missing");
    requireValue(Number.isFinite(latency.p95Ms) && latency.p95Ms >= latency.p50Ms, "Latency p95 is invalid");
  }

  const cost = report.cost;
  const missing = report.cases.filter((item) => item.costUsd == null).length;
  requireValue(cost && cost.missingCaseCount === missing, "Cost missing count is inconsistent");
  requireValue(cost.knownCaseCount === report.samples - missing, "Cost known count is inconsistent");
  if (missing > 0 || report.samples === 0) {
    requireValue(cost.totalCostUsd == null, "Incomplete cost must not publish a total");
    requireValue(cost.costPerSuccessfulTaskUsd == null, "Incomplete cost must not publish cost per success");
  }
  if (cost.costPerSuccessfulTaskUsd != null) {
    const successful = report.cases.filter((item) => item.status === "PASS").length;
    requireValue(successful > 0 && cost.totalCostUsd != null, "Cost per success needs a total and successful task");
    requireValue(cost.pricing?.source && cost.pricing?.currency === "USD", "Cost per success needs versioned USD pricing");
    requireValue(Math.abs(cost.costPerSuccessfulTaskUsd - Number((cost.totalCostUsd / successful).toFixed(6))) < 0.000001,
      "Cost per success does not match total cost and successful tasks");
  }
  return report;
}

function main() {
  const files = process.argv.slice(2);
  requireValue(files.length > 0, "Usage: node scripts/agent-eval-contract.mjs <report.json> [...]");
  for (const file of files) {
    const report = validateReport(JSON.parse(fs.readFileSync(file, "utf8")));
    console.log(`OK ${file}: ${report.samples} cases, split=${report.hashes.split}, costMissing=${report.cost.missingCaseCount}`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(); }
  catch (error) {
    console.error(`EVALUATION CONTRACT FAILED: ${error.message}`);
    process.exitCode = 1;
  }
}
