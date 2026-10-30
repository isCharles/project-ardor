"use client";

import { ArrowUpRight, FileText, MessageSquareText } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { ApplicationRhythmInline } from "@/components/ardor/application-rhythm-inline";
import { api } from "@/lib/api";
import { destinations } from "@/lib/navigation";
import { useLocale, type Locale } from "@/lib/locale";

type ResumeItem = {
  id: string;
  originalFilename: string;
  parseStatus: "PENDING" | "PARSED" | "FAILED";
  createdAt: string;
  analysisId: string | null;
  analysisStatus: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | null;
  analysisError: string | null;
};
type Round = { id: string; roundNumber: number; stage: string; scheduledAt: string | null; occurredAt: string | null; durationMinutes: number | null; note: string | null };
type Opportunity = {
  id: string;
  company: string;
  targetRole: string | null;
  platform: string | null;
  status: string;
  currentRound: number;
  rounds: Round[];
  createdAt: string;
  updatedAt: string;
};
type Tip = { label: [string, string]; text: string };
type ResumePreview = { file: string; score: number | null; tips: Tip[]; state: "ready" | "pending" | "failed" | "unanalyzed" };
type InterviewPreview = { id: string; company: string; role: string | null; status: string; round: number; stage: string | null; at: string | null };

const criterionLabels: Record<string, [string, string]> = {
  completeness: ["Professional information", "职业信息完整性"],
  timeline: ["Timeline", "时间线"],
  responsibilities: ["Tasks and scope", "任务与范围"],
  ownership: ["Personal contribution", "个人贡献"],
  outcomes: ["Outcomes and measurement", "结果与口径"],
  verification: ["Verifiable evidence", "可核验依据"],
  structure: ["Text structure", "文字结构"],
  precision: ["Specificity and concision", "具体与简洁"],
  consistency: ["Internal consistency", "事实一致性"],
  requiredSkills: ["Target skill evidence", "目标技能证据"],
  appliedSkills: ["Applied skills", "技能应用"],
  scopeFit: ["Responsibility level", "职责层级"],
};
const statusLabels: Record<string, [string, string]> = {
  SCHEDULED: ["Scheduled", "已约面"],
  AWAITING_RESULT: ["Awaiting result", "等通知"],
  NEXT_ROUND: ["Next round", "下一轮"],
  REJECTED: ["Closed", "终止"],
};
const statusStyles: Record<string, string> = {
  SCHEDULED: "bg-violet-100 text-violet-700",
  AWAITING_RESULT: "bg-amber-100 text-amber-800",
  NEXT_ROUND: "bg-sky-100 text-sky-700",
  REJECTED: "bg-stone-100 text-stone-600",
};
const stageLabels: Record<string, [string, string]> = {
  HR: ["HR", "HR 面"],
  TECHNICAL: ["Technical", "技术面"],
  MANAGER: ["Manager", "主管面"],
  CROSS: ["Cross-functional", "交叉面"],
  FINAL: ["Final", "终面"],
  OTHER: ["Interview", "面试"],
};

function asTextList(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  return value
    .map(item => {
      if (typeof item === "string") return item;
      if (item && typeof item === "object") return Object.values(item).filter(part => typeof part === "string" || typeof part === "number").join(" · ");
      return String(item ?? "");
    })
    .filter(Boolean);
}

function formatDate(value: string | null, locale: Locale): string {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleDateString(locale, { month: "short", day: "numeric" });
}

function resumeScore(data: Record<string, unknown>): number | null {
  if (typeof data.overallScore === "number") return Math.round(data.overallScore);
  const categories = data.categoryScores;
  if (!categories || typeof categories !== "object") return null;
  const values = Object.values(categories as Record<string, unknown>).filter((value): value is number => typeof value === "number");
  return values.length > 0 ? Math.round(values.reduce((sum, value) => sum + value, 0) / values.length) : null;
}

function resumeTips(data: Record<string, unknown>): Tip[] {
  if (Array.isArray(data.criteria)) {
    return data.criteria
      .filter((item): item is { id: string; level: number; rationale: string } =>
        !!item && typeof item === "object" &&
        typeof (item as Record<string, unknown>).id === "string" &&
        typeof (item as Record<string, unknown>).level === "number" &&
        typeof (item as Record<string, unknown>).rationale === "string")
      .filter(item => item.level < 3)
      .sort((left, right) => left.level - right.level)
      .slice(0, 2)
      .map(item => ({ label: criterionLabels[item.id] ?? [item.id, item.id], text: item.rationale }));
  }
  const legacy = [...asTextList(data.recommendations), ...asTextList(data.weaknesses)];
  return legacy.slice(0, 2).map(text => ({ label: ["Suggestion", "优化点"], text }));
}

function roundInstant(round: Round): number {
  const value = round.occurredAt ?? round.scheduledAt;
  return value ? new Date(value).getTime() : Number.NaN;
}

function latestRound(rounds: Round[]): Round | null {
  return rounds.reduce<Round | null>((best, round) => {
    if (!best) return round;
    const time = roundInstant(round);
    const bestTime = roundInstant(best);
    if (!Number.isNaN(time) && (Number.isNaN(bestTime) || time > bestTime)) return round;
    if (Number.isNaN(time) && Number.isNaN(bestTime) && round.roundNumber > best.roundNumber) return round;
    return best;
  }, null);
}

function latestOpportunity(items: Opportunity[]): InterviewPreview | null {
  if (items.length === 0) return null;
  // Editing an old record is not an interview event; rank by the newest round
  // and fall back to the edit time only when no round carries a date.
  const latest = [...items].map(item => ({ item, round: latestRound(item.rounds) })).sort((left, right) => {
    const leftTime = left.round ? roundInstant(left.round) : Number.NaN;
    const rightTime = right.round ? roundInstant(right.round) : Number.NaN;
    const leftDated = !Number.isNaN(leftTime);
    const rightDated = !Number.isNaN(rightTime);
    if (leftDated !== rightDated) return leftDated ? -1 : 1;
    if (leftDated && leftTime !== rightTime) return rightTime - leftTime;
    return (right.item.updatedAt ?? right.item.createdAt).localeCompare(left.item.updatedAt ?? left.item.createdAt);
  })[0];
  return {
    id: latest.item.id,
    company: latest.item.company,
    role: latest.item.targetRole,
    status: latest.item.status,
    round: latest.round?.roundNumber ?? latest.item.currentRound,
    stage: latest.round?.stage ?? null,
    at: latest.round ? latest.round.occurredAt ?? latest.round.scheduledAt : null,
  };
}

function PreviewRow({ href, icon, accent, title, cta, children }: {
  href: string;
  icon: React.ReactNode;
  accent: string;
  title: string;
  cta: string;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      aria-label={cta}
      className="group flex items-start gap-4 rounded-[2rem] border border-white/70 bg-white/65 p-6 shadow-[inset_0_1px_0_rgba(255,255,255,0.9),0_18px_50px_rgba(49,39,70,0.07)] backdrop-blur-xl transition hover:-translate-y-0.5 hover:bg-white/80 hover:shadow-[inset_0_1px_0_rgba(255,255,255,0.95),0_24px_60px_rgba(49,39,70,0.10)] md:p-7"
    >
      <span className={`grid size-12 shrink-0 place-items-center rounded-2xl ${accent}`}>{icon}</span>
      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-4">
          <h2 className="text-base font-semibold text-stone-900">{title}</h2>
          <span className="shrink-0 text-stone-300 transition-colors group-hover:text-violet-600">
            <ArrowUpRight aria-hidden="true" className="size-4" />
          </span>
        </div>
        {children}
      </div>
    </Link>
  );
}

function RowSkeleton() {
  return (
    <div className="flex items-start gap-4 rounded-[2rem] border border-white/70 bg-white/50 p-6 md:p-7" aria-hidden="true">
      <span className="size-12 shrink-0 animate-pulse rounded-2xl bg-stone-200/70" />
      <div className="flex-1 space-y-3">
        <span className="block h-4 w-40 animate-pulse rounded-full bg-stone-200/70" />
        <span className="block h-8 w-64 animate-pulse rounded-full bg-stone-200/60" />
      </div>
    </div>
  );
}

export function DomainHome({ practice = false }: { practice?: boolean }) {
  const { locale, t } = useLocale();
  const [loading, setLoading] = useState(!practice);
  const [resume, setResume] = useState<ResumePreview | null>(null);
  const [interview, setInterview] = useState<InterviewPreview | null>(null);

  useEffect(() => {
    if (practice) return;
    let active = true;
    (async () => {
      const [resumesResult, opportunitiesResult] = await Promise.allSettled([
        api<ResumeItem[]>("/api/resumes"),
        api<Opportunity[]>("/api/opportunities"),
      ]);
      if (!active) return;

      if (opportunitiesResult.status === "fulfilled" && Array.isArray(opportunitiesResult.value)) {
        setInterview(latestOpportunity(opportunitiesResult.value));
      }

      const resumes = resumesResult.status === "fulfilled" && Array.isArray(resumesResult.value) ? resumesResult.value : [];
      if (resumes.length > 0) {
        const sorted = [...resumes].sort((left, right) => (right.createdAt ?? "").localeCompare(left.createdAt ?? ""));
        const chosen = sorted.find(item => item.analysisId && item.analysisStatus === "COMPLETED") ?? sorted[0];
        let data: Record<string, unknown> | null = null;
        if (chosen.analysisId && chosen.analysisStatus === "COMPLETED") {
          try {
            const payload = await api<{ analysis: Record<string, unknown> }>(`/api/resumes/${chosen.id}/analysis`);
            data = payload.analysis ?? null;
          } catch {
            data = null;
          }
        }
        if (!active) return;
        const state = data
          ? "ready"
          : chosen.analysisStatus === "FAILED"
            ? "failed"
            : chosen.analysisStatus === "QUEUED" || chosen.analysisStatus === "RUNNING"
              ? "pending"
              : "unanalyzed";
        setResume({
          file: chosen.originalFilename,
          score: data ? resumeScore(data) : null,
          tips: data ? resumeTips(data) : [],
          state,
        });
      }
      if (active) setLoading(false);
    })().catch(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [practice]);

  const practiceCards = practice
    ? ["learning.mock-interviews", "learning.question-practice"].map(id => {
        const item = destinations.find(destination => destination.id === id)!;
        return { item, description: id === "learning.mock-interviews" ? t("Practice by text or voice, including coding questions.", "文字或语音模拟，包含编程题训练。") : t("Retry a question from your interview notes and compare answers.", "从面试记录中的一道题开始重答，比较前后回答。") };
      })
    : [];

  return <main className="ardor-workbench min-h-full px-5 py-10 md:px-10"><div className="mx-auto max-w-5xl">
    <header className="pb-6 pt-2">
      <h1 className="text-3xl font-semibold tracking-tight">{practice ? t("Practice", "训练") : t("Job search", "求职")}</h1>
      <p className="mt-3 text-sm text-stone-500">{practice ? t("Prepare for the next real conversation.", "为下一场真实面试做好准备。") : t("From sending applications to your next interview.", "从投递开始，推进下一场面试。")}</p>
    </header>

    {practice ? (
      <div className="grid gap-4 md:grid-cols-3">{practiceCards.map(({ item, description }) => (
        <Link key={item.id} href={item.href} className="group rounded-3xl border border-stone-200/70 bg-white/80 p-6 transition hover:border-violet-200 hover:bg-violet-50/60">
          <div className="flex items-center justify-between gap-4"><h2 className="font-semibold text-stone-900">{t(item.en, item.zh)}</h2><ArrowUpRight aria-hidden="true" className="size-4 text-stone-400 group-hover:text-violet-600" /></div>
          <p className="mt-3 text-sm leading-6 text-stone-500">{description}</p>
        </Link>
      ))}</div>
    ) : loading ? (
      <div className="space-y-4" aria-label={t("Loading overview", "正在加载总览")}>
        <RowSkeleton /><RowSkeleton /><RowSkeleton />
      </div>
    ) : (
      <div className="space-y-4">
        <PreviewRow
          href="/app/resumes"
          icon={<FileText aria-hidden="true" className="size-5" />}
          accent="bg-violet-100 text-violet-700"
          title={t("Latest resume score", "最新简历评分")}
          cta={t("Resume report", "查看简历")}
        >
          {resume ? (
            <>
              <div className="mt-2 flex flex-wrap items-end gap-x-3 gap-y-1">
                <span className="text-4xl font-semibold tracking-tight text-stone-900">{resume.score ?? "—"}</span>
                <span className="pb-1 text-xs text-stone-500">{t("out of 100", "/ 100")}</span>
                <span className="max-w-full truncate pb-1 text-sm text-stone-500">{resume.file}</span>
              </div>
              {resume.state === "ready" && resume.tips.length > 0 ? (
                <ul className="mt-3 space-y-1.5">
                  {resume.tips.map((tip, index) => (
                    <li key={`${tip.label[0]}-${index}`} className="flex items-start gap-2 text-sm leading-6 text-stone-600">
                      <span aria-hidden="true" className="mt-2 size-1.5 shrink-0 rounded-full bg-orange-400" />
                      <span className="line-clamp-2 min-w-0"><span className="font-medium text-stone-700">{t(...tip.label)}：</span>{tip.text}</span>
                    </li>
                  ))}
                </ul>
              ) : null}
              {(resume.state === "pending" || resume.state === "failed") && (
                <p className="mt-2 text-sm text-stone-500">
                  {resume.state === "pending"
                    ? t("The report is being generated.", "报告正在生成中。")
                    : t("The last analysis failed — open resumes to retry.", "上次分析失败，打开简历重试。")}
                </p>
              )}
            </>
          ) : (
            <p className="mt-2 text-sm text-stone-500">{t("No resume yet — upload one to get a score and concrete next fixes.", "还没有简历，上传后即可获得评分和具体优化点。")}</p>
          )}
        </PreviewRow>

        <PreviewRow
          href={interview ? `/app/opportunities?selected=${interview.id}` : "/app/opportunities"}
          icon={<MessageSquareText aria-hidden="true" className="size-5" />}
          accent="bg-sky-100 text-sky-700"
          title={t("Latest interview process", "最新面试流程")}
          cta={t("Open interviews", "进入面试")}
        >
          {interview ? (
            <>
              <div className="mt-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="text-lg font-semibold text-stone-900">{interview.company}</span>
                {interview.role && <span className="text-sm text-stone-500">{interview.role}</span>}
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs">
                <span className={`rounded-full px-2.5 py-1 font-medium ${statusStyles[interview.status] ?? "bg-stone-100 text-stone-600"}`}>
                  {t(...(statusLabels[interview.status] ?? [interview.status, interview.status]))}
                </span>
                <span className="text-stone-500">{t(`Round ${interview.round}`, `第 ${interview.round} 轮`)}</span>
                {interview.stage && <span className="text-stone-500">{t(...(stageLabels[interview.stage] ?? [interview.stage, interview.stage]))} · {formatDate(interview.at, locale)}</span>}
              </div>
            </>
          ) : (
            <p className="mt-2 text-sm text-stone-500">{t("No interview process yet — record the next real interview here.", "还没有面试流程，记录下一场真实面试。")}</p>
          )}
        </PreviewRow>

        <ApplicationRhythmInline />
      </div>
    )}
  </div></main>;
}
