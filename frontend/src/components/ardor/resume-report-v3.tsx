"use client";

import { ArrowLeft, ArrowUpRight, FileText, Sparkles, Target } from "lucide-react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
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

function Radar({ dimensions }: { dimensions: ResumeDimension[] }) {
  const { locale } = useLocale();
  const center = 180, radius = 100;
  const values = dimensions.map(dimension => gradeValue[dimension.grade]);
  const market = dimensions.map(dimension => gradeValue[dimension.marketGrade]);
  return <svg viewBox="-30 -10 420 380" className="mx-auto h-auto w-full max-w-[470px]" role="img" aria-label="能力雷达">
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
      return <g key={dimension.id}>
        <circle cx={gradeX} cy={gradeY} r="10" fill={gradeFill[dimension.grade]} stroke="white" strokeWidth="1.5" />
        <text x={gradeX} y={gradeY + 3.5} textAnchor="middle" fill="white" style={{ fontSize: 10, fontWeight: 700 }}>{dimension.grade}</text>
        <text x={labelX} y={labelY + 4} textAnchor={anchor} className="fill-stone-600" style={{ fontSize: 12, fontWeight: 600 }}>{locale === "en" ? label?.shortEn : label?.shortZh}</text>
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
  const meta = severityMeta[issue.severity];
  return <article className={`rounded-2xl border border-white/80 border-l-[3px] ${meta.border} bg-white/85 p-5 shadow-[0_8px_26px_rgba(49,39,70,0.05)]`}>
    <div className="flex items-start gap-3">
      <span className={`mt-0.5 grid size-6 shrink-0 place-items-center rounded-full text-xs font-semibold ${meta.tag}`}>{issue.severity}</span>
      <div className="min-w-0 flex-1">
        <span className="rounded-full bg-stone-100 px-2 py-0.5 text-[11px] text-stone-500">{issue.category}</span>
        <div className="mt-1.5 whitespace-pre-wrap text-base font-semibold leading-7 text-stone-900"><InlineMarkdown text={issue.problem} /></div>
      </div>
    </div>
    {issue.why && <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-stone-500"><InlineMarkdown text={issue.why} /></p>}
    {issue.benchmark && <div className="mt-3 rounded-xl bg-stone-50/90 px-3.5 py-3 text-sm leading-6 text-stone-600 ring-1 ring-stone-100"><InlineMarkdown text={issue.benchmark} /></div>}
    {issue.action && <div className="mt-2.5 rounded-xl bg-emerald-50/80 px-3.5 py-3 text-sm leading-6 text-emerald-950 ring-1 ring-emerald-100"><InlineMarkdown text={issue.action} /></div>}
    {(!!issue.evidence || !!issue.example) && <details className="mt-3 text-sm">
      <summary className="cursor-pointer text-xs font-medium text-violet-700">{t("Resume evidence and rewrite example", "简历原文与改写示例")}</summary>
      {!!issue.evidence && <blockquote className="mt-2 border-l-2 border-stone-200 pl-3 text-stone-500">{issue.evidence}</blockquote>}
      {!!issue.example && <pre className="mt-2 whitespace-pre-wrap rounded-xl bg-stone-50 p-3 text-xs leading-5 text-stone-600">{issue.example}</pre>}
    </details>}
  </article>;
}

export function ResumeReportV3({ report, onBack, onReanalyze, onViewResume }: {
  report: ResumeReportV3Data;
  onBack?: () => void;
  onReanalyze?: () => void;
  onViewResume?: () => void;
}) {
  const { t } = useLocale();
  const issueGroups: IssueSeverity[] = ["A", "B", "C"];
  return <main className="ardor-workbench min-h-screen text-[#1d1d1f]"><div className="mx-auto max-w-6xl px-5 pb-20 pt-6 md:px-10">
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
        <button type="button" onClick={onReanalyze} className="inline-flex items-center gap-1.5 rounded-full bg-[#1d1d1f] px-4 py-2 font-medium text-white hover:bg-black">{t("Re-analyze", "重新分析")}<ArrowUpRight className="size-3.5" /></button>
      </div>
    </header>

    {report.general && <p className="mb-5 rounded-2xl border border-amber-200 bg-amber-50/80 px-4 py-3 text-sm text-amber-900">{t("Generalised analysis only: without a JD this report may not reflect a specific role. Add a JD to re-analyze with role-specific evidence.", "本次为通用化分析，可能不具针对性；补充 JD 后重新分析，才能按目标岗位判断匹配度。")}</p>}

    <section className="grid gap-5 lg:grid-cols-[0.95fr_1.05fr]">
      <div className="rounded-[2rem] border border-white/70 bg-white/80 p-6 shadow-[0_18px_60px_rgba(49,39,70,0.07)] backdrop-blur-xl md:p-8">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{t("Competitiveness", "当前竞争力")}</p>
        <div className="mt-3 flex flex-wrap items-end gap-x-4 gap-y-2">
          <span className="text-6xl font-semibold leading-none tracking-[-0.05em]">{report.score}</span>
          <span className="pb-1.5 text-sm text-stone-400">/ 100</span>
          <span className="mb-1 rounded-full bg-violet-100 px-3 py-1 text-sm font-medium text-violet-800">{report.band}</span>
        </div>
        <div className="mt-4 border-t border-stone-100 pt-4">
          <p className="mt-2 text-xs font-medium uppercase tracking-[0.14em] text-violet-600">{t("Diagnosis", "简历诊断")}</p>
          <MarkdownBlock text={[report.summary, report.background].filter(Boolean).join("\n\n")} />
        </div>
        <div className="mt-4 flex flex-wrap gap-2 text-[11px] text-stone-400">
          <span className="inline-flex items-center gap-1.5"><span className="size-2 rounded-full bg-violet-600" />{t("You", "你")}</span>
          <span className="inline-flex items-center gap-1.5"><span className="size-2 rounded-full border border-stone-400 bg-stone-200" />{t("Market requirement", "市场要求")}</span>
        </div>
      </div>

      <div className="flex flex-col rounded-[2rem] border border-white/70 bg-white/80 p-6 shadow-[0_18px_60px_rgba(49,39,70,0.07)] backdrop-blur-xl md:p-8">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{t("Capability radar", "能力雷达")}</p>
        <div className="flex flex-1 items-center justify-center py-2"><Radar dimensions={report.dimensions} /></div>
      </div>
    </section>

    <div className="mt-5 space-y-5">
      {issueGroups.map(severity => {
        const issues = report.issues.filter(issue => issue.severity === severity);
        if (issues.length === 0) return null;
        const meta = severityMeta[severity];
        return <section key={severity} aria-label={t(...meta.label)}>
          <div className="mb-3 flex flex-wrap items-center gap-2"><span className={`grid size-6 place-items-center rounded-lg text-xs font-semibold ${meta.tag}`}>{severity}</span><h2 className="text-lg font-semibold">{t(...meta.label)}</h2><span className="text-xs text-stone-400">{issues.length} {t("items", "条")}</span></div>
          <div className="space-y-2.5">{issues.map((issue, index) => <IssueCard key={`${severity}-${index}`} issue={issue} t={t} />)}</div>
        </section>;
      })}
    </div>

    {report.strengths.length > 0 && <section className="mt-6 rounded-[2rem] border border-white/70 bg-white/75 p-6 shadow-[0_14px_40px_rgba(49,39,70,0.05)] backdrop-blur-xl">
      <div className="flex items-center gap-2 text-emerald-600"><Sparkles className="size-4" /><h2 className="text-sm font-semibold uppercase tracking-[0.14em]">{t("What works", "优点")}</h2></div>
      <ul className="mt-3 space-y-1.5 text-sm leading-6 text-stone-700">{report.strengths.map((strength, index) => <li key={index}>· <InlineMarkdown text={strength} /></li>)}</ul>
    </section>}

    <footer className="mt-6 flex justify-end">
      <button type="button" onClick={onReanalyze} className="inline-flex items-center gap-1.5 rounded-full border border-white/80 bg-white/70 px-4 py-2 text-sm text-violet-700 backdrop-blur-xl">{t("Re-analyze", "重新分析")}<Target className="size-4" /></button>
    </footer>
  </div></main>;
}
