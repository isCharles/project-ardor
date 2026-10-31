"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { readFileSync } = require("node:fs");
const path = require("node:path");

test("packaged update source is the public source repository", () => {
  const config = readFileSync(path.join(__dirname, "../electron-builder.yml"), "utf8");
  assert.match(config, /publish:\s+provider: github\s+owner: isCharles\s+repo: project-ardor\s*$/);
  const workflow = readFileSync(path.join(__dirname, "../../.github/workflows/desktop-release.yml"), "utf8");
  assert.ok(!workflow.includes("ARDOR_RELEASES_TOKEN"));
  assert.ok(!workflow.includes("project-ardor-releases"));
  assert.ok(workflow.includes("visibility -ne 'PUBLIC'"));
  // The draft check runs before checkout, so gh needs the repository explicitly.
  assert.match(workflow, /GH_REPO: \$\{\{ github\.repository \}\}/);
});

test("every local module loaded by the main process is packaged", () => {
  const config = readFileSync(path.join(__dirname, "../electron-builder.yml"), "utf8");
  const main = readFileSync(path.join(__dirname, "../main.cjs"), "utf8");
  for (const [, file] of main.matchAll(/require\("\.\/([^"]+)"\)/g)) {
    const escaped = file.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    assert.match(config, new RegExp(`^  - ${escaped}$`, "m"), `${file} is required by main.cjs but missing from electron-builder files`);
  }
});

test("desktop installers are opt-in and never published with partial assets", () => {
  const workflow = readFileSync(path.join(__dirname, "../../.github/workflows/desktop-release.yml"), "utf8");
  // The old trigger built an installer for every product release.
  assert.doesNotMatch(workflow, /^\s{2}release:\s*$/m);
  assert.match(workflow, /workflow_dispatch:/);
  assert.match(workflow, /Require an existing draft release/);
  assert.match(workflow, /check-desktop-release\.mjs --tag \$env:RELEASE_TAG --dist desktop\/dist --require-draft/);
  assert.match(workflow, /gh release edit \$env:RELEASE_TAG --draft=false/);
  assert.match(workflow, /--require-published/);
  // The build must refuse a tag whose desktop version was not bumped.
  assert.match(workflow, /check-version\.mjs --desktop-tag \$env:RELEASE_TAG/);
});
