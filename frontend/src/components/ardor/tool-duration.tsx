"use client";

import { useLocale } from "@/lib/locale";

export type ToolMeasurement = { approvalWaitMs?: number | null; businessMs?: number | null; status?: string };

export function ToolDuration({ elapsedMs, approvalWaitMs, businessMs, status }: ToolMeasurement & { elapsedMs: number }) {
  const { locale, t } = useLocale();
  const duration = (ms: number) => ms < 1000 ? `${Math.max(0, Math.round(ms))} ms` : `${(ms / 1000).toFixed(1)} ${locale === "en" ? "s" : "秒"}`;
  const measured = approvalWaitMs != null && businessMs != null;
  return <span className="ml-auto shrink-0 text-right tabular-nums text-stone-400">
    {status === "NOT_EXECUTED" ? <span className="text-amber-600">{t("Not executed", "未执行")}</span> : duration(measured ? businessMs : elapsedMs)}
    {measured && approvalWaitMs > 0 && <span className="mt-0.5 block text-[10px] font-normal">{t("Approval wait", "等待授权")} {duration(approvalWaitMs)}</span>}
  </span>;
}
