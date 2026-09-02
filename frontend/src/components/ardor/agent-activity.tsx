"use client";

import { ChevronRight } from "lucide-react";

import { cn } from "@/lib/utils";

export type RunStep = { key: string; label: string; elapsedMs: number; done: boolean };

function seconds(ms: number) {
  return `${(ms / 1000).toFixed(1)}s`;
}

/* What Ardor is doing, on the thread.

   The same line as the landing page and the rail, here doing its most
   literal job: it advances as the agent works, and the red is only ever on
   the step actually running. The backend already emits specific labels —
   "提交简历分析", "整理面经与薄弱点" — so there is no need for a spinner or
   for the word "thinking". */
export function AgentActivity({
  steps,
  running,
  failed = false,
  elapsedMs,
  open,
  onToggle,
}: {
  steps: RunStep[];
  running: boolean;
  failed?: boolean;
  elapsedMs: number;
  open: boolean;
  onToggle: () => void;
}) {
  if (steps.length === 0 && !running) return null;

  const state = running ? "运行中" : failed ? "已中断" : "已完成";
  const shown = steps.length || 1;
  const finished = steps.filter((step) => step.done).length;
  // While running, the thread reaches the step in flight rather than the
  // last finished one, so the line is always ahead of the text.
  const reached = running ? Math.min(shown, finished + 1) : shown;

  return (
    <section className="ardor-fade" aria-live="polite">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="group flex w-full items-center gap-2 py-1 text-left"
      >
        <ChevronRight
          className={cn(
            "size-3.5 shrink-0 text-[var(--ardor-ink-4)] transition-transform duration-[var(--ardor-fast)] ease-[var(--ease-ardor)]",
            open && "rotate-90",
          )}
          aria-hidden
        />
        <span
          className={cn(
            "t-meta",
            running
              ? "text-[var(--ardor-accent)]"
              : failed
                ? "text-[var(--ardor-bad)]"
                : "text-[var(--ardor-ink-3)]",
          )}
        >
          {state}
        </span>
        <span className="t-meta text-[var(--ardor-ink-4)]">
          {steps.length} 步 · {seconds(elapsedMs)}
        </span>
      </button>

      {open && (
        <ol className="relative ml-[0.4375rem] pl-5">
          <span aria-hidden className="absolute bottom-3 left-0 top-3 w-px bg-[var(--ardor-rule-strong)]" />
          <span
            aria-hidden
            className={cn(
              "absolute left-0 top-3 w-px transition-[height] duration-500 ease-[var(--ease-ardor)]",
              failed ? "bg-[var(--ardor-bad)]" : "bg-[var(--ardor-accent)]",
            )}
            style={{ height: `calc(${Math.max(0, reached - 1)} * 1.75rem)` }}
          />

          {steps.map((step) => (
            <li key={step.key} className="relative flex h-7 items-center gap-3">
              <span
                aria-hidden
                className={cn(
                  "absolute -left-5 size-[5px] -translate-x-1/2 rounded-full ring-4 ring-[var(--ardor-surface)]",
                  step.done
                    ? "bg-[var(--ardor-accent)]"
                    : failed
                      ? "bg-[var(--ardor-bad)]"
                      : "bg-[var(--ardor-accent)] ardor-ember",
                )}
              />
              <span
                className={cn(
                  "t-body min-w-0 flex-1 truncate",
                  step.done ? "text-[var(--ardor-ink-2)]" : "text-[var(--ardor-ink)]",
                )}
              >
                {step.label}
              </span>
              {step.elapsedMs > 0 && (
                <span className="t-meta shrink-0 text-[var(--ardor-ink-4)]">
                  {seconds(step.elapsedMs)}
                </span>
              )}
            </li>
          ))}

          {running && steps.length === 0 && (
            <li className="relative flex h-7 items-center gap-3">
              <span
                aria-hidden
                className="ardor-ember absolute left-0 size-[5px] -translate-x-1/2 rounded-full bg-[var(--ardor-accent)] ring-4 ring-[var(--ardor-surface)]"
              />
              <span className="t-body text-[var(--ardor-ink)]">等待模型响应</span>
            </li>
          )}
        </ol>
      )}
    </section>
  );
}

/* Loading, as the thread inching forward. Used where there is nothing to
   report yet — a page waking up, a record still loading. */
export function ThreadLoading({ label, className }: { label: string; className?: string }) {
  return (
    <div className={cn("flex items-center gap-3", className)} role="status">
      <span aria-hidden className="relative block h-px w-24 overflow-hidden bg-[var(--ardor-rule-strong)]">
        <span className="ardor-thread-crawl absolute inset-y-0 left-0 w-1/3 bg-[var(--ardor-accent)]" />
      </span>
      <span className="t-meta text-[var(--ardor-ink-3)]">{label}</span>
    </div>
  );
}
