import assert from "node:assert/strict";
import http from "node:http";
import { mkdir, readdir, writeFile } from "node:fs/promises";
import path from "node:path";

const id = "20000000-0000-4000-8000-000000000001";
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
  const pdf = Buffer.from(await result.arrayBuffer());
  assert.equal(pdf.subarray(0, 4).toString(), "%PDF");
  assert.ok(pdf.subarray(-1024).toString("latin1").trimEnd().endsWith("%%EOF"));
  await mkdir(output, { recursive: true });
  await writeFile(path.join(output, "synthetic-report.pdf"), pdf);
  console.log(JSON.stringify({ status: "passed", bytes: pdf.length, output,
    scope: "real frontend container and compiler; synthetic authenticated backend fixture" }));
}
