import { execFileSync } from "node:child_process";
import { readFileSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";

const require = createRequire(import.meta.url);
const {
  PRODUCT_REPO,
  normalizeRelease,
  parseLatestYml,
  validateReleaseMetadata,
  latestNormalRelease,
  legacyChannelRisk,
} = require("../desktop/update-channel.cjs");

// Verifies a desktop release the same way the client does, so a release cannot be
// announced until the updater would actually accept it. Fixture JSON inputs keep
// the checks testable without touching GitHub.

function parseArgs(argv) {
  const options = { dist: "desktop/dist", repo: `${PRODUCT_REPO.owner}/${PRODUCT_REPO.repo}` };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const next = () => {
      const value = argv[index + 1];
      if (!value || value.startsWith("--")) throw new Error(`${argument} needs a value`);
      index += 1;
      return value;
    };
    if (argument === "--tag") options.tag = next();
    else if (argument === "--dist") options.dist = next();
    else if (argument === "--repo") options.repo = next();
    else if (argument === "--release-json") options.releaseJson = next();
    else if (argument === "--releases-json") options.releasesJson = next();
    else if (argument === "--require-draft") options.requireDraft = true;
    else if (argument === "--require-published") options.requirePublished = true;
    else if (argument === "--legacy") options.legacy = true;
    else throw new Error(`Unknown argument: ${argument}`);
  }
  if (!options.tag && !options.legacy) throw new Error("--tag is required (or use --legacy to check the old-client channel)");
  return options;
}

function gh(args) {
  return execFileSync("gh", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function readSingleRelease(options) {
  if (options.releaseJson) {
    return normalizeRelease(JSON.parse(readFileSync(options.releaseJson, "utf8")));
  }
  const json = JSON.parse(gh([
    "release", "view", options.tag, "--repo", options.repo,
    "--json", "tagName,isDraft,isPrerelease,publishedAt,assets",
  ]));
  return normalizeRelease({
    tag_name: json.tagName,
    draft: json.isDraft,
    prerelease: json.isPrerelease,
    published_at: json.publishedAt,
    assets: json.assets,
  });
}

function readReleases(options) {
  if (options.releasesJson) return JSON.parse(readFileSync(options.releasesJson, "utf8"));
  return JSON.parse(gh(["api", `repos/${options.repo}/releases?per_page=30`]));
}

function verifyUploadedAssets(options) {
  const release = readSingleRelease(options);
  const metadata = parseLatestYml(readFileSync(path.join(options.dist, "latest.yml"), "utf8"));
  const validation = validateReleaseMetadata(release, metadata);
  if (validation.kind !== "ok") throw new Error(`${options.tag}: ${validation.kind} — ${validation.detail}`);
  let localSize;
  try { localSize = statSync(path.join(options.dist, validation.installer)).size; }
  catch { throw new Error(`${options.tag}: ${validation.installer} is missing from ${options.dist}; build before verifying.`); }
  if (localSize !== Number(metadata.files[0].size)) {
    throw new Error(`${options.tag}: local installer is ${localSize} bytes, latest.yml says ${metadata.files[0].size}.`);
  }
  if (options.requireDraft && !release.draft) {
    throw new Error(`${options.tag} is already published; desktop assets must be uploaded to a draft first.`);
  }
  if (options.requirePublished && release.draft) {
    throw new Error(`${options.tag} is still a draft; publish it before pointing clients at it.`);
  }
  console.log(`${options.tag}: release assets match latest.yml and the local installer (${localSize} bytes, draft=${release.draft}).`);
}

function reportLegacyChannel(options) {
  const releases = readReleases(options);
  const risk = legacyChannelRisk(releases);
  if (!risk.safe) {
    const latest = latestNormalRelease(releases);
    throw new Error([
      `Old clients (0.9.x and earlier) read /releases/latest and need latest.yml there, so they would fail on ${risk.tag}.`,
      risk.detail,
      `Attach a complete desktop payload to ${latest?.tag ?? risk.tag}, mark it as a prerelease, or confirm no old clients remain before publishing it.`,
    ].join(" "));
  }
  console.log(`Old clients keep a usable update path through ${risk.tag ?? "no published release"}: ${risk.detail}`);
}

try {
  const options = parseArgs(process.argv.slice(2));
  if (options.tag) verifyUploadedAssets(options);
  if (options.legacy) reportLegacyChannel(options);
} catch (error) {
  console.error(error.message ?? error);
  process.exit(1);
}
