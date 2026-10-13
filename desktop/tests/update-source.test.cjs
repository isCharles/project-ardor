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
});
