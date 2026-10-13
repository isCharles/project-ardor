"use client";

import { ChevronDown, Zap } from "lucide-react";
import { useState } from "react";
import { useDismiss } from "./overlay";
import { useLocale } from "@/lib/locale";

type Depth = "AUTO" | "QUICK" | "STANDARD" | "DEEP";
const levels: Depth[] = ["QUICK", "STANDARD", "DEEP"];
const names = ["Low", "High", "Max"];
export function ReasoningControl({ value, onChange, disabled, nativeControl }: { value: Depth; onChange: (depth: Depth) => void; disabled: boolean; nativeControl: boolean }) {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const ref = useDismiss<HTMLDivElement>(open, () => setOpen(false));
  const index = Math.max(0, levels.indexOf(value));
  return <div ref={ref} className="relative">
    <button data-reasoning-depth={value} type="button" disabled={disabled} aria-label={t("Thinking depth", "思考深度")} aria-expanded={open} onClick={() => setOpen((current) => !current)} className="flex items-center gap-2 rounded-full px-3 py-2 text-xs text-stone-500 transition hover:bg-violet-50 disabled:opacity-40"><Zap className="size-3.5" />{value === "AUTO" ? t("Auto", "自动") : names[index]}<ChevronDown className={`size-3 transition-transform ${open ? "rotate-180" : ""}`} /></button>
    {open && <div role="dialog" aria-label={t("Thinking depth options", "思考深度选项")} className="ardor-depth-popover absolute bottom-12 left-0 z-40 w-64 rounded-3xl border border-stone-200 bg-[#fcfcf9] p-4 shadow-xl">
      <div className="mb-4 flex items-center justify-between"><span className="text-sm font-medium">{t("Thinking depth", "思考深度")}</span><button type="button" aria-pressed={value === "AUTO"} onClick={() => onChange("AUTO")} className={`rounded-full px-3 py-1 text-xs ${value === "AUTO" ? "bg-emerald-100 text-emerald-800" : "text-stone-500 hover:bg-stone-100"}`}>{t("Auto", "自动")}</button></div>
      <div className="relative h-9 rounded-full bg-stone-200/80"><div className="absolute inset-y-0 left-0 rounded-full bg-emerald-700/80 transition-[width] duration-300 motion-reduce:transition-none" style={{ width: value === "AUTO" ? "50%" : `${index * 50}%` }} />
        <input type="range" min={0} max={2} step={1} value={value === "AUTO" ? 1 : index} aria-label={t("Thinking depth level", "思考深度档位")} aria-valuetext={value === "AUTO" ? t("Auto", "自动") : names[index]} onChange={(event) => onChange(levels[Number(event.target.value)])} className="ardor-depth-range absolute inset-0 w-full cursor-pointer" />
      </div>
      <div className="mt-2 flex justify-between">{names.map((name, level) => <button type="button" key={name} onClick={() => onChange(levels[level])} className={`rounded-lg px-2 py-1 text-xs ${value === levels[level] ? "font-semibold text-emerald-800" : "text-stone-400 hover:text-stone-700"}`}>{name}</button>)}</div>
      {!nativeControl && <p className="mt-3 text-[11px] leading-4 text-stone-400">{t("This model adapts through prompting; native depth control is unavailable.", "此模型通过提示自适应，不支持原生深度控制。")}</p>}
    </div>}
  </div>;
}
