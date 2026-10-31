"use strict";

const assert = require("node:assert/strict");
const { test } = require("node:test");
const {
  compareVersions,
  compareProductVersions,
  parseLatestYml,
  selectDesktopRelease,
  resolveDesktopUpdate,
  validateReleaseMetadata,
  legacyChannelRisk,
} = require("../update-channel.cjs");

const INSTALLER = version => `Project-Ardor-Setup-${version}-x64.exe`;

function asset(name, size = 1000, state = "uploaded") {
  return { name, size, state };
}

function release(tag, { assets = [], draft = false, prerelease = false, publishedAt = "2026-10-31T00:00:00Z" } = {}) {
  return { tag_name: tag, draft, prerelease, published_at: publishedAt, assets };
}

function desktopAssets(version, { installerSize = 1000 } = {}) {
  return [
    asset("latest.yml", 300),
    asset(INSTALLER(version), installerSize),
    asset(`${INSTALLER(version)}.blockmap`, 900),
  ];
}

function latestYml(version, { size = 1000, sha512 = "c2hhNTEy", name = INSTALLER(version) } = {}) {
  return [
    `version: ${version}`,
    "files:",
    `  - url: ${name}`,
    `    sha512: ${sha512}`,
    `    size: ${size}`,
    `path: ${name}`,
    `sha512: ${sha512}`,
    "releaseDate: '2026-10-31T00:00:00.000Z'",
    "",
  ].join("\n");
}

function serverOnly(tag) {
  return release(tag, { assets: [asset("release-notes.txt", 120)] });
}

function fetcher(handlers) {
  return async (url) => {
    for (const [fragment, body] of Object.entries(handlers)) {
      if (url.includes(fragment)) return typeof body === "function" ? body(url) : body;
    }
    throw new Error(`unexpected fetch: ${url}`);
  };
}

test("versions compare numerically, so 0.10.0 is newer than 0.9.0", () => {
  assert.equal(compareVersions("0.10.0", "0.9.0"), 1);
  assert.equal(compareVersions("v0.9.0", "0.9.0"), 0);
  assert.equal(compareVersions("0.8.9", "0.9.0"), -1);
});

test("release candidates order below their stable product version", () => {
  assert.equal(compareProductVersions("0.10.0-rc.1", "0.10.0-rc.2"), -1);
  assert.equal(compareProductVersions("0.10.0", "0.10.0-rc.2"), 1);
  assert.equal(compareProductVersions("v0.10.0-rc.1", "0.10.0-rc.1"), 0);
  assert.equal(compareProductVersions("0.10.0-rc.1", "0.9.0"), 1);
  assert.equal(compareProductVersions("0.9.0", "0.10.0-rc.1"), -1);
  assert.throws(() => compareProductVersions("0.10.0-beta.1", "0.9.0"), /Not a version/);
});

test("a server-only product release never enters the desktop channel", () => {
  const releases = [serverOnly("v0.10.2"), release("v0.10.1", { assets: desktopAssets("0.10.1") })];
  const selection = selectDesktopRelease({ releases, currentVersion: "0.10.1" });
  assert.equal(selection.kind, "current");
  assert.equal(selection.tag, "v0.10.1");
  assert.equal(selection.reason, "up-to-date");
});

test("a complete desktop release is selected and pinned to its own tag", async () => {
  const releases = [serverOnly("v0.10.2"), release("v0.10.1", { assets: desktopAssets("0.10.1") })];
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: fetcher({
      "/releases?per_page=30": JSON.stringify(releases),
      "/download/v0.10.1/latest.yml": latestYml("0.10.1"),
    }),
  });
  assert.equal(result.kind, "update");
  assert.equal(result.tag, "v0.10.1");
  assert.equal(result.version, "0.10.1");
  assert.equal(result.downloadUrl, "https://github.com/isCharles/project-ardor/releases/download/v0.10.1/");
  assert.equal(result.installer, INSTALLER("0.10.1"));
});

test("an installer that is still uploading is reported as incomplete, not current", async () => {
  const releases = [release("v0.10.1", { assets: desktopAssets("0.10.1", { installerSize: 640 }) })];
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: fetcher({
      "/releases?per_page=30": JSON.stringify(releases),
      "/download/v0.10.1/latest.yml": latestYml("0.10.1", { size: 1000 }),
    }),
  });
  assert.equal(result.kind, "incomplete");
  assert.match(result.detail, /640 bytes/);
});

test("a release whose installer is not attached yet is incomplete", async () => {
  const releases = [release("v0.10.1", { assets: [asset("latest.yml", 300)] })];
  const selection = selectDesktopRelease({ releases, currentVersion: "0.9.0" });
  assert.equal(selection.kind, "candidate");
  const metadata = parseLatestYml(latestYml("0.10.1"));
  const validation = validateReleaseMetadata(selection.release, metadata);
  assert.equal(validation.kind, "incomplete");
  assert.match(validation.detail, /not attached yet/);
});

test("a missing blockmap keeps the release out of the download path", async () => {
  const releases = [release("v0.10.1", {
    assets: [asset("latest.yml", 300), asset(INSTALLER("0.10.1"), 1000)],
  })];
  const selection = selectDesktopRelease({ releases, currentVersion: "0.9.0" });
  const validation = validateReleaseMetadata(selection.release, parseLatestYml(latestYml("0.10.1")));
  assert.equal(validation.kind, "incomplete");
  assert.match(validation.detail, /blockmap/);
});

test("no desktop release at all means the client is current", async () => {
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: fetcher({ "/releases?per_page=30": JSON.stringify([serverOnly("v0.10.2")]) }),
  });
  assert.equal(result.kind, "current");
  assert.equal(result.reason, "no-desktop-release");
});

test("a full page of server-only releases does not hide a later desktop release", async () => {
  const pageOne = Array.from({ length: 30 }, (_, index) => serverOnly(`v0.10.${index + 2}`));
  const desktop = release("v0.10.1", { assets: desktopAssets("0.10.1") });
  const pages = [];
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: async (url) => {
      if (url.includes("/releases?") && url.includes("page=1")) { pages.push(1); return JSON.stringify(pageOne); }
      if (url.includes("/releases?") && url.includes("page=2")) { pages.push(2); return JSON.stringify([desktop]); }
      if (url.includes("/download/v0.10.1/latest.yml")) return latestYml("0.10.1");
      throw new Error(`unexpected fetch: ${url}`);
    },
  });
  assert.equal(result.kind, "update");
  assert.equal(result.tag, "v0.10.1");
  assert.deepEqual(pages, [1, 2]);
});

test("an exhausted history of server-only releases stays current", async () => {
  const pageOne = Array.from({ length: 30 }, (_, index) => serverOnly(`v0.10.${index + 2}`));
  let requests = 0;
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: async (url) => {
      requests += 1;
      if (url.includes("page=1")) return JSON.stringify(pageOne);
      if (url.includes("page=2")) return JSON.stringify([serverOnly("v0.10.0")]);
      throw new Error(`unexpected fetch: ${url}`);
    },
  });
  assert.equal(result.kind, "current");
  assert.equal(result.reason, "no-desktop-release");
  assert.equal(requests, 2, "Paging stops at the first short page");
});

test("an unreadable release list is corrupt metadata, not a network outage", async () => {
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: async () => "<html>rate limited</html>",
  });
  assert.equal(result.kind, "metadata");
});

test("metadata that contradicts its release is reported as metadata, never as current", async () => {
  const releases = [release("v0.10.1", { assets: desktopAssets("0.10.1") })];
  for (const body of [latestYml("0.9.9"), latestYml("0.10.1", { sha512: "" }), "", "version: 0.10.1\nfiles:\n"]) {
    const result = await resolveDesktopUpdate({
      currentVersion: "0.9.0",
      fetchText: fetcher({ "/releases?per_page=30": JSON.stringify(releases), "latest.yml": body }),
    });
    assert.equal(result.kind, "metadata", `body should be rejected: ${JSON.stringify(body)}`);
  }
});

test("a broken release list or network failure is reported as network", async () => {
  const offline = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: async () => { throw new Error("getaddrinfo ENOTFOUND api.github.com"); },
  });
  assert.equal(offline.kind, "network");
  assert.match(offline.detail, /ENOTFOUND/);
});

test("an unreadable repository is reported as missing, not as a network outage", async () => {
  const missing = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: async () => { throw new Error("HTTP 404 https://api.github.com/repos/isCharles/project-ardor/releases"); },
  });
  assert.equal(missing.kind, "not-found");
});

test("metadata that disappears mid-check counts as an unfinished release", async () => {
  const releases = [release("v0.10.1", { assets: desktopAssets("0.10.1") })];
  const result = await resolveDesktopUpdate({
    currentVersion: "0.9.0",
    fetchText: fetcher({
      "/releases?per_page=30": JSON.stringify(releases),
      "latest.yml": () => { throw new Error("HTTP 404 https://github.com/…/latest.yml"); },
    }),
  });
  assert.equal(result.kind, "incomplete");
  assert.equal(result.tag, "v0.10.1");
});

test("latest.yml parsing tolerates CRLF, a BOM and quoted values", () => {
  const text = `\uFEFFversion: '0.10.1'\r\nfiles:\r\n  - url: "${INSTALLER("0.10.1")}"\r\n    sha512: abc\r\n    size: 1000\r\npath: ${INSTALLER("0.10.1")}\r\nsha512: abc\r\n`;
  const metadata = parseLatestYml(text);
  assert.equal(metadata.version, "0.10.1");
  assert.equal(metadata.files[0].url, INSTALLER("0.10.1"));
  assert.equal(metadata.files[0].size, "1000");
});

test("old clients stay safe only while the newest normal release ships a desktop payload", () => {
  const safe = legacyChannelRisk([
    release("v0.10.1", { assets: desktopAssets("0.10.1"), publishedAt: "2026-11-02T00:00:00Z" }),
    serverOnly("v0.10.0"),
  ]);
  assert.equal(safe.safe, true);
  assert.equal(safe.tag, "v0.10.1");

  const unsafe = legacyChannelRisk([
    release("v0.10.1", { assets: desktopAssets("0.10.1"), publishedAt: "2026-11-01T00:00:00Z" }),
    release("v0.10.2", { assets: [asset("release-notes.txt", 120)], publishedAt: "2026-11-02T00:00:00Z" }),
  ]);
  assert.equal(unsafe.safe, false);
  assert.equal(unsafe.tag, "v0.10.2");
  assert.deepEqual(unsafe.missing.sort(), ["<installer>.exe", "<installer>.exe.blockmap", "latest.yml"].sort());
});

test("server-only releases marked prerelease keep the old path working", () => {
  const risk = legacyChannelRisk([
    release("v0.10.1", { assets: desktopAssets("0.10.1"), publishedAt: "2026-11-01T00:00:00Z" }),
    release("v0.10.2", { assets: [asset("release-notes.txt", 120)], prerelease: true, publishedAt: "2026-11-02T00:00:00Z" }),
  ]);
  assert.equal(risk.safe, true);
  assert.equal(risk.tag, "v0.10.1");
});
