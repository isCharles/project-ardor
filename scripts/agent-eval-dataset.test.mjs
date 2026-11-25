import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { buildDatasetEvidence, loadEvaluationDataset, selectEvaluationCases } from "./agent-eval-dataset.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));
const dataset = JSON.parse(fs.readFileSync(path.join(here, "agent-eval-cases.json"), "utf8"));
const manifest = JSON.parse(fs.readFileSync(path.join(here, "agent-eval-split.json"), "utf8"));
const cases = dataset.cases;

const REQUIRED_CATEGORIES = ["read", "create", "modify", "clarification", "approval", "cancel",
  "bad-params", "ownership", "tool-failure", "write-response-lost", "forbidden-side-effect", "multi-step"];

test("dataset is versioned and sized for a minimal business loop", () => {
  assert.equal(typeof dataset.version, "number");
  assert.match(dataset.datasetVersion, /\d{4}-\d{2}-\d{2}/);
  assert.ok(cases.length >= 20 && cases.length <= 30, `expected 20-30 cases, got ${cases.length}`);
});

test("case ids are unique and every case declares a prompt and tier", () => {
  const ids = cases.map((item) => item.id);
  assert.equal(new Set(ids).size, ids.length);
  for (const item of cases) {
    assert.ok(item.prompt && item.prompt.length > 0, `${item.id} needs a prompt`);
    assert.ok(["representative", "challenge"].includes(item.tier), `${item.id} has invalid tier`);
  }
});

test("both tiers and the required categories are represented", () => {
  assert.ok(cases.filter((item) => item.tier === "representative").length >= 10);
  assert.ok(cases.filter((item) => item.tier === "challenge").length >= 8);
  const categories = new Set(cases.map((item) => item.category));
  const missing = REQUIRED_CATEGORIES.filter((category) => !categories.has(category));
  assert.deepEqual(missing, [], `missing categories: ${missing.join(", ")}`);
});

test("a small hold-out set exists and is not the whole dataset", () => {
  const heldOut = cases.filter((item) => item.heldOut === true);
  assert.ok(heldOut.length >= 1);
  assert.ok(heldOut.length < cases.length / 2);
});

test("split manifest freezes all 30 cases and exactly four held-out ids", () => {
  const evidence = buildDatasetEvidence(dataset, manifest);
  assert.equal(evidence.caseCount, 30);
  assert.deepEqual(evidence.tierCounts, { representative: 15, challenge: 15 });
  assert.deepEqual(evidence.heldOutCaseIds, [
    "business-error-not-masked", "cross-module-conflict-replan-capped",
    "read-then-create-multistep", "write-executed-response-lost",
  ]);
  assert.match(evidence.datasetHash, /^[a-f0-9]{64}$/);
  assert.match(evidence.splitHash, /^[a-f0-9]{64}$/);
  assert.deepEqual(loadEvaluationDataset().split, evidence);
});

test("manual tuning defaults to development cases and held-out requires an explicit switch", () => {
  assert.equal(selectEvaluationCases(dataset).length, 26);
  assert.ok(selectEvaluationCases(dataset).every((item) => item.heldOut !== true));
  assert.equal(selectEvaluationCases(dataset, { includeHeldOut: true }).length, 30);
});

test("split rejects flag, version and membership drift instead of silently re-freezing it", () => {
  const changedFlag = structuredClone(dataset);
  changedFlag.cases[0].heldOut = true;
  assert.throws(() => buildDatasetEvidence(changedFlag, manifest), /Held-out split mismatch/);
  assert.throws(() => buildDatasetEvidence(dataset, { ...manifest, datasetVersion: "changed" }), /does not match split/);
  assert.throws(() => buildDatasetEvidence(dataset, { ...manifest, heldOutCaseIds: manifest.heldOutCaseIds.slice(1) }), /Held-out split mismatch/);
});

test("executed and forbidden tool sets never contradict each other", () => {
  for (const item of cases) {
    const required = new Set(item.requiredExecuted ?? []);
    for (const name of item.forbiddenExecuted ?? []) {
      assert.ok(!required.has(name), `${item.id} both requires and forbids ${name}`);
    }
    if (item.allowProposalOnly) {
      assert.ok((item.expectProposed ?? []).length > 0, `${item.id} must declare expectProposed`);
    }
    if (item.expectInvalidParams) {
      assert.equal(item.expectToolFailure, true, `${item.id} bad params must expect a tool failure`);
    }
  }
});

test("mutating cases declare a synthetic fixture with state and cleanup", () => {
  const mutating = new Set(["create", "modify", "approval", "bad-params", "ownership", "cancel", "write-response-lost", "multi-step"]);
  for (const item of cases.filter((entry) => mutating.has(entry.category))) {
    assert.ok(item.fixture, `${item.id} needs a fixture`);
    assert.ok(item.fixture.initialState, `${item.id} needs initialState`);
    assert.ok(item.fixture.expectedEndState, `${item.id} needs expectedEndState`);
    assert.ok(Array.isArray(item.fixture.forbiddenSideEffects), `${item.id} needs forbiddenSideEffects`);
    assert.equal(typeof item.fixture.cleanup, "string", `${item.id} needs cleanup`);
  }
});

test("referenced tool names are well-formed identifiers", () => {
  const names = cases.flatMap((item) => [
    ...(item.expectedTools ?? []), ...(item.requiredExecuted ?? []),
    ...(item.forbiddenTools ?? []), ...(item.forbiddenExecuted ?? []), ...(item.expectProposed ?? []),
  ]);
  for (const name of names) assert.match(name, /^[a-z][a-z0-9_]*$/);
});
