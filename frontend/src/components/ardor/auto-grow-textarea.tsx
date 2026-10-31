"use client";

import { useEffect, useRef, type ComponentProps } from "react";

import { cn } from "@/lib/utils";

function grow(textarea: HTMLTextAreaElement) {
  textarea.style.height = "auto";
  textarea.style.height = `${textarea.scrollHeight + textarea.offsetHeight - textarea.clientHeight}px`;
}

export function AutoGrowTextarea({ className, onInput, ...props }: ComponentProps<"textarea">) {
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (ref.current) grow(ref.current);
  });

  useEffect(() => {
    const resize = () => {
      if (ref.current) grow(ref.current);
    };
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, []);

  return (
    <textarea
      ref={ref}
      className={cn("max-h-[60vh] resize-none overflow-y-auto", className)}
      onInput={(event) => {
        grow(event.currentTarget);
        onInput?.(event);
      }}
      {...props}
    />
  );
}
