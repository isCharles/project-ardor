import * as React from "react";

import { cn } from "@/lib/utils";

/* ---------------------------------------------------------------
   Ardor's recurring mark.

   Everything durable the agent knows or does is addressable, and the
   interface shows the address: resumes are R-01, interviews I-01,
   recaps V-01, cards C-014, memories M-03, agent steps 01/02/03.
   It is a real handle, not ornament — you can say "open R-02" and the
   product means something by it.
   --------------------------------------------------------------- */

export type IndexSeries = "R" | "I" | "V" | "C" | "M" | "T";

/** Formats a 1-based position into a stable, sortable index token. */
export function indexOf(series: IndexSeries, position: number, pad = 2) {
  return `${series}-${String(position).padStart(pad, "0")}`;
}

export function IndexMark({
  value,
  active = false,
  className,
}: {
  value: string;
  active?: boolean;
  className?: string;
}) {
  return (
    <span className={cn("ardor-index", active && "ardor-index-active", className)} aria-hidden>
      {value}
    </span>
  );
}

/* ---------------------------------------------------------------
   The rule. A Rail is a list hung on one hairline; the active row marks
   that hairline in ember. Reach for it where structure reads better as
   rules and space than as boxes — cards, ambient light and gradient are
   available too (DESIGN.md §4), this is not the only way to build a list.
   --------------------------------------------------------------- */

export function Rail({
  children,
  className,
}: {
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <ul className={cn("relative border-l border-[var(--ardor-rule)]", className)}>{children}</ul>
  );
}

export function RailItem({
  index,
  active = false,
  children,
  className,
}: {
  index?: string;
  active?: boolean;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <li className={cn("group relative", className)}>
      {/* The row's own segment of the rule, lit when current. */}
      <span
        aria-hidden
        className={cn(
          "absolute -left-px top-0 h-full w-px transition-colors duration-[var(--ardor-fast)]",
          active ? "bg-[var(--ardor-accent)]" : "bg-transparent group-hover:bg-[var(--ardor-rule-strong)]",
        )}
      />
      {index && (
        <IndexMark
          value={index}
          active={active}
          className="absolute left-3 top-1/2 -translate-y-1/2"
        />
      )}
      {children}
    </li>
  );
}

/* ---------------------------------------------------------------
   Metadata line: mono, hairline-separated, never a row of pills.
   --------------------------------------------------------------- */

export function Meta({
  items,
  className,
}: {
  items: React.ReactNode[];
  className?: string;
}) {
  const shown = items.filter(Boolean);
  return (
    <p className={cn("t-meta flex flex-wrap items-center text-[var(--ardor-ink-3)]", className)}>
      {shown.map((item, i) => (
        <React.Fragment key={i}>
          {i > 0 && (
            <span aria-hidden className="mx-2 h-3 w-px bg-[var(--ardor-rule-strong)]" />
          )}
          <span>{item}</span>
        </React.Fragment>
      ))}
    </p>
  );
}
