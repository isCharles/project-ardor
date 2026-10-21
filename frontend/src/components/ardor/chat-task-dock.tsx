"use client";

import { Bell, Check, CircleAlert, LoaderCircle, Settings2 } from "lucide-react";
import Link from "next/link";
import { useCallback, useEffect, useState } from "react";

import { useDismiss } from "@/components/ardor/overlay";
import { api } from "@/lib/api";
import { type BackgroundTask, type TaskSummary, readBackgroundTask } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";

function taskLabel(task: BackgroundTask, locale: string, t: (en: string, zh: string) => string) {
  const labels: Record<string, [string, string]> = {
    RESUME_ANALYSIS: ["Resume analysis", "简历分析"],
    RECAP_ORGANIZE: ["Interview notes", "面经整理"],
    KNOWLEDGE_INDEX: ["Knowledge indexing", "知识库向量化"],
    LEARNING_CREATE: ["Lesson preparation", "学习内容生成"],
    LEARNING_EVALUATE: ["Practice assessment", "学习评分"],
    REPLAY_EVALUATE: ["Interview replay", "面试回放"],
    INTERVIEW_EVALUATE: ["Interview assessment", "模拟面试评价"],
  };
  const label = labels[task.kind] ?? [t("Background task", "后台任务"), t("Background task", "后台任务")];
  return locale === "en" ? label[0] : label[1];
}

/**
 * Background work belongs next to the composer, not in the main navigation.
 * Only appears when something is running or a result is waiting.
 */
export function ChatTaskDock() {
  const { locale, t } = useLocale();
  const [summary, setSummary] = useState<TaskSummary | null>(null);
  const [tasks, setTasks] = useState<BackgroundTask[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const ref = useDismiss<HTMLDivElement>(open, () => setOpen(false));

  const refreshSummary = useCallback(async () => {
    try { setSummary(await api<TaskSummary>("/api/background-tasks/summary")); }
    catch { /* The dock is informational and should never block chat. */ }
  }, []);

  useEffect(() => {
    const refresh = () => { if (!document.hidden) void refreshSummary(); };
    refresh();
    const timer = window.setInterval(refresh, 5000);
    window.addEventListener("ardor:background-refresh", refresh);
    window.addEventListener("focus", refresh);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("ardor:background-refresh", refresh);
      window.removeEventListener("focus", refresh);
    };
  }, [refreshSummary]);

  const activeCount = summary?.activeCount ?? 0;
  const unreadCount = Object.values(summary?.unreadByModule ?? {}).reduce((total, count) => total + count, 0);
  if (activeCount === 0 && unreadCount === 0) return null;

  async function show() {
    setOpen((value) => !value);
    if (open) return;
    setLoading(true);
    try {
      const value = await api<BackgroundTask[]>("/api/background-tasks?limit=30");
      setTasks(value.filter(task => task.status === "QUEUED" || task.status === "RUNNING" || task.unread));
    } catch { setTasks([]); }
    finally { setLoading(false); }
  }

  async function acknowledge(task: BackgroundTask) {
    try {
      await readBackgroundTask(task);
      setTasks(current => current.filter(item => item.id !== task.id || item.kind !== task.kind));
      await refreshSummary();
    } catch { /* A stale result can be refreshed from the full task page. */ }
  }

  const text = activeCount > 0
    ? t(`${activeCount} background task${activeCount > 1 ? "s" : ""} running`, `${activeCount} 个后台任务运行中`)
    : t(`${unreadCount} result${unreadCount > 1 ? "s" : ""} to review`, `${unreadCount} 个结果待查看`);

  return <div className="relative mx-auto w-full max-w-3xl px-4 md:px-8">
    <button type="button" aria-expanded={open} aria-label={text} onClick={() => void show()} className={`mb-1 inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] transition ${open ? "bg-violet-50 text-violet-700" : "text-stone-400 hover:bg-white/70 hover:text-stone-600"}`}>
      {activeCount > 0 ? <LoaderCircle className="size-3 animate-spin" /> : unreadCount > 0 ? <Bell className="size-3" /> : null}
      <span>{text}</span>
    </button>
    {open && <div ref={ref} role="dialog" aria-label={text} className="absolute bottom-full left-4 right-4 z-40 mb-2 rounded-2xl border border-stone-200 bg-white p-2 shadow-[0_20px_60px_rgba(49,39,70,0.16)] md:left-8 md:right-8">
      {loading
        ? <p className="px-3 py-4 text-xs text-stone-400">{t("Loading…", "加载中…")}</p>
        : tasks.length === 0
          ? <p className="px-3 py-4 text-xs text-stone-400">{t("Nothing running right now.", "当前没有运行中的任务。")}</p>
          : <div className="space-y-1">{tasks.map(task => {
            const pending = task.status === "QUEUED" || task.status === "RUNNING";
            const Icon = pending ? LoaderCircle : task.status === "COMPLETED" ? Check : CircleAlert;
            const tone = pending ? "text-violet-600" : task.status === "COMPLETED" ? "text-emerald-600" : "text-rose-600";
            return <div key={`${task.kind}:${task.id}`} className="flex items-start gap-2.5 rounded-xl px-2.5 py-2 hover:bg-stone-50">
              <Icon className={`mt-0.5 size-3.5 shrink-0 ${tone} ${pending ? "animate-spin" : ""}`} />
              <div className="min-w-0 flex-1">
                <p className="truncate text-xs font-medium text-stone-700">{task.title || taskLabel(task, locale, t)}</p>
                <p className={`mt-0.5 text-[10px] ${task.status === "FAILED" ? "text-rose-600" : "text-stone-400"}`}>
                  {pending ? (task.status === "QUEUED" ? t("Queued", "排队中") : t("Running", "进行中")) : task.status === "COMPLETED" ? t("Ready to view", "已完成，可以查看") : t("Needs attention", "未完成，需要处理")}
                  {pending && ` · ${t(`usually ${task.estimateMinSeconds}–${task.estimateMaxSeconds}s`, `通常 ${task.estimateMinSeconds}–${task.estimateMaxSeconds} 秒`)}`}
                </p>
              </div>
              {task.status === "COMPLETED" && task.resultPath ? <Link href={task.resultPath} onClick={() => setOpen(false)} className="shrink-0 text-[10px] font-medium text-violet-700 hover:underline">{t("Open", "查看")}</Link>
                : task.status === "FAILED" || pending ? <Link href="/app/tasks" onClick={() => setOpen(false)} className="shrink-0 text-[10px] text-stone-500 hover:underline">{t("Details", "详情")}</Link>
                  : null}
              {task.unread && task.status === "COMPLETED" && <button type="button" onClick={() => void acknowledge(task)} className="shrink-0 text-[10px] text-stone-400 hover:text-stone-700">{t("Mark read", "标记已读")}</button>}
            </div>;
          })}</div>}
      <Link href="/app/tasks" onClick={() => setOpen(false)} className="mt-1 flex items-center gap-1.5 border-t border-stone-100 px-2.5 py-2 text-[11px] text-stone-500 hover:text-violet-700"><Settings2 className="size-3" />{t("All background tasks", "全部后台任务")}</Link>
    </div>}
  </div>;
}
