import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import sharp from "sharp";
import pngToIco from "png-to-ico";

// One generator for every raster brand asset. Sources live in brand/ and keep
// their supplied vectors untouched; the favicon and small app icon are optical
// cuts of those sources with the same paths.
const repo = fileURLToPath(new URL("../../", import.meta.url));
const brand = (name) => fileURLToPath(new URL(`../../brand/${name}`, import.meta.url));
const asset = (name) => fileURLToPath(new URL(`../assets/${name}`, import.meta.url));
const frontendApp = (name) => fileURLToPath(new URL(`../../frontend/src/app/${name}`, import.meta.url));
const check = process.argv.includes("--check");

const [mark, faviconMark, appLight, appDark, appSmall] = await Promise.all([
  readFile(brand("ardor-mark.svg")),
  readFile(brand("ardor-mark-favicon.svg")),
  readFile(brand("ardor-app-light.svg")),
  readFile(brand("ardor-app-dark.svg")),
  readFile(brand("ardor-app-small.svg")),
]);

async function raster(source, size) {
  return sharp(source, { density: 144 })
    .resize(size, size, { fit: "contain", background: { r: 0, g: 0, b: 0, alpha: 0 } })
    .png()
    .toBuffer();
}

function icoSizes(buffer) {
  const count = buffer.readUInt16LE(4);
  return Array.from({ length: count }, (_, index) => {
    const width = buffer.readUInt8(6 + index * 16);
    return width === 0 ? 256 : width;
  });
}

function assertSizes(buffer, expected, label) {
  const actual = icoSizes(buffer);
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${label} contains ${actual.join("/")} instead of ${expected.join("/")}`);
  }
}

const webFaviconSizes = [16, 24, 32, 48];
const desktopSizes = [16, 24, 32, 48, 64, 128, 256];

const webFavicon = await pngToIco(await Promise.all(webFaviconSizes.map((size) => raster(faviconMark, size))));
assertSizes(webFavicon, webFaviconSizes, "favicon.ico");

const desktopIco = await pngToIco(await Promise.all(desktopSizes.map((size) =>
  raster(size <= 32 ? appSmall : appLight, size))));
assertSizes(desktopIco, desktopSizes, "icon.ico");

const desktopPng = await raster(appLight, 512);
const appleIcon = await sharp(appLight, { density: 144 })
  .resize(180, 180, { fit: "contain" })
  .flatten({ background: "#ffffff" })
  .png()
  .toBuffer();

const outputs = new Map([
  [asset("icon.svg"), Buffer.from(appLight)],
  [asset("icon.png"), desktopPng],
  [asset("icon.ico"), desktopIco],
  [frontendApp("favicon.ico"), webFavicon],
  [frontendApp("icon.svg"), Buffer.from(faviconMark)],
  [frontendApp("apple-icon.png"), appleIcon],
]);

// icon.png and icon.ico are build outputs (gitignored); check only the files
// that ship in git so a clean checkout can verify the brand without stale rasters.
const buildOnly = new Set([asset("icon.png"), asset("icon.ico")]);
for (const [path, content] of outputs) {
  if (!check) {
    await mkdir(dirname(path), { recursive: true });
    continue;
  }
  if (buildOnly.has(path)) continue;
  let existing;
  try { existing = await readFile(path); } catch { existing = null; }
  if (!existing || !existing.equals(content)) {
    throw new Error(`${path} is out of date; run "npm run icons" in desktop/`);
  }
}

if (check) {
  console.log(`Brand icons are current (favicon ${webFaviconSizes.join("/")}, desktop ${desktopSizes.join("/")}).`);
  process.exit(0);
}

for (const [path, content] of outputs) await writeFile(path, content);

const lightPreview = await contactSheet({
  background: "#f7f4ef",
  ink: "#22252b",
  muted: "#736c64",
  rule: "#ddd6cc",
  title: "Ardor · light surfaces",
  subtitle: "Generated from brand/ardor-app-light.svg, brand/ardor-mark-favicon.svg and brand/ardor-app-small.svg.",
  light: true,
});
const darkPreview = await contactSheet({
  background: "#0b1220",
  ink: "#f2f6ff",
  muted: "#93a0b6",
  rule: "#22304a",
  title: "Ardor · dark surfaces",
  subtitle: "The dark app tile for dark surfaces; the light tile is shown on a taskbar strip.",
  light: false,
});
await writeFile(brand("preview-light.png"), lightPreview);
await writeFile(brand("preview-dark.png"), darkPreview);

console.log(`favicon.ico ${webFaviconSizes.join("/")}`);
console.log(`icon.ico ${desktopSizes.join("/")}`);
console.log("icon.png 512, apple-icon.png 180, preview-light.png, preview-dark.png");

async function contactSheet({ background, ink, muted, rule, title, subtitle, light }) {
  const width = 1280;
  const height = 800;
  const parts = [`<rect width="${width}" height="${height}" fill="${background}"/>`];
  const text = (x, y, size, fill, content, weight = 400, anchor = "start") =>
    parts.push(`<text x="${x}" y="${y}" font-family="Segoe UI, Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="${fill}" text-anchor="${anchor}">${escapeXml(content)}</text>`);
  const line = (x1, y1, x2, y2) => parts.push(`<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${rule}" stroke-width="1"/>`);
  const image = async (buffer, x, y, size) => parts.push(`<image x="${x}" y="${y}" width="${size}" height="${size}" xlink:href="data:image/png;base64,${buffer.toString("base64")}"/>`);

  text(56, 64, 28, ink, title, 650);
  text(56, 94, 13, muted, subtitle);
  line(56, 118, width - 56, 118);

  const heroApp = light ? appLight : appDark;
  await image(await raster(heroApp, 128), 56, 148, 128);
  text(56, 306, 13, muted, "App icon");
  await image(await raster(faviconMark, 128), 232, 148, 128);
  text(232, 306, 13, muted, "Mark (16–48 px optical cut)");
  await image(await raster(faviconMark, 40), 420, 178, 40);
  text(468, 213, 42, ink, "Ardor", 650);
  text(420, 306, 13, muted, "Wordmark");

  text(56, 366, 13, ink, light ? "App icon — actual size (16–32 use the small cut)" : "Dark app tile — actual size");
  const appSizes = [16, 24, 32, 48, 64, 128];
  for (const [index, size] of appSizes.entries()) {
    const x = 56 + index * 88;
    const source = light && size <= 32 ? appSmall : heroApp;
    await image(await raster(source, size), x, 520 - size, size);
    text(x + size / 2, 544, 11, muted, String(size), 400, "middle");
  }

  text(680, 366, 13, ink, "16 · 24 · 32 · 48 at 4×");
  let zoomX = 680;
  for (const size of [16, 24, 32, 48]) {
    const zoom = size * 4;
    await image(await raster(faviconMark, zoom), zoomX, 520 - zoom, zoom);
    text(zoomX + zoom / 2, 544, 11, muted, `${size} ×4`, 400, "middle");
    zoomX += zoom + 34;
  }

  text(56, 618, 13, ink, "Mark — actual size");
  const markSizes = [16, 24, 32, 48, 64, 96];
  for (const [index, size] of markSizes.entries()) {
    const x = 56 + index * 88;
    await image(await raster(faviconMark, size), x, 712 - size, size);
    text(x + size / 2, 736, 11, muted, String(size), 400, "middle");
  }

  if (!light) {
    text(680, 618, 13, ink, "Light app tile on a dark taskbar (the shipped Windows cut)");
    parts.push(`<rect x="680" y="636" width="544" height="72" rx="16" fill="#182235"/>`);
    for (const [index, size] of [16, 24, 32, 48].entries()) {
      await image(await raster(size <= 32 ? appSmall : appLight, size), 706 + index * 84, 672 - size / 2, size);
    }
  }

  text(56, 776, 11, muted, "Same outlines as the supplied vectors. Only the small favicon/app cuts tighten the viewBox and omit glow layers; no petal path changed.");
  return sharp(Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${width}" height="${height}">${parts.join("")}</svg>`)).png().toBuffer();
}

function escapeXml(value) {
  return value.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}
