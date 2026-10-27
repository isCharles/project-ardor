/**
 * Compare two evaluation reports so a run is judged against history, not in isolation.
 * A regression is a case that passed in the baseline and no longer passes; it is the
 * signal that matters most, and it is surfaced separately from aggregate rate moves.
 */

export function diffReports(baseline, current) {
  const baseCases = Object.fromEntries((baseline?.cases ?? []).map((item) => [item.id, item]));
  const curCases = Object.fromEntries((current?.cases ?? []).map((item) => [item.id, item]));
  const ids = [...new Set([...Object.keys(baseCases), ...Object.keys(curCases)])].sort();

  const regressions = [];
  const improvements = [];
  const added = [];
  const removed = [];
  const environmentErrors = [];
  for (const id of ids) {
    const before = baseCases[id];
    const after = curCases[id];
    if (before && !after) { removed.push(id); continue; }
    if (!before && after) { added.push({ id, status: after.status }); continue; }
    // A run that produced no scorable trace is an environment error, not a
    // business regression: it is counted in its own metric, never folded in here.
    if (after.status === "ERROR") {
      environmentErrors.push({ id, from: before.status, to: "ERROR", errorKind: after.errorKind ?? null });
      continue;
    }
    if (before.status === "PASS" && after.status === "FAIL") {
      regressions.push({ id, from: before.status, to: after.status, failures: after.failures ?? [] });
    } else if (before.status !== "PASS" && after.status === "PASS") {
      improvements.push({ id, from: before.status, to: after.status });
    }
  }

  const metricDeltas = {};
  for (const key of Object.keys(current?.metrics ?? {})) {
    const before = baseline?.metrics?.[key]?.rate ?? null;
    const after = current?.metrics?.[key]?.rate ?? null;
    metricDeltas[key] = {
      baseline: before,
      current: after,
      delta: before == null || after == null ? null : Number((after - before).toFixed(4)),
    };
  }

  const contextChanged = {};
  for (const field of ["codeSha", "datasetVersion", "model"]) {
    const before = baseline?.[field] ?? null;
    const after = current?.[field] ?? null;
    contextChanged[field] = { baseline: before, current: after, changed: before !== after };
  }
  const hashChanges = {};
  for (const key of Object.keys(current?.hashes ?? {})) {
    const before = baseline?.hashes?.[key] ?? null;
    const after = current?.hashes?.[key] ?? null;
    hashChanges[key] = { changed: before !== after, baseline: before, current: after };
  }

  return {
    regressions,
    improvements,
    added,
    removed,
    environmentErrors,
    metricDeltas,
    contextChanged,
    hashChanges,
    // hasRegression tracks business regressions only; environment errors are separate.
    hasRegression: regressions.length > 0,
  };
}

export function formatDiff(diff) {
  const lines = [];
  lines.push(`regressions: ${diff.regressions.length}`);
  for (const item of diff.regressions) lines.push(`  REGRESSED ${item.id}: ${item.from} -> ${item.to}${item.failures.length ? ` (${item.failures.join("; ")})` : ""}`);
  lines.push(`improvements: ${diff.improvements.length}`);
  for (const item of diff.improvements) lines.push(`  FIXED ${item.id}: ${item.from} -> ${item.to}`);
  lines.push(`environment errors: ${diff.environmentErrors.length}`);
  for (const item of diff.environmentErrors) lines.push(`  ENV ${item.id}: ${item.from} -> ERROR${item.errorKind ? ` (${item.errorKind})` : ""}`);
  if (diff.added.length) lines.push(`added: ${diff.added.map((item) => item.id).join(", ")}`);
  if (diff.removed.length) lines.push(`removed: ${diff.removed.join(", ")}`);
  for (const [key, value] of Object.entries(diff.metricDeltas)) {
    lines.push(`metric ${key}: ${value.baseline} -> ${value.current}${value.delta == null ? "" : ` (${value.delta >= 0 ? "+" : ""}${value.delta})`}`);
  }
  const changedContext = Object.entries(diff.contextChanged).filter(([, value]) => value.changed).map(([key]) => key);
  const changedHashes = Object.entries(diff.hashChanges).filter(([, value]) => value.changed).map(([key]) => key);
  if (changedContext.length) lines.push(`context changed: ${changedContext.join(", ")}`);
  if (changedHashes.length) lines.push(`hash changed: ${changedHashes.join(", ")}`);
  return lines.join("\n");
}
