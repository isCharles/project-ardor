"use client";

import { CheckCircle2, ChevronRight } from "lucide-react";
import { useEffect, useRef, type ReactNode } from "react";
import { useLocale } from "@/lib/locale";
import { ToolDuration, type ToolMeasurement } from "./tool-duration";

export type ExecutionStep = ToolMeasurement & { label: string; elapsedMs: number; status?: string; done?: boolean };

/**
 * One quiet process disclosure above the answer, not a separate card.
 *
 * While the run is active it opens itself (including the reasoning output) so the
 * work is visible; as soon as the run ends it collapses back to a single line.
 * The user can still toggle it manually in between.
 */
export function ExecutionTrace({ title, elapsed, thinking, steps, children, action, active = false }: {
  title: string; elapsed: string; thinking?: string | null; steps: ExecutionStep[]; children?: ReactNode;
  action?: ReactNode; active?: boolean;
}) {
  const { t } = useLocale();
  const detailsRef = useRef<HTMLDetailsElement>(null);
  const thinkingRef = useRef<HTMLDetailsElement>(null);
  useEffect(() => {
    if (detailsRef.current) detailsRef.current.open = active;
    if (thinkingRef.current) thinkingRef.current.open = active;
  }, [active]);
  return <div data-execution-trace className="mb-4 min-w-0 text-xs text-stone-500">
    <div className="flex items-start gap-3">
      <details ref={detailsRef} className="group min-w-0 flex-1">
        <summary className="flex w-fit cursor-pointer list-none items-center gap-2 py-1 transition-colors hover:text-stone-800 [&::-webkit-details-marker]:hidden">
          <ChevronRight className="size-3.5 shrink-0 transition-transform duration-200 group-open:rotate-90 motion-reduce:transition-none" />
          <span>{title}</span><span className="tabular-nums text-stone-400">{elapsed}</span>
        </summary>
        <div className="mt-2 space-y-2 border-l border-stone-200/70 pl-4">
          {thinking && <details ref={thinkingRef}><summary className="w-fit cursor-pointer py-1">{t("Model reasoning output", "模型思考输出")}</summary><div className="max-h-64 overflow-auto whitespace-pre-wrap break-words py-2 leading-6 [overflow-wrap:anywhere]">{thinking}</div></details>}
          {steps.map((step, index) => <div key={`${step.label}-${index}`} className="flex items-center gap-2"><CheckCircle2 className={`size-3.5 shrink-0 ${step.status === "FAILED" ? "text-rose-500" : step.status === "NOT_EXECUTED" ? "text-amber-500" : "text-stone-400"}`} /><span>{step.label}</span>{step.done === false ? <span className="ml-auto text-stone-400">{t("In progress", "进行中")}</span> : <ToolDuration {...step} />}</div>)}
          {children}
        </div>
      </details>
      {action}
    </div>
  </div>;
}
