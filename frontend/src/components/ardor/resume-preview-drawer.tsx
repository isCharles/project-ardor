"use client";

import { ChevronLeft, ChevronRight, FileDown, GripVertical, X } from "lucide-react";
import { useEffect, useState } from "react";

import { useLocale } from "@/lib/locale";

export function ResumePreviewPanel({ resumeId, filename, width, onWidthChange, onClose }: {
  resumeId: string;
  filename: string;
  width: number;
  onWidthChange: (width: number) => void;
  onClose: () => void;
}) {
  const { t } = useLocale();
  const [page, setPage] = useState(1);
  const [pageCount, setPageCount] = useState(1);
  const [src, setSrc] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    let objectUrl = "";
    fetch(`/api/resumes/${resumeId}/preview/pages/${page}`, { cache: "no-store" })
      .then(async response => {
        if (!response.ok) throw new Error(t("Could not render this resume page", "无法渲染该简历页"));
        const count = Number(response.headers.get("X-Resume-Page-Count") ?? "");
        if (active && Number.isFinite(count) && count > 0) setPageCount(count);
        const blob = await response.blob();
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
        setLoading(false);
      })
      .catch(reason => {
        if (!active) return;
        setError(reason instanceof Error ? reason.message : t("Could not render this resume page", "无法渲染该简历页"));
        setLoading(false);
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [resumeId, page, t]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  function goToPage(next: number) {
    setPage(next);
    setLoading(true);
    setError("");
    setSrc("");
  }

  function startResize(event: React.PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = width;
    const previousSelect = document.body.style.userSelect;
    document.body.style.userSelect = "none";
    function move(pointer: PointerEvent) {
      onWidthChange(Math.min(920, Math.max(360, startWidth + startX - pointer.clientX)));
    }
    function stop() {
      document.body.style.userSelect = previousSelect;
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", stop);
    }
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", stop);
  }

  return <aside role="complementary" aria-label={t("Original resume", "原简历")} className="fixed bottom-0 right-0 top-[46px] z-40 flex flex-col rounded-tl-[1.25rem] border-l border-t border-stone-200/80 bg-white shadow-[-12px_0_40px_rgba(49,39,70,0.08)]" style={{ width }}>
    <div role="separator" aria-orientation="vertical" aria-label={t("Resize preview", "调整预览宽度")} onPointerDown={startResize} className="group absolute inset-y-0 left-0 z-10 w-2 cursor-col-resize">
      <div className="mx-auto flex h-full w-2 items-center justify-center">
        <span className="flex h-10 w-1 items-center justify-center rounded-full bg-stone-200 transition group-hover:bg-violet-300"><GripVertical className="size-3 text-stone-400 group-hover:text-violet-600" /></span>
      </div>
    </div>
    <header className="flex items-center justify-between gap-3 border-b border-stone-100 py-4 pl-5 pr-4">
      <div className="min-w-0">
        <p className="text-xs font-medium uppercase tracking-[0.14em] text-violet-600">{t("Original resume", "原简历")}</p>
        <p className="mt-1 truncate text-sm font-medium text-stone-800">{filename}</p>
      </div>
      <button type="button" onClick={onClose} aria-label={t("Close preview", "关闭预览")} className="grid size-9 shrink-0 place-items-center rounded-full text-stone-500 hover:bg-stone-100"><X className="size-4" /></button>
    </header>
    <div className="flex flex-1 items-start justify-center overflow-auto bg-stone-100/70 p-4">
      {loading && <div className="mt-24 flex flex-col items-center gap-3 text-sm text-stone-400"><span className="size-8 animate-spin rounded-full border-2 border-stone-200 border-t-violet-500" />{t("Rendering page…", "正在渲染页面…")}</div>}
      {!loading && error && <div className="mt-20 max-w-sm text-center">
        <p className="text-sm text-stone-500">{error}</p>
        <a href={`/api/resumes/${resumeId}/file`} download={filename} className="mt-4 inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-4 py-2 text-sm font-medium text-white hover:bg-black"><FileDown className="size-4" />{t("Download original", "下载原文件")}</a>
      </div>}
      {!loading && !error && src && <img src={src} alt={t("Original resume page", "原简历页面")} className="h-auto w-full max-w-[620px] rounded-sm bg-white shadow-[0_10px_40px_rgba(30,25,20,0.12)]" />}
    </div>
    <footer className="flex items-center justify-between gap-3 border-t border-stone-100 px-5 py-3">
      <button type="button" disabled={page <= 1 || loading} onClick={() => goToPage(Math.max(1, page - 1))} className="inline-flex items-center gap-1 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-stone-100 disabled:opacity-30"><ChevronLeft className="size-4" />{t("Previous", "上一页")}</button>
      <span className="text-xs text-stone-400">{page} / {pageCount}</span>
      <button type="button" disabled={page >= pageCount || loading} onClick={() => goToPage(Math.min(pageCount, page + 1))} className="inline-flex items-center gap-1 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-stone-100 disabled:opacity-30">{t("Next", "下一页")}<ChevronRight className="size-4" /></button>
    </footer>
  </aside>;
}
