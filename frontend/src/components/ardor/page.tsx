import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import * as React from "react";

import { cn } from "@/lib/utils";

/* Page chrome for the working screens. Deliberately not a hero:
   a title, the facts about the collection, and the actions. The old
   pages opened with a marketing slogan at 72px above the actual data. */

export function PageHeader({
  eyebrow,
  title,
  meta,
  actions,
  back,
  className,
}: {
  eyebrow?: string;
  title: string;
  meta?: React.ReactNode;
  actions?: React.ReactNode;
  /** Either a route to go back to, or an in-page handler for master/detail. */
  back?: { label: string; href?: string; onClick?: () => void };
  className?: string;
}) {
  const backStyle =
    "t-meta mb-5 inline-flex items-center gap-1.5 text-[var(--ardor-ink-3)] transition-colors hover:text-[var(--ardor-ink)]";
  return (
    <header className={cn("border-b border-[var(--ardor-rule)] pb-5", className)}>
      {back &&
        (back.href ? (
          <Link href={back.href} className={backStyle}>
            <ArrowLeft className="size-3.5" aria-hidden />
            {back.label}
          </Link>
        ) : (
          <button type="button" onClick={back.onClick} className={backStyle}>
            <ArrowLeft className="size-3.5" aria-hidden />
            {back.label}
          </button>
        ))}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          {eyebrow && <p className="t-eyebrow mb-2 text-[var(--ardor-accent)]">{eyebrow}</p>}
          <h1 className="t-title-1 text-[var(--ardor-ink)]">{title}</h1>
          {meta && <div className="mt-2">{meta}</div>}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
    </header>
  );
}

/** A titled band of content, separated by rule and space rather than a card. */
export function Section({
  title,
  eyebrow,
  aside,
  children,
  className,
}: {
  title?: string;
  eyebrow?: string;
  aside?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("py-8", className)}>
      {(title || eyebrow || aside) && (
        <div className="mb-4 flex flex-wrap items-baseline justify-between gap-x-6 gap-y-2">
          <div>
            {eyebrow && <p className="t-eyebrow mb-1.5 text-[var(--ardor-ink-4)]">{eyebrow}</p>}
            {title && <h2 className="t-title-2 text-[var(--ardor-ink)]">{title}</h2>}
          </div>
          {aside}
        </div>
      )}
      {children}
    </section>
  );
}

/* An empty state should say what this place is for and offer the one
   next step — not draw an icon in a circle. */
export function Empty({
  title,
  body,
  action,
  className,
}: {
  title: string;
  body: React.ReactNode;
  action?: React.ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "border-l border-[var(--ardor-accent-line)] py-1 pl-5",
        className,
      )}
    >
      <p className="t-title-3 text-[var(--ardor-ink)]">{title}</p>
      <p className="t-body mt-1.5 max-w-[30rem] text-[var(--ardor-ink-3)]">{body}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/* One notice component for every transient message in the app, so that
   errors, confirmations and warnings never invent their own styling. */
export function Notice({
  tone = "info",
  children,
  onDismiss,
  className,
}: {
  tone?: "info" | "ok" | "warn" | "bad";
  children: React.ReactNode;
  onDismiss?: () => void;
  className?: string;
}) {
  const line = {
    info: "border-[var(--ardor-rule-strong)]",
    ok: "border-[var(--ardor-ok)]",
    warn: "border-[var(--ardor-warn)]",
    bad: "border-[var(--ardor-bad)]",
  }[tone];
  const ink = {
    info: "text-[var(--ardor-ink-2)]",
    ok: "text-[var(--ardor-ok)]",
    warn: "text-[var(--ardor-warn)]",
    bad: "text-[var(--ardor-bad)]",
  }[tone];
  return (
    <div
      role={tone === "bad" ? "alert" : "status"}
      className={cn("ardor-fade flex items-start gap-3 border-l-2 py-1.5 pl-3.5", line, className)}
    >
      <div className={cn("t-body min-w-0 flex-1", ink)}>{children}</div>
      {onDismiss && (
        <button
          type="button"
          onClick={onDismiss}
          className="t-meta shrink-0 text-[var(--ardor-ink-4)] transition-colors hover:text-[var(--ardor-ink)]"
        >
          知道了
        </button>
      )}
    </div>
  );
}
