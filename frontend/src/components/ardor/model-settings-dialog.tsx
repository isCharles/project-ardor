"use client";

import { useMemo, useState } from "react";

import { AppDialog } from "@/components/ui/app-dialog";
import { useLocale } from "@/lib/locale";
import type { ModelOption } from "@/components/ardor/reasoning-control";
import { MODEL_GRID, ModelSettingsRow, toDraft, windowError, windowTokens, type Depth, type Draft, type ModelSettingsUpdate } from "./model-settings-row";

export type { ModelSettingsUpdate } from "./model-settings-row";

export function ModelSettingsDialog({ models, onClose, onSave }: {
  models: ModelOption[];
  onClose: () => void;
  onSave: (updates: ModelSettingsUpdate[]) => Promise<void>;
}) {
  const { t } = useLocale();
  const groups = useMemo(() => {
    const byKey = new Map<string, ModelOption[]>();
    for (const model of models) {
      const key = model.settingsKey ?? model.id;
      const group = byKey.get(key);
      if (group) group.push(model);
      else byKey.set(key, [model]);
    }
    return [...byKey.values()].map((group) => ({ model: group[0], shared: group.length - 1 }));
  }, [models]);
  const [drafts, setDrafts] = useState<Draft[]>(() => groups.map(({ model, shared }) => toDraft(model, shared)));
  const [tab, setTab] = useState<"default" | "custom">("default");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  const customizedKeys = useMemo(() => new Set(models.filter((model) => model.visible === false
    || (model.thinkingEffort ?? "AUTO") !== "AUTO" || (model.contextWindowTokens ?? null) !== null)
    .map((model) => model.settingsKey ?? model.id)), [models]);
  const shownDrafts = tab === "custom" ? drafts.filter((draft) => customizedKeys.has(draft.settingsKey)) : drafts;
  const updates = useMemo(() => drafts.flatMap((draft) => {
    const original = models.find((model) => model.id === draft.id);
    if (!original || windowError(draft)) return [];
    const tokens = windowTokens(draft);
    const before = typeof original.contextWindowTokens === "number" ? original.contextWindowTokens : null;
    const visible = original.visible !== false;
    const effort = original.thinkingEffort ?? "AUTO";
    if (draft.visible === visible && draft.thinkingEffort === effort && tokens === before) return [];
    return [{ modelChoice: draft.id, visible: draft.visible, thinkingEffort: draft.thinkingEffort, contextWindowTokens: tokens }];
  }), [drafts, models]);
  const invalidRows = drafts.filter((draft) => windowError(draft)).length;
  const visibleModels = drafts.filter((draft) => draft.visible && draft.available).length;
  const allHidden = visibleModels === 0 && drafts.some((draft) => draft.available);

  function update(id: string, patch: Partial<Draft>) {
    setDrafts((current) => current.map((draft) => draft.id === id ? { ...draft, ...patch } : draft));
  }

  function setThinking(draft: Draft, enabled: boolean) {
    update(draft.id, { thinkingEffort: enabled ? (draft.lastEffort === "AUTO" ? "STANDARD" : draft.lastEffort) : "AUTO" });
  }

  function setEffort(draft: Draft, effort: Depth) {
    update(draft.id, { thinkingEffort: effort, lastEffort: effort === "AUTO" ? draft.lastEffort : effort });
  }

  async function submit() {
    if (updates.length === 0 || invalidRows > 0 || saving) return;
    setSaving(true);
    setError("");
    try {
      await onSave(updates);
      window.setTimeout(onClose, 250);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not save", "保存失败"));
    } finally {
      setSaving(false);
    }
  }

  return <AppDialog title={t("Manage models", "管理模型")} size="xl" onClose={() => { if (!saving) onClose(); }}
    bodyClassName="flex flex-col"
    header={<div role="tablist" aria-label={t("Model settings", "模型设置")} className="flex items-center gap-6">
      {(["default", "custom"] as const).map((item) => <button key={item} type="button" role="tab" aria-selected={tab === item}
        onClick={() => setTab(item)}
        className={`text-sm transition-colors ${tab === item ? "font-semibold text-stone-900" : "text-stone-400 hover:text-stone-600"}`}>
        {item === "default" ? t("Default", "默认") : t("Custom", "自定义")}
      </button>)}
    </div>}>
    <div className="min-h-0 flex-1 overflow-auto px-6">
      <div className="min-w-[820px]">
        <div className={`${MODEL_GRID} sticky top-0 z-10 border-b border-stone-200 bg-white py-3 text-xs font-medium text-stone-400`}>
          <span>{t("Model", "模型")}</span>
          <span>{t("Thinking", "思考")}</span>
          <span>{t("Thinking effort", "思考力度")}</span>
          <span>{t("Visibility", "可见性")}</span>
          <span>{t("Context window", "上下文窗口")}</span>
        </div>
        <div data-model-settings-list>
          {shownDrafts.map((draft) => <ModelSettingsRow key={draft.id} draft={draft} t={t}
            onUpdate={update} onThinking={setThinking} onEffort={setEffort} />)}
          {drafts.length === 0 && <p className="py-16 text-center text-sm text-stone-400">{t("No models.", "暂无模型。")}</p>}
          {drafts.length > 0 && shownDrafts.length === 0 && <p className="py-16 text-center text-sm text-stone-400">{t("No custom settings.", "暂无自定义设置。")}</p>}
        </div>
      </div>
    </div>
    <div className="shrink-0 border-t border-stone-200 bg-[#fafaf9] px-6 py-3">
      {allHidden && <p role="alert" className="mb-2 text-xs text-rose-600">{t("Keep one model visible.", "请保留一个可见模型。")}</p>}
      {error && <p role="alert" className="mb-2 text-xs text-rose-700">{error}</p>}
      <div className="flex justify-end gap-2">
        <button type="button" disabled={saving} onClick={onClose}
          className="rounded-xl border border-stone-200 bg-white px-4 py-2 text-xs text-stone-600 transition hover:bg-stone-50 disabled:opacity-40">{t("Cancel", "取消")}</button>
        <button type="button" disabled={saving || updates.length === 0 || invalidRows > 0 || allHidden}
          title={t("Changes apply from the next turn.", "更改将在下一轮生效。")}
          onClick={() => void submit()}
          className="rounded-xl bg-[#4f7a68] px-4 py-2 text-xs font-medium text-white transition hover:bg-[#426653] disabled:cursor-not-allowed disabled:opacity-40">
          {saving ? t("Saving…", "保存中…") : t("Save", "保存")}
        </button>
      </div>
    </div>
  </AppDialog>;
}
