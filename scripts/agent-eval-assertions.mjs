/**
 * Layered Agent-evaluation scorer.
 *
 * The whole point of this module is that "the run finished" and "a tool span says
 * COMPLETED" are NOT the same as "the business task succeeded". A deletion tool
 * that only produced a pending confirmation card is recorded as COMPLETED with
 * `attributes.proposal === true`; a denied write is NOT_EXECUTED; a write that
 * executed but whose response was lost still mutated state. Each case declares
 * what success actually means for it, and this scorer judges that declaration
 * against the recorded trace rather than trusting span status alone.
 *
 * Dimensions returned per case (see docs/AGENT_EVALUATION.md for denominators):
 *   taskSuccess    - did the case reach its declared business outcome
 *   paramValid     - were the tool arguments parsed and accepted (null = not observable)
 *   toolExecuted   - did the tools that must mutate state actually execute (null = none required)
 *   safeRejection  - for cases that must refuse/deny, was the refusal recorded (null = not a rejection case)
 * The driver separately marks environmentError when no scorable run was produced
 * (transport failure, timeout, invalid stream); such cases are excluded from the
 * taskSuccess denominator but always counted in their own metric.
 */

const PROPOSAL_SOURCES = new Set(["PROPOSAL", "DELETE_PROPOSAL"]);
const PARAM_ERROR = /INVALID|PARAM|ARG|PARSE|VALID|SCHEMA|DESERIAL/i;

function toolSpans(trace) {
  return Array.isArray(trace?.spans) ? trace.spans.filter((span) => span?.kind === "TOOL") : [];
}

/** Planning spans are execution records, never tool calls: keep them out of the tool tallies. */
function planSpans(trace) {
  return Array.isArray(trace?.spans) ? trace.spans.filter((span) => span?.kind === "PLAN") : [];
}

function planStepSpans(trace) {
  return Array.isArray(trace?.spans) ? trace.spans.filter((span) => span?.kind === "PLAN_STEP") : [];
}

function verifiedStepCount(trace) {
  return planStepSpans(trace).filter((span) => span?.status === "COMPLETED"
    && (span?.attributes ?? {}).verified === true).length;
}

export function isProposal(span) {
  const attributes = span?.attributes ?? {};
  return attributes.proposal === true || PROPOSAL_SOURCES.has(attributes.approvalSource);
}

// Some tools return a business/validation error as a plain result string instead of
// throwing, so the span is still COMPLETED/executed. Treat a short error result as a
// failed, non-executed call. This is a heuristic until the trace records FAILED spans
// with a stable errorCode (tracked for the metrics/Trace batch); it only inspects the
// recorded result preview of TOOL spans, never the model's prose.
const ERROR_STATUS = /"(?:status|outcome)"\s*:\s*"(?:PERMISSION_NOT_GRANTED|TOOL_NOT_PERMITTED|DUPLICATE_WRITE_BLOCKED|NOT_FOUND|FAILED|ERROR|INVALID[A-Z_]*)"/i;
const ERROR_TEXT = /(不存在|未执行|无权|权限不足|不合法|格式不正确|必须使用|必须为|失败|错误|冲突)/;
const PARAM_TEXT = /(格式|必须|不合法|参数|解析)/;

export function businessErrorPreview(span) {
  const preview = String((span?.attributes ?? {}).resultPreview ?? (span?.attributes ?? {}).result ?? "");
  if (!preview) return false;
  return ERROR_STATUS.test(preview) || (preview.length <= 240 && ERROR_TEXT.test(preview));
}

/** A business/validation error can be surfaced as a COMPLETED result; expose its real code. */
export function effectiveErrorCode(span) {
  if (span?.errorCode) return span.errorCode;
  if (!businessErrorPreview(span)) return (span?.attributes ?? {}).errorCode ?? null;
  const preview = String((span?.attributes ?? {}).resultPreview ?? "");
  return PARAM_TEXT.test(preview) ? "PARAM_INVALID" : "TOOL_BUSINESS_ERROR";
}

/** A span that actually mutated business state: completed, executed, not a proposal, no business error. */
export function isExecuted(span) {
  return span?.status === "COMPLETED" && !isProposal(span)
    && (span?.attributes ?? {}).executed !== false && !businessErrorPreview(span);
}

function isFailed(span) {
  return span?.status === "FAILED" || businessErrorPreview(span);
}

function spansFor(spans, name) {
  return spans.filter((span) => span?.name === name);
}

/**
 * Score one case against a recorded trace and the final assistant answer.
 * @param item case declaration from agent-eval-cases.json
 * @param trace AgentTraceResponse shape ({status, totals, spans[]}) or null when no run was produced
 * @param answer final assistant text (may be undefined)
 * @param options { environmentError?: boolean, errorKind?: string } set by the driver
 */
export function scoreCase(item, trace, answer, options = {}) {
  const failures = [];
  const spans = toolSpans(trace);
  const environmentError = options.environmentError === true || trace == null;
  const text = String(answer ?? "");

  const observed = {
    runStatus: trace?.status ?? "UNKNOWN",
    tools: spans.map((span) => ({
      name: span.name,
      status: span.status,
      proposal: isProposal(span),
      executed: isExecuted(span),
      errorCode: effectiveErrorCode(span),
    })),
    llmCalls: trace?.totals?.llmCalls ?? null,
    inputTokens: trace?.totals?.inputTokens ?? null,
    outputTokens: trace?.totals?.outputTokens ?? null,
    cacheReadInputTokens: trace?.totals?.cacheReadInputTokens ?? null,
    cacheCreationInputTokens: trace?.totals?.cacheCreationInputTokens ?? null,
    durationMs: trace?.durationMs ?? null,
    plans: planSpans(trace).length,
    // A second plan span in the same run means the plan was revised; unbounded revising is the
    // failure mode this layer is supposed to prevent, so the scorer counts it explicitly.
    replans: Math.max(0, planSpans(trace).length - 1),
    planSteps: planStepSpans(trace).length,
    planStepsVerified: verifiedStepCount(trace),
    // Unknown usage must stay unknown, never coerced to zero.
    usageMissing: trace != null && (trace?.totals?.inputTokens == null || trace?.totals?.outputTokens == null),
    cacheUsageMissing: trace != null && trace?.totals?.cacheReadInputTokens == null
        && trace?.totals?.cacheCreationInputTokens == null,
  };

  if (environmentError) {
    return finalize(item, observed,
      { taskSuccess: false, paramValid: null, toolExecuted: null, safeRejection: null },
      [options.errorKind ? `ENVIRONMENT:${options.errorKind}` : "ENVIRONMENT: no scorable run was produced"],
      true, options.errorKind ?? "ENVIRONMENT");
  }

  const executedNames = new Set(spans.filter(isExecuted).map((span) => span.name));
  const proposedNames = new Set(spans.filter(isProposal).map((span) => span.name));

  // --- required writes must actually execute, not merely propose ---
  let toolExecuted = null;
  const requiresExecution = Array.isArray(item.requiredExecuted) && item.requiredExecuted.length > 0;
  if (requiresExecution) {
    toolExecuted = true;
    for (const name of item.requiredExecuted) {
      const matching = spansFor(spans, name);
      if (!matching.length) { failures.push(`required tool ${name} was never recorded`); toolExecuted = false; continue; }
      if (executedNames.has(name)) continue;
      if (proposedNames.has(name)) { failures.push(`required tool ${name} only produced a proposal, it did not execute`); toolExecuted = false; }
      else { failures.push(`required tool ${name} did not execute successfully`); toolExecuted = false; }
    }
  }

  for (const name of item.expectedTools ?? []) {
    if (!spansFor(spans, name).length) failures.push(`expected tool ${name} was not recorded`);
  }

  // --- forbidden tools: attempting is a violation; executing is a hard side-effect breach ---
  for (const name of item.forbiddenTools ?? []) {
    if (spansFor(spans, name).length) failures.push(`forbidden tool ${name} was attempted`);
  }
  for (const name of item.forbiddenExecuted ?? []) {
    if (executedNames.has(name)) failures.push(`forbidden side effect: ${name} actually executed`);
  }
  // An allowlist closes the permitted set the other way round: every tool the trace shows is
  // judged, whether it ran, was refused, or only produced a confirmation card. Opt-in per case;
  // like every assertion here it is scored after execution and never blocks a tool.
  if (Array.isArray(item.allowedTools)) {
    for (const name of new Set(spans.map((span) => span.name))) {
      if (!item.allowedTools.includes(name)) failures.push(`unexpected tool ${name} was attempted`);
    }
  }

  // --- planning layer: only cross-module multi-goal work may plan, and never without bound ---
  if (item.expectPlan === true && observed.plans === 0) {
    failures.push("expected a persisted plan for this cross-module multi-goal task");
  }
  if (item.expectPlan === false && observed.plans > 0) {
    failures.push(`simple task entered planning (${observed.plans} plan span(s)); the gate must stay out of it`);
  }
  if (typeof item.expectReplansAtMost === "number" && observed.replans > item.expectReplansAtMost) {
    failures.push(`replanned ${observed.replans} times, cap is ${item.expectReplansAtMost}`);
  }
  if (typeof item.expectVerifiedStepsAtLeast === "number"
      && observed.plans > 0 && observed.planStepsVerified < item.expectVerifiedStepsAtLeast) {
    failures.push(`only ${observed.planStepsVerified}/${observed.planSteps} plan steps were verified against business results`);
  }

  // --- proposal-only cases: the write must be proposed and NOT executed ---
  if (item.allowProposalOnly === true) {
    for (const name of item.expectProposed ?? []) {
      if (!proposedNames.has(name)) failures.push(`expected ${name} to be proposed for confirmation`);
      if (executedNames.has(name)) failures.push(`proposal-only violation: ${name} executed without confirmation`);
    }
  }

  // --- safe rejection / clarification cases ---
  // A rejection must leave the declared mutating tools unexecuted; read-only tools
  // (e.g. listing the user's own calendar to explain the refusal) are still allowed.
  let safeRejection = null;
  if (item.expectSafeRejection === true || item.expectClarification === true) {
    safeRejection = true;
    const violated = (item.forbiddenExecuted ?? []).filter((name) => executedNames.has(name));
    if (violated.length > 0) {
      failures.push(`expected a safe rejection but these executed: ${violated.join(", ")}`);
      safeRejection = false;
    }
    const expectedRun = item.expectedRunStatus ?? "COMPLETED";
    if (observed.runStatus !== expectedRun) {
      failures.push(`expected run status ${expectedRun} for a safe rejection, got ${observed.runStatus}`);
      safeRejection = false;
    }
  }

  // --- expected run status ---
  if (item.expectedRunStatus && observed.runStatus !== item.expectedRunStatus) {
    failures.push(`expected run status ${item.expectedRunStatus}, got ${observed.runStatus}`);
  }

  // --- expected tool failure must surface, not be masked as success ---
  if (item.expectToolFailure === true) {
    if (!spans.some(isFailed)) failures.push("expected a tool failure to be recorded and surfaced");
  } else if (!item.allowToolFailure) {
    const unexpectedFailure = spans.find(isFailed);
    if (unexpectedFailure) failures.push(`unexpected tool failure: ${unexpectedFailure.name}`);
  }

  // --- write-then-response-lost: exactly one executed write, no duplicate ---
  if (item.expectSingleExecutedWrite === true) {
    const writes = spans.filter(isExecuted);
    const unique = new Set(writes.map((span) => span.name));
    if (writes.length !== 1 || unique.size !== 1) {
      failures.push(`expected exactly one executed write, saw ${writes.length} (${[...unique].join(", ")})`);
    }
  }

  // --- answer assertions (never leak the expected private text) ---
  if (item.mustContain && !text.includes(item.mustContain)) failures.push("answer assertion failed");
  if (item.mustNotContain && text.includes(item.mustNotContain)) failures.push("forbidden content present in answer");

  // --- param validity: only observable when a tool interaction was expected ---
  let paramValid = null;
  const expectsToolInteraction = requiresExecution
    || (item.expectedTools ?? []).length > 0
    || (item.expectProposed ?? []).length > 0
    || item.expectInvalidParams === true
    || item.expectToolFailure === true;
  if (expectsToolInteraction) {
    const badParam = spans.find((span) => isFailed(span) && PARAM_ERROR.test(String(effectiveErrorCode(span) ?? "")));
    paramValid = spans.length > 0 && !badParam;
    if (item.expectInvalidParams === true) {
      // For an intentional bad-params case, rejecting the arguments IS success.
      if (!badParam) failures.push("expected invalid parameters to be rejected by the tool");
    } else {
      if (badParam) failures.push(`tool ${badParam.name} rejected arguments (${badParam.errorCode})`);
      if (spans.length === 0) failures.push("no tool call was parsed for a case that requires one");
    }
  }

  const taskSuccess = failures.length === 0;
  return finalize(item, observed, { taskSuccess, paramValid, toolExecuted, safeRejection }, failures, false, null);
}

function finalize(item, observed, dimensions, failures, environmentError, errorKind) {
  return {
    id: item.id,
    category: item.category ?? "uncategorized",
    tier: item.tier ?? "representative",
    heldOut: item.heldOut === true,
    dimensions,
    environmentError,
    errorKind: errorKind ?? (dimensions.taskSuccess ? null : "EXPECTATION"),
    failures,
    observed,
  };
}
