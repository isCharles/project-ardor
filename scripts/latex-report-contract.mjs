import assert from "node:assert/strict";
import { buildLatexReport } from "../frontend/src/lib/latex-report.ts";

const report = buildLatexReport(String.raw`C:\CV_{2026}&50%$#^~`, {
  summary: String.raw`**Evidence** \input{private} & 100%`,
});
assert.ok(report.includes(String.raw`C:\textbackslash{}CV\_\{2026\}\&50\%\$\#\textasciicircum{}\textasciitilde{}`));
assert.ok(report.includes(String.raw`\textbf{Evidence} \textbackslash{}input\{private\} \& 100\%`));
assert.ok(!report.includes(String.raw`\textbackslash\{\}`));
assert.ok(!report.includes(String.raw`\input{private}`));
console.log("LaTeX report escaping contract passed (no compiler invoked).");
