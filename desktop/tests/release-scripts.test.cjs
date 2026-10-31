"use strict";

const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const { mkdtempSync, mkdirSync, writeFileSync, rmSync } = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { test } = require("node:test");

const repoRoot = path.join(__dirname, "..", "..");
const releaseScript = path.join(repoRoot, "scripts", "check-desktop-release.mjs");
const versionScript = path.join(repoRoot, "scripts", "check-version.mjs");
const INSTALLER = "Project-Ardor-Setup-0.10.0-x64.exe";

function run(script, args) {
  try {
    const stdout = execFileSync(process.execPath, [script, ...args], { cwd: repoRoot, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    return { code: 0, stdout, stderr: "" };
  } catch (error) {
    return { code: error.status ?? 1, stdout: error.stdout ?? "", stderr: error.stderr ?? "" };
  }
}

function fixture({ draft = true, prerelease = false, installerBytes = 12, metadataSize = 12, blockmap = true } = {}) {
  const dir = mkdtempSync(path.join(os.tmpdir(), "ardor-desktop-release-"));
  const dist = path.join(dir, "dist");
  mkdirSync(dist);
  writeFileSync(path.join(dist, INSTALLER), Buffer.alloc(installerBytes, 1));
  if (blockmap) writeFileSync(path.join(dist, `${INSTALLER}.blockmap`), Buffer.alloc(4));
  const metadata = [
    "version: 0.10.0",
    "files:",
    `  - url: ${INSTALLER}`,
    "    sha512: c2hhNTEy",
    `    size: ${metadataSize}`,
    `path: ${INSTALLER}`,
    "sha512: c2hhNTEy",
    "releaseDate: '2026-11-01T00:00:00.000Z'",
    "",
  ].join("\n");
  writeFileSync(path.join(dist, "latest.yml"), metadata);
  const assets = [
    { name: "latest.yml", size: Buffer.byteLength(metadata), state: "uploaded" },
    { name: INSTALLER, size: installerBytes, state: "uploaded" },
  ];
  if (blockmap) assets.push({ name: `${INSTALLER}.blockmap`, size: 4, state: "uploaded" });
  const release = {
    tag_name: "v0.10.0", draft, prerelease, published_at: "2026-11-01T00:00:00Z", assets,
  };
  const releaseJson = path.join(dir, "release.json");
  writeFileSync(releaseJson, JSON.stringify(release));
  return { dir, dist, releaseJson, release };
}

function cleanup(dir) {
  rmSync(dir, { recursive: true, force: true });
}

test("a draft with a complete payload passes the same check the updater uses", () => {
  const files = fixture();
  try {
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--require-draft"]);
    assert.equal(result.code, 0, result.stderr);
    assert.match(result.stdout, /release assets match latest\.yml/);
  } finally { cleanup(files.dir); }
});

test("a release that is already published cannot be treated as a build target", () => {
  const files = fixture({ draft: false });
  try {
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--require-draft"]);
    assert.notEqual(result.code, 0);
    assert.match(result.stderr, /already published/);
  } finally { cleanup(files.dir); }
});

test("a draft is not announced as a finished desktop release", () => {
  const files = fixture({ draft: true });
  try {
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--require-published"]);
    assert.notEqual(result.code, 0);
    assert.match(result.stderr, /still a draft/);
  } finally { cleanup(files.dir); }
});

test("a truncated upload fails before publication", () => {
  const files = fixture({ installerBytes: 5, metadataSize: 12 });
  try {
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--require-draft"]);
    assert.notEqual(result.code, 0);
    // The remote asset is smaller than latest.yml declares, so the release is incomplete.
    assert.match(result.stderr, /incomplete/);
  } finally { cleanup(files.dir); }
});

test("a missing blockmap fails before publication", () => {
  const files = fixture({ blockmap: false });
  try {
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--require-draft"]);
    assert.notEqual(result.code, 0);
    assert.match(result.stderr, /blockmap/);
  } finally { cleanup(files.dir); }
});

test("the legacy channel check accepts a desktop-complete newest release", () => {
  const files = fixture({ draft: false });
  try {
    const releasesJson = path.join(files.dir, "releases.json");
    writeFileSync(releasesJson, JSON.stringify([files.release]));
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--releases-json", releasesJson, "--legacy"]);
    assert.equal(result.code, 0, result.stderr);
    assert.match(result.stdout, /Old clients keep a usable update path through v0\.10\.0/);
  } finally { cleanup(files.dir); }
});

test("the legacy channel check refuses a server-only newest release while old clients exist", () => {
  const files = fixture({ draft: false });
  try {
    const serverOnly = {
      tag_name: "v0.10.1", draft: false, prerelease: false,
      published_at: "2026-11-02T00:00:00Z", assets: [{ name: "notes.txt", size: 10, state: "uploaded" }],
    };
    const releasesJson = path.join(files.dir, "releases.json");
    writeFileSync(releasesJson, JSON.stringify([serverOnly, files.release]));
    const result = run(releaseScript, ["--tag", "v0.10.0", "--dist", files.dist, "--release-json", files.releaseJson, "--releases-json", releasesJson, "--legacy"]);
    assert.notEqual(result.code, 0);
    assert.match(result.stderr, /Old clients .* read \/releases\/latest/);
    assert.match(result.stderr, /v0\.10\.1/);
  } finally { cleanup(files.dir); }
});

test("the desktop build is pinned to the release tag, while the product may run ahead", () => {
  const current = run(versionScript, []);
  assert.equal(current.code, 0, current.stderr);
  const desktopVersion = JSON.parse(require("node:fs").readFileSync(path.join(repoRoot, "desktop", "package.json"), "utf8")).version;

  const matching = run(versionScript, ["--desktop-tag", `v${desktopVersion}`]);
  assert.equal(matching.code, 0, matching.stderr);
  assert.match(matching.stdout, new RegExp(`building v${desktopVersion.replace(/\./g, "\\.")}`));

  const mismatch = run(versionScript, ["--desktop-tag", "v99.0.0"]);
  assert.notEqual(mismatch.code, 0);
  assert.match(mismatch.stderr, /must build desktop version 99\.0\.0/);
});
