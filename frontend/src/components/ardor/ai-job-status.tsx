"use client";

import { useEffect, useState } from "react";
import { CircleAlert, LoaderCircle, RotateCcw } from "lucide-react";
import Link from "next/link";
import { type AiJob, jobPending } from "@/lib/ai-jobs";
import { useLocale } from "@/lib/locale";

export function AiJobStatus({ job, retry, pollFailed = false }: { job: AiJob | null; retry: () => Promise<void>; pollFailed?: boolean }) {
  const { locale, t } = useLocale();
  const [elapsed, setElapsed] = useState(0);
  const [retrying, setRetrying] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => {
    if (!job || !jobPending(job)) return;
    const update = () => setElapsed(Math.max(0, Math.floor((Date.now() - Date.parse(job.startedAt ?? job.createdAt)) / 1000)));
    const timer = window.setInterval(update, 1000);
    return () => window.clearInterval(timer);
  }, [job]);
  if (!job) return null;
  if (job.status === "COMPLETED") return job.type === "LEARNING_CREATE" && job.resultPath
    ? <div className="my-5 flex items-center justify-between rounded-2xl bg-emerald-50/75 p-4 text-sm text-emerald-800"><span>{t("Your lesson is ready", "学习内容已准备好")}</span><Link href={job.resultPath} className="font-medium underline underline-offset-4">{t("Open lesson", "打开学习")}</Link></div> : null;
  const pending = jobPending(job);
  return <div role="status" className={`my-5 rounded-2xl border p-5 text-sm ${pending ? "border-violet-100 bg-violet-50/65 text-violet-900" : "border-rose-100 bg-rose-50/70 text-rose-900"}`}>
    <div className="flex items-center gap-3">{pending ? <LoaderCircle className="size-4 animate-spin" /> : <CircleAlert className="size-4" />}<strong>{pending ? job.status === "QUEUED" ? t("Queued for assessment", "已提交，等待评估") : t("Assessing in the background", "正在后台评估") : t("Assessment paused", "评估未完成")}</strong></div>
    {pending ? <><p className="mt-2 text-xs opacity-75">{t(`Usually ${job.estimateMinSeconds}–${job.estimateMaxSeconds} seconds · ${elapsed}s elapsed`, `通常约 ${job.estimateMinSeconds}–${job.estimateMaxSeconds} 秒 · 已用时 ${elapsed} 秒`)}</p><p className="mt-2 text-xs">{elapsed > job.estimateMaxSeconds ? t("Taking longer than usual. Your task is still running; you can come back later.", "本次耗时较长，后台仍在处理；可以稍后回来。") : t("You can leave this page and check the result later.", "可以离开此页，稍后回来查看结果。")}</p></> : <><p className="mt-2 break-words text-xs">{locale === "zh-CN" ? job.errorMessage ?? "原提交已保留，可以重试。" : "Your submission is saved. Retry when the model is available."}</p><button disabled={retrying} onClick={() => { setRetrying(true); setError(""); void retry().catch((reason) => setError(reason instanceof Error ? reason.message : t("Retry failed", "重试失败"))).finally(() => setRetrying(false)); }} className="mt-3 inline-flex items-center gap-2 rounded-full bg-white px-4 py-2 text-xs font-medium shadow-sm disabled:opacity-50"><RotateCcw className="size-3.5" />{t("Retry assessment", "重试评估")}</button></>}
    {pollFailed && <p className="mt-2 text-xs">{t("Status connection interrupted. Your task is saved; reconnecting…", "状态连接暂时中断，任务已保存，正在重新连接…")}</p>}
    {error && <p className="mt-2 break-words text-xs">{error}</p>}
  </div>;
}
