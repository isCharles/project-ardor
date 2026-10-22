"use client";

import { ArrowUpRight, Sparkles } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";

import { agentCreationHref } from "@/lib/agent-features";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type AdviceTask = {
  title: string;
  dueAt: string | null;
  status: string;
  priority: "LOW" | "MEDIUM" | "HIGH";
  taskKind?: string;
  seriesId?: string | null;
};

type StoredAdvice = {
  headline: string | null;
  days: string[];
  weeks: string[];
  months: string[];
  updatedAt: string | null;
};

const DAY = 86_400_000;

function horizon(tasks: AdviceTask[], from: number, to: number) {
  return tasks.filter(task => {
    if (!task.dueAt) return false;
    const at = new Date(task.dueAt).getTime();
    return at >= from && at < to;
  });
}

/**
 * The right-hand calendar card prefers advice Ardor actually saved for this user.
 * Only when no saved advice exists does it fall back to a conservative reading of
 * the user's own calendar, so the card never invents a plan Ardor did not give.
 */
export function CalendarAdvice({ tasks }: { tasks: AdviceTask[] }) {
  const { locale, t } = useLocale();
  const [stored, setStored] = useState<StoredAdvice | null>(null);

  useEffect(() => {
    let active = true;
    const refresh = () => {
      if (!active) return;
      void api<StoredAdvice>("/api/calendar/advice").then(value => { if (active) setStored(value); }).catch(() => undefined);
    };
    refresh();
    window.addEventListener("focus", refresh);
    return () => { active = false; window.removeEventListener("focus", refresh); };
  }, []);

  const now = new Date().getTime();
  const open = tasks.filter(task => task.status === "TODO" || task.status === "IN_PROGRESS");
  // Occurrences of a repeating arrangement (a weekly meeting, a recurring class)
  // are fixed commitments, not one-off todos that became overdue.
  const recurring = open.filter(task => !!task.seriesId);
  const oneOff = open.filter(task => !task.seriesId);
  const overdue = oneOff.filter(task => task.dueAt && new Date(task.dueAt).getTime() < now);
  const week = horizon(oneOff, now, now + 7 * DAY);
  const month = horizon(oneOff, now + 7 * DAY, now + 30 * DAY);
  const later = oneOff.filter(task => !task.dueAt || new Date(task.dueAt).getTime() >= now + 30 * DAY);
  const high = week.filter(task => task.priority === "HIGH");

  const fallback: string[] = [];
  if (overdue.length > 0) {
    fallback.push(locale === "en"
      ? `Close ${overdue.length} overdue one-off item${overdue.length > 1 ? "s" : ""} first: ${overdue.slice(0, 2).map(task => task.title).join(", ")}.`
      : `先把 ${overdue.length} 项逾期的一次性安排收尾：${overdue.slice(0, 2).map(task => task.title).join("、")}。`);
  }
  if (week.length > 0) {
    const names = week.slice(0, 3).map(task => task.title).join(locale === "en" ? ", " : "、");
    fallback.push(locale === "en"
      ? `Next 7 days: ${week.length} one-off item${week.length > 1 ? "s" : ""} (${names}). Keep each day to one or two concrete outcomes${high.length ? "; the high-priority one comes first" : ""}.`
      : `接下来 7 天有 ${week.length} 项一次性安排：${names}。每天只安排 1–2 个能交付的结果${high.length ? "，优先处理高优先级那一项" : ""}。`);
  }
  if (recurring.length > 0) {
    const names = recurring.slice(0, 3).map(task => task.title).join(locale === "en" ? ", " : "、");
    fallback.push(locale === "en"
      ? `Fixed commitments: ${recurring.length} repeating occurrence${recurring.length > 1 ? "s" : ""} (${names}). They are not overdue todos; don't tick off past meetings or squeeze them into a catch-up list.`
      : `固定安排：${recurring.length} 个重复日程（${names}）。它们是固定承诺，不是逾期待办；过去的组会/课程不需要补勾，也不要塞进补做清单。`);
  }
  if (month.length > 0) {
    fallback.push(locale === "en"
      ? `Weeks 2–4: ${month.length} one-off item${month.length > 1 ? "s" : ""}; leave room for interview preparation instead of filling every day.`
      : `第 2–4 周还有 ${month.length} 项一次性安排；不要把每天都填满，给面试准备和临时邀约留空间。`);
  }
  if (later.length > 0) {
    fallback.push(locale === "en"
      ? `${later.length} longer-horizon one-off item${later.length > 1 ? "s" : ""} (1–3 months). Review the theme monthly, not daily.`
      : `未来 1–3 个月还有 ${later.length} 项一次性安排；按每月一次检视主题，不必每天盯。`);
  }
  if (fallback.length === 0) {
    fallback.push(locale === "en"
      ? "The next few weeks are open. Use the space for one mock interview, one interview recap, and one learning plan rather than adding more reminders."
      : "接下来几周比较空。与其继续加提醒，不如安排一场模拟面试、整理一份面经、推进一个学习计划。");
  }

  const days = stored?.days ?? [];
  const weeks = stored?.weeks ?? [];
  const months = stored?.months ?? [];
  const hasStored = !!(stored && (stored.headline || days.length || weeks.length || months.length));
  const prompt = locale === "en"
    ? "Look at my calendar, interview, application and learning state. Plan my next 7 days, next 4 weeks and next 3 months. Tell me what to do, what to drop, and where to leave buffer for interviews. Save the advice to my calendar card. Do not give generic encouragement."
    : "结合我的日历、面试、投递和学习状态，帮我规划接下来 7 天、4 周和 3 个月的重点。明确告诉我该做什么、该砍掉什么、哪里要留出面试缓冲。把建议保存到我的日历建议里。不要泛泛鼓励。";

  return <section className="rounded-[1.4rem] border border-violet-100 bg-gradient-to-br from-violet-50/80 via-white/80 to-rose-50/60 p-4">
    <div className="flex items-center gap-2"><Sparkles className="size-4 text-violet-600" /><h2 className="text-sm font-semibold">{t("Ardor's read", "Ardor 的建议")}</h2></div>
    {hasStored ? <div className="mt-3 space-y-3 text-xs leading-5 text-stone-600">
      {stored?.headline && <p className="text-sm font-semibold leading-6 text-stone-800">{stored.headline}</p>}
      {days.length > 0 && <section>
        <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-violet-600">{t("Next few days", "未来几天")}</p>
        <ol className="mt-1.5 space-y-1.5">{days.map((item, index) => <li key={item} className={`flex gap-2 rounded-xl px-2.5 py-2 ${index === 0 ? "bg-violet-100/80 text-stone-900" : "bg-white/45 text-stone-600"}`}>
          <span className={`mt-0.5 grid size-4 shrink-0 place-items-center rounded-full text-[9px] font-semibold ${index === 0 ? "bg-violet-600 text-white" : "bg-stone-100 text-stone-500"}`}>{index + 1}</span>
          <span className="line-clamp-2">{item}</span>
        </li>)}</ol>
      </section>}
      {(weeks.length > 0 || months.length > 0) && <details className="group rounded-xl bg-white/45 px-2.5 py-2">
        <summary className="cursor-pointer list-none text-[10px] font-semibold uppercase tracking-[0.14em] text-violet-600">{t(`Weeks & months · ${weeks.length + months.length} items`, `往后几周 · 几个月 · ${weeks.length + months.length} 条`)}</summary>
        {weeks.length > 0 && <div className="mt-2"><p className="text-[10px] font-medium text-stone-500">{t("Next few weeks", "接下来几周")}</p><ul className="mt-1 space-y-1 text-stone-600">{weeks.map(item => <li key={item} className="flex gap-1.5"><span className="mt-1.5 size-1 shrink-0 rounded-full bg-violet-300" />{item}</li>)}</ul></div>}
        {months.length > 0 && <div className="mt-2"><p className="text-[10px] font-medium text-stone-500">{t("Next few months", "未来几个月")}</p><ul className="mt-1 space-y-1 text-stone-600">{months.map(item => <li key={item} className="flex gap-1.5"><span className="mt-1.5 size-1 shrink-0 rounded-full bg-stone-300" />{item}</li>)}</ul></div>}
      </details>}
      {stored?.updatedAt && <p className="text-[10px] text-stone-400">{t("Updated", "更新于")} {new Date(stored.updatedAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}</p>}
    </div> : <ul className="mt-3 space-y-2 text-xs leading-5 text-stone-600">
      {fallback.map(line => <li key={line} className="flex gap-1.5"><span className="mt-1.5 size-1 shrink-0 rounded-full bg-violet-400" />{line}</li>)}
    </ul>}
    <Link href={agentCreationHref("CALENDAR", locale, prompt)} className="mt-3 inline-flex items-center gap-1 text-xs font-medium text-violet-700 hover:text-violet-900">{t("Plan with Ardor", "让 Ardor 重新规划")}<ArrowUpRight className="size-3.5" /></Link>
  </section>;
}
