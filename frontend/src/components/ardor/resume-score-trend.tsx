"use client";

import { useId } from "react";

import { useLocale } from "@/lib/locale";

export type ResumeScoreTrendPoint = {
  id: string;
  name: string;
  score: number;
  createdAt: string;
};

const WIDTH = 720;
const HEIGHT = 220;
const PLOT = { left: 48, right: 22, top: 28, bottom: 42 };

export function ResumeScoreTrend({ points }: { points: ResumeScoreTrendPoint[] }) {
  const { locale, t } = useLocale();
  const gradientId = useId();
  const ordered = [...points]
    .filter((point) => Number.isFinite(point.score))
    .sort((left, right) => left.createdAt.localeCompare(right.createdAt));
  if (ordered.length < 2) return null;

  const scores = ordered.map((point) => point.score);
  const lowest = Math.min(...scores);
  const highest = Math.max(...scores);
  const padding = Math.max(5, Math.max(8, highest - lowest) * 0.35);
  let min = Math.max(0, Math.floor((lowest - padding) / 10) * 10);
  let max = Math.min(100, Math.ceil((highest + padding) / 10) * 10);
  if (max - min < 20) {
    if (min === 0) max = Math.min(100, 20);
    else min = Math.max(0, max - 20);
    if (max - min < 20) max = Math.min(100, min + 20);
  }
  const domain = max - min;
  const plotWidth = WIDTH - PLOT.left - PLOT.right;
  const plotHeight = HEIGHT - PLOT.top - PLOT.bottom;
  const baseline = HEIGHT - PLOT.bottom;
  const coordinates = ordered.map((point, index) => ({
    x: PLOT.left + (ordered.length === 1 ? plotWidth / 2 : (index / (ordered.length - 1)) * plotWidth),
    y: PLOT.top + ((max - Math.max(min, Math.min(max, point.score))) / domain) * plotHeight,
  }));
  const line = smoothPath(coordinates);
  const area = `${line} L ${coordinates[coordinates.length - 1].x} ${baseline} L ${coordinates[0].x} ${baseline} Z`;
  const latest = ordered[ordered.length - 1];
  const first = ordered[0];
  const delta = Math.round(latest.score - first.score);
  const mid = min + domain / 2;
  const dateLabel = (value: string) => new Intl.DateTimeFormat(locale === "en" ? "en-US" : "zh-CN", { month: "numeric", day: "numeric" }).format(new Date(value));
  const showEachLabel = ordered.length <= 6;
  const lastIndex = ordered.length - 1;
  const deltaTone = delta > 0 ? "bg-emerald-50 text-emerald-700" : delta < 0 ? "bg-rose-50 text-rose-700" : "bg-stone-100 text-stone-500";

  return <section aria-label={t("Resume score trend", "简历分数趋势")}
    className="relative overflow-hidden rounded-[1.75rem] border border-stone-200/70 bg-white/80 p-5 shadow-[0_18px_60px_rgba(49,39,70,0.05)] md:p-6">
    <div className="pointer-events-none absolute -right-14 -top-16 size-48 rounded-full bg-violet-100/40 blur-2xl" aria-hidden="true" />
    <div className="relative flex flex-wrap items-end justify-between gap-4">
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-[0.18em] text-violet-600">SCORE TREND</p>
        <h3 className="mt-1.5 text-sm font-medium text-stone-600">{t("Score across versions", "各版本分数变化")}</h3>
      </div>
      <div className="flex items-end gap-3">
        <p className="text-3xl font-semibold leading-none tracking-[-0.04em] tabular-nums text-stone-900">{Math.round(latest.score)}</p>
        <span className={`mb-0.5 rounded-full px-2.5 py-1 text-[11px] font-medium tabular-nums ${deltaTone}`}>
          {delta > 0 ? "+" : ""}{delta} {t("vs first", "较首版")}
        </span>
      </div>
    </div>

    <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} role="img" aria-label={t("Scores across resume versions, from first to latest", "从首版到最新版本的简历分数变化")}
      className="relative mt-2 h-auto w-full">
      <defs>
        <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor="#7c3aed" stopOpacity="0.22" />
          <stop offset="100%" stopColor="#7c3aed" stopOpacity="0" />
        </linearGradient>
      </defs>

      {[max, mid, min].map((value, index) => {
        const y = PLOT.top + (index * plotHeight) / 2;
        return <g key={value}>
          <line x1={PLOT.left} x2={WIDTH - PLOT.right} y1={y} y2={y} stroke="rgba(41,37,36,0.08)" strokeDasharray={index === 2 ? undefined : "3 7"} />
          <text x={PLOT.left - 10} y={y + 3.5} textAnchor="end" fill="#a8a29e" style={{ fontSize: 10, fontVariantNumeric: "tabular-nums" }}>{value}</text>
        </g>;
      })}

      <path d={area} fill={`url(#${gradientId})`} />
      <path d={line} fill="none" stroke="#7c3aed" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />

      {coordinates.map((coordinate, index) => {
        const point = ordered[index];
        const isLatest = index === lastIndex;
        return <g key={point.id} tabIndex={0} role="img" aria-label={`${point.name} · ${Math.round(point.score)}`} className="outline-none">
          <title>{`${point.name} · ${Math.round(point.score)} · ${dateLabel(point.createdAt)}`}</title>
          {isLatest && <circle cx={coordinate.x} cy={coordinate.y} r="12" fill="rgba(124,58,237,0.12)" />}
          <circle cx={coordinate.x} cy={coordinate.y} r={isLatest ? 6 : 4}
            fill={isLatest ? "#7c3aed" : "#ffffff"} stroke="#7c3aed" strokeWidth={isLatest ? 2.5 : 2} />
        </g>;
      })}

      {coordinates.map((coordinate, index) => {
        const point = ordered[index];
        if (!showEachLabel && index !== 0 && index !== lastIndex) return null;
        return <text key={point.id} x={coordinate.x} y={HEIGHT - 14} textAnchor={index === 0 ? "start" : index === lastIndex ? "end" : "middle"}
          fill={index === lastIndex ? "#57534e" : "#a8a29e"} style={{ fontSize: 10, fontVariantNumeric: "tabular-nums" }}>
          {dateLabel(point.createdAt)}
        </text>;
      })}
    </svg>
  </section>;
}

function smoothPath(points: { x: number; y: number }[]) {
  if (points.length === 0) return "";
  let path = `M ${points[0].x} ${points[0].y}`;
  for (let index = 1; index < points.length; index += 1) {
    const previous = points[index - 1];
    const current = points[index];
    const middle = (previous.x + current.x) / 2;
    path += ` C ${middle} ${previous.y}, ${middle} ${current.y}, ${current.x} ${current.y}`;
  }
  return path;
}
