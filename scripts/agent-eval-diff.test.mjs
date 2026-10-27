import { test } from "node:test";
import assert from "node:assert/strict";
import { diffReports, formatDiff } from "./agent-eval-diff.mjs";

function report({ cases, codeSha = "sha1", datasetVersion = "v1", model = "m", hashes = {}, metrics = {} }) {
  return { codeSha, datasetVersion, model, hashes, metrics, cases };
}
const pass = (id) => ({ id, status: "PASS", failures: [] });
const fail = (id, failures = ["x"]) => ({ id, status: "FAIL", failures });
const error = (id, errorKind = "TIMEOUT") => ({ id, status: "ERROR", errorKind, failures: ["ENVIRONMENT"] });

test("a previously passing case that now fails is a regression", () => {
  const baseline = report({ cases: [pass("A"), fail("B"), pass("C")] });
  const current = report({ cases: [pass("A"), pass("B"), fail("C", ["boom"])] });
  const diff = diffReports(baseline, current);
  assert.deepEqual(diff.regressions.map((item) => item.id), ["C"]);
  assert.deepEqual(diff.improvements.map((item) => item.id), ["B"]);
  assert.equal(diff.hasRegression, true);
});

test("an environment error is not a business regression", () => {
  const baseline = report({ cases: [pass("A"), pass("B")] });
  const current = report({ cases: [pass("A"), error("B", "TIMEOUT")] });
  const diff = diffReports(baseline, current);
  assert.equal(diff.regressions.length, 0);
  assert.equal(diff.hasRegression, false);
  assert.deepEqual(diff.environmentErrors.map((item) => item.id), ["B"]);
});

test("added and removed cases are surfaced", () => {
  const baseline = report({ cases: [pass("A"), pass("gone")] });
  const current = report({ cases: [pass("A"), pass("new")] });
  const diff = diffReports(baseline, current);
  assert.deepEqual(diff.added, [{ id: "new", status: "PASS" }]);
  assert.deepEqual(diff.removed, ["gone"]);
});

test("code, dataset, model and hash changes are reported as context changes", () => {
  const baseline = report({ cases: [pass("A")], hashes: { dataset: "d1", toolSchema: "s1" } });
  const current = report({ cases: [pass("A")], codeSha: "sha2", datasetVersion: "v2", model: "m2", hashes: { dataset: "d2", toolSchema: "s1" } });
  const diff = diffReports(baseline, current);
  assert.equal(diff.contextChanged.codeSha.changed, true);
  assert.equal(diff.contextChanged.datasetVersion.changed, true);
  assert.equal(diff.contextChanged.model.changed, true);
  assert.equal(diff.hashChanges.dataset.changed, true);
  assert.equal(diff.hashChanges.toolSchema.changed, false);
});

test("metric deltas compare rates across reports", () => {
  const baseline = report({ cases: [pass("A")], metrics: { taskSuccess: { pass: 1, denominator: 1, rate: 1 } } });
  const current = report({ cases: [fail("A")], metrics: { taskSuccess: { pass: 0, denominator: 1, rate: 0 } } });
  const diff = diffReports(baseline, current);
  assert.deepEqual(diff.metricDeltas.taskSuccess, { baseline: 1, current: 0, delta: -1 });
});

test("formatDiff prints regressions and environment errors", () => {
  const diff = diffReports(
    report({ cases: [pass("A"), pass("B")] }),
    report({ cases: [fail("A"), error("B")], metrics: {} }),
  );
  const text = formatDiff(diff);
  assert.match(text, /REGRESSED A/);
  assert.match(text, /ENV B: PASS -> ERROR/);
});
