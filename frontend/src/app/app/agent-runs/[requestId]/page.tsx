"use client";

import { Activity, ArrowLeft, Bot, CheckCircle2, CircleAlert, Clock3, GitBranch, List, LoaderCircle, Search, Wrench } from "lucide-react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";

import { api } from "@/lib/api";
import { MarkdownContent } from "@/components/ardor/markdown-content";
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
  conversationId?: string | null;
  status: string;
  durationMs: number;
  totals: { llmCalls: number; toolCalls: number; loopRounds: number; inputTokens: number; outputTokens: number };
  spans: TraceSpan[];
};

type ConversationTrace = {
  conversationId: string;
  title: string;
  runs: Array<{ runId: string; message: string; assistantMessage: string; thinkingOutput: string; status: string; createdAt: string; updatedAt: string; spans: TraceSpan[] }>;
};

type ToolSchema = Record<string, unknown>;

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
  const [conversationTrace, setConversationTrace] = useState<ConversationTrace | null>(null);
  const [conversationId, setConversationId] = useState("");
  const [toolSchemas, setToolSchemas] = useState<Record<string, ToolSchema>>({});
  const [error, setError] = useState("");
  const [view, setView] = useState<"flow" | "timeline" | "inspector">("flow");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [filter, setFilter] = useState("");

  useEffect(() => {
    setConversationId(new URLSearchParams(window.location.search).get("conversation") ?? "");
    let active = true;
    void api<Record<string, ToolSchema>>("/api/agent/tools/schemas").then((value) => {
      if (active) setToolSchemas(value);
    }).catch(() => { /* Schema is optional in the developer view. */ });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!requestId || conversationId) return;
    let active = true;
    let timer: number | undefined;
    const load = async () => {
      try {
        const value = await api<Trace>(`/api/agent/runs/${requestId}/trace`);
        if (!active) return;
        setTrace(value);
        setSelectedId((current) => value.spans.some((span) => span.id === current) ? current : value.spans[0]?.id ?? null);
        setError("");
        if (value.status === "RUNNING") timer = window.setTimeout(() => void load(), 2000);
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : t("Could not load trace", "无法读取执行轨迹"));
      }
    };
    void load();
    return () => { active = false; if (timer) window.clearTimeout(timer); };
  }, [requestId, conversationId, t]);

  useEffect(() => {
    if (!conversationId) return;
    let active = true;
    let timer: number | undefined;
    const load = async () => {
      try {
        const value = await api<ConversationTrace>(`/api/agent/conversations/${encodeURIComponent(conversationId)}/trace`);
        if (!active) return;
        setConversationTrace(value);
        setError("");
        if (value.runs.some((run) => run.status === "RUNNING")) timer = window.setTimeout(() => void load(), 2000);
      } catch (reason) {
        if (active) setError(reason instanceof Error ? reason.message : t("Could not load conversation trace", "无法读取多轮轨迹"));
      }
    };
    void load();
    return () => { active = false; if (timer) window.clearTimeout(timer); };
  }, [conversationId, t]);

  const flow = useMemo(() => buildFlow(trace?.spans ?? []), [trace]);
  const selected = trace?.spans.find((span) => span.id === selectedId) ?? null;
  const filteredSpans = useMemo(() => {
    const query = filter.trim().toLowerCase();
    const spans = (trace?.spans ?? []).filter((span) => !query
      || span.name.toLowerCase().includes(query)
      || span.kind.toLowerCase().includes(query)
      || span.status.toLowerCase().includes(query));
    return [...spans].sort((left, right) => new Date(left.startedAt).getTime() - new Date(right.startedAt).getTime());
  }, [trace, filter]);

  return <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-[1600px]">
      <div className="flex items-center justify-between gap-3">
        <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("Back to chat", "返回对话")}</Link>
        <div className="flex items-center gap-2">{trace?.conversationId && <Link href={`/app/agent-runs/${requestId}?conversation=${trace.conversationId}`} className="rounded-full bg-white/80 px-3 py-1.5 text-[11px] text-stone-500 hover:text-violet-700">{t("Conversation trace", "多轮轨迹")}</Link>}<span className="rounded-full bg-violet-50 px-3 py-1 text-[11px] font-medium text-violet-700">{t("Developer trace", "开发者视图")}</span></div>
      </div>
      <header className="pb-7 pt-8">
        <div className="flex items-center gap-3"><Activity className="size-5 text-violet-600" /><h1 className="text-3xl font-semibold tracking-tight md:text-4xl">{conversationId ? t("Conversation trace", "多轮对话轨迹") : t("Agent run trace", "Agent 执行轨迹")}</h1></div>
        <p className="mt-3 truncate font-mono text-xs text-stone-400">{conversationId ? `${t("Conversation", "对话")} ${conversationId}` : requestId}</p>
      </header>

      {conversationTrace && <ConversationTraceView trace={conversationTrace} schemas={toolSchemas} />}
      {!conversationTrace && !trace && !error && <div role="status" className="flex items-center gap-3 rounded-2xl bg-white/70 px-4 py-3 text-sm text-stone-500"><LoaderCircle className="size-4 animate-spin text-violet-500" />{t("Loading trace…", "正在读取执行轨迹…")}</div>}
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
            <button type="button" aria-pressed={view === "inspector"} onClick={() => setView("inspector")} className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs transition ${view === "inspector" ? "bg-[#1d1d1f] text-white" : "text-stone-500 hover:text-stone-800"}`}><Search className="size-3.5" />{t("Trajectory", "轨迹检查器")}</button>
          </div>
        </div>

        {view === "flow"
          ? <section className="mt-3">
              <FlowGraph nodes={flow} selectedId={selectedId} onSelect={setSelectedId} />
              <div className="mt-3 flex flex-wrap gap-3 text-[10px] text-stone-400">
                {(["LLM", "TOOL", "CONTEXT", "ASYNC"] as const).map((kind) => <span key={kind} className="inline-flex items-center gap-1.5"><span className={`size-2 rounded-full ${kind === "LLM" ? "bg-violet-400" : kind === "TOOL" ? "bg-sky-400" : kind === "CONTEXT" ? "bg-stone-400" : "bg-emerald-400"}`} />{kindMeta[kind].label}</span>)}
              </div>
              <p className="mt-3 text-xs text-stone-400">{t("Switch to Trajectory to inspect parameters, results, schema and timing.", "切换到轨迹检查器查看参数、结果、Schema 和计时。")}</p>
            </section>
          : view === "timeline"
          ? <section className="mt-3">{trace.spans.filter((span) => span.kind === "RUN").map((span) => <TraceRow key={span.id} span={span} root />)}<div className="relative ml-5 mt-2 space-y-2 border-l border-stone-200/80 pl-5">{trace.spans.filter((span) => span.kind !== "RUN").map((span) => <TraceRow key={span.id} span={span} />)}</div>{trace.spans.length === 0 && <p className="text-sm text-stone-500">{t("No spans were recorded for this run.", "这次运行没有记录到步骤。")}</p>}</section>
          : <InspectorView spans={filteredSpans} selected={selected} filter={filter} onFilter={setFilter} onSelect={setSelectedId} schemas={toolSchemas} />}
      </>}
    </div>
  </main>;
}

function InspectorView({ spans, selected, filter, onFilter, onSelect, schemas }: {
  spans: TraceSpan[]; selected: TraceSpan | null; filter: string;
  onFilter: (value: string) => void; onSelect: (id: string) => void; schemas: Record<string, ToolSchema>;
}) {
  const { t } = useLocale();
  return <section data-trace-inspector className="mt-3 grid gap-3 lg:grid-cols-[300px_minmax(0,1fr)]">
    <div className="min-w-0 overflow-hidden rounded-2xl border border-white/80 bg-white/70">
      <div className="border-b border-stone-200/70 p-3">
        <label className="flex items-center gap-2 rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs text-stone-500 focus-within:border-violet-300">
          <Search className="size-3.5 shrink-0" />
          <input aria-label={t("Search spans", "搜索步骤")} value={filter} onChange={(event) => onFilter(event.target.value)} placeholder={t("Search name, kind or status", "搜索名称、类型或状态")} className="min-w-0 flex-1 bg-transparent outline-none placeholder:text-stone-400" />
        </label>
        <p className="mt-2 text-[10px] text-stone-400">{spans.length} {t("spans", "个步骤")}</p>
      </div>
      <div className="max-h-[72vh] overflow-y-auto p-2">
        {spans.map((span, index) => {
          const meta = kindMeta[span.kind];
          const Icon = meta.icon;
          return <button key={span.id} type="button" data-trace-inspector-item onClick={() => onSelect(span.id)} className={`flex w-full items-start gap-2 rounded-xl px-3 py-2.5 text-left transition ${selected?.id === span.id ? "bg-violet-50 ring-1 ring-violet-200" : "hover:bg-stone-50"}`}>
            <span className={`mt-0.5 inline-flex shrink-0 items-center gap-1 rounded-full px-1.5 py-0.5 text-[9px] font-semibold ${meta.color}`}><Icon className="size-2.5" />{meta.label}</span>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-xs font-medium text-stone-800">{span.kind === "RUN" ? t("Agent run", "Agent 运行") : span.name}</span>
              <span className="mt-1 flex items-center gap-2 text-[10px] text-stone-400"><span>#{index + 1}</span><span>{formatDuration(span.durationMs)}</span><span className={span.status === "FAILED" ? "text-rose-500" : span.status === "RUNNING" ? "text-violet-500" : "text-stone-400"}>{span.status}</span></span>
            </span>
          </button>;
        })}
        {spans.length === 0 && <p className="p-3 text-xs text-stone-500">{t("No matching spans.", "没有匹配的步骤。")}</p>}
      </div>
    </div>
    <div className="min-w-0">{selected ? <InspectorPanel span={selected} schema={schemas[selected.name]} /> : <div className="rounded-2xl border border-white/80 bg-white/70 p-5 text-sm text-stone-500">{t("Select a step to inspect.", "选择一个步骤查看详情。")}</div>}</div>
  </section>;
}

function InspectorPanel({ span, schema, sourceLabel, thinking }: { span: TraceSpan; schema?: ToolSchema; sourceLabel?: string; thinking?: string }) {
  const { t } = useLocale();
  const [tab, setTab] = useState<"overview" | "preview" | "raw" | "input" | "result" | "schema" | "metadata" | "timing">("overview");
  useEffect(() => setTab("overview"), [span.id]);
  const input = preview(span.kind === "TOOL" ? span.attributes.argumentsPreview : span.attributes.inputPreview);
  const result = preview(span.kind === "TOOL" ? span.attributes.resultPreview : span.attributes.outputPreview);
  const requestedTools = Array.isArray(span.attributes.requestedTools)
    ? span.attributes.requestedTools.filter((item): item is string => typeof item === "string")
    : [];
  const isAssistant = span.kind === "LLM";
  const tabs = isAssistant ? [
    ["overview", t("Overview", "概述")],
    ["preview", t("Preview", "预览")],
    ["raw", t("Raw content", "原始内容")],
  ] as const : [
    ["overview", t("Overview", "概述")],
    ["input", t("Input", "输入")],
    ["result", t("Result", "结果")],
    ["schema", t("Schema", "Schema")],
    ["metadata", t("Metadata", "元数据")],
    ["timing", t("Timing", "计时")],
  ] as const;
  return <div data-trace-inspector-panel className="overflow-hidden rounded-2xl border border-white/80 bg-white/80">
    <div className="border-b border-stone-200/70 px-5 pt-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{isAssistant ? t("Assistant", "助手") : span.kind}</p><h2 className="mt-1 truncate text-lg font-semibold">{span.name}</h2></div>
        <div className="flex flex-wrap items-center gap-2 text-[11px] text-stone-500"><span className={`rounded-full px-2 py-0.5 ${span.status === "COMPLETED" ? "bg-emerald-50 text-emerald-700" : span.status === "RUNNING" ? "bg-violet-50 text-violet-700" : "bg-rose-50 text-rose-700"}`}>{span.status}</span><span>{formatDuration(span.durationMs)}</span></div>
      </div>
      <div role="tablist" aria-label={t("Span details", "步骤详情")} className="mt-4 flex gap-1 overflow-x-auto">
        {tabs.map(([value, label]) => <button key={value} type="button" role="tab" aria-selected={tab === value} onClick={() => setTab(value)} className={`shrink-0 rounded-t-lg px-3 py-2 text-xs transition ${tab === value ? "border-b-2 border-violet-500 text-violet-700" : "text-stone-500 hover:text-stone-800"}`}>{label}</button>)}
      </div>
    </div>
    <div className="p-5">
      {tab === "overview" && (isAssistant ? <div className="grid gap-x-8 gap-y-4 text-xs sm:grid-cols-2">
        <InspectorField label={t("Source", "来源")} value={sourceLabel ?? span.runId} />
        <InspectorField label={t("Status", "状态")} value={span.status} />
        <InspectorField label={t("Model", "模型")} value={span.model ?? "—"} />
        <InspectorField label={t("Tokens", "Token")} value={`${span.inputTokens ?? 0} / ${span.outputTokens ?? 0}`} />
        <InspectorField label={t("Duration", "总时长")} value={formatDuration(span.durationMs)} />
        <InspectorField label={t("Prompt version", "Prompt 版本")} value={span.promptVersion ?? "—"} />
        <div className="sm:col-span-2"><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{t("Preview", "预览")}</p>{thinking && <details className="mt-2 rounded-xl bg-stone-50 p-4"><summary className="cursor-pointer text-[11px] font-medium text-stone-600">{t("Model reasoning output", "模型思考输出")}</summary><div className="mt-2 max-h-56 overflow-auto"><MarkdownContent className="text-sm leading-7 text-stone-700">{thinking}</MarkdownContent></div></details>}<div className="mt-2 max-h-56 overflow-auto rounded-xl bg-stone-50 p-4">{result ? <MarkdownContent className="text-sm leading-7 text-stone-700">{result}</MarkdownContent> : <p className="text-xs text-stone-500">{requestedTools.length > 0 ? t("Tool call only; no assistant text.", "仅工具调用，没有助手文本。") : t("No assistant output recorded.", "没有记录助手输出。")}</p>}</div></div>
        <div className="sm:col-span-2"><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{t("Request timing", "请求计时")}</p><div className="mt-2 grid gap-3 sm:grid-cols-2">
          <InspectorField label={t("Started at", "开始时间")} value={formatDateTime(span.startedAt)} mono />
          <InspectorField label={t("Total duration", "总时长")} value={formatDuration(span.durationMs)} />
          <InspectorField label={t("TTFT", "首 token 延迟")} value="—" />
          <InspectorField label={t("Generation", "生成")} value="—" />
          <InspectorField label={t("Throughput", "吞吐量")} value="—" />
          <InspectorField label={t("Timing source", "计时来源")} value={t("Recorded span timestamps", "记录的 span 时间戳")} />
        </div></div>
        {requestedTools.length > 0 && <div className="sm:col-span-2"><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{t("Requested tools", "请求的工具")}</p><div className="mt-1.5 flex flex-wrap gap-1.5">{requestedTools.map((tool) => <span key={tool} className="rounded-full bg-violet-50 px-2 py-0.5 font-mono text-[10px] text-violet-700">{tool}</span>)}</div></div>}
      </div> : <div className="grid gap-x-8 gap-y-3 text-xs sm:grid-cols-2">
        <InspectorField label={t("Kind", "类型")} value={span.kind} />
        <InspectorField label={t("Status", "状态")} value={span.status} />
        <InspectorField label={t("Model", "模型")} value={span.model ?? "—"} />
        <InspectorField label={t("Prompt version", "Prompt 版本")} value={span.promptVersion ?? "—"} />
        <InspectorField label={t("Input tokens", "输入 Token")} value={span.inputTokens ?? "—"} />
        <InspectorField label={t("Output tokens", "输出 Token")} value={span.outputTokens ?? "—"} />
        <InspectorField label={t("Parent span", "父步骤")} value={span.parentSpanId ?? "—"} mono />
        <InspectorField label={t("Error code", "错误码")} value={span.errorCode ?? "—"} />
        <InspectorField label={t("Span ID", "步骤 ID")} value={span.id} mono wide />
        {requestedTools.length > 0 && <div className="sm:col-span-2"><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{t("Requested tools", "请求的工具")}</p><div className="mt-1.5 flex flex-wrap gap-1.5">{requestedTools.map((tool) => <span key={tool} className="rounded-full bg-violet-50 px-2 py-0.5 font-mono text-[10px] text-violet-700">{tool}</span>)}</div></div>}
      </div>)}
      {tab === "preview" && <div className="space-y-4">{thinking && <div><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{t("Model reasoning output", "模型思考输出")}</p><div className="mt-2 max-h-[45vh] overflow-auto rounded-xl bg-stone-50 p-4"><MarkdownContent className="text-sm leading-7 text-stone-700">{thinking}</MarkdownContent></div></div>}<div><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{t("Assistant output", "助手输出")}</p>{result ? <MarkdownContent className="mt-2 text-sm leading-7 text-stone-700">{result}</MarkdownContent> : <p className="mt-2 rounded-xl bg-stone-50 p-4 text-xs text-stone-500">{t("No assistant output recorded.", "没有记录助手输出。")}</p>}</div></div>}
      {tab === "raw" && <div className="space-y-4">{thinking && <InspectorPre label={t("Raw reasoning", "原始思考")} text={thinking} empty="" />}<InspectorPre label={t("Raw output", "原始输出")} text={result} empty={t("No output recorded.", "没有记录输出。")} /><InspectorPre label={t("Raw input", "原始输入")} text={input} empty={t("No input recorded.", "没有记录输入。")} /></div>}
      {tab === "input" && <InspectorPre label={span.kind === "TOOL" ? t("Arguments", "参数") : t("Input preview", "输入预览")} text={input} empty={t("No input preview was recorded.", "没有记录输入预览。")} />}
      {tab === "result" && <InspectorPre label={span.kind === "THOUGHT" ? t("Phase conclusion", "阶段结论") : t("Result", "结果")} text={result} empty={t("No result preview was recorded.", "没有记录结果预览。")} />}
      {tab === "schema" && (schema ? <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words rounded-xl bg-stone-50 p-4 text-[11px] leading-5 text-stone-600">{JSON.stringify(schema, null, 2)}</pre> : <p className="rounded-xl bg-stone-50 p-4 text-xs text-stone-500">{t("No schema was registered for this step.", "这个步骤没有注册 Schema。")}</p>)}
      {tab === "metadata" && <pre className="max-h-[60vh] overflow-auto whitespace-pre-wrap break-words rounded-xl bg-stone-50 p-4 text-[11px] leading-5 text-stone-600">{JSON.stringify(span.attributes, null, 2)}</pre>}
      {tab === "timing" && <div className="grid gap-3 sm:grid-cols-2">
        <InspectorField label={t("Started at", "开始时间")} value={formatDateTime(span.startedAt)} mono />
        <InspectorField label={t("Finished at", "结束时间")} value={span.finishedAt ? formatDateTime(span.finishedAt) : "—"} mono />
        <InspectorField label={t("Duration", "耗时")} value={formatDuration(span.durationMs)} />
        <InspectorField label={t("Duration (ms)", "耗时（毫秒）")} value={span.durationMs ?? "—"} mono />
        <InspectorField label={t("TTFT", "首 token 延迟")} value="—" />
        <InspectorField label={t("Generation", "生成")} value="—" />
        <InspectorField label={t("Throughput", "吞吐量")} value="—" />
        <InspectorField label={t("Timing source", "计时来源")} value={t("Recorded span timestamps", "记录的 span 时间戳")} />
      </div>}
      <p className="mt-4 text-[10px] leading-5 text-stone-400">{t("Previews are truncated and redacted; full prompts, tool payloads and private chain-of-thought are not stored.", "预览已截断并脱敏；不存储完整 prompt、工具 payload 或私有思维链。")}</p>
    </div>
  </div>;
}

function InspectorField({ label, value, mono = false, wide = false }: { label: string; value: string | number; mono?: boolean; wide?: boolean }) {
  return <div className={wide ? "sm:col-span-2" : ""}><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{label}</p><p className={`mt-1 break-all text-xs text-stone-700 ${mono ? "font-mono" : ""}`}>{value}</p></div>;
}

function InspectorPre({ label, text, empty }: { label: string; text: string; empty: string }) {
  return <div><p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-stone-400">{label}</p>{text ? <pre className="mt-2 max-h-[55vh] overflow-auto whitespace-pre-wrap break-words rounded-xl bg-stone-50 p-4 text-[11px] leading-5 text-stone-600">{text}</pre> : <p className="mt-2 rounded-xl bg-stone-50 p-4 text-xs text-stone-500">{empty}</p>}</div>;
}

function formatDateTime(value: string | null) {
  if (!value) return "—";
  return new Date(value).toLocaleString();
}

function ConversationTraceView({ trace, schemas }: { trace: ConversationTrace; schemas: Record<string, ToolSchema> }) {
  const { t } = useLocale();
  const [selectedRunId, setSelectedRunId] = useState<string | null>(trace.runs.at(-1)?.runId ?? null);
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null);
  const [stepView, setStepView] = useState<"list" | "graph">("list");
  const [runFilter, setRunFilter] = useState("");
  const selectedRun = trace.runs.find((run) => run.runId === selectedRunId) ?? trace.runs.at(-1) ?? null;
  const flow = useMemo(() => buildFlow(selectedRun?.spans ?? []), [selectedRun]);
  const selectedSpan = selectedRun?.spans.find((span) => span.id === selectedSpanId) ?? null;
  useEffect(() => {
    if (!trace.runs.some((run) => run.runId === selectedRunId)) setSelectedRunId(trace.runs.at(-1)?.runId ?? null);
  }, [trace.runs, selectedRunId]);
  useEffect(() => {
    setSelectedSpanId(selectedRun?.spans[0]?.id ?? null);
  }, [selectedRun?.runId]);
  const visibleRuns = trace.runs.filter((run) => !runFilter.trim() || `${run.message}\n${run.assistantMessage}`.toLowerCase().includes(runFilter.trim().toLowerCase()));
  const allSpans = trace.runs.flatMap((run) => run.spans).filter((span) => span.kind !== "RUN");
  const starts = allSpans.map(spanStart).filter((value): value is number => value !== null);
  const ends = allSpans.map(spanEnd).filter((value): value is number => value !== null);
  const duration = starts.length && ends.length ? Math.max(...ends) - Math.min(...starts) : 0;
  const llmCalls = allSpans.filter((span) => span.kind === "LLM").length;
  const toolCalls = allSpans.filter((span) => span.kind === "TOOL").length;
  const inputTokens = allSpans.reduce((sum, span) => sum + (span.inputTokens ?? 0), 0);
  const outputTokens = allSpans.reduce((sum, span) => sum + (span.outputTokens ?? 0), 0);
  const turnIndex = selectedRun ? trace.runs.findIndex((run) => run.runId === selectedRun.runId) + 1 : 0;
  return <div className="mt-4">
    <div data-trace-overview className="grid grid-cols-2 gap-3 sm:grid-cols-5">
      {[[t("Duration", "时长"), formatDuration(duration)], [t("Turns", "轮次"), trace.runs.length], [t("Model calls", "模型调用"), llmCalls], [t("Tool calls", "工具调用"), toolCalls], [t("Tokens", "Token"), `${inputTokens} / ${outputTokens}`]].map(([label, value]) => <div key={String(label)} className="rounded-2xl border border-white/80 bg-white/70 px-4 py-3"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{label}</p><p className="mt-1 text-base font-semibold tabular-nums text-stone-800">{value}</p></div>)}
    </div>
    <TraceTimeOverview runs={trace.runs} selectedRunId={selectedRun?.runId ?? null} selectedSpanId={selectedSpanId} onSelect={(runId, spanId) => { setSelectedRunId(runId); setSelectedSpanId(spanId); }} />
    <section data-conversation-trace className="mt-3 grid gap-3 xl:grid-cols-[280px_minmax(0,1fr)_440px]">
      <div className="overflow-hidden rounded-2xl border border-white/80 bg-white/70">
        <div className="border-b border-stone-200/70 px-4 py-3">
          <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Conversation", "对话")}</p>
          <h2 className="mt-1 truncate text-sm font-semibold text-stone-800">{trace.title || t("Untitled chat", "未命名对话")}</h2>
          <p className="mt-1 text-[10px] text-stone-400">{trace.runs.length} {t("turns", "轮")}</p>
          <label className="mt-2 flex items-center gap-2 rounded-lg border border-stone-200 bg-white px-2 py-1.5 text-[11px] text-stone-500"><Search className="size-3" /><input aria-label={t("Search turns", "搜索轮次")} value={runFilter} onChange={(event) => setRunFilter(event.target.value)} placeholder={t("Search turns", "搜索轮次")} className="min-w-0 flex-1 bg-transparent outline-none placeholder:text-stone-400" /></label>
        </div>
        <div className="max-h-[68vh] overflow-y-auto p-2">
          {visibleRuns.map((run, index) => {
            const root = run.spans.find((span) => span.kind === "RUN");
            return <button key={run.runId} type="button" data-conversation-run onClick={() => setSelectedRunId(run.runId)} className={`mb-1 w-full rounded-xl px-3 py-3 text-left transition ${selectedRun?.runId === run.runId ? "bg-violet-50 ring-1 ring-violet-200" : "hover:bg-stone-50"}`}>
              <div className="flex items-center gap-2"><span className="text-[10px] font-semibold tabular-nums text-stone-400">#{index + 1}</span><span className={`rounded-full px-1.5 py-0.5 text-[9px] font-medium ${run.status === "COMPLETED" ? "bg-emerald-50 text-emerald-700" : run.status === "RUNNING" ? "bg-violet-50 text-violet-700" : "bg-rose-50 text-rose-700"}`}>{run.status}</span><span className="ml-auto text-[10px] text-stone-400">{formatDuration(root?.durationMs ?? null)}</span></div>
              <p className="mt-1.5 line-clamp-2 text-xs font-medium leading-5 text-stone-800">{run.message}</p>
              {run.assistantMessage && <p className="mt-1 line-clamp-2 text-[11px] leading-4 text-stone-400">{run.assistantMessage}</p>}
              <p className="mt-1 text-[10px] text-stone-400">{run.spans.length} {t("steps", "个步骤")} · {run.spans.filter((span) => span.kind === "TOOL").length} {t("tool calls", "个工具调用")}</p>
            </button>;
          })}
        </div>
      </div>
      <div className="overflow-hidden rounded-2xl border border-white/80 bg-white/70">
        <div className="flex items-center justify-between gap-2 border-b border-stone-200/70 px-4 py-3">
          <div><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Steps", "步骤")}</p><p className="mt-1 text-[10px] text-stone-400">{selectedRun?.spans.length ?? 0} {t("steps", "个步骤")}</p></div>
          <div className="inline-flex rounded-full border border-stone-200 bg-white/65 p-1" role="group" aria-label={t("Step view", "步骤视图")}>
            <button type="button" aria-pressed={stepView === "list"} onClick={() => setStepView("list")} className={`rounded-full px-2.5 py-1 text-[11px] transition ${stepView === "list" ? "bg-[#1d1d1f] text-white" : "text-stone-500"}`}>{t("List", "列表")}</button>
            <button type="button" aria-pressed={stepView === "graph"} onClick={() => setStepView("graph")} className={`rounded-full px-2.5 py-1 text-[11px] transition ${stepView === "graph" ? "bg-[#1d1d1f] text-white" : "text-stone-500"}`}>{t("Graph", "图")}</button>
          </div>
        </div>
        {stepView === "list" ? <div className="max-h-[62vh] overflow-y-auto p-2">
          {selectedRun?.spans.map((span) => <button key={span.id} type="button" data-trace-step-item onClick={() => setSelectedSpanId(span.id)} className={`mb-1 flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left text-xs ${selectedSpanId === span.id ? "bg-violet-50 text-violet-800 ring-1 ring-violet-200" : "text-stone-500 hover:bg-stone-50"}`}>
            <span className={`size-2 shrink-0 rounded-full ${span.kind === "LLM" ? "bg-violet-400" : span.kind === "TOOL" ? "bg-sky-400" : "bg-stone-400"}`} />
            <span className="min-w-0 flex-1 truncate">{span.kind === "RUN" ? t("Agent run", "Agent 运行") : span.name}</span>
            <span className="shrink-0 tabular-nums text-[10px] text-stone-400">{formatDuration(span.durationMs)}</span>
          </button>)}
        </div> : <div className="max-h-[62vh] overflow-auto p-2">{flow.length > 0 ? <FlowGraph nodes={flow} selectedId={selectedSpanId} onSelect={setSelectedSpanId} /> : <p className="p-3 text-xs text-stone-500">{t("No spans were recorded for this run.", "这次运行没有记录到步骤。")}</p>}</div>}
      </div>
      <div className="min-w-0">{selectedSpan ? <InspectorPanel span={selectedSpan} schema={schemas[selectedSpan.name]} thinking={selectedRun?.thinkingOutput} sourceLabel={turnIndex ? `${t("Turn", "轮次")} #${turnIndex}` : trace.conversationId} /> : <div className="rounded-2xl border border-white/80 bg-white/70 p-5 text-sm text-stone-500">{t("Select a step to inspect.", "选择一个步骤查看详情。")}</div>}</div>
    </section>
  </div>;
}
function spanStart(span: TraceSpan): number | null {
  const value = new Date(span.startedAt).getTime();
  return Number.isFinite(value) ? value : null;
}

function spanEnd(span: TraceSpan): number | null {
  const start = spanStart(span);
  if (start === null) return null;
  if (span.finishedAt) {
    const finished = new Date(span.finishedAt).getTime();
    if (Number.isFinite(finished)) return finished;
  }
  return span.durationMs != null ? start + span.durationMs : null;
}

function TraceTimeOverview({ runs, selectedRunId, selectedSpanId, onSelect }: {
  runs: ConversationTrace["runs"]; selectedRunId: string | null; selectedSpanId: string | null;
  onSelect: (runId: string, spanId: string) => void;
}) {
  const { t } = useLocale();
  const timed = runs.flatMap((run) => run.spans
    .filter((span) => span.kind !== "RUN")
    .map((span) => ({ runId: run.runId, span, start: spanStart(span), end: spanEnd(span) }))
    .filter((item): item is { runId: string; span: TraceSpan; start: number; end: number } => item.start !== null && item.end !== null));
  if (timed.length === 0) return <div className="mt-3 rounded-2xl border border-white/80 bg-white/70 px-4 py-3 text-xs text-stone-500">{t("No timing data for this conversation.", "这个对话没有计时数据。")}</div>;
  const domainStart = Math.min(...timed.map((item) => item.start));
  const domainEnd = Math.max(...timed.map((item) => item.end));
  const domain = Math.max(1, domainEnd - domainStart);
  const lanes: Array<[string, TraceSpan["kind"][]]> = [
    [t("Input / context", "输入 / 上下文"), ["CONTEXT", "THOUGHT", "ASYNC", "RETRY"]],
    [t("Model", "模型"), ["LLM"]],
    [t("Tools", "工具"), ["TOOL"]],
  ];
  return <div data-trace-timeline className="mt-3 rounded-2xl border border-white/80 bg-white/70 p-4">
    <div className="flex items-center justify-between gap-2"><p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-stone-400">{t("Time overview", "时间总览")}</p><span className="text-[10px] tabular-nums text-stone-400">{formatDuration(domain)}</span></div>
    <div className="mt-3 space-y-2">
      {lanes.map(([label, kinds]) => <div key={label} className="flex items-center gap-3"><span className="w-24 shrink-0 text-right text-[10px] text-stone-400">{label}</span><div className="relative h-7 flex-1 rounded-lg bg-stone-100/80">{timed.filter((item) => kinds.includes(item.span.kind)).map((item) => {
        const left = ((item.start - domainStart) / domain) * 100;
        const width = Math.max(0.6, ((item.end - item.start) / domain) * 100);
        const selected = item.runId === selectedRunId && item.span.id === selectedSpanId;
        const failed = item.span.status === "FAILED";
        return <button key={`${item.runId}-${item.span.id}`} type="button" data-trace-lane onClick={() => onSelect(item.runId, item.span.id)} title={`${item.span.name} · ${formatDuration(item.end - item.start)}`} style={{ left: `${left}%`, width: `${width}%` }} className={`absolute top-1 bottom-1 min-w-[3px] rounded-full transition ${selected ? "z-10 ring-2 ring-violet-500" : "hover:brightness-90"} ${failed ? "bg-rose-400" : item.span.kind === "LLM" ? "bg-violet-400" : item.span.kind === "TOOL" ? "bg-sky-400" : "bg-stone-400"}`} aria-label={`${item.span.name} ${formatDuration(item.end - item.start)}`} />;
      })}</div></div>)}
    </div>
    <p className="mt-2 text-[10px] text-stone-400">{t("Bars use recorded start, finish and duration. Click a bar to focus its step; missing timing is not invented.", "时间条只使用记录的 start/finish/duration；缺失的计时不会被伪造，点击可定位步骤。")}</p>
  </div>;
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
  const hostRef = useRef<HTMLDivElement>(null);
  const [availableWidth, setAvailableWidth] = useState(0);
  useEffect(() => {
    const element = hostRef.current;
    if (!element) return;
    const measure = () => setAvailableWidth(Math.max(0, element.clientWidth - 32));
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  if (nodes.length === 0) return <div className="rounded-2xl bg-white/60 p-5 text-sm text-stone-500">{t("No spans were recorded for this run.", "这次运行没有记录到步骤。")}</div>;
  const width = Math.max(720, ...nodes.map((node) => node.x + NODE_WIDTH + 24));
  const height = Math.max(300, ...nodes.map((node) => node.y + NODE_HEIGHT + 24));
  const scale = availableWidth > 0 ? Math.min(1.4, availableWidth / width) : 1;
  return <div ref={hostRef} data-flow-graph className="overflow-hidden rounded-[2rem] border border-white/70 bg-white/55 p-4 shadow-[0_14px_40px_rgba(49,39,70,0.04)]">
    <div style={{ width: width * scale, height: height * scale }}>
    <div className="relative" style={{ width, height, transform: `scale(${scale})`, transformOrigin: "top left" }}>
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

function SpanDetails({ span, schema }: { span: TraceSpan; schema?: ToolSchema }) {
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

    {schema && <details className="mt-3 text-[11px] text-stone-500">
      <summary className="cursor-pointer text-stone-500">{t("Schema", "Schema")}</summary>
      <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-stone-50 p-3 text-[10px] leading-5 text-stone-600">{JSON.stringify(schema, null, 2)}</pre>
    </details>}
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






















