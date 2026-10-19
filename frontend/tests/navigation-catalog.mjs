import assert from "node:assert/strict";
import { readFile, access } from "node:fs/promises";
import ts from "typescript";

const catalog = JSON.parse(await readFile(new URL("../src/lib/navigation-catalog.json", import.meta.url), "utf8"));
const backend = JSON.parse(await readFile(new URL("../../backend/src/main/resources/navigation-catalog.json", import.meta.url), "utf8"));
assert.deepEqual(catalog, backend, "Both builds must publish the same navigation contract");
const source = (await readFile(new URL("../src/lib/navigation.ts", import.meta.url), "utf8"))
  .replace('import catalog from "./navigation-catalog.json";', `const catalog = ${JSON.stringify(catalog)};`);
const compiled = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
const navigation = await import(`data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`);
const { destinationForPath, destinationActive, normalizeDestinationIds, destinationsFromLegacy, legacyHrefsForDestinations } = navigation;
assert.deepEqual(navigation.defaultDestinationIds, ["chat", "job-search", "learning", "calendar"]);
assert.equal(new Set(catalog.destinations.map(item => item.id)).size, catalog.destinations.length);
for (const item of catalog.destinations) {
  for (const path of [item.href, ...item.aliases]) {
    await access(new URL(`../src/app${path}/page.tsx`, import.meta.url));
    assert.equal(destinationForPath(`${path}?question=fixture&retest=fixture#answer`)?.id, item.id, path);
  }
  if (item.section) assert.ok(catalog.destinations.some(parent => parent.id === item.section && parent.domain === item.domain));
}
assert.equal(destinationForPath("/app/opportunities")?.id, "job-search.interviews");
assert.equal(destinationForPath("/app/interviews")?.id, "learning.mock-interviews");
assert.equal(destinationForPath("/app/replay?question=fixture")?.id, "learning.question-practice");
assert.equal(destinationForPath("/app/evidence")?.id, "learning.progress");
assert.equal(destinationForPath("/app/opportunities-unsafe"), undefined);
assert.equal(destinationForPath("/app/admin"), undefined);
assert.equal(destinationActive("learning.practice", "/app/replay?question=fixture"), true);
assert.equal(destinationActive("job-search.interviews", "/app/recaps?draft=fixture"), true);
assert.equal(destinationActive("job-search", "/app/interviews"), false);
assert.equal(destinationActive("learning", "/app/interviews"), true);
assert.deepEqual(normalizeDestinationIds(["calendar", "calendar"]), ["chat", "calendar"]);
for (const invalid of [["admin"], ["/app"], null, [1]]) assert.throws(() => normalizeDestinationIds(invalid));
assert.deepEqual(destinationsFromLegacy(["/app", "/app/interviews", "/app/opportunities", "/app/evidence"]),
  ["chat", "learning.mock-interviews", "job-search.interviews", "learning.progress"]);
assert.deepEqual(legacyHrefsForDestinations(["job-search", "job-search.interviews", "learning.practice"]),
  ["/app", "/app/opportunities", "/app/interviews"]);
console.log("Navigation contract: canonical/legacy routes, real/mock meanings, stable IDs, section ancestry and build parity passed.");
