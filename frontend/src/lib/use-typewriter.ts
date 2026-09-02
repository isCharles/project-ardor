"use client";

import { useEffect, useState } from "react";

/* The composer types its own suggestions.

   The phrases are the same ones the briefing offers, built from the user's
   real state, so the placeholder is never generic filler — it is the next
   thing this particular person could reasonably ask for. */
export function useTypewriter(phrases: string[], active: boolean) {
  const [typed, setTyped] = useState("");

  useEffect(() => {
    if (!active || phrases.length === 0) {
      const clear = window.setTimeout(() => setTyped(""), 0);
      return () => window.clearTimeout(clear);
    }
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      const settle = window.setTimeout(() => setTyped(phrases[0]), 0);
      return () => window.clearTimeout(settle);
    }

    let phrase = 0;
    let cursor = 0;
    let deleting = false;
    let timer = 0;

    const tick = () => {
      const text = phrases[phrase % phrases.length];
      cursor += deleting ? -1 : 1;
      setTyped(text.slice(0, cursor));

      let wait = deleting ? 22 : 52;
      if (!deleting && cursor >= text.length) {
        deleting = true;
        wait = 2200;
      } else if (deleting && cursor <= 0) {
        deleting = false;
        phrase += 1;
        wait = 420;
      }
      timer = window.setTimeout(tick, wait);
    };

    timer = window.setTimeout(tick, 600);
    return () => window.clearTimeout(timer);
  }, [phrases, active]);

  return typed;
}
