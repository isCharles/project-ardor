import { compileLatex, LatexBusyError } from "@/lib/latex-compile";
import { buildLatexReport, type ReportAnalysis } from "@/lib/latex-report";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type BackendAnalysis = {
  analysis?: ReportAnalysis;
  originalFilename?: string;
};

type BackendResume = {
  id?: string;
  originalFilename?: string;
};

function backendUrl(): string {
  return (process.env.ARDOR_BACKEND_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
}

export async function GET(request: Request, { params }: { params: Promise<{ resumeId: string }> }) {
  const { resumeId } = await params;
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(resumeId)) {
    return Response.json({ message: "简历 ID 格式不正确" }, { status: 400 });
  }
  const analysisId = new URL(request.url).searchParams.get("analysisId");
  if (analysisId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(analysisId)) {
    return Response.json({ message: "分析 ID 格式不正确" }, { status: 400 });
  }
  const cookie = request.headers.get("cookie") ?? "";
  const headers = { Accept: "application/json", Cookie: cookie };
  const base = backendUrl();

  const [analysisResponse, listResponse] = await Promise.all([
    fetch(`${base}/api/resumes/${resumeId}/analysis${analysisId ? `?analysisId=${encodeURIComponent(analysisId)}` : ""}`, { headers, cache: "no-store" }),
    fetch(`${base}/api/resumes`, { headers, cache: "no-store" }),
  ]);
  if (!analysisResponse.ok) {
    return Response.json({ message: analysisResponse.status === 404 ? "该简历尚未生成分析报告" : "无法读取简历报告" }, { status: analysisResponse.status });
  }

  const analysisBody = await analysisResponse.json() as BackendAnalysis;
  const analysis = analysisBody.analysis;
  if (!analysis) return Response.json({ message: "分析报告内容为空" }, { status: 422 });

  let filename = analysisBody.originalFilename ?? "resume";
  if (listResponse.ok) {
    const resumes = await listResponse.json() as BackendResume[];
    filename = resumes.find((item) => item.id === resumeId)?.originalFilename ?? filename;
  }

  let pdf: Buffer;
  try {
    pdf = await compileLatex({ "report.tex": buildLatexReport(filename, analysis) }, "report.tex");
  } catch (error) {
    if (error instanceof LatexBusyError) {
      return Response.json({ message: "报告导出繁忙，请稍后重试" }, { status: 429, headers: { "Retry-After": "3" } });
    }
    const message = error instanceof Error ? error.message : "LaTeX 导出失败";
    return Response.json({ message: `LaTeX 导出失败：${message.slice(0, 1200)}` }, { status: 503 });
  }
  const safeName = filename.replace(/\.pdf$/i, "").replace(/[\\/:*?"<>|]+/g, "-");
  return new Response(new Uint8Array(pdf), {
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(`Ardor-${safeName}-report.pdf`)}`,
      "Cache-Control": "no-store",
      "X-Report-Format": "latex",
    },
  });
}
