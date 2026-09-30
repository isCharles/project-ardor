"use client";

import { ArrowLeft, Bell, Check, Minus, Plus, Target, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";

type DayCount = { date: string; count: number };
type Rhythm = {
  today: string;
  weekStart: string;
  todayCount: number;
  weeklyCount: number;
  weeklyGoal: number;
  reminderEnabled: boolean;
  reminderTime: string;
  weekdaysOnly: boolean;
  reminderDue: boolean;
  days: DayCount[];
};

const weekdays = ["一", "二", "三", "四", "五", "六", "日"];

export default function ApplicationRhythmPage() {
  const router = useRouter();
  const [rhythm, setRhythm] = useState<Rhythm | null>(null);
  const [selectedDate, setSelectedDate] = useState("");
  const [countDraft, setCountDraft] = useState("");
  const [goalDraft, setGoalDraft] = useState("0");
  const [reminderEnabled, setReminderEnabled] = useState(false);
  const [reminderTime, setReminderTime] = useState("19:00");
  const [weekdaysOnly, setWeekdaysOnly] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  function apply(next: Rhythm, keepDate = false) {
    setRhythm(next);
    setGoalDraft(String(next.weeklyGoal));
    setReminderEnabled(next.reminderEnabled);
    setReminderTime(next.reminderTime.slice(0, 5));
    setWeekdaysOnly(next.weekdaysOnly);
    const date = keepDate && selectedDate ? selectedDate : next.today;
    setSelectedDate(date);
    setCountDraft(String(next.days.find((day) => day.date === date)?.count ?? 0));
  }

  useEffect(() => {
    let active = true;
    api<Rhythm>("/api/applications/rhythm")
      .then((result) => { if (active) apply(result); })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : "无法加载投递记录");
      });
    return () => { active = false; };
    // Initial state is deliberately loaded once; later writes use their server response.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  async function record(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const count = Number(countDraft);
    if (!Number.isInteger(count) || count < 0 || count > 500) { setError("请输入 0 到 500 的整数"); return; }
    setBusy(true); setError("");
    try {
      apply(await api<Rhythm>(`/api/applications/rhythm/days/${selectedDate}`, {
        method: "PUT", body: JSON.stringify({ count }),
      }), true);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "保存失败"); }
    finally { setBusy(false); }
  }

  async function increment() {
    setBusy(true); setError("");
    try { apply(await api<Rhythm>("/api/applications/rhythm/today/increment", { method: "POST" })); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "记录失败"); }
    finally { setBusy(false); }
  }

  async function saveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const weeklyGoal = Number(goalDraft);
    if (!Number.isInteger(weeklyGoal) || weeklyGoal < 0 || weeklyGoal > 500) { setError("每周目标须在 0 到 500 之间"); return; }
    setBusy(true); setError("");
    try {
      apply(await api<Rhythm>("/api/applications/rhythm/settings", {
        method: "PUT", body: JSON.stringify({ weeklyGoal, reminderEnabled, reminderTime, weekdaysOnly }),
      }), true);
      setSettingsOpen(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "设置保存失败"); }
    finally { setBusy(false); }
  }

  async function dismiss() {
    try { apply(await api<Rhythm>("/api/applications/rhythm/reminder/dismiss", { method: "POST" }), true); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "暂时无法关闭提醒"); }
  }

  const progress = rhythm?.weeklyGoal ? Math.min(100, Math.round(rhythm.weeklyCount / rhythm.weeklyGoal * 100)) : 0;
  const maxDay = Math.max(1, ...(rhythm?.days.map((day) => day.count) ?? []));

  return (
    <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10">
      <div className="mx-auto max-w-5xl">
        <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-white/70"><ArrowLeft className="size-4" />返回 Ardor</Link>
        <header className="pb-9 pt-12 md:pt-16">
          <div className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-white/80 text-violet-600 shadow-sm"><Target className="size-6" /></div>
          <h1 className="text-4xl font-semibold tracking-[-0.055em] md:text-6xl">投递节奏</h1>
          <p className="mt-3 text-sm text-stone-500">只记数量，不填流水。</p>
        </header>
        {error && <div role="alert" className="mb-5 rounded-2xl bg-rose-50 px-5 py-4 text-sm text-rose-700">{error}</div>}
        {rhythm?.reminderDue && <div className="mb-5 flex items-center gap-3 rounded-3xl border border-violet-100 bg-white/85 px-5 py-4 text-sm shadow-sm"><Bell className="size-5 shrink-0 text-violet-500" /><span className="flex-1">今天还没记投递数。投了多少份？</span><button onClick={() => void dismiss()} aria-label="今天不再提醒" className="rounded-full p-2 text-stone-400 hover:bg-stone-100"><X className="size-4" /></button></div>}
        <div className="grid gap-5 lg:grid-cols-[1.25fr_0.75fr]">
          <section className="rounded-[2rem] border border-white/80 bg-white/80 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] backdrop-blur-xl md:p-8">
            <div className="flex items-start justify-between gap-3"><div><p className="text-sm text-stone-500">本周投递</p><p className="mt-2 text-6xl font-semibold tabular-nums tracking-[-0.07em]">{rhythm?.weeklyCount ?? "—"}<span className="ml-2 text-xl font-normal text-stone-400">份</span></p></div><button onClick={() => setSettingsOpen((open) => !open)} className="rounded-full border border-stone-200 bg-white px-4 py-2 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-600">{settingsOpen ? "收起设置" : "目标与提醒"}</button></div>
            <div className="mt-7 h-2 overflow-hidden rounded-full bg-stone-100"><div className="h-full rounded-full bg-gradient-to-r from-[#ff6fae] via-[#a679ff] to-[#647dff] transition-[width]" style={{ width: `${progress}%` }} /></div>
            <p className="mt-2 text-xs text-stone-400">{rhythm?.weeklyGoal ? `目标 ${rhythm.weeklyGoal} 份 · 已完成 ${progress}%` : "还没有设置每周目标"}</p>
            <div className="mt-10 grid grid-cols-7 gap-2" aria-label="本周每日投递">
              {rhythm?.days.map((day, index) => <button key={day.date} type="button" disabled={day.date > rhythm.today} onClick={() => { setSelectedDate(day.date); setCountDraft(String(day.count)); }} className={`flex min-w-0 flex-col items-center rounded-2xl px-1 py-3 transition ${selectedDate === day.date ? "bg-violet-100/80 text-violet-800" : "hover:bg-stone-50"} disabled:cursor-default disabled:opacity-35`}><span className="text-xs text-stone-500">{weekdays[index]}</span><span className="mt-3 flex h-24 w-full items-end justify-center"><span className="w-3 rounded-full bg-gradient-to-t from-violet-400 to-pink-400 transition-[height]" style={{ height: `${day.count ? Math.max(10, day.count / maxDay * 100) : 3}%` }} /></span><span className="mt-2 text-sm font-medium tabular-nums">{day.count}</span></button>)}
            </div>
          </section>
          <div className="space-y-5">
            <section className="rounded-[2rem] border border-white/80 bg-white/80 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] backdrop-blur-xl md:p-8">
              <p className="text-sm text-stone-500">{selectedDate === rhythm?.today ? "今天" : selectedDate}投了</p>
              <p className="mt-2 text-5xl font-semibold tabular-nums tracking-[-0.06em]">{rhythm?.days.find((day) => day.date === selectedDate)?.count ?? 0}<span className="ml-2 text-lg font-normal text-stone-400">份</span></p>
              {selectedDate === rhythm?.today && <button type="button" disabled={busy || !rhythm} onClick={() => void increment()} className="mt-7 inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-5 py-3 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-40"><Plus className="size-4" />加一份</button>}
              <form onSubmit={record} className="mt-6 flex items-center gap-2"><label className="sr-only" htmlFor="application-count">当天总数</label><input id="application-count" type="number" min="0" max="500" step="1" value={countDraft} onChange={(event) => setCountDraft(event.target.value)} className="min-w-0 flex-1 rounded-2xl border border-stone-200 bg-white/80 px-4 py-3 text-sm outline-none focus:border-violet-400" /><button disabled={busy || !rhythm} className="rounded-2xl bg-violet-600 px-4 py-3 text-sm font-medium text-white hover:bg-violet-700 disabled:opacity-40">记总数</button></form>
            </section>
      {settingsOpen && <form onSubmit={saveSettings} className="rounded-[2rem] border border-white/80 bg-white/85 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] md:p-8"><h2 className="text-lg font-semibold">目标与提醒</h2><label className="mt-5 block text-xs text-stone-500">每周目标<input type="number" min="0" max="500" value={goalDraft} onChange={(event) => setGoalDraft(event.target.value)} className="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm text-stone-800 outline-none focus:border-violet-400" /></label><label className="mt-5 flex items-center gap-3 text-sm"><input type="checkbox" checked={reminderEnabled} onChange={(event) => setReminderEnabled(event.target.checked)} className="accent-violet-600" /><Bell className="size-4 text-violet-500" />工作台内提醒</label><div className="mt-4 flex items-center gap-3"><label className="text-xs text-stone-500">时间<input type="time" value={reminderTime} onChange={(event) => setReminderTime(event.target.value)} className="mt-2 block rounded-xl border border-stone-200 bg-white px-3 py-2 text-sm text-stone-800" /></label><label className="flex items-center gap-2 self-end pb-2 text-xs text-stone-600"><input type="checkbox" checked={weekdaysOnly} onChange={(event) => setWeekdaysOnly(event.target.checked)} className="accent-violet-600" />仅周一至周五</label></div><p className="mt-4 text-xs leading-5 text-stone-400">打开工作台后显示；不会在应用关闭时推送。</p><button disabled={busy} className="mt-5 inline-flex items-center gap-2 rounded-full bg-stone-900 px-5 py-2.5 text-sm text-white hover:bg-stone-700 disabled:opacity-40"><Check className="size-4" />保存</button></form>}
          </div>
        </div>
        <p className="mt-8 flex items-center gap-1.5 text-xs text-stone-400"><Minus className="size-3" />面试忙的时候，目标可以随时调低或暂停提醒。</p>
      </div>
    </main>
  );
}
