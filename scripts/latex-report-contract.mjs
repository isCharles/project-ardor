import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { buildLatexReport } from "../frontend/src/lib/latex-report.ts";
import { isolatesUserDocuments } from "../frontend/src/lib/latex-compile.ts";

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

// User-authored workspace sources may only run on an engine that contains file I/O.
assert.equal(isolatesUserDocuments("tectonic"), false,
  "Tectonic cannot contain user-authored documents.");
assert.equal(isolatesUserDocuments("C:\\tools\\tectonic.exe"), false);
assert.equal(isolatesUserDocuments("/usr/bin/xelatex"), true);
assert.equal(isolatesUserDocuments("C:\\texlive\\bin\\xelatex.exe"), true);
const workspaceRoute = readFileSync(new URL("../frontend/src/app/latex-api/[projectId]/route.ts", import.meta.url), "utf8");
assert.match(workspaceRoute, /compileLatex\([\s\S]*?restrictReads:\s*true/,
  "The workspace route must keep asking the compiler for restricted reads.");
console.log("LaTeX report escaping, workspace engine isolation and runtime engine contract passed (no compiler invoked).");
