"use client";

import { Download, FileDown, GripVertical, X } from "lucide-react";
import { useCallback, useEffect } from "react";

import { PdfPagePreview } from "@/components/ardor/pdf-page-preview";
import { useLocale } from "@/lib/locale";

export function ResumePreviewPanel({ resumeId, filename, width, onWidthChange, onClose }: {
  resumeId: string;
  filename: string;
  width: number;
  onWidthChange: (width: number) => void;
  onClose: () => void;
}) {
  const { t } = useLocale();
  const pageUrl = useCallback((page: number) => `/api/resumes/${resumeId}/preview/pages/${page}`, [resumeId]);

  useEffect(() => {
    function onKey(event: KeyboardEvent) { if (event.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

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
      <div className="flex shrink-0 items-center gap-1">
        <a href={`/api/resumes/${resumeId}/file`} download={filename} aria-label={t("Download original", "下载原文件")} title={t("Download original", "下载原文件")} className="grid size-9 place-items-center rounded-full text-stone-500 hover:bg-stone-100"><Download className="size-4" /></a>
        <button type="button" onClick={onClose} aria-label={t("Close preview", "关闭预览")} className="grid size-9 place-items-center rounded-full text-stone-500 hover:bg-stone-100"><X className="size-4" /></button>
      </div>
    </header>
    <PdfPagePreview
      key={resumeId}
      pageUrl={pageUrl}
      pageCountHeader="X-Resume-Page-Count"
      alt={t("Original resume page", "原简历页面")}
      className="min-h-0 flex-1"
      footerClassName="border-t border-stone-100 px-5 py-3"
      errorAction={<a href={`/api/resumes/${resumeId}/file`} download={filename} className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-4 py-2 text-sm font-medium text-white hover:bg-black"><FileDown className="size-4" />{t("Download original", "下载原文件")}</a>}
    />
  </aside>;
}
