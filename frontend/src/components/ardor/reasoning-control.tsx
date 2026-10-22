"use client";

import { Check, ChevronDown, Cpu } from "lucide-react";
import { useState } from "react";
import { useDismiss } from "./overlay";
import { useLocale } from "@/lib/locale";

type Depth = "AUTO" | "QUICK" | "STANDARD" | "DEEP";
export type ReasoningLevel = { value: Depth; label: string };
export type ModelOption = { id: string; label: string; provider: string; available: boolean; nativeControl: boolean; reasoningLevels?: ReasoningLevel[] };
const defaultLevels: ReasoningLevel[] = [
  { value: "QUICK", label: "Low" },
  { value: "STANDARD", label: "High" },
  { value: "DEEP", label: "Max" },
];
const iconRules: Array<[RegExp, string, string]> = [
  [/(?:^|[/:\s-])(?:gpt|chatgpt|o[134])(?:[/:\s-]|$)|openai/i, "lobe-openai.svg", "#e7f6ec"],
  [/claude|anthropic|opus|sonnet|haiku/i, "color-claude.svg", "#fdf0e8"],
  [/deepseek/i, "color-deepseek.svg", "#e8f1ff"],
  [/gemini|google|palm/i, "color-gemini.svg", "#eef0ff"],
  [/qwen|qwq|tongyi|alibaba/i, "color-qwen.svg", "#f4eaff"],
  [/seed|doubao|ark/i, "color-doubao.svg", "#e8f7ff"],
  [/bytedance|volcengine/i, "color-bytedance.svg", "#e8f7ff"],
  [/kimi|moonshot/i, "color-kimi.svg", "#eef0f5"],
  [/glm|zhipu|chatglm/i, "color-zhipu.svg", "#eaf2ff"],
  [/grok|xai|x-ai/i, "lobe-grok.svg", "#f1f1f1"],
  [/llama|meta/i, "color-meta.svg", "#eef3ff"],
  [/mistral|mixtral|codestral/i, "color-mistral.svg", "#fff3e6"],
];

function iconFor(label: string) {
  return iconRules.find(([pattern]) => pattern.test(label));
}

function ModelIcon({ label }: { label: string }) {
  const match = iconFor(label);
  if (!match) return <span className="grid size-7 shrink-0 place-items-center rounded-lg bg-stone-100 text-stone-400 ring-1 ring-stone-200/70"><Cpu className="size-3.5" /></span>;
  const [, icon, tint] = match;
  return <span className="grid size-7 shrink-0 place-items-center rounded-lg ring-1 ring-black/5" style={{ backgroundColor: tint }}><span aria-hidden className="size-4 bg-contain bg-center bg-no-repeat" style={{ backgroundImage: `url(/model-icons/${icon})` }} /></span>;
}

/**
 * Combined model + reasoning picker in the composer, following the same compact
 * two-row shape as Codex/DSH: model identity first, then a single reasoning-level row.
 */
export function ReasoningControl({ value, onChange, disabled, nativeControl, models = [], model, onModelChange }: {
  value: Depth; onChange: (depth: Depth) => void; disabled: boolean; nativeControl: boolean;
  models?: ModelOption[]; model?: string; onModelChange?: (model: string) => void;
}) {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const ref = useDismiss<HTMLDivElement>(open, () => setOpen(false));
  const selected = models.find((item) => item.id === model) ?? models[0];
  const levelOptions = selected?.reasoningLevels?.length ? selected.reasoningLevels : defaultLevels;
  const currentLabel = value === "AUTO" ? t("Auto", "自动") : localizedLevel(t, levelOptions.find((level) => level.value === value)?.label ?? "Auto");
  return <div ref={ref} className="relative">
    <button data-reasoning-depth={value} data-model-choice={selected?.id} data-native-reasoning={nativeControl} type="button" disabled={disabled} aria-label={t("Model and thinking depth", "模型与思考深度")} aria-expanded={open} onClick={() => setOpen((current) => !current)} className="flex max-w-[min(40vw,13rem)] items-center gap-1.5 rounded-full px-2.5 py-2 text-xs text-stone-500 transition hover:bg-violet-50 disabled:opacity-40">
      <span className="truncate font-medium text-stone-700">{selected?.label ?? t("Primary model", "主模型")}</span>
      <span className="shrink-0 text-stone-400">{currentLabel}</span>
      <ChevronDown className={`size-3 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} />
    </button>
    {open && <div role="dialog" aria-label={t("Model and thinking depth options", "模型与思考深度选项")} className="ardor-depth-popover absolute bottom-12 right-0 z-40 w-[min(22rem,calc(100vw-2rem))] rounded-3xl border border-stone-200 bg-[#fcfcf9] p-3 shadow-xl">
      <div className="px-2 pb-2 text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Models", "模型")}</div>
      <div className="max-h-80 space-y-1 overflow-y-auto pr-1">
        {models.map((item) => <button key={item.id} type="button" disabled={!item.available} aria-pressed={item.id === selected?.id} onClick={() => { onModelChange?.(item.id); }} className={`flex w-full items-center gap-2 rounded-2xl px-2.5 py-2 text-left transition ${item.id === selected?.id ? "bg-violet-50" : "hover:bg-stone-100"} disabled:cursor-not-allowed disabled:opacity-45`}>
          <ModelIcon label={item.label} />
          <span className="min-w-0 flex-1"><span className="block truncate text-xs font-medium text-stone-800">{item.label}</span><span className="block truncate text-[10px] text-stone-400">{item.id === "PRIMARY" ? t("Primary", "主力") : t("Relay", "中转站")}{!item.available ? ` · ${t("Not configured", "未配置")}` : ""}</span></span>
          {item.id === selected?.id && <Check className="size-3.5 shrink-0 text-violet-600" />}
        </button>)}
        {models.length === 0 && <p className="px-3 py-4 text-xs text-stone-400">{t("No models available.", "暂无可用模型。")}</p>}
      </div>
      <div className="my-2 h-px bg-stone-200/70" />
      <div className="flex items-center justify-between gap-2 px-2">
        <span className="shrink-0 text-xs font-medium text-stone-700">{t("Reasoning level", "推理等级")}</span>
        <div className="flex flex-wrap items-center justify-end gap-1">
          <button type="button" aria-pressed={value === "AUTO"} onClick={() => onChange("AUTO")} className={`rounded-full px-2.5 py-1 text-[11px] transition ${value === "AUTO" ? "bg-emerald-100 font-medium text-emerald-800" : "text-stone-500 hover:bg-stone-100"}`}>{t("Auto", "自动")}</button>
          {levelOptions.map((level) => <button type="button" key={level.value} aria-pressed={value === level.value} onClick={() => onChange(level.value)} className={`rounded-full px-2.5 py-1 text-[11px] transition ${value === level.value ? "bg-violet-100 font-medium text-violet-800" : "text-stone-500 hover:bg-stone-100"}`}>{localizedLevel(t, level.label)}</button>)}
        </div>
      </div>
    </div>}
  </div>;
}

function localizedLevel(t: (en: string, zh: string) => string, label: string) {
  return t(label, ({ Low: "低", Medium: "中", High: "高", Max: "最高" } as Record<string, string>)[label] ?? label);
}
