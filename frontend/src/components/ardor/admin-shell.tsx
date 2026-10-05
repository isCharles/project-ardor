"use client";

import { ArrowLeft, Flame, Gauge, Server, Users } from "lucide-react";
import Link from "next/link";
import type { ReactNode } from "react";

import { useLocale } from "@/lib/locale";

export function AdminShell({ children }: { children: ReactNode }) {
  const { t } = useLocale();
  const adminItems = [
    { href: "#overview", label: t("Overview", "系统概览"), icon: Gauge },
    { href: "#api-configs", label: t("System APIs", "系统 API"), icon: Server },
    { href: "#quotas", label: t("Quotas", "额度"), icon: Gauge },
    { href: "#users", label: t("User management", "用户管理"), icon: Users },
  ] as const;
  return (
    <div className="min-h-screen bg-[#f8f5f1] text-[#24211f] md:grid md:grid-cols-[15.5rem_minmax(0,1fr)]">
      <aside className="border-b border-black/[0.07] bg-[radial-gradient(circle_at_0_0,rgba(255,108,69,0.14),transparent_45%),#fbf9f5] p-4 md:sticky md:top-0 md:h-screen md:border-b-0 md:border-r">
        <Link href="/app/admin" className="flex items-center gap-3 px-2 py-1">
          <span className="grid size-9 place-items-center rounded-xl bg-gradient-to-br from-[#ff6a45] to-[#ef4338] text-white shadow-[0_8px_22px_rgba(240,68,53,0.22)]"><Flame className="size-4" /></span>
          <span><strong className="block text-sm">{t("Ardor Admin", "Ardor 管理")}</strong><small className="text-xs text-stone-400">{t("System console", "系统控制台")}</small></span>
        </Link>
        <nav className="mt-6 flex gap-1 overflow-x-auto md:block md:space-y-1" aria-label={t("Admin navigation", "管理导航")}>
          {adminItems.map((item) => <a key={item.href} href={item.href} className="flex shrink-0 items-center gap-3 rounded-xl px-3 py-2.5 text-sm text-stone-600 transition hover:bg-white/75 hover:text-stone-950"><item.icon className="size-4 text-stone-400" />{item.label}</a>)}
        </nav>
        <Link href="/app" className="mt-6 flex items-center gap-2 rounded-xl px-3 py-2.5 text-sm text-stone-500 transition hover:bg-white/75 hover:text-stone-950 md:absolute md:bottom-5 md:left-4 md:right-4"><ArrowLeft className="size-4" />{t("Back to Ardor", "返回用户端")}</Link>
      </aside>
      <main className="min-w-0 bg-[radial-gradient(circle_at_12%_5%,rgba(255,103,70,0.11),transparent_28rem),radial-gradient(circle_at_92%_90%,rgba(112,119,255,0.11),transparent_34rem)]">
        <div className="mx-auto w-full max-w-7xl px-5 pb-24 pt-10 md:px-10 md:pt-14">{children}</div>
      </main>
    </div>
  );
}
