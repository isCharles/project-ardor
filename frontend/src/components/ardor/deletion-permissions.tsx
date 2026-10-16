"use client";

import { ShieldAlert } from "lucide-react";
import { useLocale } from "@/lib/locale";

type Deletion = { kind: string; endpoint: string; label: string; detail: string; state: string; error?: string };

/** Real business proposals only: no model-authored permission description or automatic approval. */
export function DeletionPermissions<T extends Deletion>({ items, onKeep, onDelete }: {
  items: T[]; onKeep: (item: T) => void; onDelete: (item: T) => void;
}) {
  const { t } = useLocale();
  if (!items.length) return null;
  return <section aria-label={t("Deletion confirmation", "删除确认")} className="ardor-composer-enter mx-auto mb-3 max-h-[40dvh] w-full max-w-3xl space-y-2 overflow-y-auto rounded-2xl border border-rose-200/80 bg-white/95 p-4 shadow-[0_12px_40px_rgba(42,35,27,0.12)] backdrop-blur-xl motion-reduce:animate-none">
    {items.map((item) => <div key={`${item.kind}:${item.endpoint}`} className="flex items-start gap-3">
      <ShieldAlert className="mt-0.5 size-5 shrink-0 text-rose-500" />
      <div className="min-w-0 flex-1">
        <p className="break-words text-sm font-medium text-stone-800">{t(`Delete “${item.label}”?`, `删除「${item.label}」？`)}</p>
        <p className="mt-1 break-words text-xs leading-5 text-stone-500">{item.detail || t("This permanently removes the selected item. Nothing is deleted until you confirm.", "将永久删除这项内容。只有点击确认后才执行。")}</p>
        <p className="mt-1 text-xs text-stone-400">{item.state === "DELETING" ? t("Submitting the deletion. Waiting for the result…", "正在提交删除，请等待结果…") : item.state === "FAILED" ? t("Deletion was not confirmed. Check the error before retrying.", "尚未确认删除成功，请检查错误后重试。") : t("No deletion has happened yet. This cannot be undone.", "目前尚未删除，确认后无法撤销。")}</p>
        {item.error && <p role="alert" className="mt-2 break-words text-xs text-rose-600">{item.error}</p>}
        <div className="mt-3 flex justify-end gap-2">
          <button type="button" disabled={item.state === "DELETING"} onClick={() => onKeep(item)} className="rounded-full px-4 py-2 text-xs text-stone-600 hover:bg-stone-100 disabled:opacity-50">{t("Keep", "保留")}</button>
          <button type="button" disabled={item.state === "DELETING"} onClick={() => onDelete(item)} className="rounded-full bg-rose-600 px-4 py-2 text-xs font-medium text-white hover:bg-rose-700 disabled:opacity-50">{item.state === "DELETING" ? t("Deleting…", "正在删除…") : t("Confirm deletion", "确认删除")}</button>
        </div>
      </div>
    </div>)}
  </section>;
}
