import { Slot } from "@radix-ui/react-slot";
import { cva, type VariantProps } from "class-variance-authority";
import { LoaderCircle } from "lucide-react";
import * as React from "react";

import { cn } from "@/lib/utils";

const button = cva(
  [
    "relative inline-flex select-none items-center justify-center gap-1.5 rounded-[var(--ardor-radius)]",
    "font-medium whitespace-nowrap",
    "transition-[background-color,border-color,color,opacity] duration-[var(--ardor-fast)] ease-[var(--ease-ardor)]",
    "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[var(--ardor-focus)]",
    "disabled:pointer-events-none disabled:opacity-40",
  ].join(" "),
  {
    variants: {
      variant: {
        /* The single loud action on a screen. There should rarely be two. */
        primary: "bg-[var(--ardor-accent)] text-[var(--ardor-accent-ink)] hover:bg-[var(--ardor-accent-hover)]",
        /* The workhorse: reads as an object on the page, not a call to action. */
        default:
          "border border-[var(--ardor-rule-strong)] bg-[var(--ardor-raised)] text-[var(--ardor-ink)] hover:border-[var(--ardor-ink-4)] hover:bg-[var(--ardor-sunken)]",
        /* Inline actions inside dense lists and toolbars. */
        ghost: "text-[var(--ardor-ink-2)] hover:bg-[var(--ardor-sunken)] hover:text-[var(--ardor-ink)]",
        danger: "text-[var(--ardor-bad)] hover:bg-[var(--ardor-bad-soft)]",
      },
      size: {
        sm: "h-7 px-2 text-[0.78125rem]",
        md: "h-9 px-3.5 text-[0.84375rem]",
        lg: "h-11 px-5 text-[0.90625rem]",
        icon: "size-8 px-0",
        "icon-sm": "size-7 px-0",
      },
    },
    defaultVariants: { variant: "default", size: "md" },
  },
);

type ButtonProps = React.ComponentProps<"button"> &
  VariantProps<typeof button> & {
    asChild?: boolean;
    /** Shows a spinner and blocks input without collapsing the button's width. */
    loading?: boolean;
  };

export function Button({
  className,
  variant,
  size,
  asChild = false,
  loading = false,
  disabled,
  children,
  ...props
}: ButtonProps) {
  const Comp = asChild ? Slot : "button";
  return (
    <Comp
      className={cn(button({ variant, size }), className)}
      disabled={asChild ? undefined : disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? (
        <>
          <span className="absolute inset-0 grid place-items-center">
            <LoaderCircle className="size-3.5 animate-spin" aria-hidden />
          </span>
          <span className="invisible contents">{children}</span>
        </>
      ) : (
        children
      )}
    </Comp>
  );
}

export { button as buttonVariants };
