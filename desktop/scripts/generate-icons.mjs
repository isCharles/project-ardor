import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import sharp from "sharp";
import pngToIco from "png-to-ico";

const asset = (name) => fileURLToPath(new URL(`../assets/${name}`, import.meta.url));
const svg = await readFile(asset("icon.svg"));
const png = await sharp(svg).resize(512, 512).png().toBuffer();
await writeFile(asset("icon.png"), png);
await writeFile(asset("icon.ico"), await pngToIco(png));
