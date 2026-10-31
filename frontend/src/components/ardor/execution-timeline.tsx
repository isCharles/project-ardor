"use client";

import { CheckCircle2, ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { useLocale } from "@/lib/locale";
import { runTimeline, type TimelineStep } from "@/lib/run-timeline";
import { ToolDuration, type ToolMeasurement } from "./tool-duration";

export type ExecutionStep = TimelineStep & ToolMeasurement & { elapsedMs: number };

/**
 * One process record per reply: the short lines the model said and the tools
 * they announced, in order. It stays open while the run is working and folds
 * into a single "执行工具 N 次" disclosure once the final answer is done.
 */
export function ExecutionTimeline({ title, elapsed, thinking, steps, running = false, completed = false, children }: {
  title: string; elapsed: string; thinking?: string | null; steps: ExecutionStep[];
  running?: boolean; completed?: boolean; children?: ReactNode;
}) {
  const { t } = useLocale();
  const timeline = runTimeline(steps);
  const working = running || timeline.running;
  const summary = working
    ? t("Working", "正在执行中")
    : completed && timeline.tools.length > 0
      ? timeline.tools.length === 1 ? t("Used 1 tool", "执行工具 1 次") : t(`Used ${timeline.tools.length} tools`, `执行工具 ${timeline.tools.length} 次`)
      : title;
  if (timeline.rows.length === 0 && !thinking) return <div data-execution-timeline className="mb-4 min-w-0 text-sm text-stone-800">{children}</div>;
  return <div data-execution-timeline className="mb-4 min-w-0 text-sm text-stone-800">
    <details data-tool-group open={working} className="group text-xs text-stone-500">
      <summary className="flex w-fit cursor-pointer list-none items-center gap-2 py-1 transition-colors hover:text-stone-800 [&::-webkit-details-marker]:hidden">
        <ChevronRight className="size-3.5 shrink-0 transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none" />
        <span>{summary}</span>
        {elapsed && <span className="tabular-nums text-stone-400">{elapsed}</span>}
      </summary>
      <div className="mt-2 space-y-2 border-l border-stone-200/70 pl-4">
        {thinking && <details className="text-xs text-stone-500">
          <summary className="w-fit cursor-pointer py-1 transition-colors hover:text-stone-800">{t("Model reasoning output", "模型思考输出")}</summary>
          <div className="max-h-64 overflow-auto whitespace-pre-wrap break-words py-2 leading-6 [overflow-wrap:anywhere]">{thinking}</div>
        </details>}
        {timeline.rows.map(row => row.kind === "say"
          ? <div key={row.key} data-run-say className="whitespace-pre-wrap break-words py-1 text-sm leading-6 text-stone-600 [overflow-wrap:anywhere]">{row.text}</div>
          : <div key={row.key} data-run-tool className="flex items-start gap-2">
              <CheckCircle2 className={`mt-0.5 size-3.5 shrink-0 ${row.step.status === "FAILED" ? "text-rose-500" : row.step.status === "NOT_EXECUTED" ? "text-amber-500" : "text-stone-400"}`} />
              <span className="min-w-0 whitespace-pre-wrap break-words">{row.step.label}</span>
              {row.step.done === false ? <span className="ml-auto shrink-0 text-stone-400">{t("In progress", "进行中")}</span> : <ToolDuration elapsedMs={row.step.elapsedMs} approvalWaitMs={row.step.approvalWaitMs} businessMs={row.step.businessMs} status={row.step.status ?? undefined} />}
            </div>)}
        {working && <p data-run-phase className="py-1 text-stone-400">{title}</p>}
      </div>
    </details>
    {children}
  </div>;
}
