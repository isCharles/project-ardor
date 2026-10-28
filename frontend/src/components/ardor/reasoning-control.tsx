"use client";

import { Check, ChevronDown, ChevronRight, Settings2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { useDismiss } from "./overlay";
import { ModelIcon } from "./model-icon";
import { useLocale } from "@/lib/locale";
import { modelDisplayName } from "@/lib/model-display-name";

type Depth = "AUTO" | "QUICK" | "STANDARD" | "DEEP";
export type ReasoningLevel = { value: Depth; label: string };
export type ModelOption = { id: string; label: string; provider: string; available: boolean; nativeControl: boolean; imageInput?: boolean; reasoningLevels?: ReasoningLevel[]; description?: string; visible?: boolean; thinkingEffort?: Depth; contextWindowTokens?: number | null; settingsKey?: string };
const levelLabels: Record<Depth, string> = { AUTO: "Auto", QUICK: "Low", STANDARD: "High", DEEP: "Max" };

/**
 * Model picker in the composer. Thinking effort and context window live in the
 * per-model settings dialog so the popover stays a pure model list.
 */
export function ReasoningControl({ value, disabled, nativeControl, models = [], model, onModelChange, onManageModels, onOpenChange }: {
  value: Depth; disabled: boolean; nativeControl: boolean;
  models?: ModelOption[]; model?: string; onModelChange?: (model: string) => void;
  onManageModels?: () => void;
  onOpenChange?: (open: boolean) => void;
}) {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState<{ left: number; bottom: number; width: number; maxHeight: number } | null>(null);
  const popup = useRef<HTMLDivElement>(null);
  const ref = useDismiss<HTMLDivElement>(open, () => setOpen(false), popup);
  useEffect(() => {
    onOpenChange?.(open);
  }, [open, onOpenChange]);
  useEffect(() => {
    if (!open) return;
    const close = () => setOpen(false);
    window.addEventListener("resize", close);
    return () => window.removeEventListener("resize", close);
  }, [open]);
  const visibleModels = models.filter((item) => item.visible !== false);
  const selected = visibleModels.find((item) => item.id === model) ?? visibleModels[0];
  const currentLabel = levelLabels[value];
  return <div ref={ref} className="relative min-w-0">
    <button data-reasoning-depth={value} data-model-choice={selected?.id} data-native-reasoning={nativeControl} type="button" disabled={disabled} aria-label={t("Model and thinking depth", "模型与思考深度")} aria-expanded={open} onClick={(event) => {
      const rect = event.currentTarget.getBoundingClientRect();
      const width = Math.min(352, window.innerWidth - 24);
      setPosition({ left: Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12)),
        bottom: Math.max(12, window.innerHeight - rect.top + 8), width, maxHeight: Math.max(100, rect.top - 20) });
      setOpen(current => !current);
    }} className="flex w-full min-w-0 max-w-[min(40vw,13rem)] items-center gap-1.5 rounded-full px-2.5 py-2 text-xs text-stone-500 transition hover:bg-violet-50 disabled:opacity-40">
      <span className="truncate font-medium text-stone-700" title={selected?.label}>{selected ? modelDisplayName(selected.label) : t("Primary model", "主模型")}</span>
      <span data-reasoning-label className="shrink-0 text-stone-400">{currentLabel}</span>
      <ChevronDown className={`size-3 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
    </button>
    {open && position && createPortal(<div ref={popup} role="dialog" aria-label={t("Model and thinking depth options", "模型与思考深度选项")} style={position} className="ardor-depth-popover fixed z-40 overflow-y-auto rounded-3xl border border-stone-200 bg-[#fcfcf9] p-3 shadow-xl">
      <div className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Models", "模型")}</div>
      <div className="max-h-80 space-y-1 overflow-y-auto pr-1">
        {visibleModels.map((item) => <button key={item.id} type="button" disabled={!item.available} aria-pressed={item.id === selected?.id} onClick={() => { onModelChange?.(item.id); }} className={`flex w-full items-center gap-2 rounded-2xl px-2.5 py-2 text-left transition ${item.id === selected?.id ? "bg-violet-50" : "hover:bg-stone-100"} disabled:cursor-not-allowed disabled:opacity-45`}>
          <ModelIcon label={item.label} />
          <span className="min-w-0 flex-1"><span className="block truncate text-xs font-medium text-stone-800" title={item.label}>{modelDisplayName(item.label)}</span>{!item.available ? <span className="block truncate text-[10px] text-stone-400">{t("Not configured", "未配置")}</span> : item.description ? <span className="block truncate text-[10px] text-stone-400" title={item.description}>{item.description}</span> : null}</span>
          {item.id === selected?.id && <Check className="size-3.5 shrink-0 text-violet-600" />}
        </button>)}
        {visibleModels.length === 0 && <p className="px-3 py-4 text-xs text-stone-400">{t("No models available.", "暂无可用模型。")}</p>}
      </div>
      <div className="my-2 h-px bg-stone-200/70" />
      <button type="button" aria-label={t("Manage models", "管理模型")} onClick={() => { setOpen(false); onManageModels?.(); }} className="flex w-full items-center gap-2 rounded-2xl px-2.5 py-2 text-xs text-stone-600 transition hover:bg-violet-50">
        <Settings2 className="size-3.5 text-stone-400" />{t("Manage models", "管理模型")}
        <ChevronRight className="ml-auto size-3.5 text-stone-300" />
      </button>
    </div>, document.body)}
  </div>;
}

