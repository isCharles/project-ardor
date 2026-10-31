"use strict";

// Release-channel logic for the desktop updater. Pure functions plus one thin
// fetch wrapper, so desktop/tests can drive every branch with fixtures:
// no Electron, no network, no filesystem.

const PRODUCT_REPO = { owner: "isCharles", repo: "project-ardor" };
const METADATA_ASSET = "latest.yml";

function parseVersion(value) {
  const match = /^v?(\d+)\.(\d+)\.(\d+)$/.exec(String(value ?? "").trim());
  return match ? [Number(match[1]), Number(match[2]), Number(match[3])] : null;
}

function compareVersions(a, b) {
  const left = parseVersion(a);
  const right = parseVersion(b);
  if (!left || !right) throw new Error(`Not a version: ${!left ? a : b}`);
  for (let index = 0; index < 3; index += 1) {
    if (left[index] !== right[index]) return left[index] < right[index] ? -1 : 1;
  }
  return 0;
}

function isNewer(candidate, current) {
  if (!parseVersion(candidate) || !parseVersion(current)) return false;
  return compareVersions(candidate, current) > 0;
}

// Minimal reader for the flat shape electron-builder writes: top-level scalars,
// one `files:` list of scalar maps, and quoted values. Anything it cannot read
// is reported as corrupt metadata rather than guessed at.
function parseLatestYml(text) {
  const source = String(text ?? "").replace(/^\uFEFF/, "");
  if (!source.trim()) throw new Error("latest.yml is empty");
  const metadata = { files: [] };
  let current = null;
  let inFiles = false;
  for (const rawLine of source.split(/\r?\n/)) {
    if (!rawLine.trim() || rawLine.trim().startsWith("#")) continue;
    const item = /^\s*-\s+(.*)$/.exec(rawLine);
    if (item) {
      if (!inFiles) throw new Error("latest.yml has a list item outside files");
      current = {};
      metadata.files.push(current);
      assignPair(current, item[1]);
      continue;
    }
    if (/^files:\s*$/.test(rawLine)) { inFiles = true; current = null; continue; }
    if (/^\S/.test(rawLine)) {
      inFiles = false;
      current = metadata;
      assignPair(metadata, rawLine);
      continue;
    }
    if (!current) throw new Error("latest.yml has an indented line outside a mapping");
    assignPair(current, rawLine.trim());
  }
  if (!metadata.version) throw new Error("latest.yml has no version");
  if (!metadata.files.length) throw new Error("latest.yml has no files");
  return metadata;
}

function assignPair(target, line) {
  const pair = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(line.trim());
  if (!pair) throw new Error(`latest.yml has an unreadable field: ${line.slice(0, 40)}`);
  target[pair[1]] = unquote(pair[2]);
}

function unquote(value) {
  const trimmed = value.trim();
  if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith('"') && trimmed.endsWith('"'))) {
    return trimmed.slice(1, -1);
  }
  return trimmed;
}

function normalizeRelease(release) {
  const assets = new Map();
  for (const asset of release?.assets ?? []) {
    if (asset?.name) assets.set(asset.name, { size: Number(asset.size ?? 0), state: asset.state ?? "uploaded" });
  }
  return {
    tag: release?.tag_name ?? "",
    version: parseVersion(release?.tag_name),
    draft: Boolean(release?.draft),
    prerelease: Boolean(release?.prerelease),
    publishedAt: release?.published_at ?? null,
    assets,
  };
}

// Published, stable releases that carry update metadata. A server-only product
// release never matches, which is what keeps it out of the desktop channel.
function desktopReleases(releases) {
  return (releases ?? [])
    .map(normalizeRelease)
    .filter(release => release.version && !release.draft && !release.prerelease && release.assets.has(METADATA_ASSET))
    .sort((left, right) => compareVersions(right.version.join("."), left.version.join(".")));
}

function selectDesktopRelease({ releases, currentVersion }) {
  const candidates = desktopReleases(releases);
  if (!candidates.length) return { kind: "current", reason: "no-desktop-release" };
  const best = candidates[0];
  const bestVersion = best.version.join(".");
  if (!isNewer(bestVersion, currentVersion)) return { kind: "current", reason: "up-to-date", tag: best.tag };
  return { kind: "candidate", tag: best.tag, version: bestVersion, release: best };
}

function safeFileName(name) {
  return typeof name === "string" && name.length > 0 && !/[\\/]/.test(name) && name !== "." && name !== "..";
}

// Cross-checks the release's asset list against its own latest.yml before the
// updater is allowed to download anything.
function validateReleaseMetadata(release, metadata) {
  const version = `${metadata.version}`.trim();
  if (!parseVersion(version)) return { kind: "metadata", detail: `latest.yml version is not a version: ${version}` };
  if (compareVersions(version, release.version.join(".")) !== 0) {
    return { kind: "metadata", detail: `latest.yml version ${version} does not match release ${release.tag}` };
  }
  const file = metadata.files[0] ?? {};
  const name = metadata.path || file.url;
  if (!safeFileName(name)) return { kind: "metadata", detail: "latest.yml does not name a downloadable installer" };
  if (!file.sha512) return { kind: "metadata", detail: "latest.yml has no sha512 for the installer" };
  const installer = release.assets.get(name);
  if (!installer) return { kind: "incomplete", tag: release.tag, detail: `installer ${name} is not attached yet` };
  if (installer.state !== "uploaded") return { kind: "incomplete", tag: release.tag, detail: `installer ${name} is still uploading` };
  const declared = Number(file.size ?? 0);
  if (!declared || installer.size !== declared) {
    return { kind: "incomplete", tag: release.tag, detail: `installer ${name} is ${installer.size} bytes, latest.yml says ${declared}` };
  }
  const blockmap = release.assets.get(`${name}.blockmap`);
  if (!blockmap) return { kind: "incomplete", tag: release.tag, detail: `blockmap for ${name} is not attached yet` };
  return { kind: "ok", tag: release.tag, version, installer: name, downloadUrl: downloadBaseUrl(release.tag) };
}

function downloadBaseUrl(tag, repo = PRODUCT_REPO) {
  return `https://github.com/${repo.owner}/${repo.repo}/releases/download/${encodeURIComponent(tag)}/`;
}

async function resolveDesktopUpdate(options) {
  const {
    currentVersion,
    repo = PRODUCT_REPO,
    apiBase = `https://api.github.com/repos/${repo.owner}/${repo.repo}`,
    fetchText,
    timeoutMs = 15_000,
  } = options;
  if (typeof fetchText !== "function") throw new Error("resolveDesktopUpdate needs fetchText");

  let releases;
  try {
    const body = await fetchText(`${apiBase}/releases?per_page=30`, { signal: AbortSignal.timeout(timeoutMs) });
    releases = JSON.parse(body);
  } catch (error) {
    const detail = error?.message ?? String(error);
    // A 404 here means the repository or its releases are not publicly readable,
    // which is a different problem from an unreachable network.
    return { kind: /HTTP 404/.test(detail) ? "not-found" : "network", detail };
  }
  if (!Array.isArray(releases)) return { kind: "metadata", detail: "release list is not an array" };

  const selection = selectDesktopRelease({ releases, currentVersion });
  if (selection.kind === "current") return selection;

  let metadata;
  try {
    metadata = parseLatestYml(await fetchText(`${downloadBaseUrl(selection.tag, repo)}${METADATA_ASSET}`, {
      signal: AbortSignal.timeout(timeoutMs),
    }));
  } catch (error) {
    const detail = error?.message ?? String(error);
    // The asset list said latest.yml exists, so a fetch failure here means the
    // release is not readable yet rather than that it is corrupt.
    return { kind: /HTTP 404/.test(detail) ? "incomplete" : "metadata", tag: selection.tag, detail };
  }

  const validation = validateReleaseMetadata(selection.release, metadata);
  if (validation.kind !== "ok") return validation;
  return { ...validation, kind: "update", currentVersion };
}

function latestNormalRelease(releases) {
  const normal = (releases ?? [])
    .map(normalizeRelease)
    .filter(release => release.version && !release.draft && !release.prerelease);
  if (!normal.length) return null;
  return normal.reduce((newest, release) => {
    if (!newest) return release;
    const left = Date.parse(release.publishedAt ?? 0);
    const right = Date.parse(newest.publishedAt ?? 0);
    return left > right ? release : newest;
  }, null);
}

function releaseCompleteness(release) {
  const missing = [];
  if (!release.assets.has(METADATA_ASSET)) missing.push(METADATA_ASSET);
  const installers = [...release.assets.keys()].filter(name => name.endsWith(".exe"));
  if (!installers.length) {
    missing.push("<installer>.exe", "<installer>.exe.blockmap");
  } else if (installers.some(name => !release.assets.has(`${name}.blockmap`))) {
    missing.push("<installer>.exe.blockmap");
  }
  return missing;
}

// Clients up to 0.9.0 ask GitHub for /releases/latest, which ignores drafts and
// prereleases, and then require latest.yml in whatever it returns. They cannot be
// changed retroactively, so this reports whether the newest normal release keeps
// that old path usable.
function legacyChannelRisk(releases) {
  const latest = latestNormalRelease(releases);
  if (!latest) return { safe: true, reason: "no-normal-release" };
  const missing = releaseCompleteness(latest);
  return missing.length
    ? { safe: false, tag: latest.tag, missing, detail: `Old clients would fail: ${latest.tag} has no ${missing.join(", ")}.` }
    : { safe: true, tag: latest.tag, detail: `Old clients still receive ${latest.tag}.` };
}

module.exports = {
  PRODUCT_REPO,
  METADATA_ASSET,
  compareVersions,
  parseVersion,
  parseLatestYml,
  normalizeRelease,
  desktopReleases,
  selectDesktopRelease,
  validateReleaseMetadata,
  downloadBaseUrl,
  resolveDesktopUpdate,
  latestNormalRelease,
  legacyChannelRisk,
};
