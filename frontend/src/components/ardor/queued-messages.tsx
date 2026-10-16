"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type QueueItem = { id: string; status: string; blockedBy: string | null; errorMessage: string | null; request: { message: string; reasoningDepth?: string } };
export function QueuedMessages({ conversationId, activeRunId, onPendingChange, onCompleted, onStarted }: {
  conversationId: string | null; activeRunId: string | null;
  onPendingChange: (pending: boolean) => void; onCompleted: (id: string) => Promise<void>;
  onStarted: (id: string) => void;
}) {
  const { t } = useLocale();
  const [items, setItems] = useState<QueueItem[]>([]);
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const seen = useRef(new Map<string, string>());
  useEffect(() => {
    if (!conversationId) { onPendingChange(false); return; }
    let stopped = false;
    let revision = 0;
    let timer: number;
    const refresh = async () => {
      const current = ++revision;
      try {
        const response = await api<QueueItem[]>(`/api/agent/queue?conversationId=${encodeURIComponent(conversationId)}`);
        const next = Array.isArray(response) ? response : [];
        if (stopped || current !== revision) return;
        setItems(next);
        onPendingChange(next.some((item) => ["QUEUED", "DISPATCHING", "RUNNING"].includes(item.status)));
        const completed = next.some((item) => item.status === "COMPLETED" && seen.current.has(item.id) && seen.current.get(item.id) !== "COMPLETED");
        seen.current = new Map(next.map((item) => [item.id, item.status]));
        const started = next.find((item) => item.status === "RUNNING" && item.id !== activeRunId);
        if (started) onStarted(started.id);
        if (completed) await onCompleted(conversationId);
      } catch { /* Keep accepted messages visible during transport failure. Never replay here. */ }
      if (!stopped && current === revision) timer = window.setTimeout(() => void refresh(), 1500);
    };
    const wake = () => { window.clearTimeout(timer); void refresh(); };
    window.addEventListener("ardor:queue-refresh", wake);
    void refresh();
    return () => { stopped = true; window.clearTimeout(timer); window.removeEventListener("ardor:queue-refresh", wake); };
  }, [conversationId, activeRunId, onPendingChange, onCompleted, onStarted]);

  async function act(item: QueueItem, action: "remove" | "steer") {
    if (submitting) return;
    setSubmitting(true); setError("");
    try {
      await api<void>(`/api/agent/queue/${item.id}${action === "steer" ? "/steer" : ""}`, {
        method: action === "steer" ? "POST" : "DELETE",
        ...(action === "steer" ? { body: JSON.stringify({ interruptRunId: activeRunId }) } : {}),
      });
      window.dispatchEvent(new Event("ardor:queue-refresh"));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Queue action failed", "队列操作失败")); }
    finally { setSubmitting(false); }
  }
  const visible = items.filter((item) => !["COMPLETED", "CANCELLED"].includes(item.status) && item.id !== activeRunId);
  if (!conversationId || !visible.length) return null;
  return <section aria-label={t("Message queue", "消息队列")} className="my-4 space-y-5">
    {visible.map((item) => <article key={item.id} className="ml-auto max-w-[86%] text-right">
      <p className="inline-block rounded-3xl bg-stone-200/65 px-5 py-3 text-left text-sm leading-7 whitespace-pre-wrap break-words [overflow-wrap:anywhere]">{item.request.message}</p>
      <p className="mt-1.5 text-xs text-stone-500">{item.status === "QUEUED" ? activeRunId ? t("Queued · waiting for the current reply", "已发送 · 等待当前回复") : item.blockedBy ? t("Paused after the previous request stopped. Choose Handle now to continue.", "上一条已停止，这条尚未开始；点“现在处理”即可继续。") : t("Sent · starting shortly", "已发送 · 即将开始") : ["FAILED", "INTERRUPTED"].includes(item.status) ? t("Stopped · review the result before continuing", "已停止 · 请检查执行结果") : t("Processing your message…", "正在处理这条消息…")}</p>
      {item.errorMessage && <p className="mt-2 text-xs text-rose-700">{item.errorMessage}</p>}
      {item.status === "QUEUED" && <div className="mt-2 flex flex-wrap justify-end gap-3 text-xs"><button type="button" disabled={submitting} onClick={() => void act(item, "remove")} className="text-stone-500 disabled:opacity-50">{t("Remove from queue", "移出队列")}</button>{(activeRunId || item.blockedBy) && <button type="button" disabled={submitting} onClick={() => void act(item, "steer")} className="text-violet-700 disabled:opacity-50">{activeRunId ? t("Interrupt and handle now", "打断并立即处理") : t("Handle now", "现在处理")}</button>}</div>}
    </article>)}
    {error && <p role="alert" className="text-xs text-rose-700">{error}</p>}
  </section>;
}
