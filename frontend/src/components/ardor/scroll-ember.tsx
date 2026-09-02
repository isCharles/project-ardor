"use client";

import * as React from "react";

/* The ember, burning down the edge of the page as you scroll.

   The same object as the mark, the run trail and the active rail row —
   which is what makes it an identity rather than an accent colour. It is
   also honest progress: it is exactly how far down the page you are. */
export function ScrollEmber() {
  const fill = React.useRef<HTMLSpanElement>(null);

  React.useEffect(() => {
    let frame = 0;
    const update = () => {
      frame = 0;
      const scrollable = document.documentElement.scrollHeight - window.innerHeight;
      const progress = scrollable > 0 ? window.scrollY / scrollable : 0;
      // Written straight to the node: this runs on every scroll frame and
      // has no business re-rendering React.
      if (fill.current) fill.current.style.transform = `scaleY(${Math.min(1, progress)})`;
    };
    const onScroll = () => {
      if (!frame) frame = window.requestAnimationFrame(update);
    };
    update();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    return () => {
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
      if (frame) window.cancelAnimationFrame(frame);
    };
  }, []);

  return (
    <div
      aria-hidden
      className="pointer-events-none fixed inset-y-0 left-0 z-50 hidden w-px bg-[var(--ardor-rule)] md:block"
    >
      <span
        ref={fill}
        className="block h-full w-full origin-top bg-[var(--ardor-accent)]"
        style={{ transform: "scaleY(0)" }}
      />
    </div>
  );
}
