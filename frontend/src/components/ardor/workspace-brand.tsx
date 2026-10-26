"use client";
import { ChevronDown, Home, ShieldCheck } from "lucide-react";
import Link from "next/link";
import { useState } from "react";
import { useDismiss } from "./overlay";
import { useLocale } from "@/lib/locale";

export function WorkspaceBrand({ admin }: { admin: boolean }) {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const ref = useDismiss<HTMLDivElement>(open, () => setOpen(false));
  const brand = <span className="truncate text-xl font-semibold tracking-tight">Ardor</span>;
  return <div ref={ref} className="relative min-w-0">
    {admin ? <button type="button" aria-label="Ardor" aria-expanded={open} onClick={() => setOpen((value) => !value)} className="flex min-w-0 items-center gap-2 text-sm font-semibold tracking-tight">{brand}<ChevronDown className="size-3 shrink-0 text-stone-400" /></button> : <div className="flex items-center gap-2 text-sm font-semibold tracking-tight">{brand}</div>}
    {admin && open && <div className="absolute left-0 top-11 z-50 w-52 rounded-2xl border border-stone-200 bg-white p-2 shadow-xl">
      <Link href="/app" onClick={() => setOpen(false)} className="flex items-center gap-2 rounded-xl px-3 py-2 text-sm hover:bg-violet-50"><Home className="size-4" />{t("Workspace", "工作台")}</Link>
      <Link href="/app/admin" onClick={() => setOpen(false)} className="flex items-center gap-2 rounded-xl px-3 py-2 text-sm hover:bg-violet-50"><ShieldCheck className="size-4" />{t("Admin dashboard", "管理员面板")}</Link>
    </div>}
  </div>;
}
