"use client";

import { Eye, EyeOff, Settings2 } from "lucide-react";
import { useMemo, useState } from "react";

import { AppDialog } from "@/components/ui/app-dialog";
import { useLocale } from "@/lib/locale";
import { modelDisplayName } from "@/lib/model-display-name";
import type { ModelOption } from "@/components/ardor/reasoning-control";

type Depth = "AUTO" | "QUICK" | "STANDARD" | "DEEP";
export type ModelSettingsUpdate = { modelChoice: string; visible: boolean; thinkingEffort: Depth; contextWindowTokens: number | null };

const effortLabels: Record<Depth, string> = { AUTO: "Auto", QUICK: "Low", STANDARD: "High", DEEP: "Max" };
const presets = [
  { value: "auto", tokens: null, label: "Auto" },
  { value: "200000", tokens: 200_000, label: "200K" },
  { value: "400000", tokens: 400_000, label: "400K" },
  { value: "1000000", tokens: 1_000_000, label: "1M" },
  { value: "custom", tokens: null, label: "Custom" },
];
const MIN_CONTEXT_WINDOW = 32_768;
const MAX_CONTEXT_WINDOW = 10_000_000;

type Draft = {
  id: string; label: string; provider: string; available: boolean;
  visible: boolean; thinkingEffort: Depth;
  windowChoice: string; customWindow: string; shared: number;
};

function toDraft(model: ModelOption, shared: number): Draft {
  const tokens = typeof model.contextWindowTokens === "number" ? model.contextWindowTokens : null;
  const preset = presets.find((item) => item.tokens === tokens);
  const effort = model.thinkingEffort && model.thinkingEffort in effortLabels ? model.thinkingEffort : "AUTO";
  return {
    id: model.id, label: model.label, provider: model.provider, available: model.available,
    visible: model.visible !== false, thinkingEffort: effort,
    windowChoice: preset ? preset.value : "custom",
    customWindow: tokens == null ? "" : String(tokens), shared,
  };
}

function windowTokens(draft: Draft): number | null {
  if (draft.windowChoice === "auto") return null;
  if (draft.windowChoice === "custom") {
    const parsed = Number(draft.customWindow);
    return Number.isFinite(parsed) && draft.customWindow.trim() !== "" ? Math.round(parsed) : null;
  }
  return Number(draft.windowChoice);
}

function windowError(draft: Draft) {
  if (draft.windowChoice !== "custom") return "";
  const tokens = windowTokens(draft);
  if (tokens == null) return "请输入上下文窗口 token 数";
  if (tokens < MIN_CONTEXT_WINDOW || tokens > MAX_CONTEXT_WINDOW) return "上下文窗口需在 32K 到 10M 之间";
  return "";
}

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
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

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

  function update(id: string, patch: Partial<Draft>) {
    setSaved(false);
    setDrafts((current) => current.map((draft) => draft.id === id ? { ...draft, ...patch } : draft));
  }

  async function submit() {
    if (updates.length === 0 || invalidRows > 0 || saving) return;
    setSaving(true);
    setError("");
    try {
      await onSave(updates);
      setSaved(true);
      window.setTimeout(onClose, 250);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not save model settings", "模型设置保存失败"));
    } finally {
      setSaving(false);
    }
  }

  return <AppDialog wide title={t("Manage models", "管理模型")} onClose={() => { if (!saving) onClose(); }}>
    <div className="space-y-4">
      <p className="text-xs leading-5 text-stone-500">
        {t("These settings apply only to your account. Hidden models disappear from the picker. A smaller context window compacts the conversation earlier, which lowers the input cost of each request.",
          "这些设置只对你的账户生效。隐藏的模型不会出现在选择器中。上下文窗口越小，对话越早压缩，每次请求的输入成本也越低。")}
      </p>
      {drafts.length === 0 && <p className="py-8 text-center text-sm text-stone-400">{t("No models available.", "暂无可用模型。")}</p>}
      <div className="space-y-2">
        {drafts.map((draft) => {
          const issue = windowError(draft);
          return <section key={draft.id} data-model-setting={draft.id} className={`rounded-2xl border p-3 transition ${draft.visible ? "border-stone-200 bg-white" : "border-stone-200/70 bg-stone-50/70"}`}>
            <div className="flex items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-stone-800" title={draft.label}>{modelDisplayName(draft.label)}</p>
                <p className="truncate text-[10px] uppercase tracking-wide text-stone-400">{draft.provider}{draft.available ? "" : ` · ${t("Not configured", "未配置")}`}{draft.shared > 0 ? <span className="normal-case tracking-normal"> · {t(`Shared by ${draft.shared + 1} entries`, `与另外 ${draft.shared} 个入口共享`)}</span> : null}</p>
              </div>
              <button type="button" aria-label={`${t("Visible in picker", "在选择器中显示")}: ${draft.label}`} aria-pressed={draft.visible} disabled={!draft.available}
                onClick={() => update(draft.id, { visible: !draft.visible })}
                className={`flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] transition disabled:cursor-not-allowed disabled:opacity-40 ${draft.visible ? "bg-emerald-50 text-emerald-700 hover:bg-emerald-100" : "bg-stone-100 text-stone-500 hover:bg-stone-200"}`}>
                {draft.visible ? <Eye className="size-3.5" /> : <EyeOff className="size-3.5" />}
                {draft.visible ? t("Visible", "可见") : t("Hidden", "已隐藏")}
              </button>
            </div>
            <div className="mt-2 grid gap-2 sm:grid-cols-2">
              <label className="flex items-center justify-between gap-2 rounded-xl bg-stone-50 px-2.5 py-1.5 text-[11px] text-stone-500">
                <span className="shrink-0">{t("Thinking level", "思考力度")}</span>
                <select aria-label={`${t("Thinking level", "思考力度")}: ${draft.label}`} value={draft.thinkingEffort} disabled={!draft.available || !draft.visible}
                  onChange={(event) => update(draft.id, { thinkingEffort: event.target.value as Depth })}
                  className="min-w-0 rounded-lg border border-stone-200 bg-white px-1.5 py-1 text-[11px] text-stone-700 disabled:opacity-50">
                  {(Object.keys(effortLabels) as Depth[]).map((depth) => <option key={depth} value={depth}>{effortLabels[depth]}</option>)}
                </select>
              </label>
              <label className="flex items-center justify-between gap-2 rounded-xl bg-stone-50 px-2.5 py-1.5 text-[11px] text-stone-500">
                <span className="shrink-0">{t("Context window", "上下文窗口")}</span>
                <select aria-label={`${t("Context window", "上下文窗口")}: ${draft.label}`} value={draft.windowChoice} disabled={!draft.available}
                  onChange={(event) => update(draft.id, { windowChoice: event.target.value })}
                  className="min-w-0 rounded-lg border border-stone-200 bg-white px-1.5 py-1 text-[11px] text-stone-700 disabled:opacity-50">
                  {presets.map((preset) => <option key={preset.value} value={preset.value}>{preset.label}</option>)}
                </select>
              </label>
            </div>
            {draft.windowChoice === "custom" && <div className="mt-2 flex items-center gap-2">
              <input aria-label={`${t("Window tokens", "窗口 token 数")}: ${draft.label}`} inputMode="numeric" value={draft.customWindow} disabled={!draft.available}
                onChange={(event) => update(draft.id, { customWindow: event.target.value.replace(/[^\d]/g, "") })}
                placeholder={t("Tokens, e.g. 64000", "Token 数，例如 64000")}
                className="w-full rounded-xl border border-stone-200 px-2.5 py-1.5 text-xs outline-none focus:border-violet-400 disabled:opacity-50" />
              <span className="shrink-0 text-[10px] text-stone-400">32K–10M</span>
            </div>}
            {issue && <p role="alert" className="mt-2 text-[11px] text-rose-600">{issue}</p>}
          </section>;
        })}
      </div>
      {visibleModels === 0 && drafts.some((draft) => draft.available) && <p role="alert" className="text-xs text-rose-600">{t("Keep at least one model visible in the picker.", "请至少保留一个在选择器中可见的模型。")}</p>}
      {error && <p role="alert" className="rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-700">{error}</p>}
      <div className="flex items-center justify-between gap-2 pt-1">
        <span className="flex items-center gap-1.5 text-[11px] text-stone-400"><Settings2 className="size-3.5" />{saved ? t("Saved", "已保存") : t("Changes apply to new messages", "设置会应用于之后的消息")}</span>
        <div className="flex gap-2">
          <button type="button" disabled={saving} onClick={onClose} className="rounded-xl border border-stone-200 px-3 py-1.5 text-xs text-stone-600 transition hover:bg-stone-50 disabled:opacity-40">{t("Cancel", "取消")}</button>
          <button type="button" disabled={saving || updates.length === 0 || invalidRows > 0 || (visibleModels === 0 && drafts.some((draft) => draft.available))}
            onClick={() => void submit()}
            className="rounded-xl bg-violet-600 px-3 py-1.5 text-xs font-medium text-white transition hover:bg-violet-700 disabled:cursor-not-allowed disabled:opacity-40">
            {saving ? t("Saving…", "保存中…") : t("Save", "保存")}
          </button>
        </div>
      </div>
    </div>
  </AppDialog>;
}
