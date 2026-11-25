import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { hashOf } from "./agent-eval-report.mjs";

const here = path.dirname(fileURLToPath(import.meta.url));

export function buildDatasetEvidence(dataset, manifest) {
  const cases = dataset?.cases;
  if (!Array.isArray(cases) || cases.length === 0) throw new Error("Evaluation dataset has no cases");
  if (manifest?.manifestVersion !== 1) throw new Error("Unsupported evaluation split manifest");
  if (dataset.datasetVersion !== manifest.datasetVersion) {
    throw new Error(`Dataset version ${dataset.datasetVersion} does not match split ${manifest.datasetVersion}`);
  }
  const ids = cases.map((item) => item.id);
  if (new Set(ids).size !== ids.length) throw new Error("Evaluation case ids must be unique");
  const declared = cases.filter((item) => item.heldOut === true).map((item) => item.id).sort();
  const heldOutCaseIds = [...(manifest.heldOutCaseIds ?? [])].sort();
  if (new Set(heldOutCaseIds).size !== heldOutCaseIds.length) throw new Error("Held-out case ids must be unique");
  if (JSON.stringify(declared) !== JSON.stringify(heldOutCaseIds)) {
    throw new Error(`Held-out split mismatch: dataset=${declared.join(",")} manifest=${heldOutCaseIds.join(",")}`);
  }
  const tierCounts = {};
  for (const item of cases) tierCounts[item.tier] = (tierCounts[item.tier] ?? 0) + 1;
  const datasetHash = hashOf(cases);
  const evidence = {
    manifestVersion: manifest.manifestVersion,
    datasetVersion: dataset.datasetVersion,
    datasetFile: manifest.datasetFile,
    datasetHash,
    caseCount: cases.length,
    tierCounts,
    heldOutCaseIds,
  };
  return { ...evidence, splitHash: hashOf(evidence) };
}

export function selectEvaluationCases(dataset, { includeHeldOut = false } = {}) {
  return (dataset.cases ?? []).filter((item) => includeHeldOut || item.heldOut !== true);
}

export function loadEvaluationDataset(casesPath = path.join(here, "agent-eval-cases.json")) {
  const dataset = JSON.parse(fs.readFileSync(casesPath, "utf8"));
  const manifest = JSON.parse(fs.readFileSync(path.join(here, "agent-eval-split.json"), "utf8"));
  return { ...dataset, split: buildDatasetEvidence(dataset, manifest) };
}
