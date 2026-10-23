"use client";

import { Activity, ArrowLeft, Bot, CheckCircle2, CircleAlert, Clock3, LoaderCircle, Wrench } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";

import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type TraceSpan = {
  id: string;
  runId: string;
  parentSpanId: string | null;
  kind: "RUN" | "LLM" | "TOOL" | "CONTEXT" | "RETRY" | "ASYNC";
  name: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | "NOT_EXECUTED" | "UNKNOWN";
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  model: string | null;
  promptVersion: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  errorCode: string | null;
  attributes: Record<string, unknown>;
};

type Trace = {
  runId: string;
  status: string;
  durationMs: number;
  totals: { llmCalls: number; toolCalls: number; loopRounds: number; inputTokens: number; outputTokens: number };
  spans: TraceSpan[];
};

export default function AgentRunTracePage() {
  const params = useParams<{ requestId: string }>();
  const requestId = typeof params.requestId === "string" ? params.requestId : params.requestId?.[0] ?? "";
  const { t } = useLocale();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!requestId) return;
    let active = true;
    void api<Trace>(`/api/agent/runs/${requestId}/trace`).then((value) => {
      if (active) setTrace(value);
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : t("Could not load trace", "无法读取执行轨迹"));
    });
    return () => { active = false; };
  }, [requestId, t]);

  return <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-5xl">
      <div className="flex items-center justify-between gap-3">
        <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("Back to chat", "返回对话")}</Link>
        <span className="rounded-full bg-violet-50 px-3 py-1 text-[11px] font-medium text-violet-700">{t("Developer trace", "开发者视图")}</span>
      </div>
      <header className="pb-7 pt-8">
        <div className="flex items-center gap-3"><Activity className="size-5 text-violet-600" /><h1 className="text-3xl font-semibold tracking-tight md:text-4xl">{t("Agent run trace", "Agent 执行轨迹")}</h1></div>
        <p className="mt-3 truncate font-mono text-xs text-stone-400">{requestId}</p>
      </header>

      {!trace && !error && <div role="status" className="flex items-center gap-3 rounded-2xl bg-white/70 px-4 py-3 text-sm text-stone-500"><LoaderCircle className="size-4 animate-spin text-violet-500" />{t("Loading trace…", "正在读取执行轨迹…")}</div>}
      {error && <div role="alert" className="rounded-2xl bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {trace && <>
        <section className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            [t("Model calls", "模型调用"), trace.totals.llmCalls],
            [t("Tool calls", "工具调用"), trace.totals.toolCalls],
            [t("Loop rounds", "循环轮次"), trace.totals.loopRounds],
            [t("Duration", "总耗时"), formatDuration(trace.durationMs)],
          ].map(([label, value]) => <div key={String(label)} className="rounded-2xl border border-white/80 bg-white/70 px-4 py-3"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{label}</p><p className="mt-1 text-lg font-semibold tabular-nums">{value}</p></div>)}
        </section>
        <div className="mt-3 flex flex-wrap gap-2 text-xs text-stone-500">
          <span className="rounded-full bg-white/70 px-3 py-1">{t("Input tokens", "输入 Token")}: <b className="font-medium text-stone-700">{trace.totals.inputTokens || "—"}</b></span>
          <span className="rounded-full bg-white/70 px-3 py-1">{t("Output tokens", "输出 Token")}: <b className="font-medium text-stone-700">{trace.totals.outputTokens || "—"}</b></span>
          <span className={`rounded-full px-3 py-1 font-medium ${trace.status === "COMPLETED" ? "bg-emerald-50 text-emerald-700" : trace.status === "FAILED" ? "bg-rose-50 text-rose-700" : "bg-amber-50 text-amber-700"}`}>{trace.status}</span>
        </div>

        <section className="mt-6">
          {trace.spans.filter((span) => span.kind === "RUN").map((span) => <TraceRow key={span.id} span={span} root />)}
          <div className="relative ml-5 mt-2 space-y-2 border-l border-stone-200/80 pl-5">
            {trace.spans.filter((span) => span.kind !== "RUN").map((span) => <TraceRow key={span.id} span={span} />)}
          </div>
          {trace.spans.length === 0 && <p className="text-sm text-stone-500">{t("No spans were recorded for this run.", "这次运行没有记录到步骤。")}</p>}
        </section>
      </>}
    </div>
  </main>;
}

function TraceRow({ span, root = false }: { span: TraceSpan; root?: boolean }) {
  const { t } = useLocale();
  const Icon = span.kind === "LLM" ? Bot : span.kind === "TOOL" ? Wrench : span.kind === "CONTEXT" ? Clock3 : Activity;
  const ok = span.status === "COMPLETED";
  return <div className={`relative flex gap-3 rounded-2xl ${root ? "border border-violet-100 bg-white/80 px-4 py-4" : "py-2"}`}>
    {!root && <span className="absolute -left-[1.95rem] top-4 grid size-5 place-items-center rounded-full bg-[#fbfaf8] ring-1 ring-stone-200"><Icon className="size-3 text-stone-500" /></span>}
    <div className={`${root ? "grid size-9 shrink-0 place-items-center rounded-xl bg-violet-50" : "hidden"}`}><Icon className="size-4 text-violet-600" /></div>
    <div className="min-w-0 flex-1">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
        <span className="text-sm font-medium text-stone-800">{root ? t("Agent run", "Agent 运行") : span.name}</span>
        {span.model && <span className="font-mono text-[11px] text-stone-400">{span.model}</span>}
        {ok ? <CheckCircle2 className="size-3.5 text-emerald-500" /> : <CircleAlert className={`size-3.5 ${span.status === "RUNNING" ? "text-violet-500" : "text-rose-500"}`} />}
        <span className="ml-auto text-[11px] tabular-nums text-stone-400">{span.durationMs == null ? "—" : formatDuration(span.durationMs)}</span>
      </div>
      <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-stone-500">
        <span>{span.kind}</span>
        {span.inputTokens != null && <span>{t("In", "输入")} {span.inputTokens}</span>}
        {span.outputTokens != null && <span>{t("Out", "输出")} {span.outputTokens}</span>}
        {span.promptVersion && <span>{span.promptVersion}</span>}
        {span.errorCode && <span className="text-rose-600">{span.errorCode}</span>}
        {span.attributes.toolRound === true && <span className="text-violet-600">{t("Tool round", "工具轮次")}</span>}
      </div>
    </div>
  </div>;
}

function formatDuration(value: number) {
  if (!value || value <= 0) return "—";
  return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(1)} s`;
}
