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

function caseRecord(result) {
  const observed = result.observed ?? {};
  const status = result.environmentError ? "ERROR" : result.dimensions.taskSuccess ? "PASS" : "FAIL";
  return {
    id: result.id,
    category: result.category,
    tier: result.tier,
    heldOut: result.heldOut,
    status,
    taskSuccess: result.dimensions.taskSuccess,
    paramValid: result.dimensions.paramValid,
    toolExecuted: result.dimensions.toolExecuted,
    safeRejection: result.dimensions.safeRejection,
    environmentError: result.environmentError,
    errorKind: result.errorKind,
    failures: result.failures,
    runStatus: observed.runStatus ?? "UNKNOWN",
    // Unknown usage stays null; a missing total is not the same as zero tokens.
    usageMissing: observed.usageMissing === true,
    inputTokens: observed.inputTokens ?? null,
    outputTokens: observed.outputTokens ?? null,
    cacheReadInputTokens: observed.cacheReadInputTokens ?? null,
    cacheCreationInputTokens: observed.cacheCreationInputTokens ?? null,
    cacheUsageMissing: observed.cacheUsageMissing === true,
    llmCalls: observed.llmCalls ?? null,
    // Diagnostic rubric score; null when the case had no scorable run.
    rubric: result.rubric ?? null,
  };
}

/**
 * @param input {
 *   generatedAt: string, codeSha: string, dataset: {version,datasetVersion,cases},
 *   toolSchema: object, model: string, effectiveParams: object,
 *   results: Array<scoreCase result>
 * }
 */
export function buildReport(input) {
  const results = input.results ?? [];
  const cases = input.dataset?.cases ?? [];
  const records = results.map(caseRecord);
  const judged = records.filter((record) => record.rubric != null);
  const rubricMean = judged.length === 0 ? null
    : Number((judged.reduce((total, record) => total + (record.rubric.score ?? 0), 0) / judged.length).toFixed(1));
  const metrics = {
    // A case with no scorable run cannot be judged for business success.
    taskSuccess: rate(records, (r) => !r.environmentError, (r) => r.taskSuccess === true),
    // Param validity is only observable where a tool interaction was expected.
    paramValid: rate(records, (r) => r.paramValid !== null, (r) => r.paramValid === true),
    // Tool execution is only judged for cases that require a real mutation.
    toolExecuted: rate(records, (r) => r.toolExecuted !== null, (r) => r.toolExecuted === true),
    // Safe rejection is only judged for refusal/clarification cases.
    safeRejection: rate(records, (r) => r.safeRejection !== null, (r) => r.safeRejection === true),
    // Environment errors are measured over every attempted case.
    environmentError: rate(records, () => true, (r) => r.environmentError === true),
    // Deterministic rubric is diagnostic; a case counts only when it was judged.
    rubric: rate(records, (r) => r.rubric != null, (r) => r.rubric.passed === true),
  };
  return {
    generatedAt: input.generatedAt,
    codeSha: input.codeSha,
    datasetVersion: input.dataset?.datasetVersion ?? null,
    hashes: {
      dataset: hashOf(cases),
      prompt: sha256(cases.map((item) => item.prompt ?? "").join("\n")),
      toolSchema: input.toolSchema == null ? null : hashOf(input.toolSchema),
    },
    model: input.model ?? null,
    effectiveParams: input.effectiveParams ?? {},
    samples: records.length,
    metrics,
    rubricVersion: judged[0]?.rubric.version ?? null,
    rubricMean,
    heldOut: records.filter((r) => r.heldOut).map((r) => ({ id: r.id, status: r.status })),
    usageMissingCount: records.filter((r) => r.usageMissing).length,
    cacheUsageMissingCount: records.filter((r) => r.cacheUsageMissing).length,
    cacheUsage: {
      readInputTokens: records.some((r) => r.cacheReadInputTokens != null)
          ? records.reduce((sum, r) => sum + (r.cacheReadInputTokens ?? 0), 0) : null,
      creationInputTokens: records.some((r) => r.cacheCreationInputTokens != null)
          ? records.reduce((sum, r) => sum + (r.cacheCreationInputTokens ?? 0), 0) : null,
    },
    cases: records,
  };
}
