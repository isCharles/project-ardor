"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, CircleAlert, Clock3, LoaderCircle, RefreshCw } from "lucide-react";
import Link from "next/link";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { type BackgroundTask, readBackgroundTask } from "@/lib/background-tasks";

const labels: Record<string, [string, string]> = {
  RESUME_ANALYSIS: ["Resume analysis", "简历分析"], RECAP_ORGANIZE: ["Interview notes", "面经整理"],
  KNOWLEDGE_INDEX: ["Knowledge indexing", "知识库向量化"], LEARNING_CREATE: ["Lesson preparation", "学习内容生成"],
  LEARNING_EVALUATE: ["Practice assessment", "学习评分"], REPLAY_EVALUATE: ["Interview replay", "面试回放"],
  INTERVIEW_EVALUATE: ["Interview assessment", "模拟面试评价"],
};
export default function BackgroundTasksPage() {
  const { t } = useLocale();
  const [tasks, setTasks] = useState<BackgroundTask[]>([]);
  const [limit, setLimit] = useState(30);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const refresh = useCallback(async () => {
    try {
      const pages = await Promise.all(Array.from({ length: Math.ceil(limit / 100) }, (_, index) =>
        api<BackgroundTask[]>(`/api/background-tasks?offset=${index * 100}&limit=${Math.min(100, limit - index * 100)}`)));
      setTasks(pages.flat()); setError("");
    }
    catch { setError(t("Connection interrupted. Your tasks are saved.", "连接暂时中断，后台任务仍已保存。")); }
    finally { setLoading(false); }
  }, [limit, t]);
  useEffect(() => {
    let active = true;
    const update = () => { if (active && !document.hidden) void refresh(); };
    update();
    const timer = window.setInterval(update, 5000);
    document.addEventListener("visibilitychange", update);
    window.addEventListener("focus", update);
    return () => { active = false; window.clearInterval(timer); document.removeEventListener("visibilitychange", update); window.removeEventListener("focus", update); };
  }, [refresh]);
  async function acknowledge(task: BackgroundTask) {
    try { await readBackgroundTask(task); await refresh(); }
    catch { setError(t("The result changed. Refresh and try again.", "结果已更新，请刷新后重试。")); }
  }
  async function retry(task: BackgroundTask) {
    try {
      await api<void>(`/api/background-tasks/${task.kind}/${task.id}/retry`, { method: "POST", body: JSON.stringify({ completionToken: task.completionToken }) });
      window.dispatchEvent(new Event("ardor:background-refresh"));
      await refresh();
    } catch { setError(t("Could not retry. Refresh the task and check your model settings.", "重试未提交，请刷新任务并检查模型设置。")); }
  }
  return <main className="ardor-workbench min-h-screen px-6 py-10 md:px-10">
    <div className="mx-auto max-w-4xl">
      <header className="mb-9 flex items-center justify-between gap-4"><h1 className="text-3xl font-semibold tracking-tight">{t("Background tasks", "后台任务")}</h1><button type="button" onClick={() => void refresh()} aria-label={t("Refresh tasks", "刷新任务")} className="rounded-full bg-white/80 p-3 shadow-sm"><RefreshCw className="size-4" /></button></header>
      {error && <p role="alert" className="mb-5 rounded-2xl bg-rose-50 p-4 text-sm text-rose-800">{error}</p>}
      {loading ? <LoaderCircle aria-label={t("Loading", "加载中")} className="size-5 animate-spin" /> : tasks.length === 0 ? <p className="text-stone-500">{t("Nothing running. Start something when you’re ready.", "还没有后台任务。")}</p> : <div className="space-y-3">{tasks.map((task) => {
        const label = labels[task.kind] ?? [task.kind, task.kind];
        const pending = task.status === "RUNNING" || task.status === "QUEUED";
        const status = task.status === "QUEUED" ? t("Queued", "排队中") : task.status === "RUNNING" ? t("Running", "进行中") : task.status === "COMPLETED" ? t("Ready", "已完成") : t("Needs attention", "未完成");
        const Icon = task.status === "RUNNING" ? LoaderCircle : task.status === "QUEUED" ? Clock3 : task.status === "COMPLETED" ? Check : CircleAlert;
        return <article key={`${task.kind}:${task.id}`} className="ardor-panel rounded-3xl p-5" aria-label={t(...label)}>
          <div className="flex items-start gap-4"><span className={`mt-1 rounded-2xl p-3 ${task.status === "FAILED" ? "bg-rose-50 text-rose-600" : "bg-violet-50 text-violet-600"}`}><Icon className={`size-4 ${task.status === "RUNNING" ? "animate-spin" : ""}`} /></span><div className="min-w-0 flex-1"><div className="flex items-center gap-2"><h2 className="font-medium">{t(...label)}</h2>{task.unread && <span aria-label={t("Unread result", "未读结果")} className="size-2 rounded-full bg-rose-500" />}</div>{task.title !== task.kind && <p className="mt-1 truncate text-sm text-stone-500">{task.title}</p>}<p className="mt-2 text-xs text-stone-400">{status}{pending && ` · ${t(`Usually ${task.estimateMinSeconds}–${task.estimateMaxSeconds}s`, `通常约 ${task.estimateMinSeconds}–${task.estimateMaxSeconds} 秒`)}`}</p></div></div>
          {task.status === "FAILED" && <p className="mt-3 pl-16 text-xs text-rose-700">{task.errorCode === "EMBEDDING_NOT_CONFIGURED" ? t("Set up an embedding model to continue.", "请先配置向量模型。") : t("The model could not finish. Your original submission is saved.", "模型未能完成处理，原提交已保留。")}</p>}
          <div className="mt-4 flex flex-wrap gap-3 pl-16 text-xs">{task.status === "COMPLETED" && task.resultPath && <Link href={task.resultPath} onClick={() => { if (task.unread) void acknowledge(task); }} className="rounded-full bg-violet-100 px-4 py-2 font-medium text-violet-800">{t("Open result", "查看结果")}</Link>}{task.status === "FAILED" && <button onClick={() => void retry(task)} className="rounded-full bg-violet-100 px-4 py-2 text-violet-800">{t("Retry", "重试")}</button>}{task.unread && <button onClick={() => void acknowledge(task)} className="rounded-full bg-white px-4 py-2 text-stone-600">{t("Mark read", "标记已读")}</button>}</div>
        </article>;
      })}</div>}
      {tasks.length === limit && limit < 100000 && <button onClick={() => setLimit(limit + 30)} className="mt-6 text-sm text-violet-700">{t("Show more", "显示更多")}</button>}
    </div>
  </main>;
}
