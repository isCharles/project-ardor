"use client";

import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";

import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { approvalModes, type ApprovalMode } from "@/lib/approval-modes";

type ModuleGrant = { module: string; level: number; overridden: boolean };
type Permissions = { globalLevel: number; modules: ModuleGrant[]; mode?: ApprovalMode };

const levelLabels: Array<[string, string]> = [
  ["View only", "仅查看"], ["View & add", "查 + 增"], ["View, add & edit", "查 + 增 + 改"], ["Full access", "完全权限"],
];

const moduleLabels: Record<string, [string, string]> = {
  profile: ["Profile", "个人资料"], web: ["Web search", "网络检索"], knowledge: ["Knowledge", "知识库"],
  learning: ["Learning plans", "学习计划"], memory: ["Memories", "长期记忆"], applications: ["Applications", "投递记录"],
  calendar: ["Calendar", "日历"], resumes: ["Resumes", "简历"], interviews: ["Interviews", "面试"],
  recaps: ["Interview notes", "面经"], cards: ["Flashcards", "记忆卡"], opportunities: ["Pipeline records", "面试流程"],
  system: ["Background jobs", "后台任务"],
};

/** Global default plus per-module overrides; a module without an override follows the global level. */
export function AgentPermissionsPanel() {
  const { t } = useLocale();
  const [data, setData] = useState<Permissions | null>(null);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    void api<Permissions>("/api/agent/permissions")
      .then((value) => { if (active) setData(value); })
      .catch(() => { if (active) setError(t("Could not load agent permissions", "无法加载 Agent 权限")); });
    return () => { active = false; };
  }, [t]);

  async function update(module: string, level: number | null) {
    if (!data) return;
    const previous = data;
    setSaving(module);
    setError("");
    setData(module === "*"
      ? { ...data, globalLevel: level ?? 0, modules: data.modules.map((item) => item.overridden ? item : { ...item, level: level ?? 0 }) }
      : { ...data, modules: data.modules.map((item) => item.module === module
          ? { ...item, level: level ?? data.globalLevel, overridden: level !== null } : item) });
    try {
      await api<void>(`/api/agent/permissions/${encodeURIComponent(module)}`,
        level === null ? { method: "DELETE" } : { method: "PUT", body: JSON.stringify({ level }) });
      setData(await api<Permissions>("/api/agent/permissions"));
    } catch (reason) {
      setData(previous);
      setError(reason instanceof Error ? reason.message : t("Could not save permissions", "权限保存失败"));
    } finally { setSaving(null); }
  }

  async function chooseMode(mode: ApprovalMode) {
    if (!data) return;
    const previous = data;
    setSaving("mode"); setError(""); setData({ ...data, mode });
    try {
      await api<void>("/api/agent/permissions/mode", { method: "PUT", body: JSON.stringify({ mode }) });
      setData(await api<Permissions>("/api/agent/permissions"));
    } catch (reason) {
      setData(previous);
      setError(reason instanceof Error ? reason.message : t("Could not save permissions", "权限保存失败"));
    } finally { setSaving(null); }
  }

  return <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
    <div className="flex items-center gap-3"><ShieldCheck className="size-5 text-violet-500" /><h2 className="text-xl font-semibold">{t("Agent permissions", "Agent 权限")}</h2></div>
    <p className="mt-2 text-sm leading-6 text-stone-500">{t("Choose when Ardor asks for approval. Reads run directly; deletion and proposal-only actions still require confirmation.", "选择 Ardor 何时询问。查询直接执行，删除与建议卡操作仍须确认。")}</p>
    {error && <p role="alert" className="mt-4 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}
    {!data ? <p className="mt-6 text-sm text-stone-400">{t("Loading…", "加载中…")}</p> : <>
      <div className="mt-6 space-y-2" role="group" aria-label={t("Approval mode", "权限模式")}>{approvalModes.map(option => <button key={option.mode} type="button" disabled={saving !== null} aria-pressed={(data.mode ?? "CUSTOM") === option.mode} onClick={() => void chooseMode(option.mode)} className={`block w-full rounded-2xl border px-4 py-3 text-left transition ${(data.mode ?? "CUSTOM") === option.mode ? "border-violet-200 bg-violet-50/70" : "border-transparent hover:bg-white/70"}`}><span className="text-sm font-medium">{t(...option.label)}</span><span className="mt-1 block text-xs leading-5 text-stone-500">{t(...option.description)}</span></button>)}</div>
      {(data.mode ?? "CUSTOM") === "CUSTOM" && <>
      <div className="mt-6 rounded-2xl bg-white/70 p-4">
        <p className="text-sm font-medium">{t("Default for all modules", "全局默认")}</p>
        <div className="mt-3 flex flex-wrap gap-2">{levelLabels.map(([en, zh], level) =>
          <button key={level} type="button" aria-pressed={data.globalLevel === level} disabled={saving !== null} onClick={() => void update("*", level)} className={`rounded-full px-4 py-2 text-xs transition ${data.globalLevel === level ? "bg-violet-600 font-medium text-white" : "border border-stone-200 bg-white text-stone-600 hover:bg-stone-50"}`}>{t(en, zh)}</button>)}</div>
      </div>
      <div className="mt-5 divide-y divide-stone-200/60">
        {data.modules.map((item) => {
          const label = moduleLabels[item.module] ?? [item.module, item.module];
          return <div key={item.module} className="flex flex-wrap items-center justify-between gap-3 py-3">
            <div><p className="text-sm">{t(label[0], label[1])}</p><p className="mt-0.5 text-xs text-stone-400">{item.overridden ? t("Custom level", "已单独设置") : t("Following the default", "跟随全局")}</p></div>
            <div className="flex items-center gap-2">
              <select aria-label={t(`Permission for ${label[0]}`, `${label[1]}权限`)} value={item.overridden ? item.level : -1} disabled={saving !== null} onChange={(event) => { const value = Number(event.target.value); void update(item.module, value < 0 ? null : value); }} className="rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs">
                <option value={-1}>{t(`Follow default (${levelLabels[data.globalLevel][0]})`, `跟随全局（${levelLabels[data.globalLevel][1]}）`)}</option>
                {levelLabels.map(([en, zh], level) => <option key={level} value={level}>{t(en, zh)}</option>)}
              </select>
            </div>
          </div>;
        })}
      </div>
      </>}
    </>}
  </section>;
}
