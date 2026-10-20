"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { FileText, Target } from "lucide-react";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

export type PlanContext = {
  sourceType: "MANUAL" | "AGENT" | "RESUME" | "RECAP" | "KNOWLEDGE";
  sourceId: string | null;
  originKind?: "MANUAL" | "AGENT" | "RESUME" | "KNOWLEDGE" | "INTERVIEW_RECORD" | "INTERVIEW_QUESTION" | "LEGACY_RECAP";
  originResourceId?: string | null; originRecordId?: string | null;
  targetInterviewId?: string | null; targetRoundId?: string | null;
};

export function LearningContext({ plan, sourceName }: { plan: PlanContext; sourceName?: string }) {
  const { t } = useLocale();
  const [source, setSource] = useState<{ resourceId: string; recordId: string | null } | null>(null);
  const questionId=plan.originKind === "INTERVIEW_QUESTION" && !plan.originRecordId ? plan.originResourceId : null;
  useEffect(() => {
    if (!questionId) return;
    let alive=true;
    void api<NonNullable<typeof source>>(`/api/learning-plans/origins/INTERVIEW_QUESTION/${encodeURIComponent(questionId)}`)
      .then(value => { if (alive) setSource(value); }).catch(() => { /* A missing or foreign source must not become a guessed report link. */ });
    return () => { alive=false; };
  },[questionId]);
  const [target, setTarget] = useState<{ id: string; company: string; targetRole: string | null; rounds?: { id: string; roundNumber: number }[] } | null>(null);
  const targetId=plan.targetInterviewId;
  useEffect(() => {
    if (!targetId) return;
    let alive=true;
    void api<NonNullable<typeof target>>(`/api/opportunities/${targetId}`)
      .then(value => { if(alive) setTarget(value); }).catch(() => { /* Keep the persisted target link without inventing details. */ });
    return () => { alive=false; };
  },[targetId]);
  const recordId=plan.originRecordId ?? (plan.originKind === "INTERVIEW_RECORD" ? plan.originResourceId
    : source && source.resourceId === questionId ? source.recordId : null);
  const resourceId=plan.originResourceId ?? plan.sourceId;
  const sourceHref=recordId ? `/app/job-search/notes?selected=${encodeURIComponent(recordId)}`
    : (plan.originKind ?? plan.sourceType) === "RESUME" && resourceId ? `/app/resumes?selected=${encodeURIComponent(resourceId)}`
    : (plan.originKind ?? plan.sourceType) === "KNOWLEDGE" && resourceId ? `/app/knowledge?document=${encodeURIComponent(resourceId)}`
    : !plan.originKind && plan.sourceType === "RECAP" && plan.sourceId ? `/app/recaps?source=${encodeURIComponent(plan.sourceId)}` : null;
  const labels={
    MANUAL:t("Added by you","自己添加"),AGENT:t("Suggested by Ardor","Ardor 建议"),RESUME:t("From a resume","来自简历"),
    KNOWLEDGE:t("From knowledge","来自知识库"),RECAP:t("From interview notes","来自面试记录"),
    INTERVIEW_RECORD:t("Source interview record","来源面试记录"),INTERVIEW_QUESTION:t("Source interview question","来源面试问题"),
    LEGACY_RECAP:t("Historical source needs verification","历史来源待核对"),
  };
  const sourceLabel=labels[plan.originKind ?? plan.sourceType];
  const currentTarget=target?.id === targetId ? target : null;
  const round=currentTarget?.rounds?.find(item => item.id === plan.targetRoundId);
  return <div className="flex flex-wrap items-center gap-x-4 gap-y-2" aria-label={t("Learning source and preparation target","学习来源与准备目标")}>
    {sourceHref ? <Link href={sourceHref} aria-label={sourceLabel} title={sourceName} className="inline-flex min-w-0 max-w-xs items-center gap-1.5 text-sm text-stone-600 underline decoration-stone-300 underline-offset-4 hover:text-stone-900"><FileText className="size-3.5 shrink-0" /><span className="truncate">{sourceLabel}{sourceName ? ` · ${sourceName}` : ""}</span></Link>
      : <span className="text-xs text-stone-500">{sourceLabel}</span>}
    {targetId && <Link href={`/app/job-search/interviews?selected=${encodeURIComponent(targetId)}`} aria-label={t("Preparation target","准备目标")} className="inline-flex min-w-0 max-w-sm items-center gap-1.5 text-sm text-violet-700 underline decoration-violet-200 underline-offset-4"><Target className="size-3.5 shrink-0" /><span className="truncate">{t("Preparing for","准备目标")}{" · "}{currentTarget?.company ?? t("Interview","面试")}{currentTarget?.targetRole ? ` · ${currentTarget.targetRole}` : ""}{round ? t(` · Round ${round.roundNumber}`,` · 第 ${round.roundNumber} 轮`) : ""}</span></Link>}
  </div>;
}
