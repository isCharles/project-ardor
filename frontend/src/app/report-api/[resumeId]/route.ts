import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";

import { buildLatexReport, type ReportAnalysis } from "@/lib/latex-report";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

let activeCompilations = 0;
const MAX_COMPILATIONS = 2;

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

function latexEngine(): string {
  if (process.env.ARDOR_LATEX_ENGINE) return process.env.ARDOR_LATEX_ENGINE;
  const local = path.resolve(process.cwd(), "..", "preview", "latex-tools", "tectonic.exe");
  return existsSync(local) ? local : "tectonic";
}

async function run(command: string, args: string[], cwd: string): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const child = spawn(command, args, {
      cwd,
      env: {
        ...process.env,
        TECTONIC_CACHE_DIR: process.env.ARDOR_TECTONIC_CACHE_DIR
          ?? (existsSync(path.resolve(process.cwd(), "..", "preview", "latex-cache"))
            ? path.resolve(process.cwd(), "..", "preview", "latex-cache")
            : path.join(tmpdir(), "ardor-tectonic-cache")),
      },
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    let timedOut = false;
    const append = (chunk: Buffer) => { output = (output + chunk.toString()).slice(-8000); };
    child.stdout.on("data", append);
    child.stderr.on("data", append);
    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGKILL");
    }, 90_000);
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      if (timedOut) reject(new Error("LaTeX 编译超时"));
      else if (code === 0) resolve();
      else reject(new Error(output.trim() || `LaTeX 编译失败（退出码 ${code}）`));
    });
  });
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

  if (activeCompilations >= MAX_COMPILATIONS) {
    return Response.json({ message: "报告导出繁忙，请稍后重试" }, { status: 429, headers: { "Retry-After": "3" } });
  }
  activeCompilations++;
  let workDir: string | undefined;
  try {
    const scratch = process.env.ARDOR_LATEX_WORK_DIR ?? (process.platform === "win32" ? process.cwd() : tmpdir());
    workDir = await mkdtemp(path.join(scratch, ".latex-report-"));
    await writeFile(path.join(workDir, "report.tex"), buildLatexReport(filename, analysis), "utf8");
    const engine = latexEngine();
    const isXeLatex = /xelatex(?:\.exe)?$/i.test(engine);
    let compileError: unknown = null;
    try {
      if (isXeLatex) {
        await run(engine, ["-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", "report.tex"], workDir);
        await run(engine, ["-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", "report.tex"], workDir);
      } else {
        await run(engine, ["-X", "compile", "report.tex"], workDir);
      }
    } catch (error) {
      // Tectonic can emit the PDF and then fail while cleaning its temporary
      // cache on Windows. Accept the finished PDF; never accept a partial one.
      compileError = error;
    }
    let pdf: Buffer;
    try {
      pdf = await readFile(path.join(workDir, "report.pdf"));
    } catch {
      throw compileError ?? new Error("LaTeX 没有生成 report.pdf");
    }
    if (pdf.length < 800 || pdf.subarray(0, 4).toString() !== "%PDF"
        || !pdf.subarray(-1024).toString("latin1").trimEnd().endsWith("%%EOF")) {
      throw compileError ?? new Error("LaTeX 生成的 PDF 不完整");
    }
    if (compileError) {
      // Preserve only the known Windows Tectonic cache-cleanup exception, never
      // accept a timeout or a failed XeLaTeX pass merely because a PDF exists.
      const cleanupOnly = process.platform === "win32" && /tectonic(?:\.exe)?$/i.test(engine)
        && compileError instanceof Error && /Access is denied|os error 5/i.test(compileError.message);
      if (!cleanupOnly) throw compileError;
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
  } catch (error) {
    const message = error instanceof Error ? error.message : "LaTeX 导出失败";
    return Response.json({ message: `LaTeX 导出失败：${message.slice(0, 1200)}` }, { status: 503 });
  } finally {
    try {
      if (workDir) await rm(workDir, { recursive: true, force: true });
    } finally {
      activeCompilations--;
    }
  }
}
