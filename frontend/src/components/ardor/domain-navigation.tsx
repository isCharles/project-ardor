"use client";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { destinations, destinationForPath } from "@/lib/navigation";
import { useLocale } from "@/lib/locale";

export function DomainNavigation() {
  const pathname = usePathname();
  const { t } = useLocale();
  const current = destinationForPath(pathname);
  if (!current?.domain) return null;
  const root = destinations.find(item => item.id === current.domain)!;
  const tabs = destinations.filter(item => item.domain === current.domain && item.tab);
  const currentTab = current.section ?? current.id;
  return <div className="border-b border-stone-200/60 bg-white/80 px-5 py-3 md:px-10">
    <p className="mb-2 text-xs font-semibold text-stone-500">{t(root.en, root.zh)}</p>
    <nav aria-label={t("Section navigation", "领域导航")} className="flex flex-wrap gap-1">
      {tabs.map(item => {
        const label = item.id === "job-search" ? t("Overview", "总览") : item.id === "learning" ? t("Topics", "主题") : t(item.en, item.zh);
        const active = currentTab === item.id;
        return <Link key={item.id} href={item.href} aria-current={active ? "page" : undefined}
          className={`rounded-full px-3 py-1.5 text-xs font-medium transition-colors ${active ? "bg-violet-100 text-violet-800" : "text-stone-500 hover:bg-stone-100 hover:text-stone-900"}`}>{label}</Link>;
      })}
    </nav>
  </div>;
}
