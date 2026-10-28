"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { useLocale } from "@/lib/locale";

/** Native dialog provides focus trapping, Escape and focus restoration in web and Electron. */
export function AppDialog({ title, onClose, children, wide = false, size, header, bodyClassName = "" }: {
  title: string; onClose: () => void; children: ReactNode; wide?: boolean;
  size?: "md" | "lg" | "xl"; header?: ReactNode; bodyClassName?: string;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const { t } = useLocale();
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => dialog?.close(); }, []);
  const width = size === "xl" ? "max-w-6xl" : wide ? "max-w-3xl" : "max-w-md";
  const close = <button type="button" aria-label={t("Close", "关闭")} onClick={onClose}
    className="shrink-0 rounded-xl p-2 text-stone-500 hover:bg-stone-100"><X className="size-4" /></button>;
  return <dialog ref={ref} aria-labelledby={titleId} onCancel={(event) => { event.preventDefault(); onClose(); }}
    onClick={(event) => { const bounds = event.currentTarget.getBoundingClientRect(); if (event.target === event.currentTarget && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) onClose(); }}
    className={`fixed inset-0 m-auto w-[calc(100%-2rem)] ${width} rounded-3xl border border-violet-100 bg-white text-stone-900 shadow-2xl backdrop:bg-stone-900/20 backdrop:backdrop-blur-sm ${header ? "flex max-h-[85dvh] flex-col overflow-hidden p-0" : "max-h-[85dvh] overflow-y-auto p-6"}`}>
    {header ? <>
      <div className="flex shrink-0 items-center justify-between gap-4 border-b border-stone-200 bg-[#f7f7f5] px-6 py-3">
        <h2 id={titleId} className="sr-only">{title}</h2>
        {header}
        {close}
      </div>
      <div className={`min-h-0 flex-1 ${bodyClassName}`}>{children}</div>
    </> : <>
      <div className="mb-5 flex items-center justify-between gap-4"><h2 id={titleId} className="text-lg font-semibold">{title}</h2>{close}</div>
      {children}
    </>}
  </dialog>;
}
