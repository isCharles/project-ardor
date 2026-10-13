"use client";

import { Bell, LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useEffect, useState } from "react";
import { AppDialog } from "@/components/ui/app-dialog";
import { api } from "@/lib/api";
import { type BackgroundTask, type TaskSummary } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";

export function ChatNotifications() {
  const { t } = useLocale();
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(false);
  const [tasks, setTasks] = useState<BackgroundTask[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    const refresh = () => { if (document.hidden) return; void api<TaskSummary>("/api/background-tasks/summary").then((summary) => { if (active) setUnread(Object.values(summary.unreadByModule).some((count) => count > 0)); }).catch(() => undefined); };
    refresh();
    const timer = window.setInterval(refresh, 30_000);
    window.addEventListener("ardor:background-refresh", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => { active = false; window.clearInterval(timer); window.removeEventListener("ardor:background-refresh", refresh); document.removeEventListener("visibilitychange", refresh); };
  }, []);
  async function show() {
    setOpen(true); setLoading(true); setError("");
    try { setTasks((await api<BackgroundTask[]>("/api/background-tasks?limit=30")).filter((task) => task.status === "COMPLETED" || task.status === "FAILED")); }
    catch { setError(t("Could not load notifications. Try again.", "暂时无法加载通知，请重试。")); }
    finally { setLoading(false); }
  }
  return <>
    <button aria-label={t("Notifications", "通知")} onClick={() => void show()} className="relative rounded-lg p-2 text-stone-500 hover:bg-white"><Bell className="size-4" />{unread && <span className="absolute right-1 top-1 size-1.5 rounded-full bg-red-500" />}</button>
    {open && <AppDialog title={t("Notifications", "通知")} onClose={() => setOpen(false)}>
      {loading ? <LoaderCircle className="size-5 animate-spin text-violet-500" /> : error ? <p role="alert" className="text-sm text-red-600">{error}</p> : tasks.length === 0 ? <p className="text-sm text-stone-500">{t("No recent results yet.", "暂无最近完成的任务。")}</p> : <div className="space-y-2">{tasks.map((task) => <Link key={`${task.kind}:${task.id}`} href={task.status === "COMPLETED" && task.resultPath ? task.resultPath : "/app/tasks"} onClick={() => setOpen(false)} className="block rounded-2xl border border-stone-100 p-3 hover:bg-violet-50"><p className="truncate text-sm font-medium">{task.title}</p><p className={`mt-1 text-xs ${task.status === "FAILED" ? "text-rose-600" : "text-stone-500"}`}>{task.status === "COMPLETED" ? t("Ready to view", "已完成，可以查看") : t("Needs attention", "未完成，需要处理")}</p></Link>)}</div>}
      <Link href="/app/tasks" onClick={() => setOpen(false)} className="mt-5 block text-sm text-violet-700">{t("View all background tasks", "查看全部后台任务")} →</Link>
    </AppDialog>}
  </>;
}
