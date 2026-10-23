"use client";

import { Bell, LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { AppDialog } from "@/components/ui/app-dialog";
import { api } from "@/lib/api";
import { readBackgroundTask, type BackgroundTask, type TaskSummary } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";

const statusLabel = {
  QUEUED: ["Queued", "排队中"],
  RUNNING: ["Running", "进行中"],
  COMPLETED: ["Ready", "已完成"],
  FAILED: ["Needs attention", "未完成"],
} as const;

export function ChatNotifications() {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const [activeCount, setActiveCount] = useState(0);
  const [unreadCount, setUnreadCount] = useState(0);
  const [tasks, setTasks] = useState<BackgroundTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    const refresh = () => {
      if (document.hidden) return;
      void api<TaskSummary>("/api/background-tasks/summary").then((summary) => {
        if (!active) return;
        setActiveCount(summary.activeCount);
        setUnreadCount(Object.values(summary.unreadByModule).reduce((sum, count) => sum + count, 0));
      }).catch(() => undefined);
    };
    refresh();
    const timer = window.setInterval(refresh, 15_000);
    window.addEventListener("ardor:background-refresh", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      active = false;
      window.clearInterval(timer);
      window.removeEventListener("ardor:background-refresh", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, []);

  async function show() {
    setOpen(true);
    setLoading(true);
    setError("");
    try {
      setTasks(await api<BackgroundTask[]>("/api/background-tasks?limit=30"));
    } catch {
      setError(t("Could not load background messages. Try again.", "暂时无法加载后台消息，请重试。"));
    } finally {
      setLoading(false);
    }
  }

  function markRead(task: BackgroundTask) {
    if (!task.unread || (task.status !== "COMPLETED" && task.status !== "FAILED")) return;
    void readBackgroundTask(task).catch(() => undefined);
  }

  const badge = activeCount + unreadCount;

  return <>
    <button aria-label={t("Background messages", "后台消息")} title={t("Background messages", "后台消息")} onClick={() => void show()} className="relative rounded-lg p-2 text-stone-500 hover:bg-white">
      <Bell className="size-4" />
      {badge > 0 && <span className="absolute right-0.5 top-0.5 grid min-w-3.5 place-items-center rounded-full bg-red-500 px-1 text-[9px] font-semibold leading-3.5 text-white ring-2 ring-[#f8f6f2]">{badge > 9 ? "9+" : badge}</span>}
    </button>
    {open && <AppDialog title={t("Background messages", "后台消息")} onClose={() => setOpen(false)}>
      {loading ? <LoaderCircle className="size-5 animate-spin text-violet-500" /> : error ? <p role="alert" className="text-sm text-red-600">{error}</p> : tasks.length === 0 ? <p className="text-sm text-stone-500">{t("No background messages yet.", "暂无后台消息。")}</p> : <div className="space-y-2">
        {tasks.map((task) => {
          const [en, zh] = statusLabel[task.status];
          return <Link key={`${task.kind}:${task.id}`} href={task.status === "COMPLETED" && task.resultPath ? task.resultPath : "/app/tasks"} onClick={() => { markRead(task); setOpen(false); }} className="block rounded-2xl border border-stone-100 p-3 hover:bg-violet-50">
            <div className="flex items-start justify-between gap-3"><p className="min-w-0 flex-1 truncate text-sm font-medium">{task.title}</p><span className={`shrink-0 text-[11px] ${task.status === "FAILED" ? "text-rose-600" : task.status === "RUNNING" || task.status === "QUEUED" ? "text-violet-600" : "text-stone-500"}`}>{t(en, zh)}</span></div>
            <p className={`mt-1 text-xs ${task.status === "FAILED" ? "text-rose-600" : "text-stone-500"}`}>
              {task.status === "FAILED" ? t("Not completed; open to check or retry.", "未完成，点开查看或重试。") : task.status === "RUNNING" || task.status === "QUEUED" ? t("Working in the background.", "正在后台处理中。") : t("Ready to view.", "已完成，可以查看。")}
            </p>
          </Link>;
        })}
      </div>}
      <Link href="/app/tasks" onClick={() => setOpen(false)} className="mt-5 block text-sm text-violet-700">{t("View all background tasks", "查看全部后台任务")} →</Link>
    </AppDialog>}
  </>;
}
