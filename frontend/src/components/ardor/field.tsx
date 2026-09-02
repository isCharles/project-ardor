import * as React from "react";

import { cn } from "@/lib/utils";

/* One control surface shared by input, textarea and select, so focus,
   disabled and invalid look identical everywhere in the product. */
const control = [
  "w-full rounded-[var(--ardor-radius)] border border-[var(--ardor-rule-strong)]",
  "bg-[var(--ardor-raised)] px-3 py-2 text-[0.90625rem] leading-6 text-[var(--ardor-ink)]",
  "transition-[border-color,box-shadow] duration-[var(--ardor-fast)] ease-[var(--ease-ardor)]",
  "placeholder:text-[var(--ardor-ink-4)]",
  "focus:border-[var(--ardor-accent)] focus:outline-none focus:ring-2 focus:ring-[var(--ardor-accent-soft)]",
  "disabled:cursor-not-allowed disabled:bg-[var(--ardor-sunken)] disabled:text-[var(--ardor-ink-3)]",
  "aria-[invalid=true]:border-[var(--ardor-bad)]",
].join(" ");

export function Input({ className, ...props }: React.ComponentProps<"input">) {
  return <input className={cn(control, "h-9 py-0", className)} {...props} />;
}

export function Textarea({ className, ...props }: React.ComponentProps<"textarea">) {
  return <textarea className={cn(control, "resize-y", className)} {...props} />;
}

export function Select({ className, ...props }: React.ComponentProps<"select">) {
  return <select className={cn(control, "h-9 py-0", className)} {...props} />;
}

/** Label, control and help text as one unit, so spacing never drifts. */
export function Field({
  label,
  hint,
  error,
  htmlFor,
  children,
  className,
}: {
  label: string;
  hint?: React.ReactNode;
  error?: string;
  htmlFor?: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("space-y-1.5", className)}>
      <label htmlFor={htmlFor} className="t-label block text-[var(--ardor-ink-2)]">
        {label}
      </label>
      {children}
      {error ? (
        <p className="t-meta text-[var(--ardor-bad)]">{error}</p>
      ) : hint ? (
        <p className="t-meta text-[var(--ardor-ink-3)]">{hint}</p>
      ) : null}
    </div>
  );
}
