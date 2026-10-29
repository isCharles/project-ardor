import assert from "node:assert/strict";
import http from "node:http";
import { mkdir, readdir, writeFile } from "node:fs/promises";
import path from "node:path";

const id = "20000000-0000-4000-8000-000000000001";
const projectId = "20000000-0000-4000-8000-000000000002";
const escapeProjectId = "20000000-0000-4000-8000-000000000003";
const workspaceMain = String.raw`\documentclass[11pt]{article}
\usepackage{fontspec}
\usepackage{xeCJK}
\IfFontExistsTF{SimSun}{\setCJKmainfont[AutoFakeBold=2.5]{SimSun}}{\setCJKmainfont[AutoFakeBold=2.5]{Noto Serif CJK SC}}
\begin{document}
\section{合成 LaTeX 工作区}
这段内容仅用于验收编译与预览路由，不包含真实个人信息。
\end{document}
`;
const escapeMain = String.raw`\documentclass{article}
\begin{document}
\input{/etc/hostname}
\end{document}
`;
const fixture = {
  overallScore: 82,
  summary: "**合成验收报告**：保留中文、反斜杠 C:\\CV 和特殊字符 50% & _。",
  background: "这是一份合成测试数据，不含真实用户简历。",
  dimensionGrades: { technicalDepth: "B", outcomes: "C", ownership: "B", clarity: "A", roleFit: "B", background: "B" },
  dimensionBenchmarks: { technicalDepth: "B", outcomes: "B", ownership: "B", clarity: "B", roleFit: "B", background: "B" },
  scoringContext: { targetRole: "后端开发工程师" },
  strengths: ["结论建立在可核验的测试数据上。"],
  issues: Array.from({ length: 9 }, (_, index) => ({ severity: index < 3 ? "A" : index < 6 ? "B" : "C",
    category: `合成问题 ${index + 1}`, problem: "项目结果需要更明确的证据。",
    why: "帮助读者理解结果与贡献，不编造业务指标。", benchmark: "描述目标、行动、结果与验证边界。",
    evidence: "当前测试覆盖 API 和持久化结果。", action: "补充真实验证步骤与适用条件。",
    example: "通过合成场景验证并发行为；未声称真实生产负载。" })),
};

if (process.argv[2] === "fixture") {
  const server = http.createServer((request, response) => {
    response.setHeader("Content-Type", "application/json");
    if (!request.headers.cookie?.includes("report_fixture=")) {
      response.writeHead(401).end(JSON.stringify({ message: "Unauthenticated fixture" })); return;
    }
    const owned = request.headers.cookie.includes("report_fixture=owner");
    if (request.url === "/api/resumes") {
      response.end(JSON.stringify(owned ? [{ id, originalFilename: "合成报告_CV.pdf" }] : [])); return;
    }
    if (request.url === `/api/resumes/${id}/analysis` && owned) {
      response.end(JSON.stringify({ analysis: fixture })); return;
    }
    if (request.url === `/api/latex/projects/${projectId}` && owned) {
      response.end(JSON.stringify({ projectId, title: "合成 LaTeX 工作区", mainFile: "main.tex", revisionNo: 3, files: { "main.tex": workspaceMain } })); return;
    }
    if (request.url === `/api/latex/projects/${escapeProjectId}` && owned) {
      response.end(JSON.stringify({ projectId: escapeProjectId, title: "越界读取用例", mainFile: "main.tex", revisionNo: 1, files: { "main.tex": escapeMain } })); return;
    }
    response.writeHead(404).end(JSON.stringify({ message: "Not found" }));
  });
  server.listen(8080, "0.0.0.0", () => console.log("Synthetic report backend ready"));
} else {
  const base = process.argv[2];
  const output = process.argv[3];
  assert.ok(base && output, "usage: node report-export-smoke.mjs fixture | <frontend-url> <output-directory>");
  const icons = (await readdir(new URL("../frontend/public/model-icons/", import.meta.url))).filter(name => name.endsWith(".svg"));
  assert.ok(icons.length > 0, "Model icon fixtures must exist");
  for (const icon of icons) {
    const response = await fetch(`${base}/model-icons/${icon}`, { signal: AbortSignal.timeout(10_000) });
    assert.equal(response.status, 200, `Runtime model icon missing: ${icon}`);
    assert.match(response.headers.get("content-type"), /image\/svg\+xml/);
    assert.match(await response.text(), /<svg\b/);
  }
  const request = (resource, cookie) => fetch(`${base}/report-api/${resource}`, {
    headers: cookie ? { Cookie: `report_fixture=${cookie}` } : {}, signal: AbortSignal.timeout(100_000),
  });
  assert.equal((await request("invalid-id", "owner")).status, 400);
  assert.equal((await request(id)).status, 401);
  assert.equal((await request(id, "other")).status, 404);
  const result = await request(id, "owner");
  assert.equal(result.status, 200, result.status === 200 ? "" : await result.text());
  assert.match(result.headers.get("content-type"), /application\/pdf/);
  assert.equal(result.headers.get("cache-control"), "no-store");
  assert.equal(result.headers.get("x-report-format"), "latex");
  const preview = (resource) => fetch(`${base}/latex-api/${resource}`, {
    headers: { Cookie: "report_fixture=owner" }, signal: AbortSignal.timeout(100_000),
  });
  assert.equal((await fetch(`${base}/latex-api/invalid-id`)).status, 400);
  assert.equal((await fetch(`${base}/latex-api/${projectId}`)).status, 401);
  const previewResult = await preview(projectId);
  assert.equal(previewResult.status, 200, previewResult.status === 200 ? "" : await previewResult.text());
  assert.match(previewResult.headers.get("content-type"), /application\/pdf/);
  assert.equal(previewResult.headers.get("cache-control"), "no-store");
  assert.match(previewResult.headers.get("content-disposition"), /^inline;/);
  const workspacePdf = Buffer.from(await previewResult.arrayBuffer());
  assert.equal(workspacePdf.subarray(0, 4).toString(), "%PDF");
  assert.ok(workspacePdf.subarray(-1024).toString("latin1").trimEnd().endsWith("%%EOF"));
  // The workspace document must not be able to read files outside its build
  // directory, so an absolute \input is rejected instead of compiled.
  assert.equal((await preview(escapeProjectId)).status, 503);
  const pdf = Buffer.from(await result.arrayBuffer());
  assert.equal(pdf.subarray(0, 4).toString(), "%PDF");
  assert.ok(pdf.subarray(-1024).toString("latin1").trimEnd().endsWith("%%EOF"));
  await mkdir(output, { recursive: true });
  await writeFile(path.join(output, "synthetic-report.pdf"), pdf);
  await writeFile(path.join(output, "synthetic-latex-workspace.pdf"), workspacePdf);
  console.log(JSON.stringify({ status: "passed", bytes: pdf.length, output,
    scope: "real frontend container and compiler; synthetic authenticated backend fixture, report export and LaTeX workspace preview" }));
}
