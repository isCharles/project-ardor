"use client";

import { ArrowUp, ArrowUpRight, FileText, Lock, Plus, RefreshCw, Square } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { MarkdownContent } from "@/components/ardor/markdown-content";
import { OperationPermissions } from "@/components/ardor/operation-permissions";
import { ApiError, api, blobApi, streamApi } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Project = { id: string; title: string; mainFile: string; currentRevision: number; frozenRevision: number | null; updatedAt: string };
type Snapshot = { projectId: string; title: string; mainFile: string; revisionNo: number; files: Record<string, string>; sha256: string; frozenRevision: number | null };
type ResumeItem = { id: string; originalFilename: string };
type AgentMessage = { id: string; role: "USER" | "ASSISTANT"; content: string; createdAt: string };
type ToolStep = { key: string; label: string; done: boolean };
type ChatItem = AgentMessage & { tools?: ToolStep[]; pending?: boolean };
type StreamEvent = { type: string; content: string | null; toolName: string | null; label: string | null; elapsedMs: number | null; messageId: string | null; conversationId: string | null };
type AgentState = { messages: AgentMessage[] };

const conversationKey = (projectId: string) => `ardor.latex.conversation.${projectId}`;

function resumeFileName(title: string, revision: number) {
  const safe = title.replace(/[\\/:*?"<>|]+/g, "-").trim().slice(0, 80) || "resume";
  return `${safe} v${revision}.pdf`;
}

export default function LatexWorkspacePage() {
  const { t } = useLocale();
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [pdfUrl, setPdfUrl] = useState<string | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [freezing, setFreezing] = useState(false);
  const [frozenResume, setFrozenResume] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatItem[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [activeRunId, setActiveRunId] = useState<string | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const requestRef = useRef<string | null>(null);
  const sendControl = useRef<AbortController | null>(null);
  const threadEnd = useRef<HTMLDivElement | null>(null);
  const previewSeq = useRef(0);
  const openSeq = useRef(0);

  const loadProjects = useCallback(async (pick?: string) => {
    const list = await api<Project[]>("/api/latex/projects");
    setProjects(list);
    setProjectId((current) => pick ?? current ?? list[0]?.id ?? null);
    return list;
  }, []);

  const loadPreview = useCallback(async (project: string, revision: number) => {
    const seq = ++previewSeq.current;
    setPreviewing(true);
    try {
      const blob = await blobApi(`/latex-api/${project}?revision=${revision}&t=${Date.now()}`);
      if (seq !== previewSeq.current) return;
      setPdfUrl((previous) => {
        if (previous) URL.revokeObjectURL(previous);
        return URL.createObjectURL(blob);
      });
    } catch (cause) {
      if (seq === previewSeq.current) setError(cause instanceof ApiError ? cause.body.message ?? cause.message : t("Preview failed", "预览编译失败"));
    } finally {
      if (seq === previewSeq.current) setPreviewing(false);
    }
  }, [t]);

  const uploadFrozen = useCallback(async (name: string, blob: Blob) => {
    const body = new FormData();
    body.append("file", new File([blob], name, { type: "application/pdf" }));
    await api<ResumeItem>("/api/resumes", { method: "POST", body });
    setFrozenResume(name);
  }, []);

  const ensureFrozenResume = useCallback(async (project: Project) => {
    if (project.frozenRevision == null) { setFrozenResume(null); return; }
    const name = resumeFileName(project.title, project.frozenRevision);
    const resumes = await api<ResumeItem[]>("/api/resumes");
    if (resumes.some((item) => item.originalFilename === name)) { setFrozenResume(name); return; }
    const blob = await blobApi(`/latex-api/${project.id}?revision=${project.frozenRevision}`);
    await uploadFrozen(name, blob);
  }, [uploadFrozen]);

  const openProject = useCallback(async (project: Project) => {
    const id = project.id;
    const seq = ++openSeq.current;
    setError("");
    setNotice("");
    try {
      const result = await api<Snapshot>(`/api/latex/projects/${id}`);
      if (seq !== openSeq.current) return;
      setSnapshot(result);
      setMessages([]);
      setConversationId(null);
      setPdfUrl((previous) => { if (previous) URL.revokeObjectURL(previous); return null; });
      void loadPreview(id, result.revisionNo);
      if (result.frozenRevision != null) void ensureFrozenResume(project).catch(() => undefined);
      const stored = window.localStorage.getItem(conversationKey(id));
      if (stored) {
        try {
          const state = await api<AgentState>(`/api/agent?conversationId=${encodeURIComponent(stored)}`);
          if (seq !== openSeq.current) return;
          setConversationId(stored);
          setMessages(state.messages.filter((item) => item.role === "USER" || item.content.trim()).map((item) => ({ ...item })));
        } catch {
          window.localStorage.removeItem(conversationKey(id));
        }
      }
    } catch (cause) {
      if (seq === openSeq.current) setError(cause instanceof Error ? cause.message : t("Could not open the workspace", "无法打开工作区"));
    }
  }, [ensureFrozenResume, loadPreview, t]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await loadProjects();
        if (!cancelled && list[0]) await openProject(list[0]);
      } catch (cause) {
        if (!cancelled) setError(cause instanceof Error ? cause.message : t("Could not load workspaces", "无法读取简历工作区"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [loadProjects, openProject, t]);

  useEffect(() => { threadEnd.current?.scrollIntoView({ block: "end" }); }, [messages]);

  async function refreshWorkspace() {
    if (!projectId) return;
    const seq = openSeq.current;
    try {
      const list = await loadProjects(projectId);
      if (seq !== openSeq.current) return;
      const project = list.find((item) => item.id === projectId);
      if (!project) return;
      const result = await api<Snapshot>(`/api/latex/projects/${projectId}`);
      if (seq !== openSeq.current) return;
      setSnapshot(result);
      void loadPreview(projectId, result.revisionNo);
      if (project.frozenRevision != null) {
        try { await ensureFrozenResume(project); }
        catch { setError(t("Frozen, but the PDF could not be copied to Resume analysis yet.", "已定稿，但 PDF 还没能同步到简历分析，请稍后重试。")); }
      }
    } catch {
      // A stale project after a rename/delete is not worth interrupting the chat for.
    }
  }

  async function createProject() {
    setError("");
    try {
      const created = await api<Snapshot>("/api/latex/projects", { method: "POST", body: JSON.stringify({ title: t("My resume", "我的简历") }) });
      const list = await loadProjects(created.projectId);
      const project = list.find((item) => item.id === created.projectId);
      if (project) await openProject(project);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not create a workspace", "创建简历失败"));
    }
  }

  async function freeze() {
    if (!snapshot || freezing) return;
    setFreezing(true);
    setError("");
    setNotice("");
    try {
      // A frozen version must compile; otherwise it is not something the user can send.
      const blob = await blobApi(`/latex-api/${snapshot.projectId}?revision=${snapshot.revisionNo}`);
      await api<Project>(`/api/latex/projects/${snapshot.projectId}/freeze`, {
        method: "POST",
        body: JSON.stringify({ revisionNo: snapshot.revisionNo }),
      });
      const list = await loadProjects(snapshot.projectId);
      const project = list.find((item) => item.id === snapshot.projectId);
      if (project && project.frozenRevision != null) {
        const name = resumeFileName(project.title, project.frozenRevision);
        const resumes = await api<ResumeItem[]>("/api/resumes");
        if (resumes.some((item) => item.originalFilename === name)) setFrozenResume(name);
        else await uploadFrozen(name, blob);
      }
      setSnapshot((current) => current ? { ...current, frozenRevision: snapshot.revisionNo } : current);
      setNotice(t("Frozen. The PDF is now in Resume analysis.", "已定稿，这份简历已出现在简历分析里。"));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.body.message ?? cause.message : cause instanceof Error ? cause.message : t("Could not freeze", "定稿失败"));
    } finally {
      setFreezing(false);
    }
  }

  async function stop() {
    const requestId = requestRef.current;
    sendControl.current?.abort();
    if (!requestId) return;
    await api<void>(`/api/agent/runs/${encodeURIComponent(requestId)}/cancel`, { method: "POST" }).catch(() => undefined);
  }

  async function syncFrozen(projectId: string) {
    const project = projects.find((item) => item.id === projectId);
    if (!project) return;
    setError("");
    try {
      await ensureFrozenResume(project);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not copy the PDF into Resume analysis", "没能把 PDF 同步到简历分析"));
    }
  }

  async function send(text: string) {
    const content = text.trim();
    if (!content || sending || !snapshot) return;
    setSending(true);
    setError("");
    setNotice("");
    setDraft("");
    const requestId = crypto.randomUUID();
    requestRef.current = requestId;
    setActiveRunId(requestId);
    const control = new AbortController();
    sendControl.current = control;
    const pendingId = `pending-${requestId}`;
    setMessages((current) => [
      ...current,
      { id: `user-${requestId}`, role: "USER", content, createdAt: "" },
      { id: pendingId, role: "ASSISTANT", content: "", createdAt: "", tools: [], pending: true },
    ]);
    const update = (change: (item: ChatItem) => ChatItem) =>
      setMessages((current) => current.map((item) => item.id === pendingId ? change(item) : item));
    try {
      let activeConversation = conversationId;
      if (!activeConversation) {
        const created = await api<{ id: string }>("/api/agent/conversations", { method: "POST" });
        activeConversation = created.id;
        setConversationId(created.id);
        window.localStorage.setItem(conversationKey(snapshot.projectId), created.id);
      }
      let streamFailure: string | null = null;
      await streamApi<StreamEvent>("/api/agent/messages/stream", {
        requestId,
        conversationId: activeConversation,
        message: content,
        contextReferences: [{ type: "LATEX", id: snapshot.projectId }],
      }, (event) => {
        if (event.type === "delta" && event.content) {
          update((item) => ({ ...item, content: item.content + event.content }));
        } else if (event.type === "intermediate") {
          update((item) => ({ ...item, content: "" }));
        } else if (event.type === "tool_start" && event.toolName) {
          const step: ToolStep = { key: `${event.toolName}-${crypto.randomUUID()}`, label: event.label ?? event.toolName, done: false };
          update((item) => ({ ...item, tools: [...(item.tools ?? []), step] }));
        } else if (event.type === "tool_end" && event.toolName) {
          update((item) => {
            const tools = [...(item.tools ?? [])];
            const index = tools.findLastIndex((step) => step.key.startsWith(`${event.toolName}-`) && !step.done);
            if (index >= 0) tools[index] = { ...tools[index], label: event.label ?? tools[index].label, done: true };
            return { ...item, tools };
          });
        } else if (event.type === "error") {
          streamFailure = event.content ?? t("Ardor could not answer", "Ardor 暂时无法回复");
        } else if (event.type === "done") {
          update((item) => ({ ...item, id: event.messageId ?? item.id, content: event.content || item.content, pending: false }));
        }
      }, control.signal);
      if (streamFailure) throw new Error(streamFailure);
      update((item) => ({ ...item, pending: false }));
    } catch (cause) {
      if (control.signal.aborted) {
        update((item) => ({ ...item, pending: false }));
      } else {
        setError(cause instanceof Error ? cause.message : t("Ardor could not answer", "Ardor 暂时无法回复"));
        update((item) => ({ ...item, pending: false, content: item.content || t("This turn did not finish. Try again.", "这一轮没有完成，可以再试一次。") }));
      }
    } finally {
      setSending(false);
      setActiveRunId(null);
      sendControl.current = null;
      requestRef.current = null;
      await refreshWorkspace();
    }
  }

  const frozen = snapshot != null && snapshot.frozenRevision === snapshot.revisionNo;
  const suggestions = [
    t("Make the project bullets more concrete and measurable.", "把项目经历写得更具体，补上量化结果"),
    t("Tighten the whole resume onto one page.", "压缩篇幅，保持在一页以内"),
    t("Rewrite the summary for a backend internship.", "按后端实习岗位调整这份简历"),
  ];

  if (loading) return <main className="ardor-workbench flex h-full items-center justify-center text-sm text-stone-400">{t("Opening workspace…", "正在打开工作区…")}</main>;

  return <main className="ardor-workbench flex h-full flex-col overflow-hidden text-[#1d1d1f]">
    <header className="flex flex-wrap items-center gap-3 px-4 py-3 lg:px-6">
      <FileText className="size-4 text-violet-600" />
      <h1 className="text-sm font-semibold">{t("Resume workspace", "简历工作区")}</h1>
      {snapshot && <span className="text-xs text-stone-400">{snapshot.title}</span>}
      <div className="ml-auto flex items-center gap-2">
        {projects.length > 1 && <select aria-label={t("Switch workspace", "切换工作区")} value={projectId ?? ""} onChange={(event) => { const next = projects.find((item) => item.id === event.target.value); if (next) { setProjectId(next.id); void openProject(next); } }} className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs outline-none">
          {projects.map((project) => <option key={project.id} value={project.id}>{project.title}</option>)}
        </select>}
        <button onClick={() => void createProject()} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-700 hover:bg-stone-50"><Plus className="size-3.5" />{t("New", "新建")}</button>
      </div>
    </header>

    {(error || notice) && <div className="mx-4 mb-2 flex items-center justify-between gap-3 rounded-2xl px-4 py-2 text-xs lg:mx-6" role={error ? "alert" : "status"}>
      <span className={`min-w-0 break-words ${error ? "text-red-600" : "text-emerald-700"}`}>{error || notice}</span>
      <button onClick={() => { setError(""); setNotice(""); }} className="shrink-0 text-stone-400 hover:text-stone-600">{t("Dismiss", "关闭")}</button>
    </div>}

    {!snapshot ? <section className="m-auto max-w-md rounded-[2rem] bg-white px-8 py-14 text-center shadow-[0_18px_70px_rgba(0,0,0,0.06)]">
      <FileText className="mx-auto size-8 text-stone-300" />
      <p className="mt-4 text-base font-medium">{t("No resume workspace yet", "还没有简历工作区")}</p>
      <p className="mt-2 text-sm text-stone-500">{t("Start from a one-page layout, then let Ardor rewrite it.", "从一页纸的简历版式开始，之后交给 Ardor 修改。")}</p>
      <button onClick={() => void createProject()} className="mt-6 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm text-white hover:bg-black">{t("Create one", "新建一份")}</button>
    </section> : <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-4 pb-4 lg:flex-row lg:overflow-hidden lg:px-6 lg:pb-6">
      <section aria-label={t("Ardor", "Ardor")} className="flex h-[58vh] min-h-[380px] min-w-0 flex-1 flex-col overflow-hidden rounded-3xl border border-stone-200/70 bg-white lg:h-auto lg:min-h-0">
        <div className="flex items-center gap-2 border-b border-stone-100 px-4 py-2.5">
          <span className="size-1.5 rounded-full bg-emerald-500" />
          <p className="text-xs font-medium text-stone-700">Ardor</p>
          <p className="text-[11px] text-stone-400">{t("edits this resume for you", "直接修改这份简历")}</p>
        </div>

        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto px-4 py-5">
          {messages.length === 0 ? <div className="mx-auto flex h-full max-w-md flex-col justify-center">
            <p className="text-lg font-medium">{t("What should change?", "想改哪里？")}</p>
            <div className="mt-4 space-y-2">{suggestions.map((suggestion) => <button key={suggestion} onClick={() => { setDraft(suggestion); }} className="block w-full rounded-2xl border border-stone-200/80 px-4 py-3 text-left text-sm text-stone-600 hover:border-violet-200 hover:bg-violet-50/40">{suggestion}</button>)}</div>
          </div> : messages.map((item) => <div key={item.id}>
            {item.role === "USER" ? <div className="ml-auto max-w-[85%] rounded-2xl rounded-br-md bg-stone-100 px-4 py-2.5 text-sm leading-6 text-stone-800">{item.content}</div> : <div className="max-w-full text-sm leading-7 text-stone-800">
              {item.tools && item.tools.length > 0 && <div className="mb-2 flex flex-wrap gap-1.5">{item.tools.map((step) => <span key={step.key} className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] ${step.done ? "bg-stone-100 text-stone-500" : "bg-violet-50 text-violet-700"}`}>{step.done ? null : <RefreshCw className="size-2.5 animate-spin" />}{step.label}</span>)}</div>}
              {item.content ? <MarkdownContent>{item.content}</MarkdownContent> : item.pending ? <p className="text-stone-400">{t("Working…", "正在处理…")}</p> : null}
            </div>}
          </div>)}
          <div ref={threadEnd} />
        </div>

        <form onSubmit={(event) => { event.preventDefault(); void send(draft); }} className="border-t border-stone-100 p-3">
          {activeRunId && <OperationPermissions runId={activeRunId} onConfirming={() => undefined} onConfirmed={() => undefined} onFailed={() => undefined} />}
          <div className="flex items-end gap-2 rounded-2xl border border-stone-200 px-3 py-2 focus-within:border-violet-300">
            <textarea aria-label={t("Message Ardor", "给 Ardor 的消息")} value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(draft); } }} rows={2} placeholder={t("Tell Ardor what to change…", "告诉 Ardor 要怎么改…")} className="max-h-40 min-h-[44px] flex-1 resize-none bg-transparent py-1 text-sm leading-6 outline-none" />
            {sending ? <button type="button" onClick={() => void stop()} aria-label={t("Stop", "停止")} className="mb-0.5 rounded-full bg-stone-900 p-2 text-white hover:bg-black"><Square className="size-3.5" /></button>
              : <button type="submit" disabled={!draft.trim()} aria-label={t("Send", "发送")} className="mb-0.5 rounded-full bg-[#1d1d1f] p-2 text-white hover:bg-black disabled:opacity-30"><ArrowUp className="size-4" /></button>}
          </div>
        </form>
      </section>

      <aside className="flex w-full shrink-0 flex-col lg:w-[340px] xl:w-[380px]">
        <section className="flex min-h-0 flex-1 flex-col rounded-3xl border border-stone-200/70 bg-white p-3">
          <div className="flex items-center justify-between px-1 pb-2">
            <div className="flex items-baseline gap-2">
              <p className="text-[11px] uppercase tracking-[0.14em] text-stone-400">{t("Current version", "当前版本")}</p>
              <p className="text-sm font-semibold tabular-nums">v{snapshot.revisionNo}</p>
            </div>
            {frozen ? <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] text-emerald-700">{t("Frozen", "已定稿")}</span>
              : <button onClick={() => void freeze()} disabled={freezing || previewing} className="inline-flex items-center gap-1 rounded-full bg-violet-600 px-3 py-1 text-[11px] font-medium text-white hover:bg-violet-700 disabled:opacity-50"><Lock className="size-3" />{freezing ? "…" : t("Freeze", "定稿")}</button>}
          </div>
          <div className="min-h-[360px] flex-1 overflow-hidden rounded-2xl border border-stone-100 bg-[#f4f2ee]">
            {pdfUrl ? <iframe src={`${pdfUrl}#view=FitH&toolbar=0&navpanes=0`} title={t("Current resume", "当前简历")} className="size-full" />
              : <div className="flex size-full items-center justify-center text-xs text-stone-400">{previewing ? <span className="inline-flex items-center gap-2"><RefreshCw className="size-3 animate-spin" />{t("Compiling…", "正在编译…")}</span> : t("No preview", "暂无预览")}</div>}
          </div>
          <div className="flex items-center justify-between gap-2 px-1 pt-2 text-xs">
            <div className="flex items-center gap-3">
              <button onClick={() => void loadPreview(snapshot.projectId, snapshot.revisionNo)} disabled={previewing} className="inline-flex items-center gap-1 text-stone-500 hover:text-stone-800 disabled:opacity-40"><RefreshCw className={`size-3 ${previewing ? "animate-spin" : ""}`} />{t("Recompile", "重新编译")}</button>
              {pdfUrl && <a href={pdfUrl} target="_blank" rel="noreferrer" aria-label={t("Open PDF", "打开 PDF")} className="text-stone-400 hover:text-stone-700"><ArrowUpRight className="size-3.5" /></a>}
            </div>
            {frozenResume ? <a href="/app/resumes" className="inline-flex items-center gap-1 text-emerald-700 hover:text-emerald-800">{t("In Resume analysis", "已在简历分析")}<ArrowUpRight className="size-3" /></a>
              : snapshot.frozenRevision != null ? <button onClick={() => void syncFrozen(snapshot.projectId)} className="text-violet-600 hover:text-violet-700">{t("Sync to analysis", "同步到简历分析")}</button> : null}
          </div>
        </section>
      </aside>
    </div>}
  </main>;
}
