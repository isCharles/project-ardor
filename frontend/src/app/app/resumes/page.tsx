"use client";

import { ArrowLeft, ArrowRight, ArrowUpRight, FileText, Plus, Trash2, Upload, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChangeEvent, FormEvent, useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { markBackgroundPending } from "@/lib/background-notifications";
import { useReadVisibleResult } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";
import { ResumeReportV3, Radar, type IssueSeverity, type RadarGrade, type ResumeReportV3Data } from "@/components/ardor/resume-report-v3";
import { ResumePreviewPanel } from "@/components/ardor/resume-preview-drawer";
import { AppDialog } from "@/components/ui/app-dialog";

type ResumeItem = {
  id: string; originalFilename: string; sizeBytes: number;
  parseStatus: "PENDING" | "PARSED" | "FAILED"; createdAt: string; analysisId: string | null;
  analysisStatus: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | null; analysisError: string | null;
  overallScore?: number | null; primary?: boolean; displayAnalysisId?: string | null;
};
type Analysis = { id: string; resumeId: string; analysis: Record<string, unknown>; modelName: string; createdAt?: string };
type AnalysisTask = { jobId: string | null; status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED"; analysisId: string | null };
type Evaluation = { id: string; label: string };
type InterviewOption = { id: string; company: string; targetRole?: string | null; jdContent?: string | null };
const DIMENSION_ORDER = ["technicalDepth", "outcomes", "ownership", "clarity", "roleFit", "background"] as const;

function bandFor(score: number, t: (en: string, zh: string) => string): string {
  if (score >= 85) return t("Top tier", "顶尖");
  if (score >= 70) return t("Competitive", "有竞争力");
  if (score >= 55) return t("Below average", "中下");
  if (score >= 40) return t("Draft", "初稿");
  return t("High risk", "高风险");
}

const evaluationDate = (value: string) => new Intl.DateTimeFormat(undefined, { month: "short", day: "numeric" }).format(new Date(value));

/** Each job description keeps its own report, so the chip has to say which one it answered. */
function evaluationOf(item: Analysis, t: (en: string, zh: string) => string, date: (value: string) => string): Evaluation {
  const context = (item.analysis?.scoringContext ?? {}) as Record<string, unknown>;
  const role = typeof context.targetRole === "string" ? context.targetRole.trim() : "";
  const family = typeof context.roleFamily === "string" ? context.roleFamily : "";
  const subject = role || (family ? familyLabel(family, t) : "") || t("General", "通用");
  return { id: item.id, label: item.createdAt ? `${subject} · ${date(item.createdAt)}` : subject };
}

const SCORING_FAMILIES = [
  { id: "general", en: "General technology", zh: "通用技术" },
  { id: "engineering", en: "Software engineering", zh: "开发" },
  { id: "algorithm", en: "Algorithm / AI", zh: "算法" },
  { id: "data", en: "Data", zh: "数据" },
  { id: "product", en: "Product", zh: "产品" },
  { id: "sales", en: "Sales / business", zh: "销售" },
  { id: "management", en: "Management / delivery", zh: "管理" },
] as const;

function familyLabel(id: string, t: (en: string, zh: string) => string) {
  const family = SCORING_FAMILIES.find(item => item.id === id) ?? SCORING_FAMILIES[0];
  return t(family.en, family.zh);
}
function cleanIssueText(text: string): string {
  return text.replace(/^(\*\*)?(100\s*分标准|顶级标准|满分标准|为什么是问题|怎么改|修改建议|建议)(\*\*)?\s*[:：]\s*/, "")
    .replace(/100\s*分标准|满分标准|顶级标准/g, "目标岗位要求").trim();
}

function buildReport(resume: ResumeItem, data: Record<string, unknown>, t: (en: string, zh: string) => string): ResumeReportV3Data {
  const context = data.scoringContext && typeof data.scoringContext === "object" ? data.scoringContext as Record<string, unknown> : {};
  const grades = data.dimensionGrades && typeof data.dimensionGrades === "object" ? data.dimensionGrades as Record<string, unknown> : {};
  const benchmarks = data.dimensionBenchmarks && typeof data.dimensionBenchmarks === "object" ? data.dimensionBenchmarks as Record<string, unknown> : {};
  const score = typeof data.overallScore === "number" ? Math.round(data.overallScore) : 0;
  const issues = Array.isArray(data.issues) ? data.issues.filter((value): value is Record<string, unknown> => !!value && typeof value === "object") : [];
  const strengths = Array.isArray(data.strengths) ? data.strengths.filter((value): value is string => typeof value === "string") : [];
  return {
    filename: resume.originalFilename,
    general: context.mode !== "USER_JD",
    condition: (context.mode === "USER_JD" ? `${familyLabel(String(data.roleFamily ?? context.roleFamily ?? "general"), t)} · ${typeof context.targetRole === "string" && context.targetRole ? context.targetRole : t("With JD", "含 JD")}` : `${familyLabel(String(data.roleFamily ?? context.roleFamily ?? "general"), t)} · ${t("Generalised", "通用化")}`),
    score,
    band: bandFor(score, t),
    summary: typeof data.summary === "string" ? data.summary : "",
    dimensions: DIMENSION_ORDER.map(id => ({ id, grade: String(grades[id] ?? "F") as RadarGrade, marketGrade: String(benchmarks[id] ?? "B") as RadarGrade })),
    background: typeof data.background === "string" ? data.background : "",
    strengths: strengths.slice(0, 2),
    issues: issues.map(item => ({
      severity: String(item.severity ?? "C") as IssueSeverity,
      category: String(item.category ?? ""),
      problem: String(item.problem ?? ""),
      why: cleanIssueText(String(item.why ?? "")),
      action: cleanIssueText(String(item.action ?? "")),
      benchmark: typeof item.benchmark === "string" && item.benchmark ? cleanIssueText(item.benchmark) : undefined,
      evidence: typeof item.evidence === "string" && item.evidence ? item.evidence : undefined,
      example: typeof item.example === "string" && item.example ? item.example : undefined,
    })),
  };
}function statusMeta(resume: ResumeItem, t: (en: string, zh: string) => string) {
  if (resume.analysisStatus === "QUEUED") return { label: t("Queued", "等待分析"), className: "bg-blue-50 text-blue-700" };
  if (resume.analysisStatus === "RUNNING") return { label: t("Analyzing", "正在分析"), className: "bg-violet-50 text-violet-700" };
  if (resume.analysisStatus === "FAILED") return { label: t("Analysis failed", "分析失败"), className: "bg-red-50 text-red-700" };
  if (resume.analysisId) return { label: t("Report ready", "报告已完成"), className: "bg-emerald-50 text-emerald-700" };
  if (resume.parseStatus === "FAILED") return { label: t("Parsing failed", "解析失败"), className: "bg-red-50 text-red-700" };
  return { label: t("Ready to analyze", "可以分析"), className: "bg-stone-100 text-stone-600" };
}

export default function ResumesPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const [resumes, setResumes] = useState<ResumeItem[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [currentReport, setCurrentReport] = useState<ResumeReportV3Data | null>(null);
  const [showUpload, setShowUpload] = useState(false);
  const [reanalyzeOpen, setReanalyzeOpen] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [previewWidth, setPreviewWidth] = useState(520);
  const [deleteTarget, setDeleteTarget] = useState<ResumeItem | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState("");
  const [fileName, setFileName] = useState("");
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [evaluations, setEvaluations] = useState<Evaluation[]>([]);
  const [pickedId, setPickedId] = useState<string | null>(null);
  const [pendingJobId, setPendingJobId] = useState("");
  const [evaluationRevision, setEvaluationRevision] = useState(0);
  const selected = resumes.find((resume) => resume.id === selectedId) ?? null;
  useReadVisibleResult("RESUME_ANALYSIS",analysis?.resumeId,!!analysis,analysis?.id);

  const load = useCallback(async () => {
    try { setResumes(await api<ResumeItem[]>("/api/resumes")); }
    catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
      setError(reason instanceof Error ? reason.message : t("Could not load resumes", "无法加载简历"));
    }
  }, [router, t]);
  useEffect(() => {
    let active = true;
    api<ResumeItem[]>("/api/resumes").then((items) => { if (active) {
      setResumes(items);
      const requested = new URLSearchParams(window.location.search).get("selected");
      if (requested && items.some(item => item.id===requested)) setSelectedId(requested);
    } }).catch((reason) => {
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
  const openResumeId = selected?.id;
  // One effect owns the report the page shows: the evaluation the reader picked, otherwise the newest on this resume.
  useEffect(() => {
    const analysisId = pickedId ?? selected?.analysisId;
    if (!openResumeId || !analysisId) return;
    let active = true;
    api<Analysis>(`/api/resumes/${openResumeId}/analysis${pickedId ? `?analysisId=${pickedId}` : ""}`)
      .then((result) => { if (active && result.resumeId === openResumeId) setAnalysis(result); })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : t("Could not load report", "无法载入报告")); });
    return () => { active = false; };
  }, [openResumeId, pickedId, selected?.analysisId, t]);
  useEffect(() => {
    if (!openResumeId) return;
    let active = true;
    api<Analysis[]>(`/api/resumes/${openResumeId}/analysis/history`)
      .then((items) => { if (active) setEvaluations(items.map((item) => evaluationOf(item, t, evaluationDate))); })
      .catch(() => { if (active) setEvaluations([]); });
    return () => { active = false; };
  }, [openResumeId, selected?.analysisId, evaluationRevision, t]);
  // One job per job description: poll the job this page actually started, not the newest one.
  useEffect(() => {
    if (!pendingJobId || !openResumeId) return;
    let active = true;
    const timer = window.setInterval(() => {
      void api<AnalysisTask>(`/api/resumes/${openResumeId}/analysis/status?jobId=${pendingJobId}`)
        .then((task) => {
          if (!active || task.status === "QUEUED" || task.status === "RUNNING") return;
          setPendingJobId("");
          if (task.status === "COMPLETED" && task.analysisId) setPickedId(task.analysisId);
          setEvaluationRevision((value) => value + 1);
        })
        .catch(() => { if (active) setPendingJobId(""); });
    }, 2500);
    return () => { window.clearInterval(timer); active = false; };
  }, [pendingJobId, openResumeId]);

  useEffect(() => {
    // The primary resume is the one the workbench headlines; newest upload only
    // wins when the user has not chosen a primary yet.
    const ordered = [...resumes].sort((left, right) => (right.createdAt ?? "").localeCompare(left.createdAt ?? ""));
    const current = resumes.find((resume) => resume.primary) ?? ordered[0];
    // eslint-disable-next-line react-hooks/set-state-in-effect
    if (!current?.analysisId) { setCurrentReport(null); return; }
    let active = true;
    api<Analysis>(`/api/resumes/${current.id}/analysis`)
      .then((result) => {
        if (!active) return;
        const data = result.analysis;
        setCurrentReport(data?.rubricVersion === "ardor-resume-rubric-v3" ? buildReport(current, data, t) : null);
      })
      .catch(() => { if (active) setCurrentReport(null); });
    return () => { active = false; };
  }, [resumes, t]);

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
  async function analyze(resume: ResumeItem, role = "", jd = "", family = "general") {
    setBusy("analyze"); setError(""); setNotice("");
    try {
      const task = await api<AnalysisTask>(`/api/resumes/${resume.id}/analysis`, { method: "POST", body: JSON.stringify({ targetRole: role, jobDescription: jd, roleFamily: family }) });
      if (task.status === "COMPLETED" && task.analysisId) setPickedId(task.analysisId);
      else { markBackgroundPending("resumes"); setPendingJobId(task.jobId ?? ""); setNotice(t("Ardor is analyzing this resume in the background. You can leave this page; the report will appear when ready.", "Ardor 正在后台分析这份简历。你可以离开页面，完成后报告会自动出现。")); }
      await load();
      setEvaluationRevision((value) => value + 1);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Analysis failed", "分析失败")); }
    finally { setBusy(""); }
  }
  function chooseFile(event: ChangeEvent<HTMLInputElement>) { setFileName(event.target.files?.[0]?.name ?? ""); }
  function openResume(resume: ResumeItem) { setSelectedId(resume.id); setPickedId(null); setAnalysis(null); setError(""); setNotice(""); }
  function viewOriginalResume() { setPreviewOpen(true); }
  async function deleteResume() {
    if (!deleteTarget || deleting) return;
    setDeleting(true);
    setDeleteError("");
    try {
      await api<void>(`/api/resumes/${deleteTarget.id}`, { method: "DELETE" });
      if (selectedId === deleteTarget.id) { setSelectedId(null); setPickedId(null); setAnalysis(null); setPreviewOpen(false); }
      setDeleteTarget(null);
      await load();
      setNotice(t("Resume deleted.", "简历已删除。"));
    } catch (reason) {
      setDeleteError(reason instanceof Error ? reason.message : t("Could not delete this resume", "无法删除这份简历"));
    } finally {
      setDeleting(false);
    }
  }

  if (selected) {
    const data = analysis?.analysis;
    const isV3 = !!data && data.rubricVersion === "ardor-resume-rubric-v3";
    const report = isV3 ? buildReport(selected, data, t) : null;
    const legacy = !!data && !isV3;
    const pending = selected.analysisStatus === "QUEUED" || selected.analysisStatus === "RUNNING";
    const dialog = reanalyzeOpen && <ReanalyzeDialog defaultFamily={typeof data?.roleFamily === "string" ? data.roleFamily : "general"} busy={busy === "analyze"} onClose={() => setReanalyzeOpen(false)} onSubmit={(role, jd, family) => { setReanalyzeOpen(false); void analyze(selected, role, jd, family); }} />;
    // Several job descriptions, several reports: the bar switches between the ones this resume holds.
    const evaluationBar = evaluations.length > 1 && <div className="mb-6 flex flex-wrap items-center gap-2" data-resume-evaluations>
      {evaluations.map((item) => <button key={item.id} type="button" data-evaluation={item.label} data-evaluation-active={analysis?.id === item.id}
        onClick={() => { if (selected) void showReport(selected, item.id); else setPickedId(item.id); }}
        className={`rounded-full px-3 py-1 text-[11px] tabular-nums transition ${analysis?.id === item.id ? "bg-[#1d1d1f] text-white" : "bg-white text-stone-600 hover:bg-stone-100"}`}>{item.label}</button>)}
    </div>;
    if (report) return <main className="ardor-workbench min-h-screen text-[#1d1d1f]">
      <div style={{ paddingRight: previewOpen ? previewWidth : 0 }}>
        {evaluationBar}
        <ResumeReportV3 report={report} resumeId={selected.id} analysisId={analysis?.id} onBack={() => { setSelectedId(null); setPickedId(null); setAnalysis(null); }} onViewResume={viewOriginalResume} onReanalyze={pending ? undefined : () => setReanalyzeOpen(true)} />
      </div>
      {previewOpen && <ResumePreviewPanel resumeId={selected.id} filename={selected.originalFilename} width={previewWidth} onWidthChange={setPreviewWidth} onClose={() => setPreviewOpen(false)} />}
      {dialog}
    </main>;
    return <main className="ardor-workbench min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-3xl px-5 pb-24 pt-6 md:px-10">
      <button onClick={() => { setSelectedId(null); setPickedId(null); setAnalysis(null); setError(""); }} className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 transition hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("All resumes", "所有简历")}</button>
      <header className="pb-8 pt-8">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">RESUME REPORT</p>
        <h1 className="mt-2 flex items-center gap-3 truncate text-3xl font-semibold tracking-tight">{selected.originalFilename}{selected.primary && <span className="shrink-0 rounded-full bg-violet-50 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.08em] text-violet-700">{t("Primary", "主简历")}</span>}</h1>
      </header>
      {(error || notice) && <div className={`mb-6 rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-blue-50 text-blue-700"}`}>{error || notice}</div>}
      {selected.analysisError && <p role="status" className="mb-4 rounded-xl bg-red-50 p-4 text-sm text-red-700">{selected.analysisError}</p>}
      {evaluationBar}
      {legacy ? <section className="rounded-[2rem] bg-white p-7 shadow-[0_18px_60px_rgba(49,39,70,0.07)] md:p-10">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-stone-400">LEGACY REPORT</p>
        <h2 className="mt-3 text-2xl font-semibold">{t("The old scoring is offline", "旧版评分已下线")}</h2>
        <p className="mt-3 text-sm leading-6 text-stone-600">{t("Old reports are no longer rendered. Start a new analysis to get the current score, radar and prioritised issues.", "旧报告不再展示。开始一次新的分析，获得当前评分、能力雷达和分档问题。")}</p>
        <button onClick={() => setReanalyzeOpen(true)} className="mt-6 rounded-full bg-[#1d1d1f] px-6 py-3 text-sm font-medium text-white hover:bg-black">{t("Start analysis", "开始分析")}</button>
      </section> : !analysis ? <section className="rounded-[2rem] bg-white p-7 shadow-[0_18px_60px_rgba(49,39,70,0.07)] md:p-10">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{pending ? "ANALYZING" : "RESUME REPORT"}</p>
        <h2 className="mt-3 text-2xl font-semibold">{pending ? t("Generating report", "正在生成报告") : t("Create a detailed report", "生成深度报告")}</h2>
        <p className="mt-3 text-sm text-stone-500">{pending ? t("Ardor is analyzing this resume in the background.", "Ardor 正在后台分析这份简历。") : t("Score · capability radar · prioritised issues", "评分 · 能力雷达 · 分档问题")}</p>
        {pending ? <div className="mt-6 h-1.5 max-w-sm overflow-hidden rounded-full bg-stone-100"><div className="h-full w-2/3 animate-pulse rounded-full bg-gradient-to-r from-violet-500 to-blue-500" /></div> : <button onClick={() => setReanalyzeOpen(true)} className="mt-6 rounded-full bg-[#1d1d1f] px-6 py-3 text-sm font-medium text-white hover:bg-black">{t("Analyze", "开始分析")}</button>}
      </section> : null}
      {dialog}
    </div></main>;
  }
  const sortedResumes = [...resumes].sort((left, right) => Number(!!right.primary) - Number(!!left.primary)
    || (right.createdAt ?? "").localeCompare(left.createdAt ?? ""));
  async function markPrimary(resume: ResumeItem) {
    setError("");
    try {
      await api<void>(`/api/resumes/${resume.id}/primary`, { method: "POST" });
      await load();
      setNotice(t(`“${resume.originalFilename}” is now the primary resume.`, `已把「${resume.originalFilename}」设为主简历。`));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not set the primary resume", "无法设为主简历")); }
  }
  async function showReport(resume: ResumeItem, analysisId: string) {
    setError("");
    setPickedId(analysisId);
    try {
      await api<void>(`/api/resumes/${resume.id}/display-analysis`, { method: "POST", body: JSON.stringify({ analysisId }) });
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not remember this report", "无法记住这份报告")); }
  }
  const resumeDate = (value: string) => new Intl.DateTimeFormat(locale === "en" ? "en-US" : "zh-CN", { year: "numeric", month: "short", day: "numeric" }).format(new Date(value));
  const resumeCard = (resume: ResumeItem, primary: boolean) => {
    const status = statusMeta(resume, t);
    const content = <>
      <span className={primary ? "grid size-14 shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-violet-100 to-blue-100 text-violet-600" : "grid size-12 place-items-center rounded-2xl bg-gradient-to-br from-violet-100 to-blue-100 text-violet-600"}><FileText className={primary ? "size-6" : "size-5"} /></span>
      <div className={primary ? "min-w-0 flex-1" : "mt-6"}>
        <div className="flex items-start justify-between gap-3">
          <h3 className={primary ? "line-clamp-2 text-2xl font-semibold leading-8 tracking-tight" : "line-clamp-2 text-lg font-semibold leading-6 tracking-tight"}>{resume.originalFilename}</h3>
          <div className="flex shrink-0 items-center gap-2">
            {resume.primary && <span className="rounded-full bg-violet-50 px-2.5 py-1 text-[10px] font-semibold uppercase tracking-[0.08em] text-violet-700">{t("Primary", "主简历")}</span>}
            {!(primary && currentReport) && (typeof resume.overallScore === "number"
              ? <span className="rounded-full bg-[#1d1d1f] px-3 py-1 text-[11px] font-semibold tabular-nums text-white">{resume.overallScore}</span>
              : <span className={`rounded-full px-3 py-1 text-[11px] font-medium ${status.className}`}>{status.label}</span>)}
          </div>
        </div>
        <p className="mt-2 text-sm text-stone-400">{resumeDate(resume.createdAt)} · {(resume.sizeBytes / 1024).toFixed(0)} KB</p>
      </div>
    </>;
    return (
      <div key={resume.id} className="group relative">
        <button onClick={() => openResume(resume)} className={primary ? "w-full rounded-[2rem] bg-white p-7 text-left shadow-[0_18px_60px_rgba(49,39,70,0.07)] transition hover:-translate-y-0.5 hover:shadow-[0_24px_70px_rgba(49,39,70,0.10)]" : "rounded-[2rem] bg-white p-6 text-left shadow-[0_12px_50px_rgba(0,0,0,0.045)] transition duration-300 hover:-translate-y-1 hover:shadow-[0_22px_70px_rgba(0,0,0,0.09)]"}>
          {primary ? (currentReport ? (
            <div className="grid gap-6 lg:grid-cols-2">
              <div className="flex min-w-0 flex-col">
                <div className="flex items-start gap-4 pr-8">
                  <span className="grid size-14 shrink-0 place-items-center rounded-2xl bg-gradient-to-br from-violet-100 to-blue-100 text-violet-600"><FileText className="size-6" /></span>
                  <div className="min-w-0">
                    <h3 className="line-clamp-2 text-2xl font-semibold leading-8 tracking-tight">{resume.originalFilename}</h3>
                    <p className="mt-2 text-sm text-stone-400">{resumeDate(resume.createdAt)} · {(resume.sizeBytes / 1024).toFixed(0)} KB</p>
                  </div>
                </div>
                <div className="mt-5 border-t border-stone-100 pt-4">
                  <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-violet-600">{t("Competitiveness", "当前竞争力")}</p>
                  <div className="mt-2 flex flex-wrap items-end gap-x-3 gap-y-1">
                    <span className="text-5xl font-semibold leading-none tracking-[-0.05em]">{currentReport.score}</span>
                    <span className="pb-1 text-xs text-stone-400">/ 100</span>
                    <span className="mb-1 rounded-full bg-violet-100 px-2.5 py-1 text-xs font-medium text-violet-800">{currentReport.band}</span>
                  </div>
                  <p className="mt-2 text-xs text-stone-400">{currentReport.condition}</p>
                  <div className="mt-4">
                    <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-violet-600">{t("Diagnosis", "简历诊断")}</p>
                    <p className="mt-1.5 line-clamp-3 text-sm leading-6 text-stone-600">{currentReport.summary.replace(/\*\*/g, "").replace(/`/g, "")}</p>
                    {currentReport.background && <p className="mt-2 line-clamp-3 text-xs leading-5 text-stone-500">{currentReport.background.replace(/\*\*/g, "").replace(/`/g, "")}</p>}
                  </div>
                  <div className="mt-4 flex flex-wrap gap-2 text-[10px]">
                    {(["A", "B", "C"] as const).map(severity => {
                      const count = currentReport.issues.filter(issue => issue.severity === severity).length;
                      return <span key={severity} className={`rounded-full px-2 py-0.5 font-medium ${severity === "A" ? "bg-rose-50 text-rose-700" : severity === "B" ? "bg-amber-50 text-amber-700" : "bg-sky-50 text-sky-700"}`}>{severity} · {count}</span>;
                    })}
                  </div>
                </div>
                <div className="mt-auto flex flex-wrap gap-3 pt-5 text-[10px] text-stone-400">
                  <span className="inline-flex items-center gap-1.5"><span className="size-1.5 rounded-full bg-violet-600" />{t("You", "你")}</span>
                  <span className="inline-flex items-center gap-1.5"><span className="size-1.5 rounded-full border border-stone-400 bg-stone-200" />{t("Market requirement", "市场要求")}</span>
                </div>
              </div>
              <div className="relative flex min-h-[320px] flex-col justify-center">
                <p className="absolute left-0 top-0 text-[10px] font-semibold uppercase tracking-[0.14em] text-violet-600">{t("Capability radar", "能力雷达")}</p>
                <div className="flex flex-1 items-center justify-center pt-5"><Radar dimensions={currentReport.dimensions} /></div>
              </div>
            </div>
          ) : <div className="flex flex-col gap-5 pr-8 md:flex-row md:items-start">{content}</div>) : content}
        </button>
        {primary && <span aria-hidden="true" className="pointer-events-none absolute right-5 top-5 text-violet-500"><ArrowUpRight className="size-5" /></span>}
        <button type="button" onClick={() => { setDeleteTarget(resume); setDeleteError(""); }} aria-label={t("Delete resume", "删除简历")} title={t("Delete resume", "删除简历")} className="absolute bottom-5 right-5 grid size-8 place-items-center rounded-full text-stone-300 opacity-0 transition hover:bg-red-50 hover:text-red-600 focus-visible:opacity-100 group-hover:opacity-100"><Trash2 className="size-4" /></button>
      </div>
    );
  };
  return <main className="ardor-workbench min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-6xl px-5 pb-24 pt-6 md:px-10">
    <header className="flex flex-wrap items-end justify-between gap-5 pb-10 pt-10 md:pb-12 md:pt-14">
      <div><p className="text-sm font-medium text-violet-600">RESUME</p><h1 className="mt-3 text-5xl font-semibold tracking-[-0.055em] md:text-6xl">{t("One resume, iterated well.", "把一份简历打磨好。")}</h1></div>
      <div className="flex flex-wrap items-center gap-2">
        <Link href="/app/latex" className="inline-flex items-center gap-2 rounded-full border border-stone-200 bg-white px-5 py-2.5 text-sm font-medium text-stone-700 transition hover:border-stone-300 hover:bg-stone-50"><FileText className="size-4" />{t("Write resume", "写简历")}</Link>
        <button onClick={() => setShowUpload(true)} className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white transition hover:scale-[1.02] hover:bg-black"><Plus className="size-4" />{t("Upload resume", "上传新简历")}</button>
      </div>
    </header>
    {(error || notice) && <div className={`mb-8 rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-blue-50 text-blue-700"}`}>{error || notice}</div>}
    {showUpload && <section className="mb-10 rounded-[2rem] bg-white p-6 shadow-[0_18px_70px_rgba(0,0,0,0.07)] md:p-8"><div className="flex items-center justify-between"><h2 className="text-2xl font-semibold">{t("Upload resume", "上传简历")}</h2><button onClick={() => setShowUpload(false)} aria-label={t("Close", "关闭")} className="rounded-full p-2 text-stone-400 hover:bg-stone-100"><X className="size-5" /></button></div><form onSubmit={upload} className="mt-6 flex flex-col gap-3 sm:flex-row"><label className="flex min-h-14 flex-1 cursor-pointer items-center gap-3 rounded-2xl border border-dashed border-stone-300 bg-[#fafafa] px-5 text-sm text-stone-500 transition hover:border-violet-400 hover:bg-violet-50/40"><Upload className="size-5 text-violet-500" /><span className="truncate">{fileName || t("PDF or DOCX · up to 10 MB", "PDF 或 DOCX · 10 MB 内")}</span><input type="file" name="file" className="hidden" accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document" required onChange={chooseFile} /></label><button disabled={busy === "upload"} className="min-h-14 rounded-2xl bg-violet-600 px-7 text-sm font-medium text-white hover:bg-violet-700 disabled:opacity-50">{busy === "upload" ? t("Parsing…", "解析中…") : t("Upload", "上传")}</button></form></section>}
    {resumes.length === 0 ? (
      <section className="rounded-[2.5rem] bg-white px-6 py-20 text-center shadow-[0_20px_80px_rgba(0,0,0,0.05)]"><span className="mx-auto grid size-16 place-items-center rounded-2xl bg-stone-100 text-stone-500"><FileText className="size-7" /></span><h2 className="mt-6 text-2xl font-semibold">{t("No resumes yet", "还没有简历")}</h2><button onClick={() => setShowUpload(true)} className="mt-7 rounded-full bg-[#1d1d1f] px-6 py-3 text-sm font-medium text-white">{t("Upload", "上传")}</button></section>
    ) : (
      <section>
        <h2 className="mb-4 text-2xl font-semibold">{t("Primary resume", "主简历")}</h2>
        {resumeCard(sortedResumes[0], true)}
        {sortedResumes.length > 1 && (
          <div className="mt-10">
            <h2 className="mb-3 text-lg font-semibold">{t(`History (${sortedResumes.length - 1})`, `历史版本（${sortedResumes.length - 1}）`)}</h2>
            <div className="divide-y divide-stone-100 overflow-hidden rounded-2xl border border-stone-200/70 bg-white/75">
              {sortedResumes.slice(1).map(resume => {
                const status = statusMeta(resume, t);
                return <div key={resume.id} className="group flex w-full items-center gap-3 px-4 py-3.5 transition hover:bg-violet-50/50">
                  <button onClick={() => openResume(resume)} className="flex min-w-0 flex-1 items-center gap-3 text-left">
                    <FileText className="size-4 shrink-0 text-stone-400" />
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-medium text-stone-800">{resume.originalFilename}</p>
                      <p className="mt-0.5 text-xs text-stone-400">{resumeDate(resume.createdAt)} · {(resume.sizeBytes / 1024).toFixed(0)} KB</p>
                    </div>
                    {typeof resume.overallScore === "number"
                      ? <span className="shrink-0 rounded-full bg-[#1d1d1f] px-2.5 py-1 text-xs font-semibold tabular-nums text-white">{resume.overallScore}</span>
                      : <span className={`hidden shrink-0 rounded-full px-2.5 py-1 text-[11px] font-medium sm:inline ${status.className}`}>{status.label}</span>}
                    <ArrowRight className="size-4 shrink-0 text-stone-300" />
                  </button>
                  <button type="button" onClick={() => void markPrimary(resume)} aria-label={t("Set as primary resume", "设为主简历")} title={t("Set as primary resume", "设为主简历")} className="shrink-0 rounded-full px-2.5 py-1 text-[11px] text-stone-400 opacity-0 transition hover:bg-violet-50 hover:text-violet-700 focus-visible:opacity-100 group-hover:opacity-100">{t("Set primary", "设为主简历")}</button>
                  <button type="button" onClick={() => { setDeleteTarget(resume); setDeleteError(""); }} aria-label={t("Delete resume", "删除简历")} title={t("Delete resume", "删除简历")} className="grid size-8 shrink-0 place-items-center rounded-full text-stone-300 opacity-0 transition hover:bg-red-50 hover:text-red-600 focus-visible:opacity-100 group-hover:opacity-100"><Trash2 className="size-4" /></button>
                </div>;
              })}
            </div>
          </div>
        )}
      </section>
    )}
    {deleteTarget && <AppDialog title={t("Delete resume", "删除简历")} onClose={() => { if (!deleting) { setDeleteTarget(null); setDeleteError(""); } }}>
      <p className="text-sm leading-6 text-stone-600">{t(`Delete “${deleteTarget.originalFilename}” and its analysis reports? This cannot be undone.`, `删除「${deleteTarget.originalFilename}」及其分析报告？此操作无法撤销。`)}</p>
      {deleteError && <p role="alert" className="mt-3 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{deleteError}</p>}
      <div className="mt-6 flex justify-end gap-2">
        <button type="button" disabled={deleting} onClick={() => { setDeleteTarget(null); setDeleteError(""); }} className="rounded-full px-4 py-2 text-sm text-stone-500 hover:bg-stone-100 disabled:opacity-40">{t("Cancel", "取消")}</button>
        <button type="button" disabled={deleting} onClick={() => void deleteResume()} className="rounded-full bg-red-600 px-5 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-40">{deleting ? t("Deleting…", "删除中…") : t("Delete", "删除")}</button>
      </div>
    </AppDialog>}
  </div></main>;
}

function ReanalyzeDialog({ defaultFamily = "general", busy, onClose, onSubmit }: { defaultFamily?: string; busy: boolean; onClose: () => void; onSubmit: (role: string, jd: string, family: string) => void }) {
  const { t } = useLocale();
  const [family, setFamily] = useState(defaultFamily);
  const [role, setRole] = useState("");
  const [jd, setJd] = useState("");
  const [interviews, setInterviews] = useState<InterviewOption[]>([]);
  const [pickedInterview, setPickedInterview] = useState("");
  useEffect(() => {
    let active = true;
    api<InterviewOption[]>("/api/opportunities").then(items => { if (active && Array.isArray(items)) setInterviews(items); }).catch(() => { if (active) setInterviews([]); });
    return () => { active = false; };
  }, []);
  function pickInterview(id: string) {
    setPickedInterview(id);
    const interview = interviews.find(item => item.id === id);
    if (!interview) return;
    const title = interview.targetRole?.trim() || "";
    setRole([interview.company, title].filter(Boolean).join(" · "));
    setJd(interview.jdContent ?? "");
  }
  const general = jd.trim().length === 0;
  return <div className="fixed inset-0 z-50 grid place-items-center bg-black/25 p-4 backdrop-blur-sm" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section role="dialog" aria-modal="true" className="max-h-[92vh] w-full max-w-xl overflow-y-auto rounded-[2rem] border border-stone-200 bg-[#fcfcf9] p-6 shadow-2xl md:p-8">
      <h2 className="text-xl font-semibold">{t("Analyze with a target", "按目标重新评估")}</h2>
      <p className="mt-2 text-sm text-stone-500">{t("With a JD the family is read from the JD. Without one, pick a family; leaving both empty means the general default.", "有 JD 就按 JD 判断目标大类；没有 JD 再自己选大类，两者都不填则按默认通用标准。")}</p>
      <form onSubmit={(event) => { event.preventDefault(); onSubmit(role, jd, general ? family : ""); }} className="mt-5 space-y-3">
        {interviews.length > 0 && <label className="block text-sm">{t("Pick from interview pipeline", "从面试流程选择")}
          <select value={pickedInterview} onChange={(event) => pickInterview(event.target.value)} className="field mt-2">
            <option value="">{t("Manual entry", "手动填写")}</option>
            {interviews.map(interview => <option key={interview.id} value={interview.id}>{interview.company}{interview.targetRole ? ` · ${interview.targetRole}` : ""}</option>)}
          </select>
        </label>}
        {pickedInterview && <p className="rounded-xl bg-emerald-50 px-3 py-2 text-xs text-emerald-800 ring-1 ring-emerald-100">{t("Company, role and JD were filled from the selected interview.", "已从所选面试带入公司、岗位和 JD。")}</p>}
        <label className="block text-sm">{t("Target role (optional)", "目标岗位（可选）")}<input value={role} onChange={(event) => setRole(event.target.value)} maxLength={240} className="field mt-2" /></label>
        <label className="block text-sm">{t("Job description", "岗位要求 JD")}<textarea value={jd} onChange={(event) => setJd(event.target.value)} maxLength={8000} rows={5} className="field mt-2 resize-y" placeholder={t("Paste the JD here for targeted scoring", "粘贴 JD，才能按目标岗位评分")} /></label>
        {general ? <label className="block text-sm">{t("Role family", "目标大类")}
          <select value={family} onChange={(event) => setFamily(event.target.value)} className="field mt-2">
            {SCORING_FAMILIES.map(item => <option key={item.id} value={item.id}>{t(item.en, item.zh)}</option>)}
          </select>
        </label> : <p className="rounded-xl bg-violet-50 px-3 py-2.5 text-xs leading-5 text-violet-900 ring-1 ring-violet-100">{t("The family is inferred from the JD; no extra choice needed.", "目标大类将按 JD 自动判断，不需要再选。")}</p>}
        {general && <p className="rounded-xl bg-amber-50 px-3 py-2.5 text-xs leading-5 text-amber-900 ring-1 ring-amber-200">{t(`No JD: this will be a general assessment under ${familyLabel(family, t)}, and may not be role-specific.`, `未提供 JD：将按「${familyLabel(family, t)}」做通用评估，可能不具岗位针对性。`)}</p>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={onClose} className="rounded-full px-4 py-2 text-sm text-stone-500 hover:bg-stone-100">{t("Cancel", "取消")}</button>
          <button disabled={busy} className="rounded-full bg-[#1d1d1f] px-5 py-2 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Submitting…", "提交中…") : t("Analyze", "开始分析")}</button>
        </div>
      </form>
    </section>
  </div>;
}
