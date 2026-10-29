import { compileLatex, LatexBusyError, LatexPreviewBusyError, LatexPreviewUnavailableError, renderPdfPages } from "@/lib/latex-compile";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type BackendSnapshot = {
  title?: string;
  mainFile?: string;
  revisionNo?: number;
  files?: Record<string, string>;
};

type CachedPreview = { pdf: Buffer; pages?: Buffer[]; at: number };

const previewCache = new Map<string, CachedPreview>();
const renderJobs = new Map<string, Promise<Buffer[]>>();
const MAX_CACHE_ENTRIES = 8;
const CACHE_TTL_MS = 10 * 60 * 1000;

function backendUrl(): string {
  return (process.env.ARDOR_BACKEND_URL ?? "http://127.0.0.1:8080").replace(/\/$/, "");
}

function cacheGet(key: string): CachedPreview | null {
  const hit = previewCache.get(key);
  if (!hit) return null;
  if (Date.now() - hit.at > CACHE_TTL_MS) {
    previewCache.delete(key);
    return null;
  }
  return hit;
}

function cachePut(key: string, entry: CachedPreview) {
  previewCache.delete(key);
  previewCache.set(key, entry);
  while (previewCache.size > MAX_CACHE_ENTRIES) {
    const oldest = previewCache.keys().next().value;
    if (oldest === undefined) break;
    previewCache.delete(oldest);
  }
}

async function loadSnapshot(request: Request, projectId: string, revision: string | null) {
  const cookie = request.headers.get("cookie") ?? "";
  const response = await fetch(
    `${backendUrl()}/api/latex/projects/${projectId}${revision ? `?revision=${revision}` : ""}`,
    { headers: { Accept: "application/json", Cookie: cookie }, cache: "no-store" },
  );
  if (!response.ok) {
    return {
      response: Response.json(
        { message: response.status === 404 ? "找不到这份简历" : "无法读取这份简历" },
        { status: response.status },
      ),
    };
  }
  const snapshot = await response.json() as BackendSnapshot;
  const files = snapshot.files;
  const mainFile = snapshot.mainFile ?? "main.tex";
  if (!files || typeof files !== "object" || typeof files[mainFile] !== "string") {
    return { response: Response.json({ message: "简历内容为空" }, { status: 422 }) };
  }
  return { snapshot: { ...snapshot, mainFile } };
}

function pdfResponse(pdf: Buffer, snapshot: BackendSnapshot) {
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

export async function GET(request: Request, { params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params;
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(projectId)) {
    return Response.json({ message: "简历 ID 格式不正确" }, { status: 400 });
  }
  const search = new URL(request.url).searchParams;
  const revision = search.get("revision");
  if (revision && !/^\d{1,6}$/.test(revision)) {
    return Response.json({ message: "修订号格式不正确" }, { status: 400 });
  }
  const pageParam = search.get("page");
  if (pageParam !== null && !/^[1-9]\d{0,3}$/.test(pageParam)) {
    return Response.json({ message: "页码格式不正确" }, { status: 400 });
  }
  const refresh = search.get("refresh");
  if (refresh !== null && !/^\d{1,6}$/.test(refresh)) {
    return Response.json({ message: "刷新标记格式不正确" }, { status: 400 });
  }

  const loaded = await loadSnapshot(request, projectId, revision);
  if ("response" in loaded) return loaded.response;
  const snapshot = loaded.snapshot;
  const key = `${projectId}:${snapshot.revisionNo ?? revision ?? "current"}`;
  if (refresh !== null) {
    previewCache.delete(key);
    renderJobs.delete(key);
  }

  let entry = cacheGet(key);
  if (!entry) {
    let pdf: Buffer;
    try {
      // Workspace sources are user-authored, so this path also forbids absolute
      // and parent-relative file access from inside the document.
      pdf = await compileLatex(snapshot.files!, snapshot.mainFile!, { restrictReads: true });
    } catch (error) {
      if (error instanceof LatexBusyError) {
        return Response.json({ message: "预览编译繁忙，请稍后重试" }, { status: 429, headers: { "Retry-After": "3" } });
      }
      const message = error instanceof Error ? error.message : "LaTeX 编译失败";
      return Response.json({ message: `LaTeX 编译失败：${message.slice(0, 1600)}` }, { status: 503 });
    }
    entry = { pdf, at: Date.now() };
    cachePut(key, entry);
  }

  if (pageParam === null) return pdfResponse(entry.pdf, snapshot);

  if (!entry.pages) {
    let job = renderJobs.get(key);
    if (!job) {
      // One render per cached PDF: a burst of page requests must not launch a
      // Ghostscript process each, and a stale render must not replace a newer entry.
      const pending = renderPdfPages(entry.pdf).then((pages) => {
        entry.pages = pages;
        entry.at = Date.now();
        if (cacheGet(key) === entry) cachePut(key, entry);
        return pages;
      });
      job = pending.finally(() => { renderJobs.delete(key); });
      renderJobs.set(key, job);
    }
    try {
      entry.pages = await job;
    } catch (error) {
      if (error instanceof LatexPreviewBusyError) {
        return Response.json({ message: error.message }, { status: 429, headers: { "Retry-After": "3" } });
      }
      const message = error instanceof LatexPreviewUnavailableError
        ? error.message
        : error instanceof Error ? `预览渲染失败：${error.message.slice(0, 600)}` : "预览渲染失败";
      return Response.json({ message }, { status: 503 });
    }
  }

  const page = Number(pageParam);
  if (page > entry.pages.length) {
    return Response.json({ message: "页码超出范围" }, { status: 404 });
  }
  return new Response(new Uint8Array(entry.pages[page - 1]), {
    headers: {
      "Content-Type": "image/png",
      "Cache-Control": "private, no-store",
      "X-Page-Count": String(entry.pages.length),
    },
  });
}
