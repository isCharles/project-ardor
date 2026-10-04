"use client";

import { useLocale } from "@/lib/locale";

export function LocaleSwitch({ className = "" }: { className?: string }) {
  const { locale, setLocale } = useLocale();
  return <div role="group" aria-label="Language / 语言" className={`inline-flex items-center rounded-full border border-stone-200/80 bg-white/70 p-0.5 text-xs shadow-sm ${className}`}>
    <button type="button" aria-pressed={locale === "en"} onClick={() => setLocale("en")} className={`rounded-full px-2.5 py-1.5 transition ${locale === "en" ? "bg-stone-900 text-white" : "text-stone-500 hover:text-stone-900"}`}>EN</button>
    <button type="button" aria-pressed={locale === "zh-CN"} onClick={() => setLocale("zh-CN")} className={`rounded-full px-2.5 py-1.5 transition ${locale === "zh-CN" ? "bg-stone-900 text-white" : "text-stone-500 hover:text-stone-900"}`}>中文</button>
  </div>;
}
