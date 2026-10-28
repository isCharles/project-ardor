import { ChevronDown } from "lucide-react";

import { ModelIcon } from "@/components/ardor/model-icon";
import { modelDisplayName } from "@/lib/model-display-name";
import type { ModelOption, ReasoningLevel } from "@/components/ardor/reasoning-control";
import styles from "./model-settings-dialog.module.css";

export type Depth = "AUTO" | "QUICK" | "STANDARD" | "DEEP";
export type ModelSettingsUpdate = { modelChoice: string; visible: boolean; thinkingEffort: Depth; contextWindowTokens: number | null };
export type Translate = (en: string, zh: string) => string;

const effortLabels: Record<Depth, string> = { AUTO: "Auto", QUICK: "Low", STANDARD: "High", DEEP: "Max" };
const effortChinese: Record<string, string> = { Low: "低", Medium: "中", High: "高", Max: "最高" };
const fallbackLevels: ReasoningLevel[] = [
  { value: "QUICK", label: "Low" },
  { value: "STANDARD", label: "High" },
  { value: "DEEP", label: "Max" },
];
const stops = [
  { value: "auto", label: "Auto" },
  { value: "200000", label: "200K" },
  { value: "400000", label: "400K" },
  { value: "1000000", label: "1M" },
  { value: "custom", label: "Custom" },
] as const;
type WindowChoice = typeof stops[number]["value"];
const MIN_CONTEXT_WINDOW = 32_768;
const MAX_CONTEXT_WINDOW = 10_000_000;

export type Draft = {
  id: string; settingsKey: string; label: string; available: boolean;
  visible: boolean; thinkingEffort: Depth; lastEffort: Depth; levels: ReasoningLevel[];
  windowChoice: WindowChoice; customWindow: string; shared: number;
};

export const MODEL_GRID = "grid grid-cols-[minmax(0,1.2fr)_5rem_9.5rem_5rem_minmax(15rem,1.5fr)] items-center gap-3";

export function toDraft(model: ModelOption, shared: number): Draft {
  const tokens = typeof model.contextWindowTokens === "number" ? model.contextWindowTokens : null;
  const preset = stops.find((stop) => stop.value !== "auto" && stop.value !== "custom" && Number(stop.value) === tokens);
  const effort = (model.thinkingEffort && model.thinkingEffort in effortLabels ? model.thinkingEffort : "AUTO") as Depth;
  const windowChoice: WindowChoice = preset ? preset.value : tokens == null ? "auto" : "custom";
  return {
    id: model.id, settingsKey: model.settingsKey ?? model.id, label: model.label, available: model.available,
    visible: model.visible !== false, thinkingEffort: effort,
    lastEffort: effort === "AUTO" ? "STANDARD" : effort,
    levels: model.reasoningLevels?.length ? model.reasoningLevels : fallbackLevels,
    windowChoice, customWindow: tokens == null ? "" : String(tokens), shared,
  };
}

export function windowTokens(draft: Draft): number | null {
  if (draft.windowChoice === "auto") return null;
  if (draft.windowChoice === "custom") {
    const parsed = Number(draft.customWindow);
    return Number.isFinite(parsed) && draft.customWindow.trim() !== "" ? Math.round(parsed) : null;
  }
  return Number(draft.windowChoice);
}

export function windowError(draft: Draft) {
  if (draft.windowChoice !== "custom") return "";
  const tokens = windowTokens(draft);
  return tokens != null && tokens >= MIN_CONTEXT_WINDOW && tokens <= MAX_CONTEXT_WINDOW ? "" : "32K–10M";
}

function formatTokens(tokens: number) {
  if (tokens >= 1_000_000) {
    const millions = tokens / 1_000_000;
    return `${Number.isInteger(millions) ? millions : millions.toFixed(1)}M`;
  }
  return tokens >= 1000 ? `${Math.round(tokens / 1000)}K` : String(tokens);
}

function windowValueLabel(draft: Draft, t: Translate) {
  if (draft.windowChoice === "auto") return t("Auto", "自动");
  const tokens = windowTokens(draft);
  if (draft.windowChoice === "custom") return tokens == null ? t("Custom", "自定义") : formatTokens(tokens);
  return stops.find((stop) => stop.value === draft.windowChoice)?.label ?? t("Custom", "自定义");
}

function Switch({ checked, label, title, disabled, onToggle }: {
  checked: boolean; label: string; title?: string; disabled?: boolean; onToggle: () => void;
}) {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} title={title} disabled={disabled}
    onClick={onToggle}
    className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors ${checked ? "bg-[#4f7a68]" : "bg-stone-300"} disabled:cursor-not-allowed disabled:opacity-40`}>
    <span className={`inline-block size-5 rounded-full bg-white shadow-sm transition-transform ${checked ? "translate-x-[22px]" : "translate-x-0.5"}`} />
  </button>;
}

export function ModelSettingsRow({ draft, t, onUpdate, onThinking, onEffort }: {
  draft: Draft; t: Translate;
  onUpdate: (id: string, patch: Partial<Draft>) => void;
  onThinking: (draft: Draft, enabled: boolean) => void;
  onEffort: (draft: Draft, effort: Depth) => void;
}) {
  const issue = windowError(draft);
  const index = Math.max(0, stops.findIndex((stop) => stop.value === draft.windowChoice));
  const percent = index / (stops.length - 1) * 100;
  const effortOptions = new Map<string, string>([["AUTO", t("Auto", "自动")]]);
  for (const level of draft.levels) effortOptions.set(level.value, t(level.label, effortChinese[level.label] ?? level.label));
  if (!effortOptions.has(draft.thinkingEffort)) {
    effortOptions.set(draft.thinkingEffort, t(effortLabels[draft.thinkingEffort], effortChinese[effortLabels[draft.thinkingEffort]] ?? effortLabels[draft.thinkingEffort]));
  }
  const name = modelDisplayName(draft.label);
  const nameTitle = draft.shared > 0
    ? `${name} · ${t(`Shared by ${draft.shared + 1} entries`, `与另外 ${draft.shared} 个入口共享`)}`
    : draft.label;

  return <div data-model-setting={draft.id} className={`${MODEL_GRID} border-b border-stone-100 py-3.5`}>
    <div className={`flex min-w-0 items-center gap-2.5 ${draft.visible ? "" : "opacity-60"}`}>
      <ModelIcon label={draft.label} size="md" />
      <div className="min-w-0">
        <p className="truncate text-sm font-medium text-stone-800" title={nameTitle}>{name}</p>
        {!draft.available && <p className="text-[10px] text-stone-400">{t("Not configured", "未配置")}</p>}
      </div>
    </div>
    <Switch checked={draft.thinkingEffort !== "AUTO"} label={`${t("Thinking", "思考")}: ${draft.label}`}
      title={t("On uses the selected effort; off uses Auto.", "开启时使用所选思考力度；关闭时使用自动。")}
      disabled={!draft.available} onToggle={() => onThinking(draft, draft.thinkingEffort === "AUTO")} />
    <div className="relative min-w-0">
      <select aria-label={`${t("Thinking level", "思考力度")}: ${draft.label}`} value={draft.thinkingEffort}
        disabled={!draft.available} onChange={(event) => onEffort(draft, event.target.value as Depth)}
        className="h-8 w-full cursor-pointer appearance-none truncate rounded-lg bg-transparent pl-2 pr-6 text-sm text-stone-700 hover:bg-stone-100 focus:outline-none focus:ring-2 focus:ring-[#4f7a68]/25 disabled:cursor-not-allowed disabled:opacity-50">
        {[...effortOptions.entries()].map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </select>
      <ChevronDown className="pointer-events-none absolute right-1.5 top-1/2 size-3.5 -translate-y-1/2 text-stone-400" />
    </div>
    <Switch checked={draft.visible} label={`${t("Visible in picker", "在选择器中显示")}: ${draft.label}`}
      disabled={!draft.available} onToggle={() => onUpdate(draft.id, { visible: !draft.visible })} />
    <div className="min-w-0">
      <div className="mb-1.5 text-[11px] font-semibold text-stone-700">{windowValueLabel(draft, t)}</div>
      <div aria-hidden className="mb-1 h-2 rounded-sm opacity-70"
        style={{ backgroundImage: "repeating-linear-gradient(to right, #d6d3d1 0 1px, transparent 1px 9px)" }} />
      <input type="range" min={0} max={stops.length - 1} step={1} value={index}
        aria-label={`${t("Context window", "上下文窗口")}: ${draft.label}`} aria-valuetext={windowValueLabel(draft, t)}
        disabled={!draft.available} onChange={(event) => {
          const stop = stops[Number(event.target.value)];
          if (stop) onUpdate(draft.id, { windowChoice: stop.value });
        }}
        className={styles.slider}
        style={{ background: `linear-gradient(to right, #9db8a8 0%, #9db8a8 ${percent}%, #e7e5e4 ${percent}%, #e7e5e4 100%)` }} />
      <div className="mt-1 grid grid-cols-5 text-center text-[10px] text-stone-400">
        {stops.map((stop) => <span key={stop.value} className={stop.value === draft.windowChoice ? "font-semibold text-stone-600" : ""}>
          {stop.value === "auto" || stop.value === "custom" ? "" : stop.label}
        </span>)}
      </div>
      {draft.windowChoice === "custom" && <>
        <input aria-label={`${t("Window tokens", "窗口 token 数")}: ${draft.label}`}
          inputMode="numeric" value={draft.customWindow} disabled={!draft.available}
          onChange={(event) => onUpdate(draft.id, { customWindow: event.target.value.replace(/[^\d]/g, "") })}
          placeholder={t("Tokens", "Token 数")}
          className="mt-1.5 h-7 w-28 rounded-lg border border-stone-200 px-2 text-xs outline-none focus:border-[#4f7a68] disabled:opacity-50" />
        {issue && <p className={`mt-1 text-[10px] ${draft.customWindow.trim() ? "text-rose-600" : "text-stone-400"}`}>{issue}</p>}
      </>}
    </div>
  </div>;
}
