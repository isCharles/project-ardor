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

  const visible = items.filter((item) => ["QUEUED", "DISPATCHING"].includes(item.status) && item.id !== activeRunId);
  if (!conversationId || !visible.length) return null;
  return <section aria-label={t("Message queue", "消息队列")} className="my-4 space-y-5">
    {visible.map((item) => <article key={item.id} className="ml-auto max-w-[86%] text-right">
      <p className="inline-block rounded-3xl bg-stone-200/65 px-5 py-3 text-left text-sm leading-7 whitespace-pre-wrap break-words [overflow-wrap:anywhere]">{item.request.message}</p>
      <p className="mt-1.5 text-xs text-stone-500">{t("Sent · updating direction", "已发送 · 正在调整方向")}</p>
    </article>)}
  </section>;
}
