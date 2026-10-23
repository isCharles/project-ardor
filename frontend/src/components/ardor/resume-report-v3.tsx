"use client";

import { ArrowLeft, Download, FileText, Sparkles } from "lucide-react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { useState } from "react";
import { useLocale } from "@/lib/locale";

export type RadarGrade = "S" | "A" | "B" | "C" | "D" | "E" | "F";
export type ResumeDimension = { id: string; grade: RadarGrade; marketGrade: RadarGrade };
export type IssueSeverity = "A" | "B" | "C";
export type ResumeIssue = {
  severity: IssueSeverity;
  category: string;
  problem: string;
  why: string;
  action: string;
  benchmark?: string;
  evidence?: string;
  example?: string;
};
export type ResumeReportV3Data = {
  filename: string;
  condition: string;
  general: boolean;
  score: number;
  band: string;
  summary: string;
  dimensions: ResumeDimension[];
  background: string;
  strengths: string[];
  issues: ResumeIssue[];
};

const gradeValue: Record<RadarGrade, number> = { S: 7, A: 6, B: 5, C: 4, D: 3, E: 2, F: 1 };
const gradeFill: Record<RadarGrade, string> = {
  S: "#e11d48", A: "#059669", B: "#0284c7", C: "#d97706", D: "#ea580c", E: "#9f1239", F: "#78716c",
};
const severityMeta: Record<IssueSeverity, { label: [string, string]; tag: string; border: string }> = {
  A: { label: ["Must fix first", "A 区 · 最该改"], tag: "bg-rose-600 text-white", border: "border-l-rose-400" },
  B: { label: ["Fix next", "B 区 · 应该改"], tag: "bg-amber-500 text-white", border: "border-l-amber-400" },
  C: { label: ["Nice-to-have", "C 区 · 可增色"], tag: "bg-sky-600 text-white", border: "border-l-sky-400" },
};
const dimensionLabels: Record<string, { en: string; zh: string; shortEn: string; shortZh: string }> = {
  technicalDepth: { en: "Professional depth", zh: "专业深度", shortEn: "Expertise", shortZh: "专业深度" },
  outcomes: { en: "Projects & outcomes", zh: "项目与成果", shortEn: "Outcomes", shortZh: "项目成果" },
  ownership: { en: "Engineering ownership", zh: "工程与 ownership", shortEn: "Ownership", shortZh: "工程能力" },
  clarity: { en: "Clarity & structure", zh: "表达与结构", shortEn: "Clarity", shortZh: "表达结构" },
  roleFit: { en: "Role fit", zh: "岗位匹配", shortEn: "Role fit", shortZh: "岗位匹配" },
  background: { en: "Background", zh: "背景竞争力", shortEn: "Background", shortZh: "背景竞争力" },
};

function polygonPoints(values: number[], radius: number, center: number) {
  return values.map((value, index) => {
    const angle = (-90 + index * 60) * (Math.PI / 180);
    const scale = value / 7;
    return `${center + Math.cos(angle) * radius * scale},${center + Math.sin(angle) * radius * scale}`;
  }).join(" ");
}

export function Radar({ dimensions }: { dimensions: ResumeDimension[] }) {
  const { locale } = useLocale();
  const center = 180, radius = 100;
  const values = dimensions.map(dimension => gradeValue[dimension.grade]);
  const market = dimensions.map(dimension => gradeValue[dimension.marketGrade]);
  return <svg viewBox="-30 -10 420 380" className="mx-auto h-auto w-full max-w-[560px]" role="img" aria-label="能力雷达">
    {[7, 6, 5, 4, 3, 2].map(level => <polygon key={level} points={polygonPoints(Array(6).fill(level), radius, center)} fill="none" stroke="rgba(49,39,70,0.10)" strokeWidth="1" />)}
    {dimensions.map((dimension, index) => {
      const angle = (-90 + index * 60) * (Math.PI / 180);
      return <line key={dimension.id} x1={center} y1={center} x2={center + Math.cos(angle) * radius} y2={center + Math.sin(angle) * radius} stroke="rgba(49,39,70,0.10)" />;
    })}
    <polygon points={polygonPoints(market, radius, center)} fill="rgba(120,113,108,0.06)" stroke="rgba(120,113,108,0.45)" strokeDasharray="5 4" strokeWidth="1.5" />
    <polygon points={polygonPoints(values, radius, center)} fill="rgba(124,58,237,0.20)" stroke="#7c3aed" strokeWidth="2.5" strokeLinejoin="round" />
    {values.map((value, index) => {
      const angle = (-90 + index * 60) * (Math.PI / 180);
      const scale = value / 7;
      return <circle key={dimensions[index].id} cx={center + Math.cos(angle) * radius * scale} cy={center + Math.sin(angle) * radius * scale} r="5" fill="#7c3aed" stroke="white" strokeWidth="2.5" />;
    })}
    {dimensions.map((dimension, index) => {
      const angle = (-90 + index * 60) * (Math.PI / 180);
      const gradeX = center + Math.cos(angle) * (radius + 17), gradeY = center + Math.sin(angle) * (radius + 17);
      const labelX = center + Math.cos(angle) * (radius + 40), labelY = center + Math.sin(angle) * (radius + 40);
      const anchor = Math.cos(angle) < -0.2 ? "end" : Math.cos(angle) > 0.2 ? "start" : "middle";
      const label = dimensionLabels[dimension.id];
      const belowMarket = gradeValue[dimension.grade] < gradeValue[dimension.marketGrade];
      return <g key={dimension.id}>
        <circle cx={gradeX} cy={gradeY} r="10" fill={gradeFill[dimension.grade]} stroke="white" strokeWidth="1.5" />
        <text x={gradeX} y={gradeY + 3.5} textAnchor="middle" fill="white" style={{ fontSize: 10, fontWeight: 700 }}>{dimension.grade}</text>
        <text x={labelX} y={labelY + 4} textAnchor={anchor} fill={belowMarket ? "#b91c1c" : "#57534e"} style={{ fontSize: 12, fontWeight: belowMarket ? 750 : 600 }}>{locale === "en" ? label?.shortEn : label?.shortZh}</text>
      </g>;
    })}
  </svg>;
}

const markdownComponents: Components = {
  p: ({ children }) => <p className="mb-2 text-sm leading-7 text-stone-700 last:mb-0">{children}</p>,
  strong: ({ children }) => <strong className="font-semibold text-stone-900">{children}</strong>,
  ul: ({ children }) => <ul className="mb-2 list-disc space-y-1 pl-5 text-sm leading-6 text-stone-700 last:mb-0">{children}</ul>,
  ol: ({ children }) => <ol className="mb-2 list-decimal space-y-1 pl-5 text-sm leading-6 text-stone-700 last:mb-0">{children}</ol>,
  li: ({ children }) => <li>{children}</li>,
  code: ({ children }) => <code className="rounded bg-stone-100 px-1 py-0.5 text-[0.9em] text-stone-700">{children}</code>,
};

function InlineMarkdown({ text }: { text: string }) {
  return <>{text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, index) => {
    if (part.startsWith("**") && part.endsWith("**")) return <strong key={index} className="font-semibold text-stone-900">{part.slice(2, -2)}</strong>;
    if (part.startsWith("`") && part.endsWith("`")) return <code key={index} className="rounded bg-stone-100 px-1 py-0.5 text-[0.9em] text-stone-700">{part.slice(1, -1)}</code>;
    return <span key={index}>{part}</span>;
  })}</>;
}

function MarkdownBlock({ text }: { text: string }) {
  return <div className="mt-2"><ReactMarkdown remarkPlugins={[remarkGfm]} components={markdownComponents}>{text}</ReactMarkdown></div>;
}

function IssueCard({ issue, t }: { issue: ResumeIssue; t: (en: string, zh: string) => string }) {
  const line = issue.severity === "A" ? "border-l-rose-400" : issue.severity === "B" ? "border-l-[#f28a2e]" : "border-l-sky-400";
  return <article className={`issue-card rounded-2xl border border-stone-200/80 border-l-[3px] ${line} bg-white/90 p-6 md:p-8`}>
    <div className="min-w-0">
      <span className="inline-flex rounded-md bg-stone-100 px-2.5 py-1 text-xs leading-none text-stone-600">{issue.category}</span>
      <div className="mt-3 whitespace-pre-wrap text-[19px] font-semibold leading-8 tracking-[-0.01em] text-stone-900 md:text-xl md:leading-9"><InlineMarkdown text={issue.problem} /></div>
      {issue.why && <p className="mt-2.5 whitespace-pre-wrap text-[15px] leading-7 text-stone-500"><InlineMarkdown text={issue.why} /></p>}
      {issue.benchmark && <div className="mt-5 flex gap-3.5 border-l-2 border-violet-300/80 py-1 pl-4"><span className="shrink-0 pt-0.5 text-xs font-bold tracking-[.06em] text-violet-700">{t("Target", "目标")}</span><p className="text-[14px] leading-7 text-stone-600"><InlineMarkdown text={issue.benchmark} /></p></div>}
      {(issue.action || issue.example) && <details className="group mt-5">
        <summary className="relative flex h-11 w-full cursor-pointer list-none items-center justify-center [&::-webkit-details-marker]:hidden">
          <span aria-hidden className="absolute inset-x-0 top-1/2 h-[2px] rounded-full bg-[linear-gradient(90deg,rgba(255,94,58,.12),#ff5e3a_14%,#ff5e3a_86%,rgba(255,94,58,.12))] transition-all duration-150 group-hover:h-[3px] group-open:h-[3px] group-open:bg-[#e84521]" />
          <span className="relative bg-white px-3.5 text-[13px] font-extrabold tracking-[.08em] text-[#e84521]">{t("Fix", "改")}<span className="ml-1 inline-block transition-transform group-open:rotate-90">›</span></span>
        </summary>
        <div className="mt-3 grid gap-3.5 rounded-xl border border-[#ffd9cc] bg-[#fff2ec] p-4 md:p-5">
          {issue.evidence && <div className="rounded-lg border border-stone-200/80 bg-white/70 p-4"><p className="mb-2 text-xs font-extrabold tracking-[.06em] text-stone-500">{t("Resume excerpt", "原文")}</p><blockquote className="whitespace-pre-wrap border-l-2 border-stone-300 pl-3.5 text-[14px] leading-7 text-stone-600"><InlineMarkdown text={issue.evidence} /></blockquote></div>}
          {issue.action && <div><p className="mb-2 text-xs font-extrabold tracking-[.06em] text-[#d94622]">{t("Suggested fix", "建议改法")}</p><p className="whitespace-pre-wrap text-[15px] leading-7 text-[#574b44]"><InlineMarkdown text={issue.action} /></p></div>}
          {issue.example && <div className="rounded-lg bg-violet-50 p-4"><p className="mb-2 text-xs font-extrabold tracking-[.06em] text-violet-700">{t("Rewrite example", "改写示例")}</p><p className="whitespace-pre-wrap text-[14px] leading-7 text-violet-950/80"><InlineMarkdown text={issue.example} /></p></div>}
        </div>
      </details>}
    </div>
  </article>;
}

export function ResumeReportV3({ report, resumeId, onBack, onViewResume }: {
  report: ResumeReportV3Data;
  resumeId?: string;
  onBack?: () => void;
  onViewResume?: () => void;
}) {
  const { t } = useLocale();
  const issueGroups: IssueSeverity[] = ["A", "B", "C"];
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState("");

  async function exportReport() {
    if (!resumeId || exporting) return;
    setExportError("");
    setExporting(true);
    try {
      const response = await fetch(`/report-api/${encodeURIComponent(resumeId)}`, { credentials: "include", cache: "no-store" });
      if (!response.ok) {
        const body = await response.json().catch(() => ({})) as { message?: string };
        throw new Error(body.message ?? "报告导出失败");
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      const safeName = report.filename.replace(/\.pdf$/i, "").replace(/[\\/:*?"<>|]+/g, "-");
      link.href = url;
      link.download = `Ardor-${safeName}-report.pdf`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error("LaTeX report export failed", error);
      setExportError(error instanceof Error ? error.message : t("Could not export the report. Try again.", "报告导出失败，请重试。"));
    } finally {
      setExporting(false);
    }
  }

  return <div className="min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-5xl px-5 pb-20 pt-6 md:px-10">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 transition hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("All resumes", "所有简历")}</button>
      <span className="rounded-full border border-white/80 bg-white/60 px-3 py-1.5 text-xs text-stone-500">{report.condition}</span>
    </div>

    <header className="flex flex-wrap items-end justify-between gap-3 pb-5 pt-6">
      <div className="min-w-0">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">RESUME REPORT</p>
        <h1 className="mt-1.5 truncate text-2xl font-semibold tracking-tight md:text-3xl">{report.filename}</h1>
      </div>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <button type="button" onClick={onViewResume} className="inline-flex items-center gap-1.5 rounded-full border border-white/80 bg-white/70 px-4 py-2 text-stone-600 hover:bg-white"><FileText className="size-4" />{t("View original", "查看原简历")}</button>
        <button type="button" onClick={() => void exportReport()} disabled={exporting} className="inline-flex items-center gap-1.5 rounded-full bg-[#1d1d1f] px-4 py-2 font-medium text-white hover:bg-black disabled:opacity-50"><Download className="size-4" />{exporting ? t("Exporting…", "导出中…") : t("Export report", "导出报告")}</button>
      </div>
    </header>
    {exportError && <p role="alert" className="mb-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700">{exportError}</p>}

    {report.general && <p className="mb-5 rounded-2xl border border-amber-200 bg-amber-50/80 px-4 py-3 text-sm text-amber-900">{t("Generalised analysis only: without a JD this report may not reflect a specific role. Add a JD when starting a new analysis for role-specific evidence.", "本次为通用化分析，可能不具针对性；如需岗位匹配，请在开始新分析时补充 JD。")}</p>}

    <section className="grid gap-5 lg:grid-cols-[1.05fr_0.95fr]">
      <div className="rounded-[2rem] border border-white/70 bg-white/80 p-6 shadow-[0_18px_60px_rgba(49,39,70,0.07)] backdrop-blur-xl md:p-8">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{t("Competitiveness", "当前竞争力")}</p>
        <div className="mt-3 flex flex-wrap items-end gap-x-4 gap-y-2">
          <span className="text-6xl font-semibold leading-none tracking-[-0.05em]">{report.score}</span>
          <span className="pb-1.5 text-sm text-stone-400">/ 100</span>
          <span className="mb-1 rounded-full bg-violet-100 px-3 py-1 text-sm font-medium text-violet-800">{report.band}</span>
        </div>
        <div className="mt-5 border-t border-stone-100 pt-5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{t("Capability radar", "能力雷达")}</p>
            <div className="flex flex-wrap gap-3 text-[11px] text-stone-400">
              <span className="inline-flex items-center gap-1.5"><span className="size-2 rounded-full bg-violet-600" />{t("You", "你")}</span>
              <span className="inline-flex items-center gap-1.5"><span className="size-2 rounded-full border border-stone-400 bg-stone-200" />{t("Market requirement", "市场要求")}</span>
            </div>
          </div>
          <div className="flex items-center justify-center py-2"><Radar dimensions={report.dimensions} /></div>
        </div>
      </div>

      <div className="rounded-[2rem] border border-white/70 bg-white/80 p-6 shadow-[0_18px_60px_rgba(49,39,70,0.07)] backdrop-blur-xl md:p-8">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{t("Diagnosis", "简历诊断")}</p>
        <MarkdownBlock text={[report.summary, report.background].filter(Boolean).join("\n\n")} />
      </div>
    </section>

    <div className="mt-5 space-y-5">
      {issueGroups.map(severity => {
        const issues = report.issues.filter(issue => issue.severity === severity);
        if (issues.length === 0) return null;
        const meta = severityMeta[severity];
        return <section key={severity} aria-label={t(...meta.label)}>
          <div className="mb-3 flex items-baseline gap-2.5 border-b border-stone-200/80 pb-3">
            <span className={`grid size-6 shrink-0 place-items-center rounded-md text-[11px] font-semibold ${meta.tag}`}>{severity}</span>
            <h2 className="text-xl font-semibold tracking-[-0.02em]">{t(...meta.label)}</h2>
            <span className="text-sm text-stone-400">{issues.length} {t("items", "条")}</span>
            <span aria-hidden className="ml-1 h-px flex-1 bg-gradient-to-r from-stone-200 to-transparent" />
          </div>
          <div className="space-y-3">{issues.map((issue, index) => <IssueCard key={`${severity}-${index}`} issue={issue} t={t} />)}</div>
        </section>;
      })}
    </div>

    {report.strengths.length > 0 && <section className="mt-6 rounded-[2rem] border border-white/70 bg-white/75 p-6 shadow-[0_14px_40px_rgba(49,39,70,0.05)] backdrop-blur-xl">
      <div className="flex items-center gap-2 text-emerald-600"><Sparkles className="size-4" /><h2 className="text-sm font-semibold uppercase tracking-[0.14em]">{t("What works", "优点")}</h2></div>
      <ul className="mt-3 space-y-1.5 text-sm leading-6 text-stone-700">{report.strengths.map((strength, index) => <li key={index}>· <InlineMarkdown text={strength} /></li>)}</ul>
    </section>}

    </div>
  </div>;
}




