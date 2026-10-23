"use client";

import { Activity, ArrowLeft, Bot, CheckCircle2, CircleAlert, Clock3, GitBranch, List, LoaderCircle, Wrench } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type TraceSpan = {
  id: string;
  runId: string;
  parentSpanId: string | null;
  kind: "RUN" | "LLM" | "TOOL" | "CONTEXT" | "THOUGHT" | "RETRY" | "ASYNC";
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

type FlowNode = { span: TraceSpan; x: number; y: number };

const NODE_WIDTH = 160;
const NODE_HEIGHT = 88;
const NODE_GAP = 28;
const START_X = 8;
const START_Y = 18;

const kindMeta: Record<TraceSpan["kind"], { label: string; color: string; icon: typeof Bot }> = {
  RUN: { label: "RUN", color: "bg-[#1d1d1f] text-white", icon: Activity },
  LLM: { label: "LLM", color: "bg-violet-100 text-violet-700", icon: Bot },
  TOOL: { label: "TOOL", color: "bg-sky-100 text-sky-700", icon: Wrench },
  CONTEXT: { label: "CONTEXT", color: "bg-stone-200 text-stone-700", icon: Clock3 },
  THOUGHT: { label: "THOUGHT", color: "bg-violet-50 text-violet-700", icon: Activity },
  RETRY: { label: "RETRY", color: "bg-amber-100 text-amber-800", icon: CircleAlert },
  ASYNC: { label: "ASYNC", color: "bg-emerald-100 text-emerald-700", icon: Activity },
};

export default function AgentRunTracePage() {
  const params = useParams<{ requestId: string }>();
  const requestId = typeof params.requestId === "string" ? params.requestId : params.requestId?.[0] ?? "";
  const { t } = useLocale();
  const [trace, setTrace] = useState<Trace | null>(null);
  const [error, setError] = useState("");
  const [view, setView] = useState<"flow" | "timeline">("flow");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  useEffect(() => {
    if (!requestId) return;
    let active = true;
    let timer: number | undefined;
    const load = async () => {
      try {
        const value = await api<Trace>(`/api/agent/runs/${requestId}/trace`);
        if (!active) return;
        setTrace(value);
        setError("");
        if (value.status === "RUNNING") timer = window.setTimeout(() => void load(), 2000);
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : t("Could not load trace", "无法读取执行轨迹"));
      }
    };
    void load();
    return () => { active = false; if (timer) window.clearTimeout(timer); };
  }, [requestId, t]);

  const flow = useMemo(() => buildFlow(trace?.spans ?? []), [trace]);
  const selected = trace?.spans.find((span) => span.id === selectedId) ?? null;

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

        <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2 text-sm font-medium text-stone-700">
            <GitBranch className="size-4 text-violet-600" />
            {t("Execution flow", "执行流转")}
            <span className="text-xs font-normal text-stone-400">{flow.length} {t("steps", "个步骤")}</span>
          </div>
          <div className="inline-flex rounded-full border border-stone-200 bg-white/65 p-1" role="group" aria-label={t("Trace view", "轨迹视图")}>
            <button type="button" aria-pressed={view === "flow"} onClick={() => setView("flow")} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs transition ${view === "flow" ? "bg-[#1d1d1f] text-white" : "text-stone-500 hover:text-stone-800"}`}><GitBranch className="size-3.5" />{t("Flow", "流程图")}</button>
            <button type="button" aria-pressed={view === "timeline"} onClick={() => setView("timeline")} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs transition ${view === "timeline" ? "bg-[#1d1d1f] text-white" : "text-stone-500 hover:text-stone-800"}`}><List className="size-3.5" />{t("Timeline", "时间线")}</button>
          </div>
        </div>

        {view === "flow"
          ? <section className="mt-3">
              <FlowGraph nodes={flow} selectedId={selectedId} onSelect={setSelectedId} />
              <div className="mt-3 flex flex-wrap gap-3 text-[10px] text-stone-400">
                {(["LLM", "TOOL", "CONTEXT", "ASYNC"] as const).map((kind) => <span key={kind} className="inline-flex items-center gap-1.5"><span className={`size-2 rounded-full ${kind === "LLM" ? "bg-violet-400" : kind === "TOOL" ? "bg-sky-400" : kind === "CONTEXT" ? "bg-stone-400" : "bg-emerald-400"}`} />{kindMeta[kind].label}</span>)}
              </div>
              {selected ? <SpanDetails span={selected} /> : <p className="mt-3 text-xs text-stone-400">{t("Select a node to inspect metadata.", "点击节点查看元数据。")}</p>}
            </section>
          : <section className="mt-3">{trace.spans.filter((span) => span.kind === "RUN").map((span) => <TraceRow key={span.id} span={span} root />)}<div className="relative ml-5 mt-2 space-y-2 border-l border-stone-200/80 pl-5">{trace.spans.filter((span) => span.kind !== "RUN").map((span) => <TraceRow key={span.id} span={span} />)}</div>{trace.spans.length === 0 && <p className="text-sm text-stone-500">{t("No spans were recorded for this run.", "这次运行没有记录到步骤。")}</p>}</section>}
      </>}
    </div>
  </main>;
}

function buildFlow(spans: TraceSpan[]): FlowNode[] {
  const root = spans.find((span) => span.kind === "RUN");
  const children = spans
    .filter((span) => span.kind !== "RUN")
    .sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime());
  const ordered = root ? [root, ...children] : children;
  if (ordered.length === 0) return [];
  const perRow = children.length > 4 ? 5 : Math.max(1, children.length);
  const firstChildY = START_Y + NODE_HEIGHT + 42;
  return ordered.map((span, index) => {
    if (index === 0) return { span, x: START_X, y: START_Y };
    const childIndex = index - 1;
    const row = Math.floor(childIndex / perRow);
    const column = childIndex % perRow;
    const visualColumn = row % 2 === 0 ? column : perRow - 1 - column;
    return {
      span,
      x: START_X + visualColumn * (NODE_WIDTH + NODE_GAP),
      y: firstChildY + row * (NODE_HEIGHT + 42),
    };
  });
}

function FlowGraph({ nodes, selectedId, onSelect }: { nodes: FlowNode[]; selectedId: string | null; onSelect: (id: string) => void }) {
  const { t } = useLocale();
  if (nodes.length === 0) return <div className="rounded-2xl bg-white/60 p-5 text-sm text-stone-500">{t("No spans were recorded for this run.", "这次运行没有记录到步骤。")}</div>;
  const width = Math.max(720, ...nodes.map((node) => node.x + NODE_WIDTH + 24));
  const height = Math.max(300, ...nodes.map((node) => node.y + NODE_HEIGHT + 24));
  return <div className="overflow-x-auto rounded-[2rem] border border-white/70 bg-white/55 p-4 shadow-[0_14px_40px_rgba(49,39,70,0.04)]">
    <div className="relative" style={{ width, height }}>
      <svg className="pointer-events-none absolute inset-0" width={width} height={height} aria-hidden="true">
        <defs><marker id="trace-arrow" markerWidth="6" markerHeight="6" refX="5" refY="3" orient="auto"><path d="M0,0 L6,3 L0,6 Z" fill="#c4b5fd" /></marker></defs>
        {nodes.slice(1).map((node, index) => {
          const from = nodes[index];
          const vertical = Math.abs(node.x - from.x) < 1;
          const x1 = vertical ? from.x + NODE_WIDTH / 2 : node.x > from.x ? from.x + NODE_WIDTH : from.x;
          const y1 = vertical ? from.y + NODE_HEIGHT : from.y + NODE_HEIGHT / 2;
          const x2 = vertical ? node.x + NODE_WIDTH / 2 : node.x > from.x ? node.x : node.x + NODE_WIDTH;
          const y2 = vertical ? node.y : node.y + NODE_HEIGHT / 2;
          const mid = vertical ? (y1 + y2) / 2 : (x1 + x2) / 2;
          const failed = node.span.status === "FAILED";
          const path = vertical ? `M ${x1} ${y1} C ${x1} ${mid}, ${x2} ${mid}, ${x2} ${y2}` : `M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`;
          return <path key={`${from.span.id}-${node.span.id}`} d={path} fill="none" stroke={failed ? "#fb7185" : "#c4b5fd"} strokeWidth="1.5" markerEnd="url(#trace-arrow)" />;
        })}
      </svg>
      {nodes.map((node, index) => <TraceNode key={node.span.id} node={node} index={index} selected={selectedId === node.span.id} onSelect={onSelect} />)}
    </div>
  </div>;
}

function TraceNode({ node, index, selected, onSelect }: { node: FlowNode; index: number; selected: boolean; onSelect: (id: string) => void }) {
  const { t } = useLocale();
  const meta = kindMeta[node.span.kind];
  const Icon = meta.icon;
  const failed = node.span.status === "FAILED";
  const running = node.span.status === "RUNNING";
  return <button type="button" data-trace-node onClick={() => onSelect(node.span.id)} style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }} className={`absolute rounded-2xl border bg-white p-3 text-left shadow-sm transition hover:-translate-y-0.5 hover:shadow-md ${selected ? "border-violet-400 ring-2 ring-violet-200" : failed ? "border-rose-200" : "border-stone-200/80"}`} aria-label={`${meta.label} ${node.span.name}, ${formatDuration(node.span.durationMs)}`}>
    <div className="flex items-center justify-between gap-2"><span className={`inline-flex items-center gap-1 rounded-full px-1.5 py-0.5 text-[9px] font-semibold tracking-wide ${meta.color}`}><Icon className="size-2.5" />{meta.label}</span><span className="text-[9px] tabular-nums text-stone-400">{index === 0 ? t("start", "起点") : `#${index}`}</span></div>
    <p className="mt-1.5 truncate text-xs font-semibold text-stone-800">{node.span.kind === "RUN" ? t("Agent run", "Agent 运行") : node.span.name}</p>
    <div className="mt-1 flex items-center gap-2 text-[9px] text-stone-400"><span>{formatDuration(node.span.durationMs)}</span>{node.span.inputTokens != null && <span>{node.span.inputTokens} in</span>}{node.span.outputTokens != null && <span>{node.span.outputTokens} out</span>}{failed ? <CircleAlert className="size-3 text-rose-500" /> : running ? <LoaderCircle className="size-3 animate-spin text-violet-500" /> : <CheckCircle2 className="size-3 text-emerald-500" />}</div>
  </button>;
}

function SpanDetails({ span }: { span: TraceSpan }) {
  const { t } = useLocale();
  const attributes = span.attributes;
  const inputPreview = preview(attributes.inputPreview);
  const outputPreview = preview(attributes.outputPreview);
  const argumentsPreview = preview(attributes.argumentsPreview);
  const resultPreview = preview(attributes.resultPreview);
  const requestedTools = Array.isArray(attributes.requestedTools)
    ? attributes.requestedTools.filter((item): item is string => typeof item === "string")
    : [];
  return <div data-span-details className="mt-3 rounded-2xl border border-stone-200/80 bg-white/80 p-5">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{span.kind}</p>
        <h2 className="mt-1 truncate text-lg font-semibold">{span.name}</h2>
        <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-stone-500">
          <span className={`rounded-full px-2 py-0.5 ${span.status === "COMPLETED" ? "bg-emerald-50 text-emerald-700" : "bg-rose-50 text-rose-700"}`}>{span.status}</span>
          <span>{formatDuration(span.durationMs)}</span>
          {span.model && <span>{span.model}</span>}
          {span.promptVersion && <span>{span.promptVersion}</span>}
        </div>
      </div>
      <div className="grid grid-cols-2 gap-x-5 gap-y-1 text-[11px] text-stone-500">
        <span>{t("Input tokens", "输入 Token")} <b className="font-medium text-stone-800">{span.inputTokens ?? "—"}</b></span>
        <span>{t("Output tokens", "输出 Token")} <b className="font-medium text-stone-800">{span.outputTokens ?? "—"}</b></span>
        <span className="col-span-2 truncate font-mono text-[10px] text-stone-400">{span.id}</span>
      </div>
    </div>

    <div className="mt-4 grid gap-3 md:grid-cols-2">
      {inputPreview && <PreviewBlock label={t("Input preview", "输入预览")} text={inputPreview} />}
      {outputPreview && <PreviewBlock label={span.kind === "THOUGHT" ? t("Phase conclusion", "阶段结论") : t("Output preview", "输出预览")} text={outputPreview} />}
      {argumentsPreview && <PreviewBlock label={t("Arguments preview", "参数预览")} text={argumentsPreview} />}
      {resultPreview && <PreviewBlock label={t("Result preview", "结果预览")} text={resultPreview} />}
    </div>

    {requestedTools.length > 0 && <div className="mt-3 flex flex-wrap items-center gap-2 text-[11px] text-stone-500"><span>{t("Requested tools", "请求的工具")}</span>{requestedTools.map((tool) => <span key={tool} className="rounded-full bg-violet-50 px-2 py-0.5 font-mono text-[10px] text-violet-700">{tool}</span>)}</div>}
    <p className="mt-3 text-[10px] leading-5 text-stone-400">{t("Preview content is truncated and redacted; it is not the full prompt, tool payload or private chain-of-thought.", "预览内容已截断并脱敏，不是完整 prompt、工具 payload 或私有思维链。")}</p>

    <details className="mt-3 text-[11px] text-stone-500">
      <summary className="cursor-pointer text-stone-500">{t("Metadata", "元数据")}</summary>
      <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-stone-50 p-3 text-[10px] leading-5 text-stone-600">{JSON.stringify(attributes, null, 2)}</pre>
    </details>
  </div>;
}

function preview(value: unknown): string {
  return typeof value === "string" && value.trim().length > 0 ? value : "";
}

function PreviewBlock({ label, text }: { label: string; text: string }) {
  return <div className="min-w-0 rounded-xl border border-stone-200/70 bg-stone-50/60 p-3">
    <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{label}</p>
    <pre className="mt-1.5 max-h-44 overflow-auto whitespace-pre-wrap break-words text-[11px] leading-5 text-stone-600">{text}</pre>
  </div>;
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

function formatDuration(value: number | null) {
  if (!value || value <= 0) return "—";
  return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(1)} s`;
}




