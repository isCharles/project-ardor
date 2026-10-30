"use client";

import {
  BrainCircuit, CalendarClock, Check, Clock3, Mail, Play, Plus, Sparkles, Trash2, TrendingUp,
} from "lucide-react";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import { AppDialog } from "@/components/ui/app-dialog";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Automation = {
  id: string;
  title: string;
  prompt: string;
  cronExpression: string;
  timezone: string;
  enabled: boolean;
  deleteAfterRun: boolean;
  nextRunAt: string | null;
  lastRunAt: string | null;
  lastStatus: string | null;
  lastError: string | null;
  createdAt: string;
  updatedAt: string;
};

type Frequency = "daily" | "weekly" | "monthly" | "custom";
type FormState = {
  id?: string;
  title: string;
  prompt: string;
  frequency: Frequency;
  time: string;
  weekday: string;
  dayOfMonth: string;
  customCron: string;
  timezone: string;
  deleteAfterRun: boolean;
};

const EXAMPLE_ICONS = [CalendarClock, Mail, TrendingUp, BrainCircuit];

function pad(value: string | number) { return String(value).padStart(2, "0"); }

function buildCron(frequency: Frequency, time: string, weekday: string, dayOfMonth: string, custom: string) {
  if (frequency === "custom") return custom.trim();
  const [hour = "09", minute = "00"] = time.split(":");
  if (frequency === "weekly") return `0 ${Number(minute)} ${Number(hour)} * * ${Number(weekday)}`;
  if (frequency === "monthly") return `0 ${Number(minute)} ${Number(hour)} ${Number(dayOfMonth)} * *`;
  return `0 ${Number(minute)} ${Number(hour)} * * *`;
}

function parseCron(cron: string): { frequency: Frequency; time: string; weekday: string; dayOfMonth: string } | null {
  const parts = cron.trim().split(/\s+/);
  if (parts.length !== 6) return null;
  const [second, minute, hour, day, month, dow] = parts;
  if (![second, minute, hour].every(part => /^\d+$/.test(part)) || month !== "*") return null;
  const time = `${pad(hour)}:${pad(minute)}`;
  if (day === "*" && dow === "*") return { frequency: "daily", time, weekday: "1", dayOfMonth: "1" };
  if (day === "*" && /^\d+$/.test(dow)) return { frequency: "weekly", time, weekday: dow, dayOfMonth: "1" };
  if (dow === "*" && /^\d+$/.test(day)) return { frequency: "monthly", time, weekday: "1", dayOfMonth: day };
  return null;
}

function formatWhen(value: string | null, locale: string) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString(locale, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function frequencyLabel(frequency: Frequency, locale: string) {
  const labels: Record<Frequency, [string, string]> = {
    daily: ["Every day", "每天"], weekly: ["Every week", "每周"], monthly: ["Every month", "每月"], custom: ["Custom schedule", "自定义"],
  };
  return locale === "en" ? labels[frequency][0] : labels[frequency][1];
}

export default function AutomationsPage() {
  const { locale, t } = useLocale();
  const [items, setItems] = useState<Automation[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState<{ conversationId: string; title: string } | null>(null);
  const [running, setRunning] = useState(false);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const value = await api<Automation[]>("/api/automations");
      setItems(Array.isArray(value) ? value : []);
      setError("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not load scheduled tasks", "无法加载自动化任务"));
    } finally { setLoading(false); }
  }, [t]);

  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load]);

  const selected = items.find(item => item.id === selectedId) ?? null;
  const upcoming = useMemo(() => [...items].sort((left, right) => {
    if (left.enabled !== right.enabled) return left.enabled ? -1 : 1;
    return (left.nextRunAt ?? "9999").localeCompare(right.nextRunAt ?? "9999");
  }), [items]);

  function openCreate(preset?: { title: string; prompt: string; frequency?: Frequency; time?: string }) {
    setForm({
      title: preset?.title ?? "",
      prompt: preset?.prompt ?? "",
      frequency: preset?.frequency ?? "daily",
      time: preset?.time ?? "09:00",
      weekday: "1",
      dayOfMonth: "1",
      customCron: "0 0 9 * * *",
      timezone: "Asia/Shanghai",
      deleteAfterRun: false,
    });
    setError("");
  }

  function openEdit(item: Automation) {
    const parsed = parseCron(item.cronExpression);
    setForm({
      id: item.id,
      title: item.title,
      prompt: item.prompt,
      frequency: parsed?.frequency ?? "custom",
      time: parsed?.time ?? "09:00",
      weekday: parsed?.weekday ?? "1",
      dayOfMonth: parsed?.dayOfMonth ?? "1",
      customCron: item.cronExpression,
      timezone: item.timezone || "Asia/Shanghai",
      deleteAfterRun: item.deleteAfterRun,
    });
    setError("");
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!form) return;
    const cron = buildCron(form.frequency, form.time, form.weekday, form.dayOfMonth, form.customCron);
    if (!cron) { setError(t("Custom schedule cannot be empty", "自定义 cron 不能为空")); return; }
    setBusy(true); setError("");
    try {
      if (form.id) {
        await api<Automation>(`/api/automations/${form.id}`, {
          method: "PATCH",
          body: JSON.stringify({ title: form.title, prompt: form.prompt, cronExpression: cron, timezone: form.timezone, deleteAfterRun: form.deleteAfterRun }),
        });
      } else {
        await api<Automation>("/api/automations", {
          method: "POST",
          body: JSON.stringify({ title: form.title, prompt: form.prompt, cronExpression: cron, timezone: form.timezone, deleteAfterRun: form.deleteAfterRun }),
        });
      }
      setForm(null);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not save scheduled task", "无法保存自动化任务"));
    } finally { setBusy(false); }
  }

  async function runNow(item: Automation) {
    setRunning(true); setError(""); setNotice(null);
    try {
      const result = await api<{ conversationId: string; title: string }>(`/api/automations/${item.id}/run`, { method: "POST" });
      setNotice(result);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not run scheduled task", "无法运行自动化任务"));
      await load();
    } finally { setRunning(false); }
  }

  async function toggle(item: Automation, enabled: boolean) {
    setBusy(true); setError("");
    try {
      await api<Automation>(`/api/automations/${item.id}`, { method: "PATCH", body: JSON.stringify({ enabled }) });
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not update scheduled task", "无法更新自动化任务"));
    } finally { setBusy(false); }
  }

  async function remove(item: Automation) {
    if (!window.confirm(t(`Delete “${item.title}”? Future runs will stop.`, `删除「${item.title}」？之后的定时运行会停止。`))) return;
    setBusy(true); setError("");
    try {
      await api<void>(`/api/automations/${item.id}`, { method: "DELETE" });
      if (selectedId === item.id) setSelectedId(null);
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not delete scheduled task", "无法删除自动化任务"));
    } finally { setBusy(false); }
  }

  const examples = [
    {
      title: t("Daily career review", "每日求职回顾"),
      prompt: t("Review my calendar, interview status, weekly greetings, actual resume sends and learning progress every morning. Keep greetings and resume sends separate. Tell me the three most important things to do today. Save the advice to my calendar card.", "每天早上检查我的日历、面试状态、本周打招呼数、实际简历投递数和学习进度，不要把打招呼和简历混在一起。告诉我今天最该做的三件事。把建议保存到日历建议里。"),
    },
    {
      title: t("Follow up on interviews", "跟进等结果的面试"),
      prompt: t("Check interviews that are waiting for a result. If one has been waiting too long, remind me to follow up with the recruiter and what to say.", "检查正在等结果的面试。如果等待时间过长，提醒我联系 HR 跟进，并给我一段跟进话术。"),
    },
    {
      title: t("Weekly application check", "每周投递检查"),
      prompt: t("Review my application pace this week using greetings and actual resume sends as separate numbers. Compare them with my interview status and tell me whether to increase, keep or reduce effort, and which company and role types fit my current stage. Unrecorded resume days are not zero. Save the advice to my application rhythm card as brief Markdown: one-line verdict plus at most 3 one-line actions.", "检查我本周的投递节奏，把打招呼数和实际简历投递数分开看，结合面试状态判断该加大、维持还是降低投入，并建议适合当前阶段的公司类型和岗位类型。未记录的简历投递天数不等于 0。用简短的 Markdown 把建议保存到投递节奏：一句结论，加最多 3 条行动。"),
    },
    {
      title: t("Learning follow-up", "学习计划跟进"),
      prompt: t("Check my learning plans and flashcards. Tell me which overdue or in-progress plan should be finished next, and arrange a concrete next step.", "检查我的学习计划和记忆卡，告诉我下一个该完成哪个逾期或进行中的计划，并安排一个具体的下一步。"),
    },
  ];

  return <main className="ardor-workbench min-h-screen text-[#1d1d1f]">
    <div className="mx-auto grid min-h-screen max-w-6xl gap-6 px-5 py-6 md:px-10 lg:grid-cols-[16rem_minmax(0,1fr)]">
      <aside className="min-w-0">
        <div className="flex items-center justify-between gap-2">
          <h1 className="text-2xl font-semibold tracking-tight">{t("Scheduled", "自动化任务")}</h1>
          <button type="button" aria-label={t("New task", "新建任务")} onClick={() => openCreate()} className="grid size-8 place-items-center rounded-full text-stone-500 hover:bg-white hover:text-violet-700"><Plus className="size-4" /></button>
        </div>
        <button type="button" onClick={() => openCreate()} className="mt-5 flex w-full items-center gap-2 rounded-2xl px-3 py-2.5 text-left text-sm font-medium text-stone-700 hover:bg-white/70"><Plus className="size-4 text-violet-600" />{t("New task", "新建任务")}</button>
        <p className="mt-6 text-[11px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Upcoming", "即将运行")}</p>
        <div className="mt-2 space-y-1">
          {loading && <p className="px-3 py-2 text-xs text-stone-400">{t("Loading…", "加载中…")}</p>}
          {!loading && upcoming.length === 0 && <p className="px-3 py-2 text-xs leading-5 text-stone-400">{t("No scheduled tasks yet.", "还没有自动化任务。")}</p>}
          {upcoming.map(item => <button key={item.id} type="button" onClick={() => setSelectedId(item.id)} className={`w-full rounded-2xl px-3 py-2.5 text-left transition ${selectedId === item.id ? "bg-white shadow-sm" : "hover:bg-white/60"}`}>
            <span className={`block truncate text-sm font-medium ${item.enabled ? "text-stone-800" : "text-stone-400"}`}>{item.title}</span>
            <span className="mt-1 block text-[11px] text-stone-400">{item.enabled ? formatWhen(item.nextRunAt, locale) : t("Paused", "已暂停")}</span>
          </button>)}
        </div>
      </aside>

      <section className="min-w-0">
        {error && <div role="alert" className="mb-4 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
        {notice && <div role="status" className="mb-4 flex flex-wrap items-center gap-2 rounded-2xl bg-emerald-50 px-4 py-3 text-sm text-emerald-800">{t("Ran now. Ardor replied in a new conversation.", "已立即运行，Ardor 已在新对话里回复。")}<a href={`/app?conversation=${notice.conversationId}`} className="font-medium underline">{t("Open conversation", "打开对话")}</a></div>}
        {selected ? <AutomationDetail item={selected} busy={busy} running={running} locale={locale} t={t} onRun={() => void runNow(selected)} onToggle={enabled => void toggle(selected, enabled)} onEdit={() => openEdit(selected)} onDelete={() => void remove(selected)} /> : <>
          <div className="flex flex-col items-center pb-8 pt-10 text-center">
            <span className="grid size-14 place-items-center rounded-full bg-white/80 text-violet-600 shadow-sm"><Clock3 className="size-7" /></span>
            <h2 className="mt-5 text-2xl font-semibold tracking-tight">{t("Schedule a task", "安排一个自动化任务")}</h2>
            <p className="mt-2 max-w-md text-sm leading-6 text-stone-500">{t("Ardor can keep checking your career state and message you when there is something to do.", "Ardor 可以持续检查你的求职状态，在需要的时候主动给你结论。")}</p>
            <p className="mt-3 max-w-md text-xs leading-5 text-stone-400">{t("Runs on the Ardor server. When due, it creates a new conversation and writes Ardor's reply there. The backend must be running; missed runs are picked up on the next check.", "任务由 Ardor 服务端调度。到点会新建一个对话，把 Ardor 的回复写进去。需要后端保持运行；如果到点时服务不在，下一次检查会补跑。")}</p>
          </div>
          <div className="mx-auto grid max-w-3xl gap-3 sm:grid-cols-2">
            {examples.map((example, index) => {
              const Icon = EXAMPLE_ICONS[index] ?? Sparkles;
              return <button key={example.title} type="button" onClick={() => openCreate({ title: example.title, prompt: example.prompt })}
                className="flex min-h-28 items-start gap-3 rounded-3xl border border-stone-200/70 bg-white/55 p-4 text-left transition hover:border-violet-200 hover:bg-white">
                <span className="grid size-9 shrink-0 place-items-center rounded-2xl bg-violet-50 text-violet-600"><Icon className="size-4" /></span>
                <span className="min-w-0"><span className="block text-sm font-medium text-stone-800">{example.title}</span><span className="mt-1 line-clamp-2 block text-xs leading-5 text-stone-500">{example.prompt}</span></span>
              </button>;
            })}
          </div>
        </>}
      </section>
    </div>

    {form && <AppDialog title={form.id ? t("Edit scheduled task", "编辑自动化任务") : t("New scheduled task", "新建自动化任务")} onClose={() => setForm(null)}>
      <form onSubmit={save} className="space-y-4">
        <label className="block text-sm">{t("Name", "名称")}<input required maxLength={160} value={form.title} onChange={event => setForm({ ...form, title: event.target.value })} className="field mt-2" placeholder={t("e.g. Daily career review", "例如：每日求职回顾")} /></label>
        <label className="block text-sm">{t("What should Ardor do each time?", "每次让 Ardor 做什么？")}<textarea required maxLength={20000} rows={5} value={form.prompt} onChange={event => setForm({ ...form, prompt: event.target.value })} className="field mt-2 resize-y" placeholder={t("Check my interviews, applications and learning progress, then tell me what to do today.", "检查我的面试、投递和学习进度，告诉我今天该做什么。")} /></label>
        <div className="grid gap-3 sm:grid-cols-[1fr_1fr]">
          <label className="block text-sm">{t("Repeat", "频率")}<select value={form.frequency} onChange={event => setForm({ ...form, frequency: event.target.value as Frequency })} className="field mt-2">
            {(["daily", "weekly", "monthly", "custom"] as Frequency[]).map(frequency => <option key={frequency} value={frequency}>{frequencyLabel(frequency, locale)}</option>)}
          </select></label>
          {form.frequency !== "custom" && <label className="block text-sm">{t("Time", "时间")}<input type="time" value={form.time} onChange={event => setForm({ ...form, time: event.target.value })} className="field mt-2" /></label>}
        </div>
        {form.frequency === "weekly" && <label className="block text-sm">{t("Day of week", "星期几")}<select value={form.weekday} onChange={event => setForm({ ...form, weekday: event.target.value })} className="field mt-2">
          {[["1", "Monday", "周一"], ["2", "Tuesday", "周二"], ["3", "Wednesday", "周三"], ["4", "Thursday", "周四"], ["5", "Friday", "周五"], ["6", "Saturday", "周六"], ["7", "Sunday", "周日"]].map(([value, en, zh]) => <option key={value} value={value}>{locale === "en" ? en : zh}</option>)}
        </select></label>}
        {form.frequency === "monthly" && <label className="block text-sm">{t("Day of month", "每月几号")}<input type="number" min="1" max="28" value={form.dayOfMonth} onChange={event => setForm({ ...form, dayOfMonth: event.target.value })} className="field mt-2" /></label>}
        {form.frequency === "custom" && <label className="block text-sm">{t("Cron expression", "Cron 表达式")}<input required value={form.customCron} onChange={event => setForm({ ...form, customCron: event.target.value })} className="field mt-2 font-mono" placeholder="0 0 9 * * *" /></label>}
        <label className="block text-sm">{t("Timezone", "时区")}<input required value={form.timezone} onChange={event => setForm({ ...form, timezone: event.target.value })} className="field mt-2" placeholder="Asia/Shanghai" /></label>
        <label className="flex items-start gap-3 rounded-2xl border border-stone-200/70 bg-white/60 px-4 py-3 text-sm">
          <input type="checkbox" checked={form.deleteAfterRun} onChange={event => setForm({ ...form, deleteAfterRun: event.target.checked })} className="mt-0.5 size-4 accent-violet-600" />
          <span><span className="font-medium">{t("Run only once", "只运行一次")}</span><span className="mt-0.5 block text-xs leading-5 text-stone-500">{t("Delete after a successful run. On failure, pause and keep it for inspection.", "成功后自动删除；失败会暂停并保留任务，方便检查。")}</span></span>
        </label>
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={() => setForm(null)} className="rounded-full px-4 py-2 text-sm text-stone-500 hover:bg-stone-100">{t("Cancel", "取消")}</button>
          <button disabled={busy} className="rounded-full bg-[#1d1d1f] px-5 py-2 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Saving…", "保存中…") : t("Save", "保存")}</button>
        </div>
      </form>
    </AppDialog>}
  </main>;
}

function AutomationDetail({ item, busy, running, locale, t, onRun, onToggle, onEdit, onDelete }: {
  item: Automation; busy: boolean; running: boolean; locale: string; t: (en: string, zh: string) => string;
  onRun: () => void; onToggle: (enabled: boolean) => void; onEdit: () => void; onDelete: () => void;
}) {
  const parsed = parseCron(item.cronExpression);
  const schedule = parsed ? `${frequencyLabel(parsed.frequency, locale)} · ${parsed.time}` : item.cronExpression;
  return <div className="rounded-[2rem] border border-white/80 bg-white/70 p-6 shadow-[0_18px_60px_rgba(49,39,70,0.06)] md:p-8">
    <div className="flex flex-wrap items-start justify-between gap-4">
      <div><p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">{t("SCHEDULED TASK", "自动化任务")}</p><h2 className="mt-2 text-2xl font-semibold tracking-tight">{item.title}</h2><p className="mt-2 text-sm text-stone-500">{schedule} · {item.timezone}{item.deleteAfterRun ? ` · ${t("One-time", "只运行一次")}` : ""}</p></div>
      <div className="flex items-center gap-2">
        <button type="button" disabled={busy} onClick={() => onToggle(!item.enabled)} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-2 text-xs font-medium ${item.enabled ? "bg-emerald-50 text-emerald-700" : "bg-stone-100 text-stone-500"}`}>{item.enabled ? <Check className="size-3.5" /> : <Clock3 className="size-3.5" />}{item.enabled ? t("Enabled", "已启用") : t("Paused", "已暂停")}</button>
        <button type="button" disabled={running || busy} onClick={onRun} className="inline-flex items-center gap-1.5 rounded-full bg-violet-600 px-3 py-2 text-xs font-medium text-white hover:bg-violet-700 disabled:opacity-40"><Play className="size-3.5" />{running ? t("Running…", "运行中…") : t("Run now", "立即运行")}</button>
        <button type="button" onClick={onEdit} className="rounded-full border border-stone-200 bg-white px-3 py-2 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-700">{t("Edit", "编辑")}</button>
        <button type="button" aria-label={t("Delete", "删除")} onClick={onDelete} className="rounded-full p-2 text-stone-300 hover:bg-rose-50 hover:text-rose-600"><Trash2 className="size-4" /></button>
      </div>
    </div>
    <div className="mt-6 grid gap-3 sm:grid-cols-2">
      <div className="rounded-2xl bg-white/70 px-4 py-3"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Next run", "下次运行")}</p><p className="mt-1 text-sm text-stone-700">{item.enabled ? formatWhen(item.nextRunAt, locale) || "—" : t("Paused", "已暂停")}</p></div>
      <div className="rounded-2xl bg-white/70 px-4 py-3"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Last run", "上次运行")}</p><p className="mt-1 text-sm text-stone-700">{formatWhen(item.lastRunAt, locale) || t("Not run yet", "尚未运行")}{item.lastStatus ? ` · ${item.lastStatus}` : ""}</p></div>
    </div>
    <div className="mt-6 rounded-2xl border border-stone-100 bg-[#fbfafc] p-4"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-violet-600">{t("Prompt", "每次发送的消息")}</p><p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-stone-600">{item.prompt}</p></div>
    {item.lastError && <p className="mt-4 rounded-xl bg-rose-50 px-3 py-2 text-xs text-rose-700">{item.lastError}</p>}
  </div>;
}
