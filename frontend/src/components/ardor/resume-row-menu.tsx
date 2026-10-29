"use client";

import { MoreHorizontal } from "lucide-react";
import { useEffect, useRef, useState } from "react";

import { useLocale } from "@/lib/locale";

type Props = {
  label: string;
  onSetPrimary: () => void;
  onDelete: () => void;
  disabled?: boolean;
};

export function ResumeRowMenu({ label, onSetPrimary, onDelete, disabled = false }: Props) {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const closeOnOutside = (event: MouseEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("mousedown", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("mousedown", closeOnOutside);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [open]);

  return <div ref={root} className="relative shrink-0">
    <button type="button" aria-label={t(`More actions for ${label}`, `${label} 的更多操作`)} aria-haspopup="menu"
      aria-expanded={open} disabled={disabled} onClick={() => setOpen((value) => !value)}
      className="grid size-8 place-items-center rounded-full text-stone-400 transition hover:bg-stone-100 hover:text-stone-700 disabled:opacity-40">
      <MoreHorizontal className="size-4" />
    </button>
    {open && <div role="menu" className="absolute right-0 top-full z-30 mt-1 w-40 overflow-hidden rounded-2xl border border-stone-200/80 bg-white p-1 shadow-[0_16px_40px_rgba(28,25,23,0.14)]">
      <button type="button" role="menuitem" autoFocus onClick={() => { setOpen(false); onSetPrimary(); }}
        className="flex w-full items-center rounded-xl px-3 py-2 text-left text-sm text-stone-700 outline-none hover:bg-stone-50 focus-visible:bg-stone-50">
        {t("Set as primary", "设为主简历")}
      </button>
      <button type="button" role="menuitem" onClick={() => { setOpen(false); onDelete(); }}
        className="flex w-full items-center rounded-xl px-3 py-2 text-left text-sm text-rose-600 outline-none hover:bg-rose-50 focus-visible:bg-rose-50">
        {t("Delete", "删除")}
      </button>
    </div>}
  </div>;
}
