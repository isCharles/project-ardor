"use client";

import * as React from "react";

import { cn } from "@/lib/utils";

/* The Ardor Thread.

   One line, drawn forward, with the product's state hanging off it. It is
   the whole identity: past → memory → what is happening now → what is
   next. Nothing here is invented decoration — every node on the thread is
   a real record the agent keeps, and the red only ever marks the point the
   thread has reached.

   Drawn as one SVG path with pathLength="1", so the draw is a plain CSS
   dashoffset animation and the nodes fade in on delays matched to it. No
   animation library, no per-frame JavaScript. */

export type ThreadNode = {
  /** Position in the box, 0–100. */
  x: number;
  y: number;
  /** Where along the draw this node belongs, 0–1. */
  at: number;
  label: string;
  value: string;
  /** Anchors the text left or right of the point. */
  side?: "left" | "right";
  /** The final node reads as an outcome, not another record. */
  terminal?: boolean;
};

/* Catmull-Rom through the points, as cubic béziers: a line that bends
   rather than a stack of rows. The tension is deliberately low — at the
   textbook 1/6 the curve overshoots hard at each reversal and the thread
   starts to loop, which reads as decoration instead of a path. */
const TENSION = 0.11;

function smoothPath(points: { x: number; y: number }[]) {
  if (points.length < 2) return "";
  const d: string[] = [`M ${points[0].x} ${points[0].y}`];
  for (let i = 0; i < points.length - 1; i += 1) {
    const p0 = points[i - 1] ?? points[i];
    const p1 = points[i];
    const p2 = points[i + 1];
    const p3 = points[i + 2] ?? p2;
    const c1x = p1.x + (p2.x - p0.x) * TENSION;
    const c1y = p1.y + (p2.y - p0.y) * TENSION;
    const c2x = p2.x - (p3.x - p1.x) * TENSION;
    const c2y = p2.y - (p3.y - p1.y) * TENSION;
    d.push(`C ${c1x.toFixed(2)} ${c1y.toFixed(2)}, ${c2x.toFixed(2)} ${c2y.toFixed(2)}, ${p2.x} ${p2.y}`);
  }
  return d.join(" ");
}

export function ArdorThread({
  nodes,
  duration = 5.2,
  className,
  play = true,
}: {
  nodes: ThreadNode[];
  /** Seconds for the thread to draw its full length. */
  duration?: number;
  className?: string;
  play?: boolean;
}) {
  const path = React.useMemo(() => smoothPath(nodes), [nodes]);

  return (
    <div className={cn("relative h-full w-full", className)}>
      <svg
        viewBox="0 0 100 100"
        preserveAspectRatio="none"
        aria-hidden
        className="absolute inset-0 h-full w-full overflow-visible"
      >
        {/* The thread's own faint bed, so the line has somewhere to go. */}
        <path
          d={path}
          pathLength={1}
          fill="none"
          stroke="var(--ardor-rail-rule)"
          strokeWidth="0.35"
          vectorEffect="non-scaling-stroke"
        />
        <path
          d={path}
          pathLength={1}
          fill="none"
          stroke="var(--ardor-rail-accent)"
          strokeWidth="1.15"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
          className={play ? "ardor-thread-draw" : undefined}
          style={{ animationDuration: `${duration}s` }}
        />
      </svg>

      {nodes.map((node) => (
        <div
          key={node.label + node.value}
          className={cn(
            "absolute -translate-y-1/2",
            node.side === "left" ? "-translate-x-full pr-4 text-right" : "pl-4",
            play ? "ardor-thread-node" : "",
          )}
          style={{
            left: `${node.x}%`,
            top: `${node.y}%`,
            animationDelay: `${(node.at * duration + 0.15).toFixed(2)}s`,
          }}
        >
          <p
            className={cn(
              "t-eyebrow whitespace-nowrap",
              node.terminal ? "text-[var(--ardor-rail-accent)]" : "text-[var(--ardor-rail-ink-3)]",
            )}
          >
            {node.label}
          </p>
          <p
            className={cn(
              "whitespace-nowrap",
              node.terminal
                ? "t-title-3 text-[var(--ardor-rail-ink)]"
                : "t-body text-[var(--ardor-rail-ink)]",
            )}
          >
            {node.value}
          </p>
        </div>
      ))}

      {/* The point the thread has reached. */}
      {nodes.map((node) => (
        <span
          key={`dot-${node.label}`}
          aria-hidden
          className={cn("absolute size-1.5 -translate-x-1/2 -translate-y-1/2 rounded-full", play && "ardor-thread-node")}
          style={{
            left: `${node.x}%`,
            top: `${node.y}%`,
            backgroundColor: "var(--ardor-rail-accent)",
            animationDelay: `${(node.at * duration).toFixed(2)}s`,
          }}
        />
      ))}
    </div>
  );
}
