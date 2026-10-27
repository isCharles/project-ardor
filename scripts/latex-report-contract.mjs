import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { buildLatexReport } from "../frontend/src/lib/latex-report.ts";

const report = buildLatexReport(String.raw`C:\CV_{2026}&50%$#^~`, {
  summary: String.raw`**Evidence** \input{private} & 100%`,
});
assert.ok(report.includes(String.raw`C:\textbackslash{}CV\_\{2026\}\&50\%\$\#\textasciicircum{}\textasciitilde{}`));
assert.ok(report.includes(String.raw`\textbf{Evidence} \textbackslash{}input\{private\} \& 100\%`));
assert.ok(!report.includes(String.raw`\textbackslash\{\}`));
assert.ok(!report.includes(String.raw`\input{private}`));

const dockerfile = readFileSync(new URL("../frontend/Dockerfile", import.meta.url), "utf8");
const runtimeStage = dockerfile.slice(dockerfile.indexOf(" AS runtime"));
assert.match(runtimeStage, /apk add --no-cache .*\btexlive-xetex\b/,
  "The runtime image must install a TeX engine; without one the export route answers 503.");
assert.match(runtimeStage, /\bfont-noto-cjk\b/,
  "The runtime image must ship CJK fonts; without them the exported report loses Chinese text.");
assert.match(runtimeStage, /ENV ARDOR_LATEX_ENGINE=xelatex/,
  "The route only reaches xelatex when ARDOR_LATEX_ENGINE names it.");
console.log("LaTeX report escaping and runtime engine contract passed (no compiler invoked).");
