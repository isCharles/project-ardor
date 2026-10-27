#!/usr/bin/env node
/**
 * Manual real-model Agent evaluation.
 *
 * Deliberately NOT part of CI: CI uses the deterministic fake LLM, the Golden Path
 * acceptance script and the zero-cost contract demo. Run this only against a
 * disposable/local account, and only after the user explicitly authorizes spend.
 *
 * Required:
 *   ARDOR_EVAL_ALLOW_REAL_MODEL=true
 *   ARDOR_SESSION_COOKIE="SESSION=..."
 *
 * Optional:
 *   ARDOR_EVAL_REPORT=<path>            write the JSON report here
 *   ARDOR_EVAL_BASELINE=<path>          diff this run against a previous report
 *   ARDOR_EVAL_TOOL_SCHEMA_FILE=<path>  JSON tool-schema snapshot for the report hash
 *   ARDOR_EVAL_MODEL=<name>             model label recorded in the report
 *
 * Caps (checked before a new case starts; a running case is allowed to finish):
 *   ARDOR_EVAL_MAX_CASES=3
 *   ARDOR_EVAL_CONCURRENCY=1
 *   ARDOR_EVAL_MAX_TOKENS=1000000
 *   ARDOR_EVAL_MAX_COST_USD=1.00
 *   ARDOR_EVAL_INPUT_PRICE_PER_M=0.15
 *   ARDOR_EVAL_OUTPUT_PRICE_PER_M=0.60
 *   ARDOR_EVAL_TIMEOUT_MS=120000
 *
 * Every case fetches the recorded trace even after a failure or timeout, cancels
 * the backend run on timeout, and never coerces missing usage to zero. Cases whose
 * usage is unknown still count toward the sample but are flagged so the budget
 * boundary is explicit rather than silently understated.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { scoreCase } from "./agent-eval-assertions.mjs";
import { buildReport } from "./agent-eval-report.mjs";
import { diffReports, formatDiff } from "./agent-eval-diff.mjs";
import { SseParser } from "./agent-eval-sse.mjs";
import { fetchCompleteTrace } from "./agent-eval-trace.mjs";

if (process.env.ARDOR_EVAL_ALLOW_REAL_MODEL !== "true") {
  throw new Error("Set ARDOR_EVAL_ALLOW_REAL_MODEL=true to run against a real model.");
}
const base = (process.env.ARDOR_BASE_URL ?? "http://127.0.0.1:3000").replace(/\/+$/, "");
const cookie = process.env.ARDOR_SESSION_COOKIE;
if (!cookie) throw new Error("ARDOR_SESSION_COOKIE is required.");
let cookieJar = cookie;
const casesPath = process.env.ARDOR_EVAL_CASES ?? path.join(import.meta.dirname, "agent-eval-cases.json");
const dataset = JSON.parse(fs.readFileSync(casesPath, "utf8"));
const cases = dataset.cases ?? [];
const maxCases = positiveInt("ARDOR_EVAL_MAX_CASES", 3);
const concurrency = positiveInt("ARDOR_EVAL_CONCURRENCY", 1);
const maxTokens = positiveNumber("ARDOR_EVAL_MAX_TOKENS", 1_000_000);
const maxCost = nonNegativeNumber("ARDOR_EVAL_MAX_COST_USD", 1);
const inputPrice = nonNegativeNumber("ARDOR_EVAL_INPUT_PRICE_PER_M", 0.15);
const outputPrice = nonNegativeNumber("ARDOR_EVAL_OUTPUT_PRICE_PER_M", 0.60);
const timeoutMs = positiveInt("ARDOR_EVAL_TIMEOUT_MS", 120_000);
const selected = cases.slice(0, maxCases);
const toolSchema = process.env.ARDOR_EVAL_TOOL_SCHEMA_FILE
  ? JSON.parse(fs.readFileSync(process.env.ARDOR_EVAL_TOOL_SCHEMA_FILE, "utf8"))
  : null;
let codeSha = "unknown";
try {
  codeSha = execFileSync("git", ["rev-parse", "HEAD"], { cwd: import.meta.dirname, encoding: "utf8" }).trim();
} catch { /* keep unknown */ }

let tokensUsed = 0;
let costUsed = 0;
let usageMissingCases = 0;
let stopped = false;

function positiveInt(name, fallback) {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isInteger(value) || value <= 0) throw new Error(`${name} must be a positive integer`);
  return value;
}
function positiveNumber(name, fallback) {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isFinite(value) || value <= 0) throw new Error(`${name} must be positive`);
  return value;
}
function nonNegativeNumber(name, fallback) {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isFinite(value) || value < 0) throw new Error(`${name} must be non-negative`);
  return value;
}

async function csrf() {
  const response = await fetch(`${base}/api/auth/csrf`, { headers: { Cookie: cookieJar } });
  if (!response.ok) throw new Error(`CSRF request failed: HTTP ${response.status}`);
  for (const line of response.headers.getSetCookie?.() ?? []) {
    const pair = line.split(";", 1)[0];
    const separator = pair.indexOf("=");
    if (separator <= 0) continue;
    const name = pair.slice(0, separator);
    const existing = cookieJar.split(/;\s*/).filter((part) => !part.startsWith(`${name}=`));
    existing.push(pair);
    cookieJar = existing.join("; ");
  }
  return response.json();
}

async function fetchTrace(requestId) {
  return fetchCompleteTrace(
    (requestPath) => fetch(`${base}${requestPath}`, {
      headers: { Cookie: cookieJar, Accept: "application/json" },
    }),
    requestId,
  );
}

async function cancelRun(requestId) {
  try {
    const csrfToken = await csrf();
    await fetch(`${base}/api/agent/runs/${requestId}/cancel`, {
      method: "POST", headers: { Cookie: cookieJar, [csrfToken.headerName]: csrfToken.token },
    });
  } catch { /* best-effort; the trace records the truthful run state */ }
}

async function runCase(item) {
  const requestId = randomUUID();
  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  const events = [];
  let runError = null;
  try {
    const csrfToken = await csrf();
    const response = await fetch(`${base}/api/agent/messages/stream`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "text/event-stream", Cookie: cookieJar,
        [csrfToken.headerName]: csrfToken.token },
      body: JSON.stringify({
        message: item.prompt, requestId, reasoningDepth: item.reasoningDepth ?? "AUTO",
        contextReferences: [],
      }),
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`HTTP_${response.status}: ${(await response.text()).slice(0, 300)}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    const parser = new SseParser((payload) => {
      try { events.push(JSON.parse(payload)); }
      catch { throw new Error("AGENT_STREAM_INVALID_EVENT: invalid event data"); }
    });
    while (true) {
      const { value, done } = await reader.read();
      parser.push(decoder.decode(value, { stream: !done }), done);
      if (done) break;
    }
    const error = events.find((event) => event.type === "error");
    if (error) throw new Error(`${error.errorCode ?? "AGENT_ERROR"}: ${error.content ?? ""}`);
    if (!events.some((event) => event.type === "done")) {
      throw new Error("AGENT_STREAM_NO_DONE: stream ended without a done event");
    }
  } catch (error) {
    runError = error;
  } finally {
    clearTimeout(timer);
  }
  if (timedOut) await cancelRun(requestId);

  // A failed or timed-out run still gets its trace so partial execution is visible.
  const trace = await fetchTrace(requestId);
  if (!trace) {
    const kind = timedOut ? "TIMEOUT" : classifyFailure(runError);
    return { ...scoreCase(item, null, undefined, { environmentError: true, errorKind: kind }), requestId, costUsd: null };
  }
  const done = events.find((event) => event.type === "done");
  const result = scoreCase(item, trace, done?.content);
  const input = trace.totals?.inputTokens;
  const output = trace.totals?.outputTokens;
  const cost = input == null || output == null
    ? null
    : Number((((input / 1_000_000) * inputPrice) + ((output / 1_000_000) * outputPrice)).toFixed(6));
  // Unknown usage is never added as zero; it is counted so the budget boundary is explicit.
  if (input == null || output == null) usageMissingCases += 1;
  else { tokensUsed += input + output; costUsed += cost; }
  return { ...result, requestId, costUsd: cost };
}

function classifyFailure(error) {
  const message = error instanceof Error ? error.message : String(error);
  const code = message.match(/^([A-Z][A-Z0-9_]+):/)?.[1];
  if (code) return code;
  if (message.startsWith("HTTP_")) return "TRANSPORT";
  if (error instanceof Error && error.name === "AbortError") return "TIMEOUT";
  return "UNEXPECTED";
}

async function worker(queue, results) {
  while (queue.length > 0 && !stopped) {
    if (tokensUsed >= maxTokens || costUsed >= maxCost) { stopped = true; break; }
    const item = queue.shift();
    if (!item) break;
    results.push(await runCase(item));
  }
}

const queue = [...selected];
const results = [];
await Promise.all(Array.from({ length: Math.min(concurrency, queue.length) }, () => worker(queue, results)));

const report = buildReport({
  generatedAt: new Date().toISOString(), codeSha, dataset, toolSchema,
  model: process.env.ARDOR_EVAL_MODEL ?? "real-model",
  effectiveParams: { reasoningDepth: "AUTO", inputPricePerM: inputPrice, outputPricePerM: outputPrice, timeoutMs },
  results,
});
report.budget = {
  maxCases, concurrency, maxTokens, maxCost, inputPrice, outputPrice, timeoutMs,
  knownTokens: tokensUsed, knownCostUsd: Number(costUsed.toFixed(6)),
  usageMissingCases, stopped,
  note: "Token/cost caps only account for cases that reported usage; usageMissingCases were sent but cannot be priced, so real spend may exceed knownCostUsd.",
};
if (process.env.ARDOR_EVAL_REPORT) fs.writeFileSync(process.env.ARDOR_EVAL_REPORT, JSON.stringify(report, null, 2));

for (const result of report.cases) {
  const tokens = result.inputTokens == null ? "unknown" : result.inputTokens + (result.outputTokens ?? 0);
  console.log(`${result.status.padEnd(5)} ${result.id} kind=${result.errorKind ?? "-"} tokens=${tokens} tools=${result.observed?.tools?.map((tool) => tool.name).join(",") ?? ""}`);
  for (const failure of result.failures) console.log(`      ${failure}`);
}
console.log(JSON.stringify(report.metrics));

if (process.env.ARDOR_EVAL_BASELINE) {
  const baseline = JSON.parse(fs.readFileSync(process.env.ARDOR_EVAL_BASELINE, "utf8"));
  const diff = diffReports(baseline, report);
  console.log(formatDiff(diff));
  if (diff.hasRegression) process.exitCode = 1;
}
if (report.cases.some((item) => item.status !== "PASS") || stopped || usageMissingCases > 0) process.exitCode = 1;
