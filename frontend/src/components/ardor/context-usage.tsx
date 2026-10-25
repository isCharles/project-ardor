"use client";

import { LoaderCircle } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useLocale } from "@/lib/locale";
import { useDismiss } from "./overlay";

export type ContextUsage = {
  state: "PREPARING" | "COMPACTING" | "COMPACTION_FAILED" | "READY";
  model: string; estimatedTokens: number | null; compressionBudgetTokens: number;
  modelWindowTokens: number | null; summaryVersion: number; compactedMessages: number;
};

export function ContextUsageIndicator({ usage: snapshot, selectedModel }: { usage?: ContextUsage | null; selectedModel?: string }) {
  const usage = selectedModel && snapshot?.model !== selectedModel ? null : snapshot;
  const { t } = useLocale();
  const [position, setPosition] = useState<{ left: number; bottom: number; maxHeight: number; width: number } | null>(null);
  const button = useRef<HTMLButtonElement>(null);
  const tooltipId = useId();
  const ref = useDismiss<HTMLDivElement>(!!position, () => setPosition(null));
  useEffect(() => {
    if (!position) return;
    const close = () => setPosition(null);
    window.addEventListener("resize", close);
    window.addEventListener("scroll", close, true);
    return () => { window.removeEventListener("resize", close); window.removeEventListener("scroll", close, true); };
  }, [position]);
  const show = () => {
    const rect = button.current?.getBoundingClientRect();
    if (!rect) return;
    const width = Math.min(240, window.innerWidth - 24);
    setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
      bottom: Math.max(12, window.innerHeight - rect.top + 8), maxHeight: Math.max(100, rect.top - 20), width });
  };
  const working = usage?.state === "PREPARING" || usage?.state === "COMPACTING";
  const count = usage?.estimatedTokens;
  const known = typeof count === "number" && Number.isFinite(count) && count >= 0;
  const windowSize = usage?.modelWindowTokens;
  const capacityKnown = typeof windowSize === "number" && Number.isFinite(windowSize) && windowSize > 0;
  const fraction = known && capacityKnown ? count / windowSize : null;
  const percent = fraction == null ? null : Math.round(fraction * 100);
  const fill = fraction == null ? 0 : Math.max(0, Math.min(1, fraction));
  const compact = (value: number) => new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(value);
  const label = usage?.state === "COMPACTING" ? t("Compacting context", "正在压缩上下文")
    : working ? t("Preparing context", "正在整理上下文")
    : usage?.state === "COMPACTION_FAILED" ? t("Compaction deferred", "上下文压缩未成功")
    : t("Context", "上下文");
  return <div ref={ref} className="relative shrink-0" onMouseLeave={() => setPosition(null)}>
    <button ref={button} type="button" aria-label={t("Context usage", "上下文用量")} aria-describedby={position ? tooltipId : undefined}
      onMouseEnter={show} onFocus={show} onBlur={() => setPosition(null)} onClick={show}
      className="flex size-8 items-center justify-center rounded-full text-stone-500 hover:bg-stone-100 focus-visible:outline-violet-400" data-context-state={usage?.state ?? "UNKNOWN"}>
      {working ? <LoaderCircle aria-hidden="true" className="size-4 animate-spin motion-reduce:animate-none" /> :
        <svg aria-hidden="true" viewBox="0 0 20 20" className="size-4" data-context-fill={fraction == null ? "unknown" : fill}>
          <circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" strokeWidth="2.5" opacity="0.2" />
          <circle cx="10" cy="10" r="8" fill="none" stroke="currentColor" strokeWidth="2.5" pathLength="100"
            strokeDasharray={`${fill * 100} 100`} transform="rotate(-90 10 10)" className="transition-[stroke-dasharray] duration-300 motion-reduce:transition-none" />
        </svg>}
    </button>
    {position && createPortal(<div id={tooltipId} role="tooltip" style={position} className="pointer-events-none fixed z-50 overflow-y-auto rounded-2xl bg-stone-900 px-4 py-3 text-center text-xs leading-6 text-white shadow-xl">
      <p className="text-stone-300">{t("Context window", "上下文窗口")}</p>
      <p>{working || usage?.state === "COMPACTION_FAILED" ? label : percent == null ? t("Capacity unknown", "容量未知") : t(`${percent}% full`, `已用 ${percent}%`)}</p>
      <p>{known ? `≈${compact(count)}` : "—"} / {capacityKnown ? compact(windowSize) : "—"} tokens</p>
    </div>, document.body)}
  </div>;
}
