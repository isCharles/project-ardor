"use client";

import { useLayoutEffect } from "react";

/** Device-local coordinates only: never saves lesson text or another account's position. */
export function useCourseReadingPosition(userId: string | null, courseId: string | null, contentVersion: number) {
  useLayoutEffect(() => {
    if (!userId || !courseId) return;
    const scroller = document.querySelector<HTMLElement>("[data-app-scroll]");
    if (!scroller) return;
    const key = `ardor:course-position:${userId}:${courseId}`;
    let top = 0;
    try {
      const saved = JSON.parse(localStorage.getItem(key) ?? "null");
      if (saved?.version === contentVersion && Number.isFinite(saved.top) && saved.top >= 0) top = saved.top;
    } catch { /* Storage is optional; a damaged entry cannot block the course. */ }
    scroller.scrollTo({ top, behavior: "instant" });
    let lastTop = scroller.scrollTop;
    let frame = 0;
    const save = () => {
      try { localStorage.setItem(key, JSON.stringify({ version: contentVersion, top: lastTop })); }
      catch { /* Reading remains available when storage is full or disabled. */ }
    };
    const onScroll = () => {
      lastTop = scroller.scrollTop;
      if (!frame) frame = requestAnimationFrame(() => { frame = 0; save(); });
    };
    scroller.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("pagehide", save);
    return () => {
      // Save the captured coordinate, not a scroll reset performed by the next route.
      cancelAnimationFrame(frame);
      save();
      scroller.removeEventListener("scroll", onScroll);
      window.removeEventListener("pagehide", save);
    };
  }, [userId, courseId, contentVersion]);
}
