"use client";

import { LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useLocale } from "@/lib/locale";
import { useDismiss } from "./overlay";

export type ContextCompaction = {
  outcome: "COMPACTED" | "FAILED"; spanId: string | null; windowTokens: number | null;
  softThresholdTokens: number; projectedInputTokens: number; estimatedInputTokensAfter: number;
  summarizedMessages: number; firstMessageOrdinal: number; summaryVersionBefore: number;
  summaryVersionAfter: number; errorCode: string | null;
};

export type ContextUsage = {
  state: "PREPARING" | "COMPACTING" | "COMPACTION_FAILED" | "READY";
  model: string; estimatedTokens: number | null; compressionBudgetTokens: number;
  modelWindowTokens: number | null; summaryVersion: number; compactedMessages: number;
  compaction?: ContextCompaction | null;
  authoritative?: boolean;
  conversationAnchor?: boolean;
};

/**
 * Context compaction changed what the model can see, so the conversation says so in its own line
 * instead of leaving the figure in a tooltip. The numbers come from the run and link to the span.
 */
export function CompactionNotice({ usage, traceHref }: { usage?: ContextUsage | null; traceHref?: string }) {
  const { t } = useLocale();
  const compaction = usage?.compaction;
  if (!compaction) return null;
  const compact = (value: number) => new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(value);
  const last = compaction.firstMessageOrdinal + Math.max(0, compaction.summarizedMessages) - 1;
  const range = compaction.summarizedMessages > 1
    ? t(`messages ${compaction.firstMessageOrdinal}-${last}`, `第 ${compaction.firstMessageOrdinal}–${last} 条历史`)
    : t(`message ${compaction.firstMessageOrdinal}`, `第 ${compaction.firstMessageOrdinal} 条历史`);
  const failed = compaction.outcome !== "COMPACTED";
  const text = failed
    ? t(`History could not be summarized (${compaction.errorCode ?? "unknown"}); this run kept every message verbatim.`,
      `未能归纳历史（${compaction.errorCode ?? "未知原因"}），本轮保留全部原文。`)
    : t(`Summarized ${range} into a shorter context: ${compact(compaction.projectedInputTokens)} → ${compact(compaction.estimatedInputTokensAfter)} tokens (threshold ${compact(compaction.softThresholdTokens)}).`,
      `已把${range}归纳为摘要：估算 ${compact(compaction.projectedInputTokens)} → ${compact(compaction.estimatedInputTokensAfter)} tokens（阈值 ${compact(compaction.softThresholdTokens)}）。`);
  const href = traceHref && compaction.spanId ? `${traceHref}?span=${encodeURIComponent(compaction.spanId)}` : traceHref;
  return <p data-compaction-notice className={`flex flex-wrap items-center gap-x-2 gap-y-1 rounded-xl px-3 py-2 text-xs leading-5 ${failed ? "bg-amber-50 text-amber-800" : "bg-violet-50 text-violet-800"}`}>
    <span className="font-medium">{failed ? t("Compaction failed", "归纳失败") : t("Context compacted", "上下文已压缩")}</span>
    <span className="min-w-0">{text}</span>
    {href && <Link href={href} className="ml-auto shrink-0 underline opacity-80 hover:opacity-100">{t("View span", "查看轨迹")}</Link>}
  </p>;
}

export function ContextUsageIndicator({ usage: snapshot, selectedModel }: { usage?: ContextUsage | null; selectedModel?: string }) {
  const usage = selectedModel && snapshot?.model !== selectedModel ? null : snapshot;
  const { t } = useLocale();
  const [position, setPosition] = useState<{ left: number; bottom: number; maxHeight: number; width: number } | null>(null);
  const button = useRef<HTMLButtonElement>(null);
  const tooltipId = useId();
  const ref = useDismiss<HTMLDivElement>(!!position, () => setPosition(null));
  const show = () => {
    const rect = button.current?.getBoundingClientRect();
    if (!rect) return;
    const width = Math.min(240, window.innerWidth - 24);
    setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
      bottom: Math.max(12, window.innerHeight - rect.top + 8), maxHeight: Math.max(100, rect.top - 20), width });
  };
  useEffect(() => {
    if (!position) return;
    // A streaming transcript scrolls under a pointer that never moved, and the estimate is only
    // useful if the note follows the ring. Anything else — a tap, keyboard focus — still closes it.
    const reactToViewport = () => { if (button.current?.matches(":hover")) show(); else setPosition(null); };
    window.addEventListener("resize", reactToViewport);
    window.addEventListener("scroll", reactToViewport, true);
    return () => { window.removeEventListener("resize", reactToViewport); window.removeEventListener("scroll", reactToViewport, true); };
  }, [position]);
  const working = usage?.state === "PREPARING" || usage?.state === "COMPACTING";
  const count = usage?.estimatedTokens;
  const known = typeof count === "number" && Number.isFinite(count) && count >= 0;
  const windowSize = usage?.modelWindowTokens;
  const capacityKnown = typeof windowSize === "number" && Number.isFinite(windowSize) && windowSize > 0;
  const fraction = known && capacityKnown ? count / windowSize : null;
  const percent = fraction == null ? null : Math.round(fraction * 100);
  const fill = fraction == null ? 0 : Math.max(0, Math.min(1, fraction));
  const compact = (value: number) => new Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 1 }).format(value);
  const label = usage?.state === "COMPACTING" ? t("Summarizing earlier messages", "正在归纳较早的对话")
    : working ? t("Preparing context", "正在整理上下文")
    : usage?.state === "COMPACTION_FAILED" ? t("History summary failed; every message was kept", "未能归纳历史，保留全部原文")
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
