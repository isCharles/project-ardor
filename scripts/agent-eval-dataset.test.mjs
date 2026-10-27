import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { evaluateCase } from "./agent-eval-assertions.mjs";

test("golden cases have unique identities and testable read-only tool expectations", () => {
  const dataset = JSON.parse(readFileSync(new URL("./agent-eval-cases.json", import.meta.url), "utf8"));
  assert.equal(dataset.version, 1);
  assert.equal(dataset.cases.length, 8);
  assert.equal(new Set(dataset.cases.map((item) => item.id)).size, dataset.cases.length);
  for (const item of dataset.cases) {
    assert.ok(item.id && item.prompt && Array.isArray(item.allowedTools));
    for (const expected of item.expectedTools ?? []) assert.ok(item.allowedTools.includes(expected), item.id);
    const spans = (item.expectedTools ?? []).map((name) => ({ kind: "TOOL", name, status: "COMPLETED" }));
    const trace = { status: "COMPLETED", spans };
    assert.deepEqual(evaluateCase(item, trace, item.mustContain ?? "").failures, [], item.id);
    trace.spans.push({ kind: "TOOL", name: "delete_memory", status: "NOT_EXECUTED" });
    assert.ok(evaluateCase(item, trace, item.mustContain ?? "").failures.length > 0, item.id);
  }
});
