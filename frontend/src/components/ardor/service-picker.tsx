"use client";

import { useId, useState, type KeyboardEvent } from "react";
import { X, ChevronDown } from "lucide-react";
import { useDismiss } from "./overlay";
import { useLocale } from "@/lib/locale";
import type { AgentFeature } from "@/lib/agent-features";

export function ServicePicker({ features, selected, draft, choose, remove, manualOpen: controlledOpen, onManualOpenChange }: {
  features: AgentFeature[]; selected?: AgentFeature; draft: string;
  choose: (feature: AgentFeature) => void; remove: () => void;
  manualOpen?: boolean; onManualOpenChange?: (open: boolean) => void;
}) {
  const { locale, t } = useLocale();
  const descriptionId = useId();
  const [internalOpen, setInternalOpen] = useState(false);
  const manualOpen = controlledOpen ?? internalOpen;
  const setManualOpen = (open: boolean | ((current: boolean) => boolean)) => {
    const next = typeof open === "function" ? open(manualOpen) : open;
    if (controlledOpen === undefined) setInternalOpen(next);
    onManualOpenChange?.(next);
  };
  const [dismissedDraft, setDismissedDraft] = useState<string | null>(null);
  const match = draft.match(/^[@$]([^\s]*)/);
  const open = manualOpen || (!!match && draft !== dismissedDraft);
  const close = () => { setManualOpen(false); setDismissedDraft(draft); };
  const ref = useDismiss<HTMLDivElement>(open, close);
  const query = manualOpen ? "" : (match?.[1] ?? "").toLowerCase();
  const options = features.filter((feature) => `${feature.key} ${feature.label} ${feature.englishLabel}`.toLowerCase().includes(query));
  const name = (feature: AgentFeature) => locale === "en" ? feature.englishLabel : feature.label;
  const description = (feature: AgentFeature) => locale === "en" ? feature.englishDescription ?? feature.englishLabel : feature.description;
  function optionKeys(event: KeyboardEvent<HTMLButtonElement>) {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    const buttons = Array.from(ref.current?.querySelectorAll<HTMLButtonElement>("[data-service-option]") ?? []);
    const index = buttons.indexOf(event.currentTarget);
    const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 : (index + (event.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next]?.focus();
  }
  return <div ref={ref} className="relative mx-3 mt-2">
    {selected && <div className="inline-flex items-center gap-1 rounded-full bg-violet-50 px-2.5 py-1 text-xs text-violet-700">
      <button type="button" title={description(selected)} aria-label={t("Change service", "更换功能")} aria-expanded={open} onClick={() => setManualOpen((value) => !value)} className="inline-flex items-center gap-1 underline decoration-violet-300 underline-offset-2">{name(selected)}<ChevronDown className="size-3" /></button>
      <button type="button" onClick={remove} aria-label={t("Remove service", "移除功能")} className="rounded-full p-0.5 hover:bg-violet-100"><X className="size-3" /></button>
    </div>}
    {open && <div role="listbox" aria-label={t("Services", "功能与服务")} className="absolute bottom-full left-0 z-40 mb-2 max-h-80 w-72 overflow-auto rounded-2xl border border-stone-200 bg-white p-2 shadow-xl">
      {options.map((feature) => <button key={feature.id} data-service-option type="button" role="option" aria-label={name(feature)} aria-describedby={`${descriptionId}-${feature.id}`} aria-selected={feature.id === selected?.id} onKeyDown={optionKeys} onClick={() => { choose(feature); close(); }} className="block w-full rounded-xl px-3 py-2.5 text-left text-sm text-stone-700 hover:bg-violet-50 focus:bg-violet-50 focus:outline-none"><span className="font-medium">{name(feature)}</span><span id={`${descriptionId}-${feature.id}`} className="mt-1 block text-xs text-stone-400">{description(feature)}</span></button>)}
      {options.length === 0 && <p className="p-3 text-xs text-stone-500">{t("No matching service", "没有匹配的功能")}</p>}
    </div>}
  </div>;
}
