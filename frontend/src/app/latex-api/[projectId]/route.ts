import { compileLatex, LatexBusyError } from "@/lib/latex-compile";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type BackendSnapshot = {
  title?: string;
  mainFile?: string;
  revisionNo?: number;
  files?: Record<string, string>;
};

function backendUrl(): string {
  return (process.env.ARDOR_BACKEND_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
}

export async function GET(request: Request, { params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(projectId)) {
    return Response.json({ message: "工作区 ID 格式不正确" }, { status: 400 });
  }
  const revision = new URL(request.url).searchParams.get("revision");
  if (revision && !/^\d{1,6}$/.test(revision)) {
    return Response.json({ message: "修订号格式不正确" }, { status: 400 });
  }

  const cookie = request.headers.get("cookie") ?? "";
  const response = await fetch(
    `${backendUrl()}/api/latex/projects/${projectId}${revision ? `?revision=${revision}` : ""}`,
    { headers: { Accept: "application/json", Cookie: cookie }, cache: "no-store" },
  );
  if (!response.ok) {
    return Response.json(
      { message: response.status === 404 ? "找不到该 LaTeX 工作区" : "无法读取 LaTeX 工作区" },
      { status: response.status },
    );
  }
  const snapshot = await response.json() as BackendSnapshot;
  const files = snapshot.files;
  const mainFile = snapshot.mainFile ?? "main.tex";
  if (!files || typeof files !== "object" || typeof files[mainFile] !== "string") {
    return Response.json({ message: "LaTeX 工作区内容为空" }, { status: 422 });
  }

  let pdf: Buffer;
  try {
    // Workspace sources are user-authored, so this path also forbids absolute
    // and parent-relative file access from inside the document.
    pdf = await compileLatex(files, mainFile, { restrictReads: true });
  } catch (error) {
    if (error instanceof LatexBusyError) {
      return Response.json({ message: "预览编译繁忙，请稍后重试" }, { status: 429, headers: { "Retry-After": "3" } });
    }
    const message = error instanceof Error ? error.message : "LaTeX 编译失败";
    return Response.json({ message: `LaTeX 编译失败：${message.slice(0, 1600)}` }, { status: 503 });
  }
  const name = `${(snapshot.title ?? "resume").slice(0, 40).replace(/[\\/:*?"<>|]+/g, "-")}-r${snapshot.revisionNo ?? 0}.pdf`;
  return new Response(new Uint8Array(pdf), {
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `inline; filename*=UTF-8''${encodeURIComponent(name)}`,
      "Cache-Control": "no-store",
      "X-Report-Format": "latex",
    },
  });
}
