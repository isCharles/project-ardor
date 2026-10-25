"use client";

import { CircleDashed, LoaderCircle } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useLocale } from "@/lib/locale";
import { useDismiss } from "./overlay";

export type ContextUsage = {
  state: "PREPARING" | "COMPACTING" | "COMPACTION_FAILED" | "READY";
  model: string; estimatedTokens: number | null; compressionBudgetTokens: number;
  modelWindowTokens: number | null; summaryVersion: number; compactedMessages: number;
};

export function ContextUsageIndicator({ usage }: { usage?: ContextUsage | null }) {
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
    const width = Math.min(288, window.innerWidth - 24);
    setPosition({ left: Math.max(12, Math.min(rect.left, window.innerWidth - width - 12)),
      bottom: Math.max(12, window.innerHeight - rect.top + 8), maxHeight: Math.max(100, rect.top - 20), width });
  };
  const working = usage?.state === "PREPARING" || usage?.state === "COMPACTING";
  const count = usage?.estimatedTokens;
  const known = typeof count === "number" && Number.isFinite(count) && count >= 0;
  const windowSize = usage?.modelWindowTokens;
  const percent = known && windowSize && windowSize > 0 ? Math.round(count / windowSize * 100) : null;
  const label = usage?.state === "COMPACTING" ? t("Compacting context", "正在压缩上下文")
    : working ? t("Preparing context", "正在整理上下文")
    : usage?.state === "COMPACTION_FAILED" ? t("Compaction deferred", "上下文压缩未成功")
    : t("Context", "上下文");
  return <div ref={ref} className="relative shrink-0" onMouseLeave={() => setPosition(null)}>
    <button ref={button} type="button" aria-label={t("Context usage", "上下文用量")} aria-describedby={position ? tooltipId : undefined}
      onMouseEnter={show} onFocus={show} onBlur={() => setPosition(null)} onClick={show}
      className="flex max-w-32 items-center gap-1 rounded-full px-2 py-2 text-[11px] text-stone-500 hover:bg-stone-100 focus-visible:outline-violet-400" data-context-state={usage?.state ?? "UNKNOWN"}>
      {working ? <LoaderCircle className="size-3.5 animate-spin motion-reduce:animate-none" /> : <CircleDashed className="size-3.5" />}
      <span className="hidden truncate sm:inline">{working || usage?.state === "COMPACTION_FAILED" ? label : percent != null ? `${percent}%` : known ? `~${count.toLocaleString()}` : "—"}</span>
    </button>
    {position && createPortal(<div id={tooltipId} role="tooltip" style={position} className="pointer-events-none fixed z-50 overflow-y-auto rounded-2xl bg-stone-900 p-3 text-xs leading-6 text-white shadow-xl">
      <p className="font-medium">{label}</p>
      <p>{known ? t(`Estimated prompt: ${count.toLocaleString()} tokens`, `提示内容估算：${count.toLocaleString()} tokens`) : t("Prompt estimate unavailable", "提示内容估算尚不可用")}</p>
      <p>{windowSize && windowSize > 0 ? t(`Model window: ${windowSize.toLocaleString()} tokens`, `模型窗口：${windowSize.toLocaleString()} tokens`) : t("Model window: unknown", "模型窗口：未知")}</p>
      {usage && usage.compressionBudgetTokens > 0 && <p>{t("App compression budget", "应用压缩预算")}：{usage.compressionBudgetTokens.toLocaleString()} tokens</p>}
      {!!usage?.summaryVersion && <p>{t("Summary version", "摘要版本")} {usage.summaryVersion} · {t("Messages compacted this run", "本轮已压缩消息")} {usage.compactedMessages}</p>}
      {usage?.state === "COMPACTION_FAILED" && <p>{t("Original messages retained; not successfully compacted.", "原消息仍保留，未计为压缩成功。")}</p>}
      <p className="mt-1 text-stone-400">{t("Last assembled prompt; excludes tool schemas and later tool results. Not billing usage. Draft and model changes require a new request to measure.", "为最近组装的提示估算，不含工具定义及后续工具结果，不是账单用量。草稿或模型变化需新请求后重新测量。")}</p>
      {usage?.model && <p className="truncate text-stone-400">{usage.model}</p>}
    </div>, document.body)}
  </div>;
}
