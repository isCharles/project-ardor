"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type QueueItem = { id: string; status: string; blockedBy: string | null; errorMessage: string | null; request: { message: string; reasoningDepth?: string } };
export function QueuedMessages({ conversationId, activeRunId, onPendingChange, onCompleted }: {
  conversationId: string | null; activeRunId: string | null;
  onPendingChange: (pending: boolean) => void; onCompleted: (id: string) => Promise<void>;
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
        if (completed) await onCompleted(conversationId);
      } catch { /* Keep accepted messages visible during transport failure. Never replay here. */ }
      if (!stopped && current === revision) timer = window.setTimeout(() => void refresh(), 1500);
    };
    const wake = () => { window.clearTimeout(timer); void refresh(); };
    window.addEventListener("ardor:queue-refresh", wake);
    void refresh();
    return () => { stopped = true; window.clearTimeout(timer); window.removeEventListener("ardor:queue-refresh", wake); };
  }, [conversationId, onPendingChange, onCompleted]);

  async function act(item: QueueItem, resume: boolean) {
    if (submitting) return;
    setSubmitting(true); setError("");
    try {
      await api<void>(`/api/agent/queue/${item.id}${resume ? "/resume" : ""}`, { method: resume ? "POST" : "DELETE" });
      window.dispatchEvent(new Event("ardor:queue-refresh"));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Queue action failed", "队列操作失败")); }
    finally { setSubmitting(false); }
  }
  const visible = items.filter((item) => !["COMPLETED", "CANCELLED"].includes(item.status) && item.id !== activeRunId);
  if (!conversationId || !visible.length) return null;
  return <section aria-label={t("Message queue", "消息队列")} className="my-4 max-w-xl space-y-2">
    {visible.map((item) => <article key={item.id} className="rounded-2xl border border-violet-100 bg-white/70 px-4 py-3">
      <div className="flex items-center gap-2 text-xs text-violet-600"><span>{item.status === "QUEUED" ? t("Queued", "排队中") : ["FAILED", "INTERRUPTED"].includes(item.status) ? t("Needs attention", "需要处理") : t("Running", "执行中")}</span><span className="text-stone-400">{item.request.reasoningDepth ?? "AUTO"}</span></div>
      <p className="mt-2 whitespace-pre-wrap break-words text-sm text-stone-700 [overflow-wrap:anywhere]">{item.request.message}</p>
      {item.errorMessage && <p className="mt-2 text-xs text-rose-700">{item.errorMessage}</p>}
      {item.status === "QUEUED" && <div className="mt-3 flex flex-wrap justify-end gap-3 text-xs"><button type="button" disabled={submitting} onClick={() => void act(item, false)} className="text-stone-500 disabled:opacity-50">{t("Remove from queue", "移出队列")}</button>{item.blockedBy && <button type="button" disabled={submitting} onClick={() => void act(item, true)} className="text-violet-700 disabled:opacity-50">{t("Continue after checking prior result", "检查上一条结果后继续")}</button>}</div>}
    </article>)}
    {error && <p role="alert" className="text-xs text-rose-700">{error}</p>}
  </section>;
}
