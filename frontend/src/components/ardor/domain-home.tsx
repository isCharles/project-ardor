"use client";
import { ArrowUpRight } from "lucide-react";
import Link from "next/link";
import { destinations } from "@/lib/navigation";
import { useLocale } from "@/lib/locale";

export function DomainHome({ practice = false }: { practice?: boolean }) {
  const { t } = useLocale();
  const ids = practice ? ["learning.mock-interviews", "learning.question-practice"] : ["job-search.interviews", "job-search.applications", "job-search.resumes"];
  const descriptions: Record<string, [string, string]> = {
    "job-search.interviews": ["Real interviews, rounds and notes in one place.", "真实面试、轮次和面试记录，放在一起。"],
    "job-search.applications": ["Log today's applications and keep your rhythm.", "记录今天的投递数量，保持求职节奏。"],
    "job-search.resumes": ["Your resume versions, analysis and priorities.", "查看简历版本、分析结果和优化优先级。"],
    "learning.mock-interviews": ["Practice by text or voice, including coding questions.", "文字或语音模拟，包含编程题训练。"],
    "learning.question-practice": ["Retry a question from your interview notes and compare answers.", "从面试记录中的一道题开始重答，比较前后回答。"],
  };
  return <main className="ardor-workbench min-h-full px-5 py-10 md:px-10"><div className="mx-auto max-w-5xl">
    <h1 className="text-3xl font-semibold tracking-tight">{practice ? t("Practice", "训练") : t("Job search", "求职")}</h1>
    <p className="mb-8 mt-3 text-sm text-stone-500">{practice ? t("Prepare for the next real conversation.", "为下一场真实面试做好准备。") : t("From sending applications to your next interview.", "从投递开始，推进下一场面试。")}</p>
    <div className="grid gap-4 md:grid-cols-3">{ids.map(id => {
      const item = destinations.find(destination => destination.id === id)!;
      return <Link key={id} href={item.href} className="group rounded-3xl border border-stone-200/70 bg-white/80 p-6 transition hover:border-violet-200 hover:bg-violet-50/60">
        <div className="flex items-center justify-between gap-4"><h2 className="font-semibold text-stone-900">{t(item.en, item.zh)}</h2><ArrowUpRight aria-hidden="true" className="size-4 text-stone-400 group-hover:text-violet-600" /></div>
        <p className="mt-3 text-sm leading-6 text-stone-500">{t(...descriptions[id])}</p>
      </Link>;
    })}</div>
  </div></main>;
}
