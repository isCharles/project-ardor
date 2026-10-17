"use client";

import { useState } from "react";
import { useLocale } from "@/lib/locale";

type Report = { id: string; analysis: Record<string, unknown>; promptVersion?: string; createdAt?: string };
type Criterion = { id: string; weight: number; anchor: string; englishAnchor?: string; conditions?: string[]; englishConditions?: string[] };
export function ResumeScoringPanel({ report, history, busy, pending, onAnalyze, onChoose }: {
  report: Report | null; history: Report[]; busy: boolean; pending: boolean;
  onAnalyze: (role: string, jd: string) => void; onChoose: (report: Report) => void;
}) {
  const { t, locale } = useLocale();
  const [role, setRole] = useState("");
  const [jd, setJd] = useState("");
  const data = report?.analysis;
  const context = data?.scoringContext as Record<string, unknown> | undefined;
  const ratings = Array.isArray(data?.criterionRatings) ? data.criterionRatings as Record<string, unknown>[] : [];
  const criteria = Array.isArray(data?.rubricCriteria) ? data.rubricCriteria as Criterion[] : [];
  const recommendations = Array.isArray(data?.recommendations) ? data.recommendations.filter((v): v is Record<string, unknown> => !!v && typeof v === "object") : [];
  const priorities = [
    { key: "P0", label: t("Must fix", "必须修改"), detail: t("Factual conflicts or essential missing information", "事实冲突或关键缺失"), color: "bg-rose-50 text-rose-700" },
    { key: "P1", label: t("Improve next", "优先优化"), detail: t("Evidence gaps that affect assessment of your fit", "影响岗位判断的重要证据缺口"), color: "bg-violet-50 text-violet-700" },
    { key: "P2", label: t("Polish when ready", "有余力再润色"), detail: t("Clarity and wording refinements", "表达与组织优化"), color: "bg-sky-50 text-sky-700" },
  ];
  return <section className="mb-8 space-y-5 rounded-3xl border border-violet-100 bg-white/90 p-6 shadow-sm">
    <h2 className="text-xl font-semibold">{t("Assessment conditions", "评分条件")}</h2>
    <p className="text-sm text-stone-500">{t("Scores describe this resume under these conditions—not hiring probability. Without a job description, this is general resume quality, not current-market fit.", "分数评价这份简历在指定条件下的表现，不代表录用概率。不提供 JD 时，仅做通用质量评估，不声称匹配最新市场。")}</p>
    <form onSubmit={event => { event.preventDefault(); onAnalyze(role, jd); }} className="space-y-3">
      <div><label htmlFor="resume-target-role" className="block text-sm">{t("Target role (optional)", "目标岗位（可选）")}</label><input id="resume-target-role" value={role} onChange={event => setRole(event.target.value)} maxLength={240} className="mt-2 w-full rounded-xl border border-stone-200 p-3" /></div>
      <div><label htmlFor="resume-target-jd" className="block text-sm">{t("Job description (optional)", "岗位要求 JD（可选）")}</label><textarea id="resume-target-jd" value={jd} onChange={event => setJd(event.target.value)} maxLength={20000} rows={3} className="mt-2 w-full rounded-xl border border-stone-200 p-3" /></div>
      <button disabled={busy || pending} className="rounded-full bg-violet-600 px-5 py-2.5 text-sm text-white disabled:opacity-50">{pending ? t("Analyzing in background…", "后台分析中…") : busy ? t("Submitting…", "提交中…") : t("Analyze with these conditions", "按这些条件分析")}</button>
    </form>
    {history.length > 0 && <label className="block text-sm">{t("Saved reports", "历史报告")}<select aria-label={t("Saved reports", "历史报告")} value={report?.id ?? ""} onChange={event => { const item = history.find(v => v.id === event.target.value); if (item) onChoose(item); }} className="ml-3 max-w-full rounded-lg border border-stone-200 p-2">{history.map(item => <option key={item.id} value={item.id}>{item.createdAt?.slice(0, 19).replace("T", " ")} · {String(item.analysis.rubricVersion ?? t("Legacy scoring", "旧版评分"))} · {String((item.analysis.scoringContext as Record<string, unknown> | undefined)?.targetRole || t("General", "通用"))}</option>)}</select></label>}
    {report && <div className="space-y-3 border-t border-stone-100 pt-4 text-sm text-stone-600">
      <p>{data?.rubricVersion ? `${String(data.rubricVersion)} · ${String(context?.marketSnapshotVersion ?? "")}` : t("Legacy free-form scoring. This report has not been silently recalculated.", "旧版自由评分报告，未被静默重算。")}</p>
      {context && <><p>{t("Frozen target", "冻结目标")}：{String(context.targetRole || t("General quality", "通用质量"))} · {String(context.mode)}</p><p>{t("Research snapshot date", "调研快照日期")}：{String(context.researchDate)}{t(" (not a live market feed)", "（不是实时市场数据）")}</p><details><summary className="cursor-pointer text-violet-700">{t("Frozen job description and sources", "冻结的岗位要求与来源")}</summary><p className="mt-2 whitespace-pre-wrap break-words">{String(context.jobDescription || t("No job description supplied.", "未提供具体 JD。"))}</p>{Array.isArray(context.sources) && context.sources.filter(v => typeof v === "string" && v.startsWith("https://careerservices.fas.harvard.edu/")).map(url => <a key={url} href={url} target="_blank" rel="noreferrer" className="block break-all text-violet-700 underline">{url}</a>)}</details></>}
      {data?.textTruncated === true && <p>{t("Only the first 50,000 normalized characters were evaluated. The full-text fingerprint is still recorded.", "仅评估规范化后的前 50,000 字符，完整文本摘要仍被记录。")}</p>}
      {ratings.some(item => Array.isArray(item.checks)) && <div className="rounded-2xl bg-gradient-to-r from-violet-50 via-white to-sky-50 p-4">
        <h3 className="font-medium text-stone-800">{t("A fixed checklist, not an impression", "按固定条件得分，不凭整体印象")}</h3>
        <p className="mt-2">{t("Content 25 · Contribution & evidence 25 · Clarity 20 · Role fit 30. Each criterion has four conditions; each met condition earns one quarter of its weight. No extra bonuses or penalties.", "内容 25 · 贡献与证据 25 · 表达 20 · 岗位匹配 30。每项有四条条件，每满足一条获得该项权重的四分之一，不另行加减分。")}</p>
        <p className="mt-2 text-xs text-stone-500">{t("The model still interprets evidence; the server calculates the score. This is not a guarantee of hiring or a perfectly objective measurement.", "证据是否满足条件仍需模型判断，算分由服务端完成；不代表录用概率，也不宣称完全客观。")}</p>
      </div>}
      {ratings.length > 0 && <details><summary className="cursor-pointer text-violet-700">{t("Scoring anchors and evidence", "评分指标与证据")}</summary><div className="mt-3 space-y-3">{ratings.map(item => {
        const criterion = criteria.find(value => value.id === item.id);
        const conditions = locale === "en" ? criterion?.englishConditions : criterion?.conditions;
        const checks = Array.isArray(item.checks) ? item.checks : [];
        const points = criterion ? criterion.weight * Number(item.level) / 4 : null;
        return <div key={String(item.id)} className="rounded-xl bg-violet-50/60 p-4">
          <p className="font-medium text-stone-800">{criterion ? (locale === "en" ? criterion.englishAnchor ?? criterion.anchor : criterion.anchor) : String(item.id)} · {points == null ? `${String(item.level)}/4` : `${points}/${criterion!.weight}`}</p>
          {conditions?.length === 4 && checks.length === 4 && <ul className="mt-3 space-y-2">{conditions.map((condition, index) => <li key={index} className="flex gap-2"><span className={checks[index] ? "text-emerald-600" : "text-stone-400"}>{checks[index] ? "✓" : "−"}</span><span><span className="sr-only">{checks[index] ? t("Awarded: ", "已得分：") : t("Not awarded: ", "未得分：")}</span>{condition}<span className="ml-2 text-xs text-stone-400">{checks[index] ? `+${criterion!.weight / 4}` : t("Not awarded", "未得分")}</span></span></li>)}</ul>}
          <blockquote className="mt-3 break-words border-l-2 border-violet-200 pl-3">{String(item.evidence || t("No evidence found", "未找到证据"))}</blockquote><p className="mt-2">{String(item.rationale)}</p>
        </div>;
      })}</div></details>}
      {Array.isArray(data?.recommendations) && data?.rubricVersion != null && <div className="space-y-5"><h3 className="font-semibold text-stone-800">{t("Prioritized improvements", "按优先级改进")}</h3>{priorities.map(priority => {
        const items = recommendations.filter(item => item.priority === priority.key);
        return <section key={priority.key} aria-label={priority.label} className="space-y-3">
          <div className="flex items-center gap-2"><h4 className={`rounded-full px-3 py-1 text-xs font-medium ${priority.color}`}>{priority.label}</h4><span className="text-xs text-stone-400">{priority.detail}</span></div>
          {items.length === 0 && <p className="text-xs text-stone-400">{t("No recommendations in this group.", "本次没有这一类建议。")}</p>}
          {items.map((item, index) => <article key={index} className="rounded-2xl border border-violet-100 bg-white p-4"><p className="font-medium text-stone-800">{String(item.problem)}</p><p className="mt-2">{String(item.action)}</p>{!!item.evidence && <blockquote className="mt-3 border-l-2 border-violet-100 pl-3">{String(item.evidence)}</blockquote>}{!!item.example && <div className="mt-3 rounded-xl bg-stone-50 p-3"><p className="text-xs text-stone-400">{t("Example · verify placeholders before using", "改写示例 · 使用前核实占位内容")}</p><p className="mt-1 whitespace-pre-wrap">{String(item.example)}</p></div>}</article>)}
        </section>;
      })}</div>}
    </div>}
  </section>;
}
