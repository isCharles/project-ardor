"use client";

import { BookOpenText } from "lucide-react";
import { useLocale } from "@/lib/locale";
import { LearningContext, type PlanContext } from "./learning-context";

type Proposal = { label: string; detail: string; state: string; error?: string; learningPlan?: (PlanContext & { requestId: string; scheduledAt: string | null }) | null };

export function LearningPermissions<T extends Proposal>({ items, onReject, onConfirm }: { items: T[]; onReject: (item: T) => void; onConfirm: (item: T) => void }) {
  const { t, locale } = useLocale();
  if (!items.length) return null;
  return <section aria-label={t("Learning creation confirmation", "学习创建确认")} className="ardor-composer-enter mx-auto mb-3 max-h-[35dvh] max-w-3xl space-y-4 overflow-y-auto rounded-2xl border border-violet-200 bg-white/95 p-4 shadow-lg backdrop-blur-xl">
    {items.map(item => <article key={item.learningPlan?.requestId} className="flex items-start gap-3 text-sm">
      <BookOpenText className="mt-1 size-5 shrink-0 text-violet-600" />
      <div className="min-w-0 flex-1">
        <h3 className="break-words font-medium text-stone-800">{t(`Create a learning plan for “${item.label}”?`, `创建「${item.label}」学习计划？`)}</h3>
        {item.detail && <p className="mt-2 break-words text-xs leading-5 text-stone-600">{item.detail}</p>}
        {item.learningPlan && <div className="mt-2"><LearningContext plan={item.learningPlan} /></div>}
        {item.learningPlan?.scheduledAt && <p className="mt-2 text-xs text-stone-500">{t("Scheduled time", "安排时间")} · {new Date(item.learningPlan.scheduledAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN")}</p>}
        <p className="mt-2 text-xs leading-5 text-stone-500">{t("Confirm to generate a lesson and practice in the background and add the plan to Learning and your calendar. Existing plans stay unchanged. Reject to create nothing.", "确认后将后台生成讲解与练习，加入学习模块和日历；已有计划保持不变。拒绝则不创建。")}</p>
        {item.error && <p role="alert" className="mt-2 break-words text-xs text-rose-600">{item.error}</p>}
        <div className="mt-3 flex justify-end gap-2">
          <button type="button" disabled={item.state === "DELETING"} onClick={() => onReject(item)} className="rounded-full px-4 py-2 text-xs text-stone-600 hover:bg-stone-100 disabled:opacity-50">{t("Reject", "拒绝")}</button>
          <button type="button" disabled={item.state === "DELETING"} onClick={() => onConfirm(item)} className="rounded-full bg-violet-600 px-4 py-2 text-xs text-white hover:bg-violet-700 disabled:opacity-50">{item.state === "DELETING" ? t("Submitting…", "提交中…") : t("Confirm creation", "确认创建")}</button>
        </div>
      </div>
    </article>)}
  </section>;
}
