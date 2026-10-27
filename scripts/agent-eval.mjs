#!/usr/bin/env node
/**
 * Manual real-model Agent evaluation.
 *
 * This is deliberately not part of CI: CI uses the deterministic fake LLM and the
 * Golden Path acceptance script. Run this against a disposable/local account only.
 *
 * Required:
 *   ARDOR_EVAL_ALLOW_REAL_MODEL=true
 *   ARDOR_SESSION_COOKIE="SESSION=..."
 *
 * Caps (all enforced before a new case starts; running cases are allowed to finish):
 *   ARDOR_EVAL_MAX_CASES=3
 *   ARDOR_EVAL_CONCURRENCY=1
 *   ARDOR_EVAL_MAX_TOKENS=1000000
 *   ARDOR_EVAL_MAX_COST_USD=1.00
 *   ARDOR_EVAL_INPUT_PRICE_PER_M=0.15
 *   ARDOR_EVAL_OUTPUT_PRICE_PER_M=0.60
 *   ARDOR_EVAL_TIMEOUT_MS=120000
 */
import fs from "node:fs";
import path from "node:path";
import { randomUUID } from "node:crypto";
import { evaluateCase } from "./agent-eval-assertions.mjs";
import { SseParser } from "./agent-eval-sse.mjs";

if (process.env.ARDOR_EVAL_ALLOW_REAL_MODEL !== "true") {
  throw new Error("Set ARDOR_EVAL_ALLOW_REAL_MODEL=true to run against a real model.");
}
const base = (process.env.ARDOR_BASE_URL ?? "http://127.0.0.1:3000").replace(/\/+$/, "");
const cookie = process.env.ARDOR_SESSION_COOKIE;
if (!cookie) throw new Error("ARDOR_SESSION_COOKIE is required.");
let cookieJar = cookie;
const casesPath = process.env.ARDOR_EVAL_CASES
  ?? path.join(import.meta.dirname, "agent-eval-cases.json");
const cases = JSON.parse(fs.readFileSync(casesPath, "utf8")).cases ?? [];
const maxCases = positiveInt("ARDOR_EVAL_MAX_CASES", 3);
const concurrency = positiveInt("ARDOR_EVAL_CONCURRENCY", 1);
const maxTokens = positiveNumber("ARDOR_EVAL_MAX_TOKENS", 1_000_000);
const maxCost = nonNegativeNumber("ARDOR_EVAL_MAX_COST_USD", 1);
const inputPrice = nonNegativeNumber("ARDOR_EVAL_INPUT_PRICE_PER_M", 0.15);
const outputPrice = nonNegativeNumber("ARDOR_EVAL_OUTPUT_PRICE_PER_M", 0.60);
const timeoutMs = positiveInt("ARDOR_EVAL_TIMEOUT_MS", 120_000);
const selected = cases.slice(0, maxCases);
let tokensUsed = 0;
let costUsed = 0;
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

async function runCase(item) {
  const requestId = randomUUID();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  const events = [];
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
    if (!response.ok) {
      throw new Error(`HTTP_${response.status}: ${(await response.text()).slice(0, 300)}`);
    }
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
    const done = events.find((event) => event.type === "done");
    if (!done) throw new Error("AGENT_STREAM_NO_DONE: stream ended without a done event");
    const traceResponse = await fetch(`${base}/api/agent/runs/${requestId}/trace`, {
      headers: { Cookie: cookieJar, Accept: "application/json" },
    });
    if (!traceResponse.ok) throw new Error(`TRACE_HTTP_${traceResponse.status}`);
    const trace = await traceResponse.json();
    const { failures, toolNames } = evaluateCase(item, trace, done.content);
    const input = Number(trace.totals?.inputTokens ?? 0);
    const output = Number(trace.totals?.outputTokens ?? 0);
    const total = input + output;
    const cost = (input / 1_000_000) * inputPrice + (output / 1_000_000) * outputPrice;
    tokensUsed += total;
    costUsed += cost;
    return { id: item.id, status: failures.length ? "FAIL" : "PASS",
      failureKind: failures.length ? "EXPECTATION" : null, failures, requestId,
      toolNames, llmCalls: trace.totals?.llmCalls ?? 0, inputTokens: input, outputTokens: output,
      costUsd: Number(cost.toFixed(6)), traceStatus: trace.status };
  } catch (error) {
    const failureKind = classifyFailure(error);
    return { id: item.id, status: "ERROR", failureKind,
      failures: [failureKind], requestId, toolNames: [], llmCalls: 0,
      inputTokens: 0, outputTokens: 0, costUsd: 0 };
  } finally {
    clearTimeout(timer);
  }
}

function classifyFailure(error) {
  const message = error instanceof Error ? error.message : String(error);
  const code = message.match(/^([A-Z][A-Z0-9_]+):/)?.[1];
  if (code) return code;
  if (message.startsWith("HTTP_") || message.startsWith("TRACE_HTTP_")) return "TRANSPORT";
  if (error instanceof Error && error.name === "AbortError") return "TIMEOUT";
  return "UNEXPECTED";
}

async function worker(queue, results) {
  while (queue.length > 0 && !stopped) {
    if (tokensUsed >= maxTokens || costUsed >= maxCost) { stopped = true; break; }
    const item = queue.shift();
    if (!item) break;
    const result = await runCase(item);
    results.push(result);
  }
}

const queue = [...selected];
const results = [];
const workers = Array.from({ length: Math.min(concurrency, queue.length) }, () => worker(queue, results));
await Promise.all(workers);
const report = {
  generatedAt: new Date().toISOString(), base, cases: results,
  totals: { cases: results.length, passed: results.filter((item) => item.status === "PASS").length,
    failed: results.filter((item) => item.status !== "PASS").length,
    tokens: tokensUsed, costUsd: Number(costUsed.toFixed(6)),
    failuresByKind: Object.fromEntries(Object.entries(
      results.reduce((counts, item) => {
        if (item.status !== "PASS") counts[item.failureKind ?? "UNEXPECTED"] = (counts[item.failureKind ?? "UNEXPECTED"] ?? 0) + 1;
        return counts;
      }, {})).sort()) },
  caps: { maxCases, concurrency, maxTokens, maxCost, inputPrice, outputPrice, timeoutMs },
  budgetStopped: stopped,
};
if (process.env.ARDOR_EVAL_REPORT) fs.writeFileSync(process.env.ARDOR_EVAL_REPORT, JSON.stringify(report, null, 2));
for (const result of results) {
  console.log(`${result.status.padEnd(5)} ${result.id} kind=${result.failureKind ?? "-"} tokens=${result.inputTokens + result.outputTokens} cost=$${result.costUsd} tools=${result.toolNames.join(",")}`);
  for (const failure of result.failures) console.log(`      ${failure}`);
}
console.log(JSON.stringify(report.totals));
if (results.some((item) => item.status !== "PASS") || stopped) process.exitCode = 1;
