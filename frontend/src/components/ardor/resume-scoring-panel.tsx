"use client";

import { useState } from "react";
import { useLocale } from "@/lib/locale";

type Report = { id: string; analysis: Record<string, unknown>; promptVersion?: string; createdAt?: string };
export function ResumeScoringPanel({ report, history, busy, pending, onAnalyze, onChoose }: {
  report: Report | null; history: Report[]; busy: boolean; pending: boolean;
  onAnalyze: (role: string, jd: string) => void; onChoose: (report: Report) => void;
}) {
  const { t } = useLocale();
  const [role, setRole] = useState("");
  const [jd, setJd] = useState("");
  const data = report?.analysis;
  const context = data?.scoringContext as Record<string, unknown> | undefined;
  const ratings = Array.isArray(data?.criterionRatings) ? data.criterionRatings as Record<string, unknown>[] : [];
  const recommendations = Array.isArray(data?.recommendations) ? data.recommendations.filter((v): v is Record<string, unknown> => !!v && typeof v === "object") : [];
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
      {ratings.length > 0 && <details><summary className="cursor-pointer text-violet-700">{t("Scoring anchors and evidence", "评分指标与证据")}</summary><div className="mt-3 space-y-3">{ratings.map(item => <div key={String(item.id)} className="rounded-xl bg-violet-50/60 p-3"><p className="font-medium">{String(item.id)} · {String(item.level)}/4</p><blockquote className="mt-2 break-words border-l-2 border-violet-200 pl-3">{String(item.evidence || t("No evidence found", "未找到证据"))}</blockquote><p className="mt-2">{String(item.rationale)}</p></div>)}</div></details>}
      {recommendations.length > 0 && <div className="space-y-3"><h3 className="font-semibold">{t("Prioritized improvements", "按优先级改进")}</h3>{recommendations.map((item, index) => <article key={index} className="rounded-xl border border-violet-100 p-4"><span className="rounded-md bg-violet-100 px-2 py-1 text-xs text-violet-700">{String(item.priority)}</span><p className="mt-3 font-medium">{String(item.problem)}</p><p className="mt-2">{String(item.action)}</p>{!!item.evidence && <blockquote className="mt-2 border-l-2 border-stone-200 pl-3">{String(item.evidence)}</blockquote>}{!!item.example && <p className="mt-2 whitespace-pre-wrap rounded-lg bg-stone-50 p-3">{String(item.example)}</p>}</article>)}</div>}
    </div>}
  </section>;
}
