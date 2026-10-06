import { spawnSync } from "node:child_process";
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { cp, mkdir, mkdtemp, readFile, rename, rm, stat } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const desktopDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const { version } = JSON.parse(await readFile(path.join(desktopDir, "package.json"), "utf8"));
const installerName = `Project-Ardor-Setup-${version}-x64.exe`;
const outputDir = path.join(desktopDir, "dist");
const tempRoot = path.resolve(os.tmpdir());

// NSIS executes a temporary installer to generate its uninstaller. Some Windows
// environments block that execution inside a development workspace, so build
// outside the workspace and copy only verified release artifacts back to dist.
const buildDir = await mkdtemp(path.join(tempRoot, "ardor-nsis-"));
let built = false;

try {
  const args = [
    path.join(desktopDir, "node_modules", "electron-builder", "cli.js"),
    "--win", "nsis", "--x64", "--publish", "never",
    `--config.directories.output=${buildDir}`,
  ];
  if (process.argv.includes("--signed")) {
    args.push("--config.forceCodeSigning=true");
  }

  const result = spawnSync(process.execPath, args, {
    cwd: desktopDir,
    env: process.env,
    stdio: "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`Windows installer build failed (exit ${result.status ?? "unknown"}).`);
  }

  const installerPath = path.join(buildDir, installerName);
  const metadata = await readFile(path.join(buildDir, "latest.yml"), "utf8");
  const expectedName = metadata.match(/^path: (.+)$/m)?.[1]?.trim();
  const expectedHash = metadata.match(/^sha512: (.+)$/m)?.[1]?.trim();
  if (expectedName !== installerName || !expectedHash) {
    throw new Error("The update metadata does not match the built installer.");
  }
  if ((await stat(installerPath)).size < 1_000_000) {
    throw new Error("The installer is unexpectedly small; refusing to publish a temporary NSIS stub.");
  }
  await stat(path.join(buildDir, `${installerName}.blockmap`));
  await stat(path.join(buildDir, "win-unpacked", "Project Ardor.exe"));

  const hash = createHash("sha512");
  for await (const chunk of createReadStream(installerPath)) hash.update(chunk);
  if (hash.digest("base64") !== expectedHash) {
    throw new Error("The installer SHA-512 does not match latest.yml.");
  }

  await mkdir(outputDir, { recursive: true });
  const unpackedDir = path.join(outputDir, "win-unpacked");
  const suffix = randomUUID();
  const stagingDir = path.join(outputDir, `.win-unpacked-next-${suffix}`);
  const previousDir = path.join(outputDir, `.win-unpacked-previous-${suffix}`);
  for (const directory of [unpackedDir, stagingDir, previousDir]) {
    if (path.dirname(path.resolve(directory)) !== path.resolve(outputDir)) {
      throw new Error(`Refusing to move an unexpected build directory: ${directory}`);
    }
  }
  await cp(path.join(buildDir, "win-unpacked"), stagingDir, { recursive: true });
  let hadPrevious = false;
  try {
    await stat(unpackedDir);
    await rename(unpackedDir, previousDir);
    hadPrevious = true;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  try {
    await rename(stagingDir, unpackedDir);
  } catch (error) {
    if (hadPrevious) await rename(previousDir, unpackedDir);
    throw error;
  }
  if (hadPrevious) await rm(previousDir, { recursive: true });
  for (const name of [installerName, `${installerName}.blockmap`, "latest.yml"]) {
    await cp(path.join(buildDir, name), path.join(outputDir, name), { force: true });
  }
  built = true;
  console.log(`Verified Windows installer: ${path.join(outputDir, installerName)}`);
} finally {
  if (built) {
    if (path.dirname(path.resolve(buildDir)) !== tempRoot || !path.basename(buildDir).startsWith("ardor-nsis-")) {
      throw new Error(`Refusing to clean an unexpected build directory: ${buildDir}`);
    }
    await rm(buildDir, { recursive: true, force: true });
  } else {
    console.error(`Build output retained for diagnosis: ${buildDir}`);
  }
}
