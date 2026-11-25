/**
 * Report building and hashing for the Agent-evaluation harness.
 *
 * A report is reproducible evidence: it pins the exact code SHA, the dataset,
 * prompt and tool-schema hashes, the model and effective parameters, the sample
 * count and every per-case result. Metrics are reported as explicit numerators
 * and denominators so a reader can recompute any rate; "Unknown" usage is never
 * coerced to zero, and environment errors are counted separately rather than
 * silently folded into task success.
 */
import { createHash } from "node:crypto";

export function sha256(text) {
  return createHash("sha256").update(text, "utf8").digest("hex");
}

/** Deterministic JSON so hashes do not depend on key insertion order. */
export function stableStringify(value) {
  if (value === null || typeof value !== "object") return JSON.stringify(value) ?? "null";
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(",")}]`;
  const keys = Object.keys(value).sort();
  return `{${keys.map((key) => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
}

export function hashOf(value) {
  return sha256(stableStringify(value));
}

function rate(results, inDenominator, isPass) {
  const denominator = results.filter(inDenominator);
  const pass = denominator.filter(isPass).length;
  return {
    pass,
    denominator: denominator.length,
    rate: denominator.length === 0 ? null : Number((pass / denominator.length).toFixed(4)),
  };
}

function nonNegative(value) {
  return typeof value === "number" && Number.isFinite(value) && value >= 0;
}

export function calculateCost(observed = {}, pricing) {
  if (!pricing || pricing.currency !== "USD" || !nonNegative(pricing.inputPricePerM)
      || !nonNegative(pricing.outputPricePerM) || !pricing.source || observed.usageMissing === true) return null;
  if (!nonNegative(observed.inputTokens) || !nonNegative(observed.outputTokens)) return null;
  const cacheRead = observed.cacheReadInputTokens;
  const cacheCreation = observed.cacheCreationInputTokens;
  if ((observed.cacheUsageMissing === true || cacheRead > 0) && pricing.cacheReadInputPricePerM == null) return null;
  if ((observed.cacheUsageMissing === true || cacheCreation > 0) && pricing.cacheCreationInputPricePerM == null) return null;
  let cost = (observed.inputTokens / 1_000_000) * pricing.inputPricePerM
    + (observed.outputTokens / 1_000_000) * pricing.outputPricePerM;
  if (pricing.cacheReadInputPricePerM != null) {
    if (!nonNegative(pricing.cacheReadInputPricePerM) || !nonNegative(cacheRead)) return null;
    cost += (cacheRead / 1_000_000) * pricing.cacheReadInputPricePerM;
  }
  if (pricing.cacheCreationInputPricePerM != null) {
    if (!nonNegative(pricing.cacheCreationInputPricePerM) || !nonNegative(cacheCreation)) return null;
    cost += (cacheCreation / 1_000_000) * pricing.cacheCreationInputPricePerM;
  }
  return Number(cost.toFixed(6));
}

function caseRecord(result) {
  const observed = result.observed ?? {};
  const status = result.environmentError ? "ERROR" : result.dimensions.taskSuccess ? "PASS" : "FAIL";
  const costUsd = nonNegative(result.costUsd) ? result.costUsd : null;
  return {
    id: result.id,
    category: result.category,
    tier: result.tier,
    heldOut: result.heldOut,
    status,
    executionSuccess: result.environmentError !== true && observed.runStatus !== "UNKNOWN",
    runCompleted: observed.runStatus === "COMPLETED",
    businessReadBackAvailable: observed.business != null,
    taskSuccess: result.dimensions.taskSuccess,
    trajectorySuccess: result.dimensions.trajectorySuccess ?? null,
    paramValid: result.dimensions.paramValid,
    toolExecuted: result.dimensions.toolExecuted,
    safeRejection: result.dimensions.safeRejection,
    environmentError: result.environmentError,
    errorKind: result.errorKind,
    failures: result.failures,
    runStatus: observed.runStatus ?? "UNKNOWN",
    tools: observed.tools ?? [],
    business: observed.business ?? null,
    durationMs: nonNegative(observed.durationMs) ? observed.durationMs : null,
    // Unknown usage stays null; a missing total is not the same as zero tokens.
    usageMissing: observed.usageMissing === true,
    usageMissingCalls: observed.usageMissingCalls ?? null,
    inputTokens: observed.usageMissing === true ? null : observed.inputTokens ?? null,
    outputTokens: observed.usageMissing === true ? null : observed.outputTokens ?? null,
    cacheReadInputTokens: observed.cacheUsageMissing === true ? null : observed.cacheReadInputTokens ?? null,
    cacheCreationInputTokens: observed.cacheUsageMissing === true ? null : observed.cacheCreationInputTokens ?? null,
    cacheUsageMissing: observed.cacheUsageMissing === true,
    cacheUsageMissingCalls: observed.cacheUsageMissingCalls ?? null,
    traceTotalsComplete: observed.traceTotalsComplete ?? null,
    llmCalls: observed.llmCalls ?? null,
    costUsd,
    costMissing: costUsd == null,
    // Diagnostic rubric score; null when the case had no scorable run.
    rubric: result.rubric ?? null,
  };
}

function percentile(values, fraction) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.max(0, Math.ceil(fraction * sorted.length) - 1)];
}

function latencySummary(records) {
  const known = records.map((record) => record.durationMs).filter(nonNegative);
  return {
    known: known.length,
    missing: records.length - known.length,
    p50Ms: percentile(known, 0.5),
    p95Ms: percentile(known, 0.95),
  };
}

function roundedSum(records) {
  return Number(records.reduce((total, record) => total + record.costUsd, 0).toFixed(6));
}

function categoryCost(records) {
  if (records.length === 0) return 0;
  return records.every((record) => record.costUsd != null) ? roundedSum(records) : null;
}

function costSummary(records, pricing) {
  const priced = records.filter((record) => record.costUsd != null);
  const missing = records.length - priced.length;
  const successful = records.filter((record) => record.status === "PASS");
  const total = missing === 0 && records.length > 0 ? roundedSum(records) : null;
  return {
    currency: pricing?.currency ?? (priced.length > 0 ? "USD" : null),
    pricing: pricing ?? null,
    knownCaseCount: priced.length,
    missingCaseCount: missing,
    knownTotalUsd: priced.length > 0 ? roundedSum(priced) : null,
    totalCostUsd: total,
    successfulTaskCostUsd: categoryCost(successful),
    failedTaskCostUsd: categoryCost(records.filter((record) => record.status === "FAIL")),
    environmentErrorCostUsd: categoryCost(records.filter((record) => record.status === "ERROR")),
    costPerSuccessfulTaskUsd: total != null && successful.length > 0
      ? Number((total / successful.length).toFixed(6)) : null,
  };
}

/**
 * @param input {
 *   generatedAt: string, codeSha: string, dataset: {version,datasetVersion,cases},
 *   split?: object, toolSchema: object, model: string, effectiveParams: object,
 *   cost?: {currency,source,inputPricePerM,outputPricePerM,cacheReadInputPricePerM?,cacheCreationInputPricePerM?},
 *   results: Array<scoreCase result>
 * }
 */
export function buildReport(input) {
  const results = input.results ?? [];
  const cases = input.dataset?.cases ?? [];
  const records = results.map((result) => caseRecord({
    ...result,
    costUsd: nonNegative(result.costUsd) ? result.costUsd : calculateCost(result.observed, input.cost),
  }));
  const judged = records.filter((record) => record.rubric != null);
  const rubricMean = judged.length === 0 ? null
    : Number((judged.reduce((total, record) => total + (record.rubric.score ?? 0), 0) / judged.length).toFixed(1));
  const taskSuccess = rate(records, (record) => !record.environmentError, (record) => record.taskSuccess === true);
  const metrics = {
    executionSuccess: rate(records, () => true, (record) => record.executionSuccess === true),
    runCompleted: rate(records, () => true, (record) => record.runCompleted === true),
    taskSuccess,
    outcome: taskSuccess,
    // Output is the diagnostic rubric, not a calibrated semantic-quality score.
    output: rate(records, (record) => record.rubric != null, (record) => record.rubric.passed === true),
    trajectory: rate(records, (record) => !record.environmentError, (record) => record.trajectorySuccess === true),
    // Param validity is only observable where a tool interaction was expected.
    paramValid: rate(records, (record) => record.paramValid !== null, (record) => record.paramValid === true),
    // Tool execution is only judged for cases that require a real mutation.
    toolExecuted: rate(records, (record) => record.toolExecuted !== null, (record) => record.toolExecuted === true),
    // Safe rejection is only judged for refusal/clarification cases.
    safeRejection: rate(records, (record) => record.safeRejection !== null, (record) => record.safeRejection === true),
    // Environment errors are measured over every attempted case.
    environmentError: rate(records, () => true, (record) => record.environmentError === true),
    // Deterministic rubric is diagnostic; a case counts only when it was judged.
    rubric: rate(records, (record) => record.rubric != null, (record) => record.rubric.passed === true),
  };
  return {
    generatedAt: input.generatedAt,
    codeSha: input.codeSha,
    datasetVersion: input.dataset?.datasetVersion ?? null,
    split: input.split ?? null,
    hashes: {
      dataset: hashOf(cases),
      split: input.split?.splitHash ?? null,
      prompt: sha256(cases.map((item) => item.prompt ?? "").join("\n")),
      toolSchema: input.toolSchema == null ? null : hashOf(input.toolSchema),
    },
    model: input.model ?? null,
    effectiveParams: input.effectiveParams ?? {},
    evaluatedCaseIds: input.evaluatedCaseIds ?? records.map((record) => record.id),
    samples: records.length,
    metrics,
    latency: latencySummary(records),
    cost: costSummary(records, input.cost),
    rubricVersion: judged[0]?.rubric.version ?? null,
    rubricMean,
    heldOut: records.filter((record) => record.heldOut).map((record) => ({ id: record.id, status: record.status })),
    usageMissingCount: records.filter((record) => record.usageMissing).length,
    cacheUsageMissingCount: records.filter((record) => record.cacheUsageMissing).length,
    cacheUsage: {
      readInputTokens: records.length > 0 && records.every((record) => !record.cacheUsageMissing)
          ? records.reduce((sum, record) => sum + (record.cacheReadInputTokens ?? 0), 0) : null,
      creationInputTokens: records.length > 0 && records.every((record) => !record.cacheUsageMissing)
          ? records.reduce((sum, record) => sum + (record.cacheCreationInputTokens ?? 0), 0) : null,
    },
    cases: records,
  };
}
