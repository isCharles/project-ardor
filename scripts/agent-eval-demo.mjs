#!/usr/bin/env node
/**
 * Zero-cost, end-to-end demonstration of the evaluation pipeline:
 * fixture -> score -> report -> history diff.
 *
 * This does NOT call any model. It feeds synthetic traces that match the recorded
 * AgentTraceResponse shape into the same scorer/report/diff modules the real driver
 * uses, then plants four known defects and one environment error. The demo only
 * succeeds if the framework catches every planted defect. It validates the
 * contracts and the report/diff machinery — not the semantic quality of a model.
 *
 * Run: node scripts/agent-eval-demo.mjs [--out <dir>]
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { scoreCase } from "./agent-eval-assertions.mjs";
import { buildReport } from "./agent-eval-report.mjs";
import { diffReports, formatDiff } from "./agent-eval-diff.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const dataset = JSON.parse(fs.readFileSync(path.join(here, "agent-eval-cases.json"), "utf8"));
const outIndex = process.argv.indexOf("--out");
const outDir = outIndex > -1 ? process.argv[outIndex + 1] : path.join(os.tmpdir(), "ardor-agent-eval-demo");

// A representative tool-schema map. The real driver passes AgentToolSchemaCatalog.
const TOOL_SCHEMA = Object.fromEntries(
  [...new Set(dataset.cases.flatMap((item) => [...(item.expectedTools ?? []), ...(item.requiredExecuted ?? []),
    ...(item.expectProposed ?? []), ...(item.forbiddenExecuted ?? [])]))].sort()
    .map((name) => [name, { description: name, parameters: { type: "object" } }]),
);

let codeSha = "unknown";
try {
  codeSha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: here, encoding: "utf8" }).trim();
} catch { /* not a git checkout: keep "unknown" */ }

const tool = (name, status, attributes = {}, errorCode = null) => ({ kind: "TOOL", name, status, attributes, errorCode });
const executed = (name, approvalSource = "CONFIRMED") => tool(name, "COMPLETED", { executed: true, approvalSource });
const proposal = (name) => tool(name, "COMPLETED", { proposal: true, approvalSource: "PROPOSAL", executed: false });
const notExecuted = (name, errorCode) => tool(name, "NOT_EXECUTED", { executed: false }, errorCode);
const failed = (name, errorCode) => tool(name, "FAILED", { executed: false }, errorCode);

function firstTool(item) {
  return item.expectedTools?.[0] ?? item.requiredExecuted?.[0] ?? item.expectProposed?.[0]
    ?? item.forbiddenExecuted?.[0] ?? "list_calendar_tasks";
}

/** A trace that is correct by construction for the case's declaration. */
function expectedTrace(item) {
  const spans = [];
  if (item.allowProposalOnly) {
    for (const name of item.expectProposed ?? []) spans.push(proposal(name));
  } else if (item.expectInvalidParams) {
    spans.push(failed(item.requiredExecuted?.[0] ?? firstTool(item), "INVALID_PARAM"));
  } else if (item.expectToolFailure) {
    const name = item.driver?.toolFailure ?? item.driver?.toolBusinessError ?? firstTool(item);
    spans.push(failed(name, item.driver?.toolBusinessError ? "BUSINESS_CONFLICT" : "TOOL_FAILED"));
  } else if (item.category === "ownership") {
    spans.push(executed("list_calendar_tasks"));
  }

  for (const name of item.expectedTools ?? []) {
    if (!spans.some((span) => span.name === name)) spans.push(executed(name));
  }
  for (const name of item.requiredExecuted ?? []) {
    if (!spans.some((span) => span.name === name)) spans.push(executed(name));
  }
  if (item.driver?.approval === "deny") {
    for (const name of item.forbiddenExecuted ?? []) spans.push(notExecuted(name, "NOT_CONFIRMED"));
  }
  if (item.driver?.cancel) spans.push(notExecuted(item.forbiddenExecuted?.[0] ?? "delete_calendar_task", "RUN_STOPPED"));

  return {
    status: item.expectedRunStatus ?? "COMPLETED",
    totals: { llmCalls: spans.length ? 2 : 1, toolCalls: spans.length, loopRounds: 1, inputTokens: 1200, outputTokens: 180 },
    spans,
  };
}

function answerFor(item) {
  if (item.driver?.toolBusinessError) return "创建失败：该时间段与已有日程冲突，未创建。";
  if (item.expectInvalidParams) return "参数不合法，未创建日程。";
  if (item.expectClarification) return "请告诉我具体的时间和标题，我再帮你创建。";
  if (item.mustContain) return `我是 ${item.mustContain}，可以帮你管理简历、日程和学习。`;
  return "好的，已按你的要求处理。";
}

function build(overrides) {
  const results = dataset.cases.map((item) => {
    const override = overrides[item.id];
    if (override?.environmentError) {
      return scoreCase(item, null, undefined, { environmentError: true, errorKind: override.errorKind ?? "TIMEOUT" });
    }
    const trace = override?.mutate ? override.mutate(expectedTrace(item)) : expectedTrace(item);
    return scoreCase(item, trace, override?.answer ?? answerFor(item));
  });
  return buildReport({
    generatedAt: new Date().toISOString(), codeSha, dataset, toolSchema: TOOL_SCHEMA,
    model: "synthetic-fixture/no-model", effectiveParams: { temperature: 0, note: "contract demo only" },
    results,
  });
}

const baseline = build({});
const current = build({
  // 1) A required write that only produced a confirmation card must fail.
  "create-calendar-approved": { mutate: () => ({ status: "COMPLETED", totals: { llmCalls: 1, toolCalls: 1, loopRounds: 1, inputTokens: 900, outputTokens: 90 }, spans: [proposal("create_calendar_task")] }) },
  // 2) A forbidden side effect (write executed on a read-only request) must fail.
  "read-only-no-write": { mutate: (trace) => ({ ...trace, spans: [...trace.spans, executed("create_calendar_task")] }) },
  // 3) A business error masked as success must fail.
  "business-error-not-masked": { mutate: () => ({ status: "COMPLETED", totals: { llmCalls: 1, toolCalls: 1, loopRounds: 1, inputTokens: 800, outputTokens: 60 }, spans: [executed("create_calendar_task")] }), answer: "已成功创建日程。" },
  // 4) A duplicated write after a lost response must fail.
  "write-executed-response-lost": { mutate: (trace) => ({ ...trace, spans: [...trace.spans, executed("create_calendar_task")] }) },
  // 5) A run that produced no trace is an environment error, not a task failure.
  "read-knowledge": { environmentError: true, errorKind: "TIMEOUT" },
});

const diff = diffReports(baseline, current);
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, "baseline.json"), JSON.stringify(baseline, null, 2));
fs.writeFileSync(path.join(outDir, "current.json"), JSON.stringify(current, null, 2));

console.log("== baseline (synthetic fixtures, no model) ==");
console.log(JSON.stringify(baseline.metrics));
console.log("== current (four planted defects + one environment error) ==");
console.log(JSON.stringify(current.metrics));
console.log("== diff ==");
console.log(formatDiff(diff));
console.log(`reports written to ${outDir}`);

const expectedRegressions = ["create-calendar-approved", "read-only-no-write", "business-error-not-masked", "write-executed-response-lost"];
const regressed = diff.regressions.map((item) => item.id).sort();
const missed = expectedRegressions.filter((id) => !regressed.includes(id));
const baselineBroken = baseline.metrics.taskSuccess.denominator !== dataset.cases.length
  || baseline.metrics.taskSuccess.pass !== dataset.cases.length
  || baseline.metrics.environmentError.pass !== 0;
const envErrorCounted = current.metrics.environmentError.pass === 1
  && current.metrics.taskSuccess.denominator === dataset.cases.length - 1
  && diff.environmentErrors.some((item) => item.id === "read-knowledge");

if (missed.length || baselineBroken || !envErrorCounted || regressed.length !== expectedRegressions.length) {
  console.error(`DEMO FAILED: missed=${missed.join(",")} baselineBroken=${baselineBroken} envErrorCounted=${envErrorCounted}`);
  process.exit(1);
}
console.log(`OK: baseline all ${dataset.cases.length} cases pass; all ${expectedRegressions.length} planted defects caught; environment error excluded from taskSuccess denominator.`);
