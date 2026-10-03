import { readFileSync } from "node:fs";

const pom = readFileSync(new URL("../backend/pom.xml", import.meta.url), "utf8");
const frontend = JSON.parse(readFileSync(new URL("../frontend/package.json", import.meta.url), "utf8"));
const lock = JSON.parse(readFileSync(new URL("../frontend/package-lock.json", import.meta.url), "utf8"));
const desktop = JSON.parse(readFileSync(new URL("../desktop/package.json", import.meta.url), "utf8"));
const desktopLock = JSON.parse(readFileSync(new URL("../desktop/package-lock.json", import.meta.url), "utf8"));
const backendVersion = pom.match(/<artifactId>ardor-backend<\/artifactId>\s*<version>([^<]+)<\/version>/)?.[1];
const version = frontend.version;
const dockerfile = readFileSync(new URL("../backend/Dockerfile", import.meta.url), "utf8");
const dockerignore = readFileSync(new URL("../backend/.dockerignore", import.meta.url), "utf8");

if (!/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-rc\.[1-9]\d*)?$/.test(version ?? "")) {
  throw new Error(`Invalid product version: ${version ?? "missing"}`);
}

const sources = {
  "backend/pom.xml": backendVersion,
  "frontend/package.json": version,
  "frontend/package-lock.json": lock.version,
  "frontend/package-lock.json root package": lock.packages?.[""]?.version,
  "desktop/package.json": desktop.version,
  "desktop/package-lock.json": desktopLock.version,
  "desktop/package-lock.json root package": desktopLock.packages?.[""]?.version,
  "backend/Dockerfile JAR": dockerfile.match(/COPY target\/ardor-backend-([^\s]+)\.jar \/app\/ardor\.jar/)?.[1],
  "backend/.dockerignore JAR": dockerignore.match(/!target\/ardor-backend-([^\s]+)\.jar/)?.[1],
};

for (const [source, actual] of Object.entries(sources)) {
  if (actual !== version) throw new Error(`${source} has ${actual ?? "no version"}, expected ${version}`);
}

process.stdout.write(`Ardor ${version}: all build versions match\n`);
