"use client";

import { X } from "lucide-react";
import * as React from "react";

import { cn } from "@/lib/utils";

/** Closes on Escape and on any pointer press outside the returned ref. */
export function useDismiss<T extends HTMLElement>(open: boolean, onClose: () => void) {
  const ref = React.useRef<T>(null);
  React.useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        onClose();
      }
    };
    const onPointer = (event: PointerEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) onClose();
    };
    document.addEventListener("keydown", onKey);
    document.addEventListener("pointerdown", onPointer);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("pointerdown", onPointer);
    };
  }, [open, onClose]);
  return ref;
}

/* ---------------------------------------------------------------
   Menu: the only floating surface in the product besides Dialog.
   --------------------------------------------------------------- */

export function Menu({
  open,
  onClose,
  align = "end",
  label,
  children,
}: {
  open: boolean;
  onClose: () => void;
  align?: "start" | "end";
  label: string;
  children: React.ReactNode;
}) {
  const ref = useDismiss<HTMLDivElement>(open, onClose);
  if (!open) return null;
  return (
    <div
      ref={ref}
      role="menu"
      aria-label={label}
      className={cn(
        "ardor-fade absolute top-full z-40 mt-1 min-w-44 rounded-[var(--ardor-radius)]",
        "border border-[var(--ardor-rule-strong)] bg-[var(--ardor-raised)] p-1",
        "shadow-[var(--ardor-shadow-overlay)]",
        align === "end" ? "right-0" : "left-0",
      )}
    >
      {children}
    </div>
  );
}

export function MenuItem({
  onSelect,
  danger = false,
  children,
}: {
  onSelect: () => void;
  danger?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      role="menuitem"
      onClick={onSelect}
      className={cn(
        "flex w-full items-center gap-2 rounded-[var(--ardor-radius-sm)] px-2 py-1.5 text-left text-[0.84375rem]",
        "transition-colors duration-[var(--ardor-fast)]",
        danger
          ? "text-[var(--ardor-bad)] hover:bg-[var(--ardor-bad-soft)]"
          : "text-[var(--ardor-ink-2)] hover:bg-[var(--ardor-sunken)] hover:text-[var(--ardor-ink)]",
      )}
    >
      {children}
    </button>
  );
}

/* ---------------------------------------------------------------
   Dialog: scroll-locked, focus-restoring, escape-dismissable.
   --------------------------------------------------------------- */

export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  footer,
  size = "md",
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
  size?: "md" | "lg";
}) {
  const ref = useDismiss<HTMLDivElement>(open, onClose);
  const titleId = React.useId();

  React.useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    // Move focus into the dialog so Escape and Tab behave.
    window.setTimeout(() => {
      ref.current?.querySelector<HTMLElement>(
        "input, textarea, select, button, [tabindex]:not([tabindex='-1'])",
      )?.focus();
    }, 0);
    return () => {
      document.body.style.overflow = overflow;
      previous?.focus?.();
    };
  }, [open, ref]);

  if (!open) return null;

  return (
    <div className="ardor-fade fixed inset-0 z-50 grid place-items-center overflow-y-auto bg-[rgba(26,25,23,0.32)] p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className={cn(
          "ardor-rise w-full rounded-[var(--ardor-radius-lg)] border border-[var(--ardor-rule-strong)]",
          "bg-[var(--ardor-raised)] shadow-[var(--ardor-shadow-overlay)]",
          size === "lg" ? "max-w-2xl" : "max-w-lg",
        )}
      >
        <div className="flex items-start justify-between gap-6 border-b border-[var(--ardor-rule)] px-5 py-4">
          <div className="min-w-0">
            <h2 id={titleId} className="t-title-3 text-[var(--ardor-ink)]">
              {title}
            </h2>
            {description && (
              <p className="t-body mt-1 text-[var(--ardor-ink-3)]">{description}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭"
            className="-mr-1 -mt-1 shrink-0 rounded-[var(--ardor-radius-sm)] p-1.5 text-[var(--ardor-ink-4)] transition-colors hover:bg-[var(--ardor-sunken)] hover:text-[var(--ardor-ink)]"
          >
            <X className="size-4" />
          </button>
        </div>
        <div className="px-5 py-5">{children}</div>
        {footer && (
          <div className="flex items-center justify-end gap-2 border-t border-[var(--ardor-rule)] px-5 py-3.5">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
