"use client";

import { ChevronLeft, ChevronRight } from "lucide-react";
import { useEffect, useState } from "react";

import { useLocale } from "@/lib/locale";

/** Shared page-image preview: the same rasterized view used by resume analysis and LaTeX. */
export function PdfPagePreview({ pageUrl, alt, pageCountHeader = "X-Page-Count", className = "", bodyClassName = "bg-stone-100/70 p-4", footerClassName = "border-t border-stone-100 px-1 pt-2", errorAction }: {
  pageUrl: (page: number) => string;
  alt: string;
  pageCountHeader?: string;
  className?: string;
  bodyClassName?: string;
  footerClassName?: string;
  errorAction?: React.ReactNode;
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
    fetch(pageUrl(page), { cache: "no-store" })
      .then(async response => {
        if (!response.ok) {
          const body = await response.json().catch(() => null) as { message?: string } | null;
          throw new Error(body?.message ?? t("Could not render this page", "无法渲染这一页"));
        }
        const count = Number(response.headers.get(pageCountHeader) ?? "");
        if (active && Number.isFinite(count) && count > 0) setPageCount(count);
        const blob = await response.blob();
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setSrc(objectUrl);
        setLoading(false);
      })
      .catch(reason => {
        if (!active) return;
        setError(reason instanceof Error ? reason.message : t("Could not render this page", "无法渲染这一页"));
        setLoading(false);
      });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [page, pageUrl, pageCountHeader, t]);

  function goToPage(next: number) {
    setPage(next);
    setLoading(true);
    setError("");
    setSrc("");
  }

  return <div className={`flex min-h-0 flex-col ${className}`}>
    <div className={`flex flex-1 items-start justify-center overflow-auto ${bodyClassName}`}>
      {loading && <div className="mt-24 flex flex-col items-center gap-3 text-sm text-stone-400"><span className="size-8 animate-spin rounded-full border-2 border-stone-200 border-t-violet-500" />{t("Rendering page…", "正在渲染页面…")}</div>}
      {!loading && error && <div className="mt-20 max-w-sm text-center">
        <p className="text-sm text-stone-500">{error}</p>
        {errorAction && <div className="mt-4">{errorAction}</div>}
      </div>}
      {!loading && !error && src && <img src={src} alt={alt} className="h-auto w-full max-w-[620px] rounded-sm bg-white shadow-[0_10px_40px_rgba(30,25,20,0.12)]" />}
    </div>
    <footer className={`flex items-center justify-between gap-3 ${footerClassName}`}>
      <button type="button" disabled={page <= 1 || loading} onClick={() => goToPage(Math.max(1, page - 1))} aria-label={t("Previous page", "上一页")} className="inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs text-stone-600 hover:bg-stone-100 disabled:opacity-30"><ChevronLeft className="size-3.5" />{t("Previous", "上一页")}</button>
      <span className="text-[11px] tabular-nums text-stone-400">{page} / {pageCount}</span>
      <button type="button" disabled={page >= pageCount || loading} onClick={() => goToPage(Math.min(pageCount, page + 1))} aria-label={t("Next page", "下一页")} className="inline-flex items-center gap-1 rounded-full px-2 py-1 text-xs text-stone-600 hover:bg-stone-100 disabled:opacity-30">{t("Next", "下一页")}<ChevronRight className="size-3.5" /></button>
    </footer>
  </div>;
}
