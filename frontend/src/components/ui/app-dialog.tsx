"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import { X } from "lucide-react";
import { useLocale } from "@/lib/locale";

/** Native dialog provides focus trapping, Escape and focus restoration in web and Electron. */
export function AppDialog({ title, onClose, children, wide = false }: { title: string; onClose: () => void; children: ReactNode; wide?: boolean }) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const { t } = useLocale();
  useEffect(() => { const dialog = ref.current; dialog?.showModal(); return () => dialog?.close(); }, []);
  return <dialog ref={ref} aria-labelledby={titleId} onCancel={(event) => { event.preventDefault(); onClose(); }}
    onClick={(event) => { const bounds = event.currentTarget.getBoundingClientRect(); if (event.target === event.currentTarget && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) onClose(); }}
    className={`fixed inset-0 m-auto w-[calc(100%-2rem)] ${wide ? "max-w-3xl" : "max-w-md"} max-h-[85dvh] overflow-y-auto rounded-3xl border border-violet-100 bg-white p-6 text-stone-900 shadow-2xl backdrop:bg-stone-900/20 backdrop:backdrop-blur-sm`}>
    <div className="mb-5 flex items-center justify-between gap-4"><h2 id={titleId} className="text-lg font-semibold">{title}</h2>
      <button type="button" aria-label={t("Close", "关闭")} onClick={onClose} className="rounded-xl p-2 text-stone-500 hover:bg-violet-50"><X className="size-4" /></button></div>
    {children}
  </dialog>;
}
