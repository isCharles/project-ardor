"use client";

import { Bell, Check, MessageCircle, Minus, Plus, Send, Sparkles, Target, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type DayCount = { date: string; greetingCount: number | null; resumeCount: number | null };
type Rhythm = {
  today: string;
  weekStart: string;
  todayGreetingCount: number | null;
  todayResumeCount: number | null;
  weeklyGreetingCount: number;
  weeklyResumeCount: number;
  weeklyGreetingRecordedDays: number;
  weeklyResumeRecordedDays: number;
  weeklyResumeGoal: number;
  weeklyGreetingGoal: number | null;
  reminderEnabled: boolean;
  reminderTime: string;
  weekdaysOnly: boolean;
  reminderDue: boolean;
  days: DayCount[];
  advice: string | null;
};

type Metric = "greeting" | "resume";

export default function ApplicationRhythmPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const weekdays = locale === "en" ? ["M", "T", "W", "T", "F", "S", "S"] : ["一", "二", "三", "四", "五", "六", "日"];
  const [rhythm, setRhythm] = useState<Rhythm | null>(null);
  const [metricView, setMetricView] = useState<Metric>("resume");
  const [selectedDate, setSelectedDate] = useState("");
  const [greetingDraft, setGreetingDraft] = useState("");
  const [resumeDraft, setResumeDraft] = useState("");
  const [resumeGoalDraft, setResumeGoalDraft] = useState("0");
  const [greetingGoalDraft, setGreetingGoalDraft] = useState("");
  const [reminderEnabled, setReminderEnabled] = useState(false);
  const [reminderTime, setReminderTime] = useState("19:00");
  const [weekdaysOnly, setWeekdaysOnly] = useState(true);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  function draftFor(day: DayCount | undefined, metric: Metric) {
    const value = metric === "greeting" ? day?.greetingCount : day?.resumeCount;
    return value == null ? "" : String(value);
  }

  function apply(next: Rhythm, keepDate = false, preserve: { greeting?: string; resume?: string } = {}) {
    setRhythm(next);
    setResumeGoalDraft(String(next.weeklyResumeGoal));
    setGreetingGoalDraft(next.weeklyGreetingGoal == null ? "" : String(next.weeklyGreetingGoal));
    setReminderEnabled(next.reminderEnabled);
    setReminderTime(next.reminderTime.slice(0, 5));
    setWeekdaysOnly(next.weekdaysOnly);
    const date = keepDate && selectedDate ? selectedDate : next.today;
    setSelectedDate(date);
    const day = next.days.find((item) => item.date === date);
    setGreetingDraft(preserve.greeting !== undefined ? preserve.greeting : draftFor(day, "greeting"));
    setResumeDraft(preserve.resume !== undefined ? preserve.resume : draftFor(day, "resume"));
  }

  useEffect(() => {
    let active = true;
    api<Rhythm>("/api/applications/rhythm")
      .then((result) => { if (active) apply(result); })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : t("Could not load application activity", "无法加载投递记录"));
      });
    return () => { active = false; };
    // Initial state is deliberately loaded once; later writes use their server response.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  function selectDay(day: DayCount) {
    setSelectedDate(day.date);
    setGreetingDraft(draftFor(day, "greeting"));
    setResumeDraft(draftFor(day, "resume"));
  }

  async function recordMetric(event: FormEvent<HTMLFormElement>, metric: Metric) {
    event.preventDefault();
    const raw = metric === "greeting" ? greetingDraft : resumeDraft;
    const count = Number(raw);
    if (raw.trim() === "" || !Number.isInteger(count) || count < 0 || count > 500) {
      setError(t("Enter a whole number from 0 to 500", "请输入 0 到 500 的整数"));
      return;
    }
    setBusy(true); setError("");
    try {
      const body = metric === "greeting" ? { greetingCount: count } : { resumeCount: count };
      const preserve = metric === "greeting" ? { resume: resumeDraft } : { greeting: greetingDraft };
      apply(await api<Rhythm>(`/api/applications/rhythm/days/${selectedDate}`, {
        method: "PUT", body: JSON.stringify(body),
      }), true, preserve);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not save", "保存失败")); }
    finally { setBusy(false); }
  }

  async function increment(metric: Metric) {
    setBusy(true); setError("");
    try {
      const preserve = metric === "greeting" ? { resume: resumeDraft } : { greeting: greetingDraft };
      apply(await api<Rhythm>(`/api/applications/rhythm/today/increment?metric=${metric.toUpperCase()}`, { method: "POST" }), false, preserve);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not record application activity", "记录失败")); }
    finally { setBusy(false); }
  }

  async function saveSettings(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const weeklyResumeGoal = Number(resumeGoalDraft);
    const weeklyGreetingGoal = greetingGoalDraft.trim() === "" ? 0 : Number(greetingGoalDraft);
    if (!Number.isInteger(weeklyResumeGoal) || weeklyResumeGoal < 0 || weeklyResumeGoal > 500
      || !Number.isInteger(weeklyGreetingGoal) || weeklyGreetingGoal < 0 || weeklyGreetingGoal > 500) {
      setError(t("Weekly goals must be between 0 and 500", "每周目标须在 0 到 500 之间"));
      return;
    }
    setBusy(true); setError("");
    try {
      apply(await api<Rhythm>("/api/applications/rhythm/settings", {
        method: "PUT", body: JSON.stringify({ weeklyResumeGoal, weeklyGreetingGoal, reminderEnabled, reminderTime, weekdaysOnly }),
      }), true, { greeting: greetingDraft, resume: resumeDraft });
      setSettingsOpen(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not save settings", "设置保存失败")); }
    finally { setBusy(false); }
  }

  async function dismiss() {
    try { apply(await api<Rhythm>("/api/applications/rhythm/reminder/dismiss", { method: "POST" }), true, { greeting: greetingDraft, resume: resumeDraft }); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not dismiss reminder", "暂时无法关闭提醒")); }
  }

  const selectedDay = rhythm?.days.find((day) => day.date === selectedDate);
  const showingGreeting = metricView === "greeting";
  const selectedWeeklyCount = (showingGreeting ? rhythm?.weeklyGreetingCount : rhythm?.weeklyResumeCount) ?? 0;
  const selectedTodayCount = showingGreeting ? rhythm?.todayGreetingCount : rhythm?.todayResumeCount;
  const selectedGoal = (showingGreeting ? rhythm?.weeklyGreetingGoal : rhythm?.weeklyResumeGoal) ?? 0;
  const selectedProgress = selectedGoal ? Math.min(100, Math.round(selectedWeeklyCount / selectedGoal * 100)) : 0;
  const selectedRecordedDays = (showingGreeting ? rhythm?.weeklyGreetingRecordedDays : rhythm?.weeklyResumeRecordedDays) ?? 0;
  const chartMax = Math.max(1, ...(rhythm?.days.map((day) => (showingGreeting ? day.greetingCount : day.resumeCount) ?? 0) ?? []));
  const elapsedDays = rhythm?.days.filter((day) => day.date <= rhythm.today).length ?? 0;
  const selectedMissingDays = Math.max(0, elapsedDays - selectedRecordedDays);
  const selectedMetricLabel = showingGreeting ? t("Greetings", "打招呼") : t("Resume sends", "简历投递");
  const selectedUnit = showingGreeting ? t("outreach", "次") : t("sent", "份");
  const showCount = (value: number | null | undefined) => value == null ? t("Not recorded", "未记录") : String(value);
  const barHeight = (value: number | null | undefined) => `${value ? Math.max(11, value / chartMax * 100) : 2}%`;
  const greetingMissingToday = rhythm != null && rhythm.todayGreetingCount == null;
  const resumeMissingToday = rhythm != null && rhythm.todayResumeCount == null;
  const reminderText = greetingMissingToday && resumeMissingToday
    ? t("Today's greetings and resume sends are not recorded yet.", "今天打招呼和简历投递都还没记录。")
    : greetingMissingToday
      ? t("Today's greeting count is not recorded yet.", "今天的打招呼数还没记录。")
      : t("Today's resume send count is not recorded yet.", "今天的简历投递数还没记录。");
  const advicePrompt = encodeURIComponent(locale === "en"
    ? "Review my current interview status (upcoming interviews, waiting for results, rejections), learning plans, weekly greetings and actual resume sends. Greetings and resume sends are separate; unrecorded resume days are not zero. Tell me whether I should increase, keep or reduce application pace, and which company/role types fit my current stage. Do not push me to apply when I have an upcoming interview; do not give generic encouragement. Save the advice to my application rhythm card."
    : "结合我现在的面试状态（有没有即将到来的面试、是否在等结果、是否连续被拒）、学习计划、本周打招呼数和实际简历投递数，判断我现在应该加大、维持还是降低投递强度，并建议适合我当前阶段的公司类型和岗位类型。打招呼和简历投递是两回事，未记录的简历天数不等于 0。不要泛泛鼓励；如果我有即将到来的面试，不要催投递。把建议保存到我的投递节奏里。");

  return (
    <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10">
      <div className="mx-auto max-w-5xl">
        <header className="flex flex-wrap items-start justify-between gap-5 pb-9 pt-12 md:pt-16">
          <div>
            <div className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-white/80 text-violet-600 shadow-sm"><Target className="size-6" /></div>
            <h1 className="text-4xl font-semibold tracking-[-0.055em] md:text-6xl">{t("Application rhythm", "投递节奏")}</h1>
            <p className="mt-3 text-sm text-stone-500">{t("Two daily totals: greetings and resumes actually sent.", "每天只记两个总数：打招呼，和真正发出的简历。")}</p>
          </div>
          <Link href={`/app?new=1&prefill=${advicePrompt}`} className="mt-1 inline-flex shrink-0 items-center gap-1.5 rounded-full bg-violet-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-violet-700"><Sparkles className="size-4" />{t("Ask Ardor", "让 Ardor 判断")}</Link>
        </header>
        {error && <div role="alert" className="mb-5 rounded-2xl bg-rose-50 px-5 py-4 text-sm text-rose-700">{error}</div>}
        {rhythm?.reminderDue && <div className="mb-5 flex items-center gap-3 rounded-3xl border border-violet-100 bg-white/85 px-5 py-4 text-sm shadow-sm"><Bell className="size-5 shrink-0 text-violet-500" /><span className="flex-1">{reminderText}</span><button onClick={() => void dismiss()} aria-label={t("Dismiss today's reminder", "今天不再提醒")} className="rounded-full p-2 text-stone-400 hover:bg-stone-100"><X className="size-4" /></button></div>}
        <div className="grid gap-5 lg:grid-cols-[1.25fr_0.75fr]">
          <section className="rounded-[2rem] border border-white/80 bg-white/80 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] backdrop-blur-xl md:p-8">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div role="tablist" aria-label={t("Metric to show", "切换查看的指标")} className="inline-flex rounded-full bg-stone-100 p-1">
                <button type="button" role="tab" aria-selected={!showingGreeting} onClick={() => setMetricView("resume")} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium transition ${!showingGreeting ? "bg-rose-500 text-white shadow-sm" : "text-stone-500 hover:text-stone-800"}`}><Send className="size-3.5" />{t("Resume sends", "简历投递")}</button>
                <button type="button" role="tab" aria-selected={showingGreeting} onClick={() => setMetricView("greeting")} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium transition ${showingGreeting ? "bg-violet-600 text-white shadow-sm" : "text-stone-500 hover:text-stone-800"}`}><MessageCircle className="size-3.5" />{t("Greetings", "打招呼")}</button>
              </div>
              <button onClick={() => setSettingsOpen((open) => !open)} className="shrink-0 rounded-full border border-stone-200 bg-white px-4 py-2 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-600">{settingsOpen ? t("Hide settings", "收起设置") : t("Goals & reminders", "目标与提醒")}</button>
            </div>
            <div className="mt-7">
              <p className="flex items-center gap-2 text-sm text-stone-500">{showingGreeting ? <MessageCircle className="size-4 text-violet-500" /> : <Send className="size-4 text-rose-500" />}{t("This week", "本周")} · {selectedMetricLabel}</p>
              <p className="mt-2 text-6xl font-semibold tabular-nums tracking-[-0.07em]">{rhythm ? selectedWeeklyCount : "—"}<span className="ml-2 text-xl font-normal text-stone-400">{selectedUnit}</span></p>
              <p className="mt-2 text-xs text-stone-400">{t("Today", "今天")} {showCount(selectedTodayCount)}</p>
              <div className="mt-5 h-2 overflow-hidden rounded-full bg-stone-100"><div className={`h-full rounded-full transition-[width] ${showingGreeting ? "bg-violet-500" : "bg-gradient-to-r from-rose-400 to-violet-500"}`} style={{ width: `${selectedProgress}%` }} /></div>
              <p className="mt-2 text-xs text-stone-400">{selectedGoal ? t(`Goal ${selectedGoal} ${showingGreeting ? "greetings" : "resumes"} · ${selectedProgress}%`, `目标 ${selectedGoal} ${selectedUnit} · 已完成 ${selectedProgress}%`) : t("No weekly goal for this metric yet", "这个指标还没有设置每周目标")}</p>
            </div>
            <div className="mt-8 flex items-center justify-between text-xs text-stone-400"><span>{t("Daily total this week", "本周每日总数")}</span><span>{selectedMetricLabel}</span></div>
            <div className="mt-3 grid grid-cols-7 gap-2" aria-label={t("Daily totals by day this week", "本周每日总数")}>
              {rhythm?.days.map((day, index) => {
                const value = showingGreeting ? day.greetingCount : day.resumeCount;
                return <button key={day.date} type="button" disabled={day.date > rhythm.today} onClick={() => selectDay(day)} className={`flex min-w-0 flex-col items-center rounded-2xl px-1 py-3 transition ${selectedDate === day.date ? (showingGreeting ? "bg-violet-100/80 text-violet-800" : "bg-rose-50 text-rose-700") : "hover:bg-stone-50"} disabled:cursor-default disabled:opacity-35`}>
                  <span className="text-xs text-stone-500">{weekdays[index]}</span>
                  <span className="mt-3 flex h-20 w-full items-end justify-center"><span className={`w-3 rounded-full transition-[height] ${showingGreeting ? "bg-violet-400" : "bg-rose-400"}`} style={{ height: barHeight(value) }} /></span>
                  <span className={`mt-2 text-sm font-medium tabular-nums ${showingGreeting ? "text-violet-600" : "text-rose-500"}`}>{value ?? "—"}</span>
                </button>;
              })}
            </div>
            <div className="mt-4 space-y-1 text-xs leading-5 text-stone-400">
              {selectedMissingDays > 0 && <p>{selectedMissingDays === 1
                ? (showingGreeting ? t("1 day this week has no recorded greeting count.", "本周有 1 天未记录打招呼数。") : t("1 day this week has no recorded resume count; unrecorded is not zero.", "本周有 1 天未记录简历投递数；未记录不按 0 计算。"))
                : (showingGreeting ? t(`${selectedMissingDays} days this week have no recorded greeting count.`, `本周有 ${selectedMissingDays} 天未记录打招呼数。`) : t(`${selectedMissingDays} days this week have no recorded resume count; unrecorded is not zero.`, `本周有 ${selectedMissingDays} 天未记录简历投递数；未记录不按 0 计算。`))}</p>}
              <p>{t("Legacy note: earlier numbers were greetings. Old resume counts stay unrecorded; they are not inferred from replies.", "旧记录说明：此前页面上的数字是打招呼数。历史简历投递数标为未记录，不填 0，也不按回复率推算。")}</p>
              {rhythm?.weeklyGreetingGoal != null && rhythm.weeklyResumeGoal === 0 && <p>{t("The old weekly goal is kept as a greeting goal; set a resume goal separately.", "旧版每周目标已按原口径归为打招呼目标；简历目标尚未设置。")}</p>}
            </div>
          </section>
          <div className="space-y-5">
            <section className="rounded-[2rem] border border-white/80 bg-white/80 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] backdrop-blur-xl md:p-8">
              <p className="text-sm text-stone-500">{selectedDate === rhythm?.today ? t("Today", "今天") : selectedDate}</p>
              <form onSubmit={(event) => void recordMetric(event, "greeting")} className="mt-4 rounded-2xl bg-stone-50/80 p-4">
                <div className="flex items-center justify-between gap-3"><label htmlFor="greeting-count" className="flex items-center gap-2 text-sm font-medium"><MessageCircle className="size-4 text-violet-500" />{t("Greetings", "打招呼")}</label><span className="text-2xl font-semibold tabular-nums">{showCount(selectedDay?.greetingCount)}</span></div>
                <div className="mt-3 flex items-center gap-2"><input id="greeting-count" type="number" min="0" max="500" step="1" value={greetingDraft} placeholder={t("Not recorded", "未记录")} onChange={(event) => setGreetingDraft(event.target.value)} className="min-w-0 flex-1 rounded-2xl border border-stone-200 bg-white px-4 py-2.5 text-sm outline-none focus:border-violet-400" /><button disabled={busy || !rhythm} className="rounded-2xl bg-violet-600 px-3 py-2.5 text-sm font-medium text-white hover:bg-violet-700 disabled:opacity-40">{t("Save", "保存")}</button>{selectedDate === rhythm?.today && <button type="button" disabled={busy || !rhythm} onClick={() => void increment("greeting")} aria-label={t("Add one greeting", "打招呼加一")} className="rounded-2xl border border-stone-200 bg-white p-2.5 text-stone-600 hover:border-violet-300 hover:text-violet-600 disabled:opacity-40"><Plus className="size-4" /></button>}</div>
              </form>
              <form onSubmit={(event) => void recordMetric(event, "resume")} className="mt-3 rounded-2xl bg-stone-50/80 p-4">
                <div className="flex items-center justify-between gap-3"><label htmlFor="resume-count" className="flex items-center gap-2 text-sm font-medium"><Send className="size-4 text-rose-500" />{t("Resumes sent", "简历投递")}</label><span className="text-2xl font-semibold tabular-nums">{showCount(selectedDay?.resumeCount)}</span></div>
                <p className="mt-1 text-[11px] text-stone-400">{t("Only actual sends; a reply is not a send.", "只算实际发出；收到回复不算投递。")}</p>
                <div className="mt-3 flex items-center gap-2"><input id="resume-count" type="number" min="0" max="500" step="1" value={resumeDraft} placeholder={t("Not recorded", "未记录")} onChange={(event) => setResumeDraft(event.target.value)} className="min-w-0 flex-1 rounded-2xl border border-stone-200 bg-white px-4 py-2.5 text-sm outline-none focus:border-rose-400" /><button disabled={busy || !rhythm} className="rounded-2xl bg-rose-500 px-3 py-2.5 text-sm font-medium text-white hover:bg-rose-600 disabled:opacity-40">{t("Save", "保存")}</button>{selectedDate === rhythm?.today && <button type="button" disabled={busy || !rhythm} onClick={() => void increment("resume")} aria-label={t("Add one resume send", "简历投递加一")} className="rounded-2xl border border-stone-200 bg-white p-2.5 text-stone-600 hover:border-rose-300 hover:text-rose-600 disabled:opacity-40"><Plus className="size-4" /></button>}</div>
              </form>
            </section>
            {settingsOpen && <form onSubmit={saveSettings} className="rounded-[2rem] border border-white/80 bg-white/85 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] md:p-8"><h2 className="text-lg font-semibold">{t("Goals & reminders", "目标与提醒")}</h2><label className="mt-5 block text-xs text-stone-500">{t("Weekly resume goal", "每周简历投递目标")}<input type="number" min="0" max="500" value={resumeGoalDraft} onChange={(event) => setResumeGoalDraft(event.target.value)} className="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm text-stone-800 outline-none focus:border-rose-400" /></label><p className="mt-1.5 text-[11px] text-stone-400">{t("The main goal. It only counts resumes actually sent.", "主目标，只衡量实际发出的简历。")}</p><label className="mt-4 block text-xs text-stone-500">{t("Weekly greeting goal (optional)", "每周打招呼目标（可选）")}<input type="number" min="0" max="500" value={greetingGoalDraft} placeholder={t("Not set", "不设置")} onChange={(event) => setGreetingGoalDraft(event.target.value)} className="mt-2 w-full rounded-2xl border border-stone-200 bg-white px-4 py-3 text-sm text-stone-800 outline-none focus:border-violet-400" /></label><p className="mt-1.5 text-[11px] text-stone-400">{t("Tracked separately; it never fills in or borrows from the resume goal.", "单独计算，不会补足或借用简历目标。")}</p><label className="mt-5 flex items-center gap-3 text-sm"><input type="checkbox" checked={reminderEnabled} onChange={(event) => setReminderEnabled(event.target.checked)} className="accent-violet-600" /><Bell className="size-4 text-violet-500" />{t("In-app reminder", "工作台内提醒")}</label><div className="mt-4 flex items-center gap-3"><label className="text-xs text-stone-500">{t("Time", "时间")}<input type="time" value={reminderTime} onChange={(event) => setReminderTime(event.target.value)} className="mt-2 block rounded-xl border border-stone-200 bg-white px-3 py-2 text-sm text-stone-800" /></label><label className="flex items-center gap-2 self-end pb-2 text-xs text-stone-600"><input type="checkbox" checked={weekdaysOnly} onChange={(event) => setWeekdaysOnly(event.target.checked)} className="accent-violet-600" />{t("Weekdays only", "仅周一至周五")}</label></div><p className="mt-4 text-xs leading-5 text-stone-400">{t("Shown when you open Ardor; no push notifications while closed.", "打开工作台后显示；不会在应用关闭时推送。")}</p><button disabled={busy} className="mt-5 inline-flex items-center gap-2 rounded-full bg-stone-900 px-5 py-2.5 text-sm text-white hover:bg-stone-700 disabled:opacity-40"><Check className="size-4" />{t("Save", "保存")}</button></form>}
          </div>
        </div>
        <section className="mt-6 rounded-[2rem] border border-violet-100 bg-gradient-to-br from-violet-50/80 via-white/80 to-rose-50/60 p-6 md:p-8">
          <div className="flex items-center gap-2"><Sparkles className="size-5 text-violet-600" /><h2 className="text-lg font-semibold">{t("Ardor's read", "Ardor 的建议")}</h2></div>
          {rhythm?.advice
            ? <p className="mt-4 whitespace-pre-wrap text-sm leading-7 text-stone-700">{rhythm.advice}</p>
            : <p className="mt-4 text-sm text-stone-500">{t("No advice yet. Ask Ardor to review your interview status, greetings and resume sends; it will save the verdict here.", "还没有建议。点“让 Ardor 判断”，它会结合面试状态、打招呼和简历投递给出判断并保存到这里。")}</p>}
        </section>
        <p className="mt-8 flex items-center gap-1.5 text-xs text-stone-400"><Minus className="size-3" />{t("You can lower a goal or pause reminders anytime.", "面试忙的时候，目标可以随时调低或暂停提醒。")}</p>
      </div>
    </main>
  );
}
