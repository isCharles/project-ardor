"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Approval = { id: string; tool: string; arguments: string; status: string; expiresAt: string };

export function ToolApprovals({ requestId }: { requestId: string | null }) {
  const { t } = useLocale();
  const [items, setItems] = useState<Approval[]>([]);
  const [submitting, setSubmitting] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [pollFailed, setPollFailed] = useState(false);
  const section = useRef<HTMLElement>(null);
  useEffect(() => {
    if (!requestId) return;
    let stopped = false;
    let timer: number;
    const refresh = async () => {
      try {
        const result = await api<Approval[]>(`/api/agent/runs/${requestId}/approvals`);
        if (!stopped) { setItems(Array.isArray(result) ? result : []); setPollFailed(false); }
      } catch (reason) {
        // A run may not be registered yet. Transport failure never grants permission.
        if (!stopped && !(reason instanceof ApiError && reason.status === 404)) setPollFailed(true);
      }
      if (!stopped) timer = window.setTimeout(() => void refresh(), 1000);
    };
    void refresh();
    return () => { stopped = true; window.clearTimeout(timer); };
  }, [requestId]);

  async function decide(item: Approval, allow: boolean) {
    if (!requestId || submitting) return;
    setSubmitting(item.id); setError("");
    try {
      await api<void>(`/api/agent/runs/${requestId}/approvals/${item.id}`, { method: "POST", body: JSON.stringify({ allow }) });
      setItems((current) => current.map((value) => value.id === item.id ? { ...value, status: allow ? "APPROVED" : "DENIED" } : value));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not submit permission", "无法提交授权"));
    } finally { setSubmitting(null); }
  }

  const pending = items.filter((item) => item.status === "PENDING");
  const pendingIds = pending.map((item) => item.id).join(",");
  useEffect(() => {
    if (pendingIds) section.current?.scrollIntoView({ block: "nearest", behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  }, [pendingIds]);
  if (!requestId || !pending.length && !pollFailed) return null;
  return <section ref={section} aria-label={t("Tool permissions", "工具权限确认")} className="my-4 max-w-xl space-y-3">
    {pollFailed && <p role="status" className="text-xs text-amber-700">{t("Permission status is temporarily unavailable. Reconnecting; nothing is approved automatically.", "暂时无法读取权限状态，正在重连；不会自动批准。")}</p>}
    {pending.map((item) => <div key={item.id} className="rounded-2xl border border-violet-200 bg-white/90 p-4 shadow-sm">
      <p className="text-sm font-medium text-stone-800">{t("Allow this operation?", "允许这次操作？")}</p>
      <p className="mt-1 break-words text-xs text-violet-700">{item.tool}</p>
      <details className="mt-3 text-xs text-stone-500"><summary className="cursor-pointer">{t("Exact parameters", "具体参数与目标")}</summary><pre className="mt-2 max-h-52 overflow-auto whitespace-pre-wrap break-words [overflow-wrap:anywhere]">{item.arguments}</pre></details>
      <p className="mt-3 text-xs text-stone-500">{t("One operation only. Denial or expiry stops further tool execution for this request.", "仅允许本次操作。拒绝或过期后，本轮不再执行其他工具。")}</p>
      <div className="mt-4 flex justify-end gap-2"><button type="button" disabled={!!submitting} onClick={() => void decide(item, false)} className="rounded-full px-4 py-2 text-xs text-stone-600 disabled:opacity-50">{t("Deny", "拒绝")}</button><button type="button" disabled={!!submitting} onClick={() => void decide(item, true)} className="rounded-full bg-violet-600 px-4 py-2 text-xs font-medium text-white disabled:opacity-50">{submitting === item.id ? t("Submitting…", "正在提交…") : t("Allow once", "允许一次")}</button></div>
    </div>)}
    {error && <p role="alert" className="break-words text-xs text-rose-700">{error}</p>}
  </section>;
}
