import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { compareProductVersions, parseVersion } = require("../desktop/update-channel.cjs");

// The product has one version. The desktop installer may lag behind it: installers
// are only built when the desktop shell itself changes, and a product release that
// only touches the server keeps the previous desktop version. What must never drift
// is the desktop build that *is* produced (checked with --desktop-tag).

const args = process.argv.slice(2);
const desktopTagIndex = args.indexOf("--desktop-tag");
const desktopTag = desktopTagIndex === -1 ? null : args[desktopTagIndex + 1];
if (desktopTagIndex !== -1 && (!desktopTag || desktopTag.startsWith("--"))) {
  throw new Error("--desktop-tag needs the release tag being built, for example v0.10.0");
}

const pom = readFileSync(new URL("../backend/pom.xml", import.meta.url), "utf8");
const frontend = JSON.parse(readFileSync(new URL("../frontend/package.json", import.meta.url), "utf8"));
const lock = JSON.parse(readFileSync(new URL("../frontend/package-lock.json", import.meta.url), "utf8"));
const desktop = JSON.parse(readFileSync(new URL("../desktop/package.json", import.meta.url), "utf8"));
const desktopLock = JSON.parse(readFileSync(new URL("../desktop/package-lock.json", import.meta.url), "utf8"));
const backendVersion = pom.match(/<artifactId>ardor-backend<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
const version = frontend.version;
const dockerfile = readFileSync(new URL("../backend/Dockerfile", import.meta.url), "utf8");
const dockerignore = readFileSync(new URL("../backend/.dockerignore", import.meta.url), "utf8");
const goldenPath = readFileSync(new URL("../.github/workflows/golden-path-acceptance.yml", import.meta.url), "utf8");
const interviewUpgrade = readFileSync(new URL("../backend/src/test/resources/interview-round-upgrade.sh", import.meta.url), "utf8");

if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-rc\.[1-9]\d*)?$/.test(version ?? "")) {
  throw new Error(`Invalid product version: ${version ?? "missing"}`);
}

const productSources = {
  ...Object.fromEntries([...goldenPath.matchAll(/java -jar backend\/target\/ardor-backend-([^\s]+)\.jar/g)].map((match, index) => [`Golden Path launcher ${index + 1}`, match[1]])),
  "backend/pom.xml": backendVersion,
  "frontend/package.json": version,
  "frontend/package-lock.json": lock.version,
  "frontend/package-lock.json root package": lock.packages?.[""]?.version,
  "backend/Dockerfile JAR": dockerfile.match(/COPY target\/ardor-backend-([^\s]+)\.jar \/app\/ardor\.jar/)?.[1],
  "backend/.dockerignore JAR": dockerignore.match(/!target\/ardor-backend-([^\s]+)\.jar/)?.[1],
  "backend/src/test/resources/interview-round-upgrade.sh": interviewUpgrade.match(/ardor-backend-([^\s]+)\.jar/)?.[1],
};

for (const [source, actual] of Object.entries(productSources)) {
  if (actual !== version) throw new Error(`${source} has ${actual ?? "no version"}, expected ${version}`);
}

const desktopVersion = desktop.version;
const desktopSources = {
  "desktop/package.json": desktopVersion,
  "desktop/package-lock.json": desktopLock.version,
  "desktop/package-lock.json root package": desktopLock.packages?.[""]?.version,
};

for (const [source, actual] of Object.entries(desktopSources)) {
  if (actual !== desktopVersion) throw new Error(`${source} has ${actual ?? "no version"}, expected ${desktopVersion}`);
}
if (!parseVersion(desktopVersion)) throw new Error(`Invalid desktop version: ${desktopVersion ?? "missing"}`);
if (compareProductVersions(desktopVersion, version) > 0) {
  throw new Error(`desktop/package.json is ${desktopVersion}, newer than product ${version}; desktop may lag, never lead.`);
}

if (desktopTag) {
  const expected = desktopTag.replace(/^v/, "");
  if (desktopVersion !== expected) {
    throw new Error(`Desktop release ${desktopTag} must build desktop version ${expected}, but desktop/package.json is ${desktopVersion}. Bump it in the release PR before building the installer.`);
  }
}

process.stdout.write(`Ardor ${version}: product versions match (desktop ${desktopVersion}${desktopTag ? `, building ${desktopTag}` : ""})\n`);
