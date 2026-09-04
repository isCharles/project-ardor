"use client";

import * as React from "react";

import { useOnScreen } from "@/components/ardor/reveal";
import { cn } from "@/lib/utils";

/* The scroll story.

   One thread, continuing. The scenes are not four features stacked up —
   they are the same line further along, and each one only exists because
   the previous one happened: it read you, so it can prepare you; it
   listened, so it knows what you missed; it knows what you missed, so
   tomorrow is already scheduled.

   The spine is a single element spanning the whole story, filled by scroll
   position. Everything else keys off whether its scene is on screen. */

export function ThreadSpine({ children }: { children: React.ReactNode }) {
  const host = React.useRef<HTMLDivElement>(null);
  const fill = React.useRef<HTMLSpanElement>(null);

  React.useEffect(() => {
    let frame = 0;
    const update = () => {
      frame = 0;
      const element = host.current;
      const bar = fill.current;
      if (!element || !bar) return;
      const box = element.getBoundingClientRect();
      // How far the viewport's middle has travelled through the story.
      const travelled = window.innerHeight * 0.5 - box.top;
      const progress = Math.max(0, Math.min(1, travelled / box.height));
      bar.style.transform = `scaleY(${progress})`;
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
    <div ref={host} className="relative">
      {/* The thread itself, continuing from the hero. */}
      <div
        aria-hidden
        className="absolute bottom-0 left-5 top-0 hidden w-px bg-[var(--ardor-rail-rule)] md:left-10 md:block"
      >
        <span
          ref={fill}
          className="block h-full w-full origin-top bg-[var(--ardor-rail-accent)]"
          style={{ transform: "scaleY(0)" }}
        />
      </div>
      {children}
    </div>
  );
}

function Scene({
  eyebrow,
  title,
  lift = false,
  children,
}: {
  eyebrow: string;
  title: React.ReactNode;
  /** An explicit contrast scene; authenticated workspaces stay light. */
  lift?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section className={cn("relative", lift && "bg-[#1b1917]")}>
      <div className="mx-auto max-w-[82rem] px-5 py-28 md:px-10 md:py-40">
        <div className="md:pl-14">
          <p className="t-eyebrow text-[var(--ardor-rail-accent)]">{eyebrow}</p>
          <h2 className="t-display mt-5 text-[var(--ardor-rail-ink)]">{title}</h2>
          <div className="mt-14">{children}</div>
        </div>
      </div>
    </section>
  );
}

/** A node on the thread: mono when it happened, then what it is. */
function Entry({
  when,
  label,
  value,
  shown,
  index,
  strong = false,
}: {
  when: string;
  label: string;
  value: string;
  shown: boolean;
  index: number;
  strong?: boolean;
}) {
  return (
    <li
      className={cn(
        "flex flex-wrap items-baseline gap-x-5 gap-y-1 py-3.5 transition-all duration-700 ease-[var(--ease-ardor)]",
        shown ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0",
      )}
      style={{ transitionDelay: `${index * 130}ms` }}
    >
      <span className="t-meta w-20 shrink-0 text-[var(--ardor-rail-ink-3)]">{when}</span>
      <span className="t-eyebrow w-28 shrink-0 text-[var(--ardor-rail-ink-3)]">{label}</span>
      <span
        className={cn(
          "min-w-0",
          strong
            ? "t-title-3 text-[var(--ardor-rail-accent)]"
            : "t-body-lg text-[var(--ardor-rail-ink)]",
        )}
      >
        {value}
      </span>
    </li>
  );
}

/* ---------------------------------------------------------------
   It remembers.
   --------------------------------------------------------------- */

const MEMORY = [
  { when: "3 个月前", label: "Resume", value: "Java 后端 · 3 年" },
  { when: "2 个月前", label: "Target", value: "字节跳动" },
  { when: "上周", label: "Interview", value: "9 月 2 日 · 一面" },
  { when: "昨天", label: "Weakness", value: "Redis 持久化", strong: true },
  { when: "一直", label: "Preference", value: "后端 / AI 应用" },
];

export function SceneRemembers() {
  const [ref, onScreen] = useOnScreen<HTMLDivElement>();
  return (
    <Scene eyebrow="It remembers" title="它记得。">
      <div ref={ref}>
        <ul className="max-w-[42rem] divide-y divide-[var(--ardor-rail-rule)]">
          {MEMORY.map((item, i) => (
            <Entry key={item.label} {...item} shown={onScreen} index={i} />
          ))}
        </ul>
      </div>
    </Scene>
  );
}

/* ---------------------------------------------------------------
   It prepares.
   --------------------------------------------------------------- */

const PLAN = ["Redis 持久化", "MySQL MVCC", "Agent 架构", "简历项目深挖"];

export function ScenePrepares() {
  const [ref, onScreen] = useOnScreen<HTMLDivElement>();
  const [beat, setBeat] = React.useState(0);

  React.useEffect(() => {
    if (!onScreen) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      const settle = window.setTimeout(() => setBeat(PLAN.length + 1), 0);
      return () => window.clearTimeout(settle);
    }
    if (beat > PLAN.length) return;
    const timer = window.setTimeout(() => setBeat((n) => n + 1), beat === 0 ? 500 : 460);
    return () => window.clearTimeout(timer);
  }, [onScreen, beat]);

  return (
    <Scene eyebrow="It prepares" title="然后开始推。">
      <div ref={ref} className="grid gap-12 lg:grid-cols-[minmax(0,0.85fr)_minmax(0,1fr)] lg:gap-20">
        <div>
          <p className="t-eyebrow text-[var(--ardor-rail-ink-3)]">Interview</p>
          <p className="t-title-1 mt-3 text-[var(--ardor-rail-ink)]">字节跳动</p>
          <p className="t-title-3 mt-1 text-[var(--ardor-rail-ink-2)]">周五 14:00</p>
          <p className="t-meta mt-6 text-[var(--ardor-rail-accent)]">还有 4 天</p>
        </div>

        <div>
          <p className="t-eyebrow text-[var(--ardor-rail-ink-3)]">Preparation plan</p>
          <ol className="mt-4 divide-y divide-[var(--ardor-rail-rule)]">
            {PLAN.map((item, i) => {
              const shown = beat > i;
              const lit = i === 0 && beat > PLAN.length;
              return (
                <li
                  key={item}
                  className={cn(
                    "flex items-baseline gap-4 py-3 transition-all duration-500 ease-[var(--ease-ardor)]",
                    shown ? "translate-y-0 opacity-100" : "translate-y-2 opacity-0",
                  )}
                >
                  <span
                    className={cn(
                      "ardor-index w-5 shrink-0 transition-colors duration-500",
                      lit ? "text-[var(--ardor-rail-accent)]" : "text-[var(--ardor-rail-ink-3)]",
                    )}
                  >
                    {String(i + 1).padStart(2, "0")}
                  </span>
                  <span
                    className={cn(
                      "t-body-lg min-w-0 flex-1 transition-colors duration-500",
                      lit ? "text-[var(--ardor-rail-ink)]" : "text-[var(--ardor-rail-ink-2)]",
                    )}
                  >
                    {item}
                  </span>
                  <span
                    className={cn(
                      "t-meta shrink-0 text-[var(--ardor-rail-accent)] transition-opacity duration-500",
                      lit ? "opacity-100" : "opacity-0",
                    )}
                  >
                    20 min · 今晚
                  </span>
                </li>
              );
            })}
          </ol>
        </div>
      </div>
    </Scene>
  );
}

/* ---------------------------------------------------------------
   It practices. The light changes here: you are inside the interview.
   --------------------------------------------------------------- */

const NOTES = [
  { good: true, text: "I/O 多路复用" },
  { good: false, text: "内存访问" },
  { good: false, text: "命令执行模型" },
];

/* A quiet waveform. It exists to show that something is being heard — not
   to visualise music — so it is a fixed shape rather than a random one. */
const WAVE = [
  14, 26, 44, 62, 38, 78, 55, 92, 70, 48, 84, 61, 96, 72, 50, 66, 40, 58, 34, 46,
  28, 52, 74, 45, 88, 60, 36, 54, 30, 42, 24, 36, 20, 30, 16, 26, 13, 20, 10, 15,
];

export function ScenePractices() {
  const [ref, onScreen] = useOnScreen<HTMLDivElement>();
  const [beat, setBeat] = React.useState(0);

  React.useEffect(() => {
    if (!onScreen) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      const settle = window.setTimeout(() => setBeat(4), 0);
      return () => window.clearTimeout(settle);
    }
    if (beat >= 4) return;
    const timer = window.setTimeout(() => setBeat((n) => n + 1), beat === 0 ? 900 : 1100);
    return () => window.clearTimeout(timer);
  }, [onScreen, beat]);

  return (
    <Scene eyebrow="It practices" title="它在听你讲。" lift>
      <div ref={ref} className="grid gap-14 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,0.7fr)] lg:gap-20">
        <div>
          <p className="t-title-1 max-w-[20ch] text-[var(--ardor-rail-ink)]">
            Redis 为什么吞吐量这么高？
          </p>

          <div className="mt-10 flex h-20 max-w-[34rem] items-center gap-[3px]" aria-hidden>
            {WAVE.map((height, i) => (
              <span
                key={i}
                className={cn(
                  "min-w-[2px] flex-1 rounded-full transition-all duration-[900ms] ease-[var(--ease-ardor)]",
                  beat >= 1 ? "bg-[var(--ardor-rail-accent)]" : "bg-[var(--ardor-rail-ink-3)]",
                )}
                style={{
                  height: beat >= 1 ? `${height}%` : "4%",
                  transitionDelay: `${i * 26}ms`,
                  opacity: beat >= 1 ? 0.45 + (height / 100) * 0.55 : 0.4,
                }}
              />
            ))}
          </div>

          <div
            className={cn(
              "mt-10 transition-all duration-700 ease-[var(--ease-ardor)]",
              beat >= 2 ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0",
            )}
          >
            <p className="t-eyebrow text-[var(--ardor-rail-ink-3)]">Follow-up</p>
            <p className="t-title-3 mt-2.5 text-[var(--ardor-rail-ink)]">
              单线程为什么没有成为瓶颈？
            </p>
          </div>
        </div>

        <div
          className={cn(
            "transition-all duration-700 ease-[var(--ease-ardor)]",
            beat >= 3 ? "translate-y-0 opacity-100" : "translate-y-3 opacity-0",
          )}
        >
          <p className="t-eyebrow text-[var(--ardor-rail-ink-3)]">Live notes</p>
          <ul className="mt-4 space-y-2.5">
            {NOTES.map((note) => (
              <li key={note.text} className="flex items-baseline gap-3">
                <span
                  className={cn(
                    "ardor-index shrink-0",
                    note.good ? "text-[var(--ardor-rail-ink-2)]" : "text-[var(--ardor-rail-accent)]",
                  )}
                >
                  {note.good ? "+" : "−"}
                </span>
                <span
                  className={cn(
                    "t-body",
                    note.good ? "text-[var(--ardor-rail-ink-2)]" : "text-[var(--ardor-rail-ink)]",
                  )}
                >
                  {note.text}
                </span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </Scene>
  );
}

/* ---------------------------------------------------------------
   It learns from you. The missed answer leaves the interview and
   becomes tomorrow.
   --------------------------------------------------------------- */

export function SceneLearns() {
  const [ref, onScreen] = useOnScreen<HTMLDivElement>();
  const [moved, setMoved] = React.useState(false);

  React.useEffect(() => {
    if (!onScreen) return;
    const timer = window.setTimeout(() => setMoved(true), 700);
    return () => window.clearTimeout(timer);
  }, [onScreen]);

  return (
    <Scene eyebrow="It learns from you" title="明天，它不会重新认识你。">
      <div ref={ref} className="max-w-[46rem]">
        <div className="flex flex-col gap-3">
          {/* The answer that did not hold, leaving where it came from. */}
          <div
            className={cn(
              "transition-all duration-[900ms] ease-[var(--ease-ardor)]",
              moved ? "translate-x-6 opacity-25 md:translate-x-12" : "translate-x-0 opacity-100",
            )}
          >
            <p className="t-eyebrow text-[var(--ardor-rail-ink-3)]">Missed</p>
            <p className="t-title-3 mt-1.5 text-[var(--ardor-rail-ink-2)]">Redis 持久化</p>
          </div>

          {/* …and arriving as tomorrow's work. */}
          <div
            className={cn(
              "border-l-2 border-[var(--ardor-rail-accent)] pl-5 transition-all duration-[900ms] ease-[var(--ease-ardor)]",
              moved ? "translate-y-0 opacity-100" : "translate-y-6 opacity-0",
            )}
            style={{ transitionDelay: "260ms" }}
          >
            <p className="t-eyebrow text-[var(--ardor-rail-accent)]">Review</p>
            <p className="t-title-1 mt-2 text-[var(--ardor-rail-ink)]">Redis 持久化</p>
            <p className="t-body mt-1.5 text-[var(--ardor-rail-ink-2)]">明天 · 20 min</p>
          </div>
        </div>
      </div>
    </Scene>
  );
}
