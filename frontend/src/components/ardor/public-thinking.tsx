"use client";

import { useLocale } from "@/lib/locale";

/** Only provider-supplied public reasoning, never reconstructed internal thoughts. */
export function PublicThinking({ content }: { content?: string | null }) {
  const { t } = useLocale();
  if (!content) return null;
  return <details className="mt-3 min-w-0 rounded-xl border border-violet-100 bg-violet-50/40 px-3 py-2">
    <summary className="cursor-pointer text-xs font-medium text-violet-700">
      {t("Model reasoning output", "模型思考输出")}
    </summary>
    <div className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words text-xs leading-6 text-stone-600 [overflow-wrap:anywhere]">{content}</div>
  </details>;
}
