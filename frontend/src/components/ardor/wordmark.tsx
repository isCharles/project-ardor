import { cn } from "@/lib/utils";

import { ArdorMark } from "./brand-mark";

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
