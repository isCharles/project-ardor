"use client";

import { type CSSProperties, useEffect, useRef, useState } from "react";

export function ConversationTitle({ children }: { children: string }) {
  const viewport = useRef<HTMLSpanElement>(null);
  const text = useRef<HTMLSpanElement>(null);
  const [distance, setDistance] = useState(0);
  useEffect(() => {
    const frame = viewport.current, label = text.current;
    if (!frame || !label) return;
    const measure = () => setDistance(Math.max(0, label.scrollWidth - frame.clientWidth));
    const observer = new ResizeObserver(measure);
    observer.observe(frame); observer.observe(label); measure();
    return () => observer.disconnect();
  }, [children]);
  return <span ref={viewport} data-conversation-title data-overflow={distance > 0} className="ardor-conversation-title" style={{
    "--title-shift": `${-(distance + 24)}px`, "--title-duration": `${Math.max(4, distance / 24)}s`,
  } as CSSProperties}><span ref={text}>{children}</span></span>;
}
