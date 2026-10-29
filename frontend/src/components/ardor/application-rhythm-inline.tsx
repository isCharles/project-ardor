"use client";

import { ArrowUpRight, Target } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Rhythm = {
  todayGreetingCount: number | null;
  todayResumeCount: number | null;
  weeklyGreetingCount: number;
  weeklyResumeCount: number;
  weeklyResumeGoal: number;
  advice: string | null;
};

/**
 * A read-only summary at the bottom of the job-search page. All actions live
 * in the detail view; clicking anywhere on this card opens it.
 */
export function ApplicationRhythmInline() {
  const { t } = useLocale();
  const [rhythm, setRhythm] = useState<Rhythm | null>(null);

  useEffect(() => {
    let active = true;
    const load = () => api<Rhythm>("/api/applications/rhythm").then(value => { if (active) setRhythm(value); }).catch(() => {});
    void load();
    const refresh = () => { if (document.visibilityState !== "hidden") void load(); };
    window.addEventListener("focus", refresh);
    return () => { active = false; window.removeEventListener("focus", refresh); };
  }, []);

  const shown = (value: number | null | undefined) => value == null ? t("Not recorded", "未记录") : String(value);
  const resumeGoal = rhythm?.weeklyResumeGoal ? ` / ${rhythm.weeklyResumeGoal}` : "";
  const weekGreetings = rhythm ? String(rhythm.weeklyGreetingCount) : "—";
  const weekResumes = rhythm ? String(rhythm.weeklyResumeCount) : "—";
  const todayGreetings = rhythm ? shown(rhythm.todayGreetingCount) : "—";
  const todayResumes = rhythm ? shown(rhythm.todayResumeCount) : "—";

  return <Link href="/app/applications" className="group mx-auto mt-8 block max-w-6xl rounded-[2rem] border border-white/80 bg-white/70 p-5 shadow-[0_12px_40px_rgba(49,39,70,0.05)] transition hover:-translate-y-0.5 hover:bg-white hover:shadow-[0_18px_50px_rgba(49,39,70,0.08)] md:p-6">
    <div className="flex flex-wrap items-center justify-between gap-4">
      <div className="flex items-center gap-3">
        <span className="grid size-10 place-items-center rounded-2xl bg-violet-50 text-violet-600"><Target className="size-5" /></span>
        <div>
          <p className="text-sm font-semibold">{t("Application rhythm", "投递节奏")}</p>
          <p className="mt-1 text-sm text-stone-500">
            {t(`Week: ${weekGreetings} greetings · ${weekResumes}${resumeGoal} resumes · today ${todayGreetings} greetings · ${todayResumes} resumes`, `本周 打招呼 ${weekGreetings} · 简历 ${weekResumes}${resumeGoal} · 今天 打招呼 ${todayGreetings} · 简历 ${todayResumes}`)}
          </p>
        </div>
      </div>
      <span className="inline-flex items-center gap-1 text-xs font-medium text-stone-400 transition group-hover:text-violet-600">{t("Open details", "查看详情")}<ArrowUpRight className="size-3.5" /></span>
    </div>
    {rhythm?.advice
      ? <div className="mt-4 rounded-2xl bg-violet-50/60 px-4 py-3"><p className="text-xs font-semibold text-violet-700">{t("Ardor's read", "Ardor 的建议")}</p><p className="mt-1.5 line-clamp-3 whitespace-pre-wrap text-sm leading-6 text-stone-700">{rhythm.advice}</p></div>
      : <p className="mt-3 text-xs text-stone-400">{t("No advice yet. Open details and ask Ardor to judge from your interview status, greetings and resume sends.", "还没有建议。进入详情让 Ardor 结合面试状态、打招呼和简历投递判断。")}</p>}
  </Link>;
}
