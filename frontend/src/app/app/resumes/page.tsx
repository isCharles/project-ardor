"use client";

import { ArrowLeft, ArrowRight, BrainCircuit, Check, ChevronRight, FileText, Plus, Sparkles, Target, TrendingUp, Upload, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChangeEvent, FormEvent, useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type ResumeItem = {
  id: string; originalFilename: string; sizeBytes: number;
  parseStatus: "PENDING" | "PARSED" | "FAILED"; createdAt: string; analysisId: string | null;
  analysisStatus: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | null; analysisError: string | null;
};
type Analysis = { id: string; resumeId: string; analysis: Record<string, unknown>; modelName: string; createdAt?: string };
type AnalysisTask = { status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED"; analysisId: string | null };
type ScoreCard = { label: string; value: number; note: string };

function clampScore(value: number) { return Math.max(0, Math.min(100, Math.round(value))); }
function textList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value.map((item) => {
    if (typeof item === "string") return item;
    if (item && typeof item === "object") return Object.values(item).filter((part) => typeof part === "string" || typeof part === "number").join(" · ");
    return String(item ?? "");
  }).filter(Boolean);
}
function reportScores(data: Record<string, unknown>, t: (en: string, zh: string) => string) {
  const strengths = textList(data.strengths), weaknesses = textList(data.weaknesses), skills = textList(data.skills);
  const experience = textList(data.experience), projects = textList(data.projects), roles = textList(data.possibleTargetRoles);
  const raw = data.categoryScores && typeof data.categoryScores === "object" ? data.categoryScores as Record<string, unknown> : {};
  const number = (key: string, fallback: number) => typeof raw[key] === "number" ? clampScore(raw[key]) : clampScore(fallback);
  const cards: ScoreCard[] = [
    { label: t("Completeness", "内容完整度"), value: number("content", 58 + Math.min(24, (experience.length + projects.length) * 6) + Math.min(10, skills.length * 2)), note: t("Experience, projects and skills", "经历、项目与技能覆盖") },
    { label: t("Impact", "成果说服力"), value: number("impact", 62 + strengths.length * 5 - weaknesses.length * 2), note: t("Results and value", "结果与价值表达") },
    { label: t("Clarity", "表达清晰度"), value: number("clarity", 68 + Math.min(16, strengths.length * 4)), note: t("Structure and focus", "结构与信息密度") },
    { label: t("Role fit", "岗位匹配度"), value: number("roleFit", 60 + Math.min(20, roles.length * 8) + Math.min(10, skills.length)), note: t("Skills relevant to target roles", "技能与目标岗位关联") },
  ];
  const explicit = typeof data.overallScore === "number" ? clampScore(data.overallScore) : null;
  return { overall: explicit ?? clampScore(cards.reduce((sum, card) => sum + card.value, 0) / cards.length), cards, estimated: explicit === null };
}
function statusMeta(resume: ResumeItem, t: (en: string, zh: string) => string) {
  if (resume.analysisId) return { label: t("Report ready", "报告已完成"), className: "bg-emerald-50 text-emerald-700" };
  if (resume.analysisStatus === "QUEUED") return { label: t("Queued", "等待分析"), className: "bg-blue-50 text-blue-700" };
  if (resume.analysisStatus === "RUNNING") return { label: t("Analyzing", "正在分析"), className: "bg-violet-50 text-violet-700" };
  if (resume.analysisStatus === "FAILED") return { label: t("Analysis failed", "分析失败"), className: "bg-red-50 text-red-700" };
  if (resume.parseStatus === "FAILED") return { label: t("Parsing failed", "解析失败"), className: "bg-red-50 text-red-700" };
  return { label: t("Ready to analyze", "可以分析"), className: "bg-stone-100 text-stone-600" };
}

export default function ResumesPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const [resumes, setResumes] = useState<ResumeItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [showUpload, setShowUpload] = useState(false);
  const [fileName, setFileName] = useState("");
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const selected = resumes.find((resume) => resume.id === selectedId) ?? null;

  const load = useCallback(async () => {
    try { setResumes(await api<ResumeItem[]>("/api/resumes")); }
    catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
      setError(reason instanceof Error ? reason.message : t("Could not load resumes", "无法加载简历"));
    }
  }, [router, t]);
  useEffect(() => {
    let active = true;
    api<ResumeItem[]>("/api/resumes").then((items) => { if (active) setResumes(items); }).catch((reason) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : t("Could not load resumes", "无法加载简历"));
    });
    return () => { active = false; };
  }, [router, t]);
  useEffect(() => {
    if (!resumes.some((resume) => resume.analysisStatus === "QUEUED" || resume.analysisStatus === "RUNNING")) return;
    const timer = window.setInterval(() => { void load(); }, 2500);
    return () => window.clearInterval(timer);
  }, [resumes, load]);
  useEffect(() => {
    if (!selected?.analysisId || analysis?.resumeId === selected.id) return;
    let active = true;
    api<Analysis>(`/api/resumes/${selected.id}/analysis`).then((result) => { if (active) setAnalysis(result); })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : t("Could not load report", "无法载入报告")); });
    return () => { active = false; };
  }, [selected, analysis?.resumeId, t]);

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = event.currentTarget;
    setBusy("upload"); setError(""); setNotice("");
    try {
      const created = await api<ResumeItem>("/api/resumes", { method: "POST", body: new FormData(form) });
      await load(); form.reset(); setFileName(""); setShowUpload(false); setSelectedId(created.id);
      if (created.parseStatus === "FAILED") setError(t("Saved, but no text was found. Upload a text-based PDF or DOCX.", "文件已保存，但没有提取到文本。请上传文本型 PDF 或 DOCX。"));
      else setNotice(t("Resume uploaded and parsed. Ready for AI analysis.", "简历已上传并完成文本解析，可以开始 AI 分析。"));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Upload failed", "上传失败")); }
    finally { setBusy(""); }
  }
  async function analyze(resume: ResumeItem) {
    setBusy("analyze"); setError(""); setNotice(""); setAnalysis(null);
    try {
      const task = await api<AnalysisTask>(`/api/resumes/${resume.id}/analysis`, { method: "POST" });
      if (task.status === "COMPLETED" && task.analysisId) setAnalysis(await api<Analysis>(`/api/resumes/${resume.id}/analysis`));
      else { window.localStorage.setItem("ardor:background-pending:resumes", "1"); setNotice(t("Ardor is analyzing this resume in the background. You can leave this page; the report will appear when ready.", "Ardor 正在后台分析这份简历。你可以离开页面，完成后报告会自动出现。")); }
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Analysis failed", "分析失败")); }
    finally { setBusy(""); }
  }
  function chooseFile(event: ChangeEvent<HTMLInputElement>) { setFileName(event.target.files?.[0]?.name ?? ""); }
  function openResume(resume: ResumeItem) { setSelectedId(resume.id); setAnalysis(null); setError(""); setNotice(""); }

  if (selected) {
    const data = analysis?.analysis ?? {}, scores = reportScores(data, t);
    const strengths = textList(data.strengths), weaknesses = textList(data.weaknesses), roles = textList(data.possibleTargetRoles), skills = textList(data.skills);
    const recommendations = textList(data.recommendations);
    const generatedRecommendations = [
      ...weaknesses.map((item) => t(`Issue: ${item}`, `问题：${item}`)),
      ...(recommendations.length > 0 ? recommendations.map((item) => t(`Suggestion: ${item}`, `建议：${item}`)) : weaknesses.map((item) => t(`Suggestion: add concrete actions, measurable outcomes or verifiable evidence for “${item}”.`, `建议：围绕“${item}”补充具体行动、量化结果或可验证证据。`))),
    ];
    const sections = [
      { key: "experience", title: t("Experience", "经历脉络"), eyebrow: "EXPERIENCE" },
      { key: "projects", title: t("Project highlights", "项目亮点"), eyebrow: "PROJECTS" },
      { key: "education", title: t("Education", "教育背景"), eyebrow: "EDUCATION" },
    ].map((section) => ({ ...section, items: textList(data[section.key]) })).filter((section) => section.items.length > 0);
    return <main className="ardor-workbench min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-6xl px-5 pb-24 pt-6 md:px-10">
      <button onClick={() => { setSelectedId(null); setAnalysis(null); setError(""); }} className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 transition hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("All resumes", "所有简历")}</button>
      <header className="pb-12 pt-10 md:pb-16 md:pt-16"><p className="text-sm font-medium text-violet-600">RESUME REPORT</p><h1 className="mt-4 text-4xl font-semibold tracking-[-0.045em] md:text-6xl">{t("Resume report", "简历报告")}</h1><p className="mt-4 max-w-2xl truncate text-sm text-stone-500">{selected.originalFilename}</p></header>
      {(error || notice) && <div className={`mb-8 rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-blue-50 text-blue-700"}`}>{error || notice}</div>}
      {!analysis ? <section className="overflow-hidden rounded-[2.25rem] bg-white p-7 shadow-[0_20px_80px_rgba(0,0,0,0.06)] md:p-12"><div className="mx-auto max-w-2xl py-10 text-center"><span className="mx-auto grid size-16 place-items-center rounded-2xl bg-gradient-to-br from-violet-500 to-blue-500 text-white shadow-lg shadow-violet-200"><BrainCircuit className="size-7" /></span><h2 className="mt-7 text-3xl font-semibold tracking-tight">{selected.analysisStatus === "QUEUED" || selected.analysisStatus === "RUNNING" || selected.analysisId ? t("Generating report", "正在生成报告") : t("Create a detailed report", "生成深度报告")}</h2><p className="mt-3 text-sm text-stone-500">{t("Score · strengths · suggestions", "评分 · 优势 · 建议")}</p>{selected.analysisStatus === "QUEUED" || selected.analysisStatus === "RUNNING" || selected.analysisId ? <div className="mx-auto mt-8 h-1.5 max-w-sm overflow-hidden rounded-full bg-stone-100"><div className="h-full w-2/3 animate-pulse rounded-full bg-gradient-to-r from-violet-500 to-blue-500" /></div> : <button onClick={() => analyze(selected)} disabled={selected.parseStatus !== "PARSED" || busy === "analyze"} className="mt-8 rounded-full bg-[#1d1d1f] px-7 py-3.5 text-sm font-medium text-white transition hover:scale-[1.02] hover:bg-black disabled:opacity-40">{busy === "analyze" ? t("Submitting…", "提交中…") : selected.analysisStatus === "FAILED" ? t("Try again", "重新分析") : t("Analyze", "开始分析")}</button>}</div></section> : <div className="space-y-6">
        <section className="grid overflow-hidden rounded-[2.5rem] bg-[#101014] text-white shadow-[0_28px_100px_rgba(0,0,0,0.16)] lg:grid-cols-[0.8fr_1.2fr]"><div className="grid place-items-center p-9 md:p-14"><div className="relative grid size-52 place-items-center rounded-full" style={{ background: `conic-gradient(#a78bfa ${scores.overall * 3.6}deg, #27272a 0deg)` }}><div className="grid size-[11.5rem] place-items-center rounded-full bg-[#101014] text-center"><div><strong className="text-6xl font-semibold tracking-[-0.06em]">{scores.overall}</strong><p className="mt-2 text-xs uppercase tracking-[0.2em] text-zinc-400">{t("Overall score", "综合评分")}</p></div></div></div>{scores.estimated && <p className="mt-5 text-xs text-zinc-500">{t("Estimated from an older report", "旧版报告按现有内容完整度补算")}</p>}</div><div className="border-t border-white/10 p-8 md:p-12 lg:border-l lg:border-t-0"><p className="text-xs font-medium uppercase tracking-[0.2em] text-violet-300">{t("Executive summary", "报告摘要")}</p><h2 className="mt-4 text-3xl font-semibold tracking-tight md:text-4xl">{typeof data.summary === "string" ? data.summary : strengths[0] ?? t("Your resume has a clear professional foundation.", "你的简历已经具备清晰的职业基础。")}</h2><div className="mt-9 grid gap-3 sm:grid-cols-2">{scores.cards.map((card) => <div key={card.label} className="rounded-2xl bg-white/[0.07] p-4"><div className="flex items-end justify-between"><span className="text-sm text-zinc-300">{card.label}</span><strong className="text-2xl">{card.value}</strong></div><div className="mt-3 h-1 overflow-hidden rounded-full bg-white/10"><div className="h-full rounded-full bg-gradient-to-r from-violet-400 to-blue-400" style={{ width: `${card.value}%` }} /></div><p className="mt-2 text-xs text-zinc-500">{card.note}</p></div>)}</div></div></section>
        <section className="grid gap-6 lg:grid-cols-2"><ReportPanel tone="emerald" icon={<Check className="size-5" />} eyebrow="WHAT WORKS" title={t("Your strongest points", "最有竞争力的部分")} items={strengths} empty={t("No clear strengths listed in the report.", "报告中暂无明确优势。")}/><ReportPanel tone="orange" icon={<TrendingUp className="size-5" />} eyebrow="NEXT LEVEL" title={t("How to improve", "下一步如何提升")} items={generatedRecommendations} empty={t("No specific improvements suggested yet.", "当前没有明显短板建议。")}/></section>
        {sections.map((section) => <section key={section.key} className="rounded-[2rem] bg-white p-7 shadow-[0_16px_60px_rgba(0,0,0,0.05)] md:p-10"><p className="text-xs font-semibold tracking-[0.18em] text-stone-400">{section.eyebrow}</p><h2 className="mt-3 text-3xl font-semibold tracking-tight">{section.title}</h2><div className="mt-7 grid gap-3 md:grid-cols-2">{section.items.map((item, index) => <div key={`${item}-${index}`} className="rounded-2xl border border-stone-100 bg-[#fafafa] p-5 text-sm leading-7 text-stone-700">{item}</div>)}</div></section>)}
        <section className="grid gap-6 lg:grid-cols-[1.2fr_0.8fr]"><div className="rounded-[2rem] bg-white p-7 shadow-[0_16px_60px_rgba(0,0,0,0.05)] md:p-9"><div className="flex items-center gap-2 text-violet-600"><Sparkles className="size-5" /><h2 className="text-xl font-semibold">{t("Skills", "技能画像")}</h2></div><div className="mt-6 flex flex-wrap gap-2">{skills.map((skill) => <span key={skill} className="rounded-full bg-violet-50 px-4 py-2 text-sm text-violet-700">{skill}</span>)}</div></div><div className="rounded-[2rem] bg-gradient-to-br from-blue-600 to-violet-600 p-7 text-white shadow-[0_20px_70px_rgba(82,82,220,0.25)] md:p-9"><Target className="size-6" /><h2 className="mt-5 text-2xl font-semibold">{t("Suggested directions", "推荐方向")}</h2><div className="mt-5 space-y-2">{roles.map((role) => <p key={role} className="rounded-xl bg-white/10 px-4 py-3 text-sm">{role}</p>)}</div><Link href={`/app/interviews?analysisId=${analysis.id}`} className="mt-7 inline-flex items-center gap-2 rounded-full bg-white px-5 py-3 text-sm font-medium text-blue-700">{t("Practice an interview with this report", "用这份报告模拟面试")}<ArrowRight className="size-4" /></Link></div></section>
      </div>}
    </div></main>;
  }

  return <main className="ardor-workbench min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-6xl px-5 pb-24 pt-6 md:px-10">
    <div className="flex items-center justify-between"><Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 transition hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("Back to Ardor", "返回 Ardor")}</Link><button onClick={() => setShowUpload(true)} className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white transition hover:scale-[1.02] hover:bg-black"><Plus className="size-4" />{t("Upload resume", "上传新简历")}</button></div>
    <header className="pb-14 pt-16 md:pb-20 md:pt-24"><p className="text-sm font-medium text-violet-600">RESUME LIBRARY</p><h1 className="mt-4 text-5xl font-semibold tracking-[-0.055em] md:text-7xl">{t("Make your experience count.", "让经历，更有说服力。")}</h1></header>
    {(error || notice) && <div className={`mb-8 rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-blue-50 text-blue-700"}`}>{error || notice}</div>}
    {showUpload && <section className="mb-10 rounded-[2rem] bg-white p-6 shadow-[0_18px_70px_rgba(0,0,0,0.07)] md:p-8"><div className="flex items-center justify-between"><h2 className="text-2xl font-semibold">{t("Upload resume", "上传简历")}</h2><button onClick={() => setShowUpload(false)} aria-label={t("Close", "关闭")} className="rounded-full p-2 text-stone-400 hover:bg-stone-100"><X className="size-5" /></button></div><form onSubmit={upload} className="mt-6 flex flex-col gap-3 sm:flex-row"><label className="flex min-h-14 flex-1 cursor-pointer items-center gap-3 rounded-2xl border border-dashed border-stone-300 bg-[#fafafa] px-5 text-sm text-stone-500 transition hover:border-violet-400 hover:bg-violet-50/40"><Upload className="size-5 text-violet-500" /><span className="truncate">{fileName || t("PDF or DOCX · up to 10 MB", "PDF 或 DOCX · 10 MB 内")}</span><input type="file" name="file" className="hidden" accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" required onChange={chooseFile} /></label><button disabled={busy === "upload"} className="min-h-14 rounded-2xl bg-violet-600 px-7 text-sm font-medium text-white hover:bg-violet-700 disabled:opacity-50">{busy === "upload" ? t("Parsing…", "解析中…") : t("Upload", "上传")}</button></form></section>}
    {resumes.length === 0 ? <section className="rounded-[2.5rem] bg-white px-6 py-20 text-center shadow-[0_20px_80px_rgba(0,0,0,0.05)]"><span className="mx-auto grid size-16 place-items-center rounded-2xl bg-stone-100 text-stone-500"><FileText className="size-7" /></span><h2 className="mt-6 text-2xl font-semibold">{t("No resumes yet", "还没有简历")}</h2><button onClick={() => setShowUpload(true)} className="mt-7 rounded-full bg-[#1d1d1f] px-6 py-3 text-sm font-medium text-white">{t("Upload", "上传")}</button></section> : <section><div className="mb-6 flex items-end justify-between"><div><h2 className="text-2xl font-semibold">{t("My resumes", "我的简历")}</h2><p className="mt-1 text-sm text-stone-500">{t(`${resumes.length} resumes`, `${resumes.length} 份`)}</p></div></div><div className="grid gap-5 md:grid-cols-2 lg:grid-cols-3">{resumes.map((resume) => { const status = statusMeta(resume, t); return <button key={resume.id} onClick={() => openResume(resume)} className="group min-h-72 rounded-[2rem] bg-white p-6 text-left shadow-[0_12px_50px_rgba(0,0,0,0.045)] transition duration-300 hover:-translate-y-1 hover:shadow-[0_22px_70px_rgba(0,0,0,0.09)]"><div className="flex items-start justify-between"><span className="grid size-12 place-items-center rounded-2xl bg-gradient-to-br from-violet-100 to-blue-100 text-violet-600"><FileText className="size-5" /></span><span className={`rounded-full px-3 py-1 text-[11px] font-medium ${status.className}`}>{status.label}</span></div><h3 className="mt-8 line-clamp-2 text-xl font-semibold leading-7 tracking-tight">{resume.originalFilename}</h3><p className="mt-3 text-sm text-stone-400">{new Intl.DateTimeFormat(locale === "en" ? "en-US" : "zh-CN", { year: "numeric", month: "short", day: "numeric" }).format(new Date(resume.createdAt))} · {(resume.sizeBytes / 1024).toFixed(0)} KB</p><div className="mt-10 flex items-center justify-between border-t border-stone-100 pt-5 text-sm font-medium text-stone-600"><span>{resume.analysisId ? t("View report", "查看报告") : t("Open", "打开")}</span><ChevronRight className="size-4 transition group-hover:translate-x-1" /></div></button>; })}</div></section>}
  </div></main>;
}

function ReportPanel({ tone, icon, eyebrow, title, items, empty }: { tone: "emerald" | "orange"; icon: React.ReactNode; eyebrow: string; title: string; items: string[]; empty: string }) {
  const style = tone === "emerald" ? { accent: "text-emerald-600", card: "bg-emerald-50/70 text-emerald-950" } : { accent: "text-orange-600", card: "bg-orange-50/70 text-orange-950" };
  return <div className="rounded-[2rem] bg-white p-7 shadow-[0_16px_60px_rgba(0,0,0,0.05)] md:p-9"><div className={`flex items-center gap-3 ${style.accent}`}>{icon}<span className="text-xs font-semibold tracking-[0.18em]">{eyebrow}</span></div><h2 className="mt-5 text-2xl font-semibold">{title}</h2><div className="mt-6 space-y-3">{items.map((item, index) => <div key={`${item}-${index}`} className={`rounded-2xl p-4 text-sm leading-6 ${style.card}`}>{item}</div>)}{items.length === 0 && <p className="text-sm text-stone-400">{empty}</p>}</div></div>;
}
