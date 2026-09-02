"use client";

import * as React from "react";

import { cn } from "@/lib/utils";

/* Tracks whether an element is on screen right now, in both directions.

   Used to drive the animated scenes: a scene that has scrolled away stops
   stepping instead of running timers for the rest of the session. */
export function useOnScreen<T extends HTMLElement>() {
  const ref = React.useRef<T>(null);
  const [onScreen, setOnScreen] = React.useState(false);

  React.useEffect(() => {
    const element = ref.current;
    if (!element) return;
    if (typeof IntersectionObserver === "undefined") {
      const settle = window.setTimeout(() => setOnScreen(true), 0);
      return () => window.clearTimeout(settle);
    }
    const observer = new IntersectionObserver(
      ([entry]) => setOnScreen(entry.isIntersecting),
      { threshold: 0.2 },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return [ref, onScreen] as const;
}

/* Scroll choreography.

   Nothing on the landing page arrives already there: content enters when
   its scene does, in order. One observer per block, disconnected after the
   first reveal — no scroll listeners, no layout reads per frame. */
export function Reveal({
  children,
  className,
  delay = 0,
  as: Tag = "div",
}: {
  children: React.ReactNode;
  className?: string;
  /** Position in the stagger, not milliseconds. */
  delay?: number;
  as?: "div" | "section" | "li" | "p" | "h2";
}) {
  const ref = React.useRef<HTMLElement>(null);
  const [shown, setShown] = React.useState(false);

  React.useEffect(() => {
    const element = ref.current;
    if (!element) return;
    if (typeof IntersectionObserver === "undefined") {
      const settle = window.setTimeout(() => setShown(true), 0);
      return () => window.clearTimeout(settle);
    }
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setShown(true);
          observer.disconnect();
        }
      },
      { threshold: 0.1, rootMargin: "0px 0px -8% 0px" },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  return React.createElement(
    Tag,
    {
      ref: ref as React.Ref<HTMLElement>,
      className: cn(shown ? "ardor-in" : "opacity-0", className),
      style: { ["--ardor-i" as string]: delay },
    },
    children,
  );
}
