import { cn } from "@/lib/utils";

/* Status is a dot plus a word. No pill, no background, no colour block —
   the dot carries the semantics and the word carries the meaning. */

export type Tone = "neutral" | "accent" | "ok" | "warn" | "bad";

const dot: Record<Tone, string> = {
  neutral: "bg-[var(--ardor-ink-4)]",
  accent: "bg-[var(--ardor-accent)]",
  ok: "bg-[var(--ardor-ok)]",
  warn: "bg-[var(--ardor-warn)]",
  bad: "bg-[var(--ardor-bad)]",
};

const text: Record<Tone, string> = {
  neutral: "text-[var(--ardor-ink-3)]",
  accent: "text-[var(--ardor-accent)]",
  ok: "text-[var(--ardor-ok)]",
  warn: "text-[var(--ardor-warn)]",
  bad: "text-[var(--ardor-bad)]",
};

export function Status({
  tone = "neutral",
  children,
  live = false,
  className,
}: {
  tone?: Tone;
  children: React.ReactNode;
  /** Pulses the dot while work is genuinely in flight. */
  live?: boolean;
  className?: string;
}) {
  return (
    <span className={cn("t-meta inline-flex items-center gap-1.5", text[tone], className)}>
      <span
        aria-hidden
        className={cn("size-1.5 shrink-0 rounded-full", dot[tone], live && "ardor-ember")}
      />
      {children}
    </span>
  );
}
