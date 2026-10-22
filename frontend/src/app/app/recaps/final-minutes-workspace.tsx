"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Minutes = { jobId: string; minutes: string; status: string; recapId: string | null; errorMessage: string | null };
type Summary = { jobId: string; preview: string; status: string; recapId: string | null };

export function FinalMinutesWorkspace({ id }: { id: string | null }) {
  const { t } = useLocale();
  const router = useRouter();
  const [items, setItems] = useState<Summary[]>([]);
  const [selected, setSelected] = useState<Minutes | null>(null);
  const [error, setError] = useState("");
  const [retrying, setRetrying] = useState(false);
  const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        if (id) {
          const result = await api<Minutes>(`/api/interview-recaps/final-minutes/${id}`);
          if (!alive) return;
          setSelected(result); setError("");
          if (result.status === "COMPLETED" && result.recapId) {
            const params = new URLSearchParams(window.location.search);
            if (params.get("selected") !== result.recapId) router.replace(`/app/recaps?minutes=${id}&selected=${result.recapId}`);
          }
          if (result.status === "QUEUED" || result.status === "RUNNING") timer = setTimeout(() => void load(), 2500);
        } else {
          const result = await api<Summary[]>("/api/interview-recaps/final-minutes");
          if (alive) { setItems(result); setError(""); }
        }
      } catch {
        if (alive) setError(t("Could not load saved minutes. Refresh to try again.", "无法读取已保存纪要，请刷新重试。"));
      }
    };
    void load();
    return () => { alive = false; clearTimeout(timer); };
  }, [id, router, t, refresh]);

  async function retry() {
    if (!id || retrying) return;
    setRetrying(true);
    try {
      await api(`/api/interview-recaps/jobs/${id}/retry`, { method: "POST" });
      setRefresh(value => value + 1);
    } catch (reason) { setError(reason instanceof ApiError && reason.body.code === "QUOTA_EXCEEDED"
      ? t("Usage or request rate limit reached. Try again after it resets; your minutes are saved.", "额度已用尽或请求过于频繁，请稍后再试；纪要已保存。")
      : t("Could not retry. Your saved minutes are unchanged.", "暂时无法重试，已保存纪要不会丢失。")); }
    finally { setRetrying(false); }
  }
  return <section key={id} aria-label={t("Saved minutes", "已保存纪要")} className="mb-6 space-y-3">
    {error && <p role="alert" className="rounded-xl bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    {id && !selected && !error && <p role="status">{t("Loading saved minutes…", "正在读取纪要…")}</p>}
    {id && selected && <div className="space-y-3 rounded-3xl border border-white/80 bg-white/70 p-5">
      <details><summary className="cursor-pointer text-sm font-medium">{t("Saved final minutes", "已保存的最终纪要")}</summary>
        <p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7 text-stone-600">{selected.minutes}</p>
      </details>
      {(selected.status === "QUEUED" || selected.status === "RUNNING") && <p role="status" className="text-sm text-violet-700">{t("Minutes saved. Ardor is organizing them in the background; usually around 1–2 minutes, depending on the model. You can leave this page.", "纪要已保存，Ardor 正在后台整理，通常约 1–2 分钟，具体取决于模型。你可以先离开页面。")}</p>}
      {selected.status === "FAILED" && <div><p className="text-sm text-rose-700">{t("Organization failed. Your minutes are saved; you do not need to paste them again.", "整理未完成，纪要已保存，无需重新粘贴。")}</p>
        <button disabled={retrying} onClick={() => void retry()} className="mt-2 text-sm text-violet-700 underline">{retrying ? t("Retrying…", "正在重试…") : t("Retry", "重试")}</button>
        {selected.errorMessage && <details className="mt-2 text-xs text-stone-500"><summary>{t("View details", "查看详情")}</summary><p className="max-h-32 overflow-auto whitespace-pre-wrap break-all">{selected.errorMessage}</p></details>}
      </div>}
      {selected.status === "COMPLETED" && !selected.recapId && <p className="text-sm text-stone-500">{t("The organized result is no longer available. Your submitted minutes remain saved.", "整理结果已不可用，提交的纪要仍保留。")}</p>}
    </div>}
    {!id && items.length > 0 && <details className="rounded-2xl bg-white/60 p-4"><summary className="cursor-pointer text-sm">{t("Saved submissions", "已保存的纪要")}</summary>
      <ul className="mt-3 divide-y divide-stone-100">{items.map(item => <li key={item.jobId}><Link href={`/app/recaps?minutes=${item.jobId}`} className="flex min-w-0 items-center gap-3 py-3 text-sm"><span className="min-w-0 flex-1 truncate">{item.preview}</span><span className="shrink-0 text-stone-500">{item.status === "COMPLETED" ? t("Ready", "已整理") : item.status === "FAILED" ? t("Needs retry", "待重试") : t("Organizing…", "整理中…")}</span></Link></li>)}</ul>
    </details>}
  </section>;
}
