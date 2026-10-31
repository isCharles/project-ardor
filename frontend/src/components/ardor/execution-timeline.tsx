"use client";

import { CheckCircle2, ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import { useLocale } from "@/lib/locale";
import { markdownPlugins } from "@/lib/markdown-plugins";
import { runSegments, type TimelineStep } from "@/lib/run-timeline";
import { ToolDuration, type ToolMeasurement } from "./tool-duration";

export type ExecutionStep = TimelineStep & ToolMeasurement & { elapsedMs: number };

/**
 * The run as it happened: each short line the model said before a tool batch,
 * then that batch behind a collapsed "执行工具 N 次" row. Reasoning stays closed
 * so the visible process is the user-facing narration, not the raw thinking.
 */
export function ExecutionTimeline({ title, elapsed, thinking, steps, children }: {
  title: string; elapsed: string; thinking?: string | null; steps: ExecutionStep[]; children?: ReactNode;
}) {
  const { t } = useLocale();
  const segments = runSegments(steps);
  return <div data-execution-timeline className="mb-4 min-w-0 text-sm text-stone-800">
    <div className="flex items-center gap-2 text-xs text-stone-500">
      <span>{title}</span>
      {elapsed && <span className="tabular-nums text-stone-400">{elapsed}</span>}
    </div>
    {thinking && <details className="mt-1 text-xs text-stone-500">
      <summary className="w-fit cursor-pointer py-1 transition-colors hover:text-stone-800">{t("Model reasoning output", "模型思考输出")}</summary>
      <div className="max-h-64 overflow-auto whitespace-pre-wrap break-words py-2 leading-6 [overflow-wrap:anywhere]">{thinking}</div>
    </details>}
    {segments.length > 0 && <div className="mt-2 space-y-3">
      {segments.map(segment => segment.kind === "say"
        ? <div key={segment.key} data-run-say className="ardor-markdown max-w-full leading-7"><ReactMarkdown remarkPlugins={markdownPlugins}>{segment.text}</ReactMarkdown></div>
        : <details key={segment.key} data-tool-group className="group text-xs text-stone-500">
            <summary className="flex w-fit cursor-pointer list-none items-center gap-2 py-1 transition-colors hover:text-stone-800 [&::-webkit-details-marker]:hidden">
              <ChevronRight className="size-3.5 shrink-0 transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none" />
              <span>{segment.steps.length === 1 ? t("Used 1 tool", "执行工具 1 次") : t(`Used ${segment.steps.length} tools`, `执行工具 ${segment.steps.length} 次`)}</span>
              {segment.running && <span className="text-stone-400">{t("in progress", "进行中")}</span>}
            </summary>
            <div className="mt-2 space-y-2 border-l border-stone-200/70 pl-4">
              {segment.steps.map((step, index) => <div key={`${step.label}-${index}`} className="flex items-start gap-2">
                <CheckCircle2 className={`mt-0.5 size-3.5 shrink-0 ${step.status === "FAILED" ? "text-rose-500" : step.status === "NOT_EXECUTED" ? "text-amber-500" : "text-stone-400"}`} />
                <span className="min-w-0 whitespace-pre-wrap break-words">{step.label}</span>
                {step.done === false ? <span className="ml-auto shrink-0 text-stone-400">{t("In progress", "进行中")}</span> : <ToolDuration elapsedMs={step.elapsedMs} approvalWaitMs={step.approvalWaitMs} businessMs={step.businessMs} status={step.status ?? undefined} />}
              </div>)}
            </div>
          </details>
      )}
    </div>}
    {children}
  </div>;
}
