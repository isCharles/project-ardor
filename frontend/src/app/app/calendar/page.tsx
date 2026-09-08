"use client";

import { ArrowLeft, ArrowRight, BrainCircuit, Check, ChevronLeft, ChevronRight, Clock3, GraduationCap, Pencil, Plus, Repeat, Sparkles, Trash2, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { chinaDayInfo } from "@/lib/china-holidays";

type TaskStatus = "TODO" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
type TaskPriority = "LOW" | "MEDIUM" | "HIGH";
type CalendarTask = {
  id: string;
  title: string;
  description: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  source: "MANUAL" | "AGENT";
  taskKind: "GENERAL" | "MEMORY_REVIEW" | "LEARNING";
  actionPath: string | null;
  seriesId: string | null;
  dueAt: string | null;
  completedAt: string | null;
};
/* A repeating arrangement. The rule lives on the server; every occurrence is
   already an ordinary task in the list above, so the calendar needs nothing
   special to draw them — only a way to see and stop the rule itself. */
type TaskSeries = {
  id: string;
  title: string;
  summary: string;
  status: "ACTIVE" | "ENDED" | "CANCELLED";
  upcoming: string[];
};

const WEEKDAYS = ["一", "二", "三", "四", "五", "六", "日"];
const priorityStyle: Record<TaskPriority, string> = { HIGH: "bg-rose-500", MEDIUM: "bg-violet-500", LOW: "bg-sky-500" };

function dayKey(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
function dayStart(value: Date) { return new Date(value.getFullYear(), value.getMonth(), value.getDate()); }
function monthCells(month: Date) {
  const first = new Date(month.getFullYear(), month.getMonth(), 1);
  const start = new Date(first);
  start.setDate(first.getDate() - ((first.getDay() + 6) % 7));
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start); date.setDate(start.getDate() + index); return date;
  });
}
function localInputValue(iso: string | null, fallbackDay: Date) {
  const date = iso ? new Date(iso) : new Date(fallbackDay.getFullYear(), fallbackDay.getMonth(), fallbackDay.getDate(), 9);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

function calendarLabel(task: CalendarTask) {
  if (task.taskKind === "MEMORY_REVIEW") return "复习卡片";
  if (task.taskKind === "LEARNING") return task.title.length <= 8 ? task.title : `${task.title.slice(0, 7)}…`;
  const title = task.title.trim();
  if (title.length <= 8) return title;
  const context = `${title} ${task.description ?? ""}`;
  const companyAliases: Array<[RegExp, string]> = [
    [/(字节跳动|抖音|今日头条)/, "字节"],
    [/(阿里巴巴|阿里|淘天|蚂蚁集团)/, "阿里"],
    [/(腾讯|微信)/, "腾讯"],
    [/(美团|大众点评)/, "美团"],
    [/(百度)/, "百度"],
    [/(京东)/, "京东"],
    [/(快手)/, "快手"],
    [/(小红书)/, "小红书"],
    [/(华为)/, "华为"],
    [/(拼多多)/, "拼多多"],
    [/(网易)/, "网易"],
    [/(滴滴)/, "滴滴"],
    [/(小米)/, "小米"],
  ];
  const company = companyAliases.find(([pattern]) => pattern.test(context))?.[1];
  const event = context.includes("笔试") ? "笔试" : context.includes("面试") ? "面试" : null;
  if (company && event) return `${company}${event}`;
  if (event) return `求职${event}`;
  if (context.includes("简历")) return context.includes("提交") ? "提交简历" : "完善简历";
  return `${title.slice(0, 7)}…`;
}

export default function CalendarPage() {
  const router = useRouter();
  const [tasks, setTasks] = useState<CalendarTask[]>([]);
  const [series, setSeries] = useState<TaskSeries[]>([]);
  const [month, setMonth] = useState(() => new Date(new Date().getFullYear(), new Date().getMonth(), 1));
  const [selectedDay, setSelectedDay] = useState(() => dayStart(new Date()));
  const [editing, setEditing] = useState<CalendarTask | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [showCreateMenu, setShowCreateMenu] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [items, rules] = await Promise.all([
        api<CalendarTask[]>("/api/calendar/tasks"),
        api<TaskSeries[]>("/api/calendar/series"),
      ]);
      setTasks(items); setSeries(rules);
    }
    catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
      setError(reason instanceof Error ? reason.message : "无法加载日历");
    }
  }, [router]);
  useEffect(() => {
    let active = true;
    Promise.all([
      api<CalendarTask[]>("/api/calendar/tasks"),
      api<TaskSeries[]>("/api/calendar/series"),
    ]).then(([items, rules]) => {
      if (!active) return;
      setTasks(items); setSeries(rules);
    }).catch((reason) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "无法加载日历");
    });
    return () => { active = false; };
  }, [router]);

  const cells = useMemo(() => monthCells(month), [month]);
  const tasksByDay = useMemo(() => {
    const grouped = new Map<string, CalendarTask[]>();
    for (const task of tasks) {
      if (!task.dueAt || task.status === "CANCELLED") continue;
      const key = dayKey(new Date(task.dueAt));
      grouped.set(key, [...(grouped.get(key) ?? []), task]);
    }
    return grouped;
  }, [tasks]);
  const selectedTasks = (tasksByDay.get(dayKey(selectedDay)) ?? []).sort((a, b) => new Date(a.dueAt ?? 0).getTime() - new Date(b.dueAt ?? 0).getTime());
  const unscheduled = tasks.filter((task) => !task.dueAt && task.status !== "CANCELLED");
  const selectedDayInfo = chinaDayInfo(selectedDay);
  const selectedIsWeekend = (selectedDay.getDay() === 0 || selectedDay.getDay() === 6) && selectedDayInfo?.kind !== "workday";
  const agentScheduleHref = `/app?new=1&prefill=${encodeURIComponent("帮我把下面这件事安排到日历：\n")}`;
  const activeSeries = series.filter((rule) => rule.status === "ACTIVE");

  function moveMonth(delta: number) {
    const next = new Date(month.getFullYear(), month.getMonth() + delta, 1);
    setMonth(next); setSelectedDay(next);
  }
  function openCreate(day = selectedDay) { setSelectedDay(day); setEditing(null); setShowForm(true); }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget), dueValue = String(data.get("dueAt") ?? "");
    const body = { title: data.get("title"), description: data.get("description"), dueAt: dueValue ? new Date(dueValue).toISOString() : null, priority: data.get("priority"), ...(editing ? { status: editing.status } : {}) };
    setBusy(true); setError("");
    try {
      await api(editing ? `/api/calendar/tasks/${editing.id}` : "/api/calendar/tasks", { method: editing ? "PUT" : "POST", body: JSON.stringify(body) });
      setShowForm(false); setEditing(null); await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "保存失败"); }
    finally { setBusy(false); }
  }

  async function toggle(task: CalendarTask) {
    setBusy(true); setError("");
    try {
      await api(`/api/calendar/tasks/${task.id}`, { method: "PUT", body: JSON.stringify({ title: task.title, description: task.description, dueAt: task.dueAt, priority: task.priority, status: task.status === "COMPLETED" ? "TODO" : "COMPLETED" }) });
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "更新失败"); }
    finally { setBusy(false); }
  }

  /* Stopping a rule only withdraws untouched future occurrences; the server
     keeps what has already happened, so the confirmation says exactly that. */
  async function stopSeries(rule: TaskSeries) {
    if (!window.confirm(`停止“${rule.title}”（${rule.summary}）？未开始的后续日程会被撤回，已完成和已经发生的记录保留。`)) return;
    setBusy(true); setError("");
    try { await api(`/api/calendar/series/${rule.id}`, { method: "DELETE" }); await load(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "停止重复安排失败"); }
    finally { setBusy(false); }
  }

  async function remove(task: CalendarTask) {
    if (!window.confirm(`删除“${task.title}”？`)) return;
    setBusy(true); setError("");
    try { await api(`/api/calendar/tasks/${task.id}`, { method: "DELETE" }); await load(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "删除失败"); }
    finally { setBusy(false); }
  }

  return <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-7xl">
      <div className="flex items-center justify-between"><Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 transition hover:bg-white/70 hover:text-stone-900"><ArrowLeft className="size-4" />返回 Ardor</Link><div className="relative"><button onClick={() => setShowCreateMenu((open) => !open)} aria-expanded={showCreateMenu} className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white transition hover:scale-[1.02] hover:bg-black"><Plus className="size-4" />添加安排</button>{showCreateMenu && <div className="absolute right-0 top-12 z-30 w-72 rounded-2xl border border-white/80 bg-[#f8f6f1]/95 p-2 shadow-[0_24px_70px_rgba(42,35,27,0.18)] backdrop-blur-xl"><button type="button" onClick={() => { setShowCreateMenu(false); openCreate(); }} className="flex w-full items-start gap-3 rounded-xl p-3 text-left transition hover:bg-white"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-stone-900 text-white"><Pencil className="size-4" /></span><span><strong className="block text-sm font-medium text-stone-900">自己填写</strong><small className="mt-1 block text-xs leading-5 text-stone-500">选择时间、优先级和备注</small></span></button><Link href={agentScheduleHref} className="flex w-full items-start gap-3 rounded-xl p-3 text-left transition hover:bg-white"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-violet-100 text-violet-700"><Sparkles className="size-4" /></span><span><strong className="block text-sm font-medium text-stone-900">告诉 Ardor</strong><small className="mt-1 block text-xs leading-5 text-stone-500">说一句话，或直接粘贴面试邮件</small></span></Link></div>}</div></div>
      <header className="pb-10 pt-12 md:pb-14 md:pt-16"><p className="text-sm font-medium text-violet-600">CALENDAR</p><h1 className="mt-3 text-5xl font-semibold tracking-[-0.055em] md:text-7xl">把目标，放进今天。</h1></header>
      {error && <div className="mb-6 rounded-2xl bg-red-50/90 px-5 py-4 text-sm text-red-700">{error}</div>}
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1.55fr)_minmax(18rem,0.75fr)]">
        <section className="ardor-panel overflow-hidden rounded-[2.25rem] p-5 md:p-7">
          <div className="mb-5 flex items-center justify-between"><div><h2 className="text-2xl font-semibold">{month.getFullYear()} 年 {month.getMonth() + 1} 月</h2><p className="mt-1 text-sm text-stone-400">{tasks.filter((task) => task.status !== "COMPLETED" && task.status !== "CANCELLED").length} 项待推进</p></div><div className="flex gap-2"><button aria-label="上个月" onClick={() => moveMonth(-1)} className="grid size-10 place-items-center rounded-full bg-white/80 text-stone-600 shadow-sm hover:bg-white"><ChevronLeft className="size-4" /></button><button aria-label="下个月" onClick={() => moveMonth(1)} className="grid size-10 place-items-center rounded-full bg-white/80 text-stone-600 shadow-sm hover:bg-white"><ChevronRight className="size-4" /></button></div></div>
          <div className="mb-4 flex flex-wrap gap-4 text-[11px] font-medium text-stone-500"><span className="inline-flex items-center gap-1.5"><i className="size-2 rounded-full bg-rose-500" />法定节假日</span><span className="inline-flex items-center gap-1.5"><i className="size-2 rounded-full bg-stone-300" />周末</span><span className="inline-flex items-center gap-1.5"><i className="size-2 rounded-full bg-stone-600" />调休上班</span></div>
          <div className="grid grid-cols-7 text-center text-xs font-semibold text-stone-500">{WEEKDAYS.map((day, index) => <div key={day} className={`py-2.5 ${index >= 5 ? "text-rose-500" : ""}`}>{day}</div>)}</div>
          <div className="mt-1 grid grid-cols-7 gap-px overflow-hidden rounded-2xl border border-stone-200 bg-stone-200">{cells.map((date) => {
            const key = dayKey(date), dayTasks = tasksByDay.get(key) ?? [], currentMonth = date.getMonth() === month.getMonth(), selected = key === dayKey(selectedDay), today = key === dayKey(new Date()), dayInfo = chinaDayInfo(date), weekend = (date.getDay() === 0 || date.getDay() === 6) && dayInfo?.kind !== "workday";
            const dayTone = selected ? "relative z-[1] bg-violet-50 ring-2 ring-inset ring-violet-400" : dayInfo?.kind === "holiday" ? "bg-[#fff7f7] hover:bg-rose-50" : weekend ? "bg-[#faf9f6] hover:bg-stone-50" : dayInfo?.kind === "workday" ? "bg-[#f4f4f2] hover:bg-white" : "bg-white hover:bg-[#fbfbfa]";
            const faded = currentMonth ? "" : "text-stone-400";
            return <button key={key} onClick={() => setSelectedDay(date)} onDoubleClick={() => openCreate(date)} className={`group min-h-20 overflow-hidden p-2.5 text-left outline-none transition md:min-h-24 ${dayTone} ${faded}`}><div className="flex items-center justify-between gap-1"><span className={`grid size-7 place-items-center rounded-full text-sm font-medium ${today ? "bg-violet-600 font-semibold text-white" : currentMonth && (dayInfo?.kind === "holiday" || weekend) ? "text-rose-600" : currentMonth ? "text-stone-800" : "text-stone-400"}`}>{date.getDate()}</span>{dayInfo && <span className={`max-w-[4.5rem] truncate rounded-full px-1.5 py-0.5 text-[9px] font-semibold ${dayInfo.kind === "holiday" ? "bg-rose-100 text-rose-700" : "bg-stone-200 text-stone-700"}`}>{dayInfo.kind === "workday" ? "班" : dayInfo.firstDay ? dayInfo.name : "休"}</span>}</div><div className="mt-1.5 space-y-1">{dayTasks.slice(0, 2).map((task) => <div key={task.id} className={`flex min-w-0 items-center gap-1.5 rounded-md border px-1.5 py-0.5 text-[10px] font-medium leading-4 ${task.status === "COMPLETED" ? "border-emerald-100 bg-emerald-50 text-stone-500 line-through" : "border-emerald-100 bg-emerald-50/90 text-stone-700"}`}><span className={`size-1.5 shrink-0 rounded-full ${task.status === "COMPLETED" ? "bg-emerald-500" : priorityStyle[task.priority]}`} /><span className="truncate">{calendarLabel(task)}</span></div>)}{dayTasks.length > 2 && <p className="px-1.5 text-[9px] font-medium text-stone-500">还有 {dayTasks.length - 2} 项</p>}</div></button>;
          })}</div>
        </section>
        <aside className="space-y-5">
          <section className="ardor-panel rounded-[2.25rem] p-6 md:p-7"><div><p className="text-xs font-medium uppercase tracking-[0.18em] text-violet-600">{selectedDay.toLocaleDateString("zh-CN", { weekday: "long" })}{selectedDayInfo ? ` · ${selectedDayInfo.name}` : selectedIsWeekend ? " · 周末" : ""}</p><h2 className="mt-2 text-3xl font-semibold">{selectedDay.getMonth() + 1} 月 {selectedDay.getDate()} 日</h2></div><div className="mt-6 space-y-3">{selectedTasks.map((task) => <TaskCard key={task.id} task={task} busy={busy} onToggle={toggle} onEdit={() => { setEditing(task); setShowForm(true); }} onDelete={() => remove(task)} />)}{selectedTasks.length === 0 && <div className="rounded-2xl bg-white/55 px-4 py-8 text-center text-sm text-stone-400">今天还没有安排</div>}</div></section>
          {activeSeries.length > 0 && <section className="ardor-soft-panel rounded-[2rem] p-6"><div className="flex items-center gap-2"><Repeat className="size-4 text-violet-600" /><h2 className="font-semibold">重复安排</h2></div><div className="mt-4 space-y-2">{activeSeries.map((rule) => <div key={rule.id} className="group flex items-start gap-3 rounded-2xl bg-white/70 p-4"><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-stone-800">{rule.title}</p><p className="mt-1 text-xs text-stone-500">{rule.summary}</p>{rule.upcoming[0] && <p className="mt-1 text-xs text-stone-400">下一次 {new Date(`${rule.upcoming[0]}T00:00:00`).toLocaleDateString("zh-CN", { month: "long", day: "numeric" })}</p>}</div><button aria-label={`停止 ${rule.title}`} title="停止这条重复安排" disabled={busy} onClick={() => stopSeries(rule)} className="rounded-lg p-1.5 text-stone-300 opacity-60 transition hover:bg-rose-50 hover:text-rose-600 group-hover:opacity-100"><Trash2 className="size-3.5" /></button></div>)}</div></section>}
          {unscheduled.length > 0 && <section className="ardor-soft-panel rounded-[2rem] p-6"><div className="flex items-center gap-2"><Clock3 className="size-4 text-violet-600" /><h2 className="font-semibold">待安排</h2></div><div className="mt-4 space-y-2">{unscheduled.map((task) => <TaskCard key={task.id} task={task} busy={busy} compact onToggle={toggle} onEdit={() => { setEditing(task); setShowForm(true); }} onDelete={() => remove(task)} />)}</div></section>}
        </aside>
      </div>
    </div>
    {showForm && <div className="fixed inset-0 z-50 grid place-items-center bg-black/20 p-4 backdrop-blur-sm"><section className="ardor-panel w-full max-w-lg rounded-[2rem] p-6 md:p-8"><div className="flex items-center justify-between"><div><p className="text-xs font-medium uppercase tracking-[0.18em] text-violet-600">{editing ? "EDIT TASK" : "NEW TASK"}</p><h2 className="mt-2 text-2xl font-semibold">{editing ? "编辑待办" : "安排一件事"}</h2></div><button aria-label="关闭" onClick={() => { setShowForm(false); setEditing(null); }} className="rounded-full p-2 text-stone-400 hover:bg-white"><X className="size-5" /></button></div><form key={editing?.id ?? dayKey(selectedDay)} onSubmit={save} className="mt-7 space-y-4"><label className="block text-sm font-medium">标题<input name="title" className="field mt-2" defaultValue={editing?.title ?? ""} maxLength={240} required autoFocus /></label><label className="block text-sm font-medium">时间<input name="dueAt" type="datetime-local" className="field mt-2" defaultValue={localInputValue(editing?.dueAt ?? null, selectedDay)} /></label><label className="block text-sm font-medium">优先级<select name="priority" className="field mt-2" defaultValue={editing?.priority ?? "MEDIUM"}><option value="LOW">低</option><option value="MEDIUM">中</option><option value="HIGH">高</option></select></label><label className="block text-sm font-medium">备注<textarea name="description" className="field mt-2 min-h-24 resize-y" defaultValue={editing?.description ?? ""} maxLength={4000} /></label><button disabled={busy} className="w-full rounded-full bg-[#1d1d1f] px-5 py-3 text-sm font-medium text-white hover:bg-black disabled:opacity-50">{busy ? "保存中…" : "保存"}</button></form></section></div>}
  </main>;
}

function TaskCard({ task, busy, compact = false, onToggle, onEdit, onDelete }: { task: CalendarTask; busy: boolean; compact?: boolean; onToggle: (task: CalendarTask) => void; onEdit: () => void; onDelete: () => void }) {
  if (task.taskKind === "MEMORY_REVIEW") {
    return <div className={`rounded-2xl bg-white/70 ${compact ? "p-3" : "p-4"}`}><div className="flex items-center gap-3"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-violet-100 text-violet-600"><BrainCircuit className="size-4" /></span><div className="min-w-0 flex-1"><p className="text-sm font-medium text-stone-800">记忆卡复习</p><p className="mt-1 text-xs text-stone-400">{task.description}</p></div><Link href={task.actionPath ?? "/app/cards"} className="inline-flex shrink-0 items-center gap-1 rounded-full bg-stone-950 px-3 py-2 text-xs font-medium text-white hover:bg-black">开始<ArrowRight className="size-3.5" /></Link></div></div>;
  }
  if (task.taskKind === "LEARNING") {
    return <div className={`rounded-2xl bg-white/70 ${compact ? "p-3" : "p-4"}`}><div className="flex items-center gap-3"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-rose-100 text-rose-600"><GraduationCap className="size-4" /></span><div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-stone-800">学习 · {task.title}</p>{task.description && <p className="mt-1 line-clamp-2 text-xs text-stone-400">{task.description}</p>}</div><Link href={task.actionPath ?? "/app/learning"} className="inline-flex shrink-0 items-center gap-1 rounded-full bg-stone-950 px-3 py-2 text-xs font-medium text-white hover:bg-black">进入<ArrowRight className="size-3.5" /></Link></div></div>;
  }
  const complete = task.status === "COMPLETED";
  return <div className={`group rounded-2xl bg-white/70 ${compact ? "p-3" : "p-4"}`}><div className="flex items-start gap-3"><button aria-label={complete ? "恢复待办" : "完成待办"} disabled={busy} onClick={() => onToggle(task)} className={`mt-0.5 grid size-6 shrink-0 place-items-center rounded-full border transition ${complete ? "border-emerald-500 bg-emerald-500 text-white" : "border-stone-300 hover:border-violet-500"}`}>{complete && <Check className="size-3.5" />}</button><div className="min-w-0 flex-1"><div className="flex items-start gap-2"><span className={`mt-2 size-1.5 shrink-0 rounded-full ${priorityStyle[task.priority]}`} /><p className={`text-sm font-medium leading-5 ${complete ? "text-stone-400 line-through" : "text-stone-800"}`}>{task.title}</p><span className="ml-auto flex shrink-0 items-center gap-1.5">{task.seriesId && <span title="重复安排中的一次" className="text-stone-400"><Repeat className="size-3.5" /></span>}{task.source === "AGENT" && <span title="Ardor 创建" className="text-violet-600"><Sparkles className="size-3.5" /></span>}</span></div>{task.dueAt && <p className="mt-2 text-xs text-stone-400">{new Date(task.dueAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}</p>}{task.description && !compact && <p className="mt-2 line-clamp-3 text-xs leading-5 text-stone-500">{task.description}</p>}</div></div><div className="mt-2 flex justify-end gap-1 opacity-60 transition group-hover:opacity-100"><button aria-label="编辑待办" onClick={onEdit} className="rounded-lg p-1.5 text-stone-400 hover:bg-white hover:text-stone-700"><Pencil className="size-3.5" /></button><button aria-label="删除待办" onClick={onDelete} className="rounded-lg p-1.5 text-stone-400 hover:bg-rose-50 hover:text-rose-600"><Trash2 className="size-3.5" /></button></div></div>;
}
