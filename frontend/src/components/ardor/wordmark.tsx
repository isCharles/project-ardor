import { cn } from "@/lib/utils";

/* The mark is the product's own notation, not a stock flame icon:
   one vertical rule with entries hung off it, and the current entry
   burning. It is the Rail component reduced to 14 pixels. */
export function ArdorMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 14 18"
      fill="none"
      aria-hidden
      className={cn("size-[18px]", className)}
    >
      <path d="M1.5 0.5v17" stroke="var(--ardor-ink)" strokeOpacity="0.28" strokeWidth="1" />
      <path d="M1.5 3.5v5" stroke="var(--ardor-accent)" strokeWidth="2" strokeLinecap="square" />
      <path d="M4 4h8M4 9h5.5M4 14h7" stroke="var(--ardor-ink)" strokeOpacity="0.28" strokeWidth="1" />
    </svg>
  );
}

export function Wordmark({
  className,
  size = "sm",
}: {
  className?: string;
  size?: "sm" | "lg";
}) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <ArdorMark className={size === "lg" ? "size-6" : "size-[18px]"} />
      <span
        className={cn(
          "font-medium tracking-[-0.01em] text-[var(--ardor-ink)]",
          size === "lg" ? "text-lg" : "text-[0.9375rem]",
        )}
        style={{ fontFamily: "var(--ardor-font-display)" }}
      >
        Ardor
      </span>
    </span>
  );
}
