"use client";

import { Archive, ArchiveRestore, ArrowUp, ArrowUpRight, FileText, Lock, Pencil, Plus, RefreshCw, Square } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { AppDialog } from "@/components/ui/app-dialog";
import { DeletionPermissions } from "@/components/ardor/deletion-permissions";
import { MarkdownContent } from "@/components/ardor/markdown-content";
import { ModelSettingsDialog, type ModelSettingsUpdate } from "@/components/ardor/model-settings-dialog";
import { OperationPermissions } from "@/components/ardor/operation-permissions";
import { PdfPagePreview } from "@/components/ardor/pdf-page-preview";
import { ReasoningControl, type ModelOption } from "@/components/ardor/reasoning-control";
import { ApiError, api, blobApi, streamApi } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Project = { id: string; title: string; mainFile: string; currentRevision: number; frozenRevision: number | null; updatedAt: string };
type Snapshot = { projectId: string; title: string; mainFile: string; revisionNo: number; files: Record<string, string>; sha256: string; frozenRevision: number | null };
type ResumeItem = { id: string; originalFilename: string };
type AgentMessage = { id: string; role: "USER" | "ASSISTANT"; content: string; createdAt: string };
type ToolStep = { key: string; label: string; done: boolean };
type ChatItem = AgentMessage & { tools?: ToolStep[]; pending?: boolean };
type Confirmation = { kind: string; targetId: string | null; label: string; detail: string; endpoint: string; targetIds?: string[] | null };
type PendingConfirmation = Confirmation & { state: "PENDING" | "DELETING" | "FAILED"; error?: string };
type StreamEvent = { type: string; content: string | null; toolName: string | null; label: string | null; elapsedMs: number | null; messageId: string | null; conversationId: string | null; confirmation?: Confirmation | null };
type AgentState = { messages: AgentMessage[] };

const conversationKey = (projectId: string) => `ardor.latex.conversation.${projectId}`;
const frozenKey = (projectId: string) => `ardor.latex.frozen.${projectId}`;
const confirmationKey = (item: Confirmation) => `${item.kind}:${item.endpoint}`;

function resumeFileName(title: string, revision: number) {
  const safe = title.replace(/[\\/:*?"<>|]+/g, "-").trim().slice(0, 80) || "resume";
  return `${safe} v${revision}.pdf`;
}

type StoredFrozen = { revision: number; resumeId: string; name: string };
const frozenMemory = new Map<string, StoredFrozen>();

function readStoredFrozen(projectId: string): StoredFrozen | null {
  const memory = frozenMemory.get(projectId);
  if (memory) return memory;
  try {
    const raw = window.localStorage.getItem(frozenKey(projectId));
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { revision?: unknown; resumeId?: unknown; name?: unknown };
    return typeof parsed.revision === "number" && typeof parsed.resumeId === "string" && typeof parsed.name === "string"
      ? { revision: parsed.revision, resumeId: parsed.resumeId, name: parsed.name }
      : null;
  } catch {
    return null;
  }
}

function storeFrozen(projectId: string, revision: number, name: string, resumeId: string) {
  const stored = { revision, resumeId, name };
  frozenMemory.set(projectId, stored);
  try { window.localStorage.setItem(frozenKey(projectId), JSON.stringify(stored)); }
  catch { /* the in-memory association still prevents duplicates this session */ }
}

export default function LatexWorkspacePage() {
  const { locale, t } = useLocale();
  const [projects, setProjects] = useState<Project[]>([]);
  const [projectId, setProjectId] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [previewReload, setPreviewReload] = useState(0);
  const [freezing, setFreezing] = useState(false);
  const [renaming, setRenaming] = useState(false);
  const [renameDraft, setRenameDraft] = useState("");
  const [renameBusy, setRenameBusy] = useState(false);
  const [archiving, setArchiving] = useState(false);
  const [archivedOpen, setArchivedOpen] = useState(false);
  const [archivedProjects, setArchivedProjects] = useState<Project[]>([]);
  const [archivedLoading, setArchivedLoading] = useState(false);
  const [restoringId, setRestoringId] = useState<string | null>(null);
  const [modelOptions, setModelOptions] = useState<ModelOption[]>([]);
  const [modelChoice, setModelChoice] = useState("PRIMARY");
  const [reasoningDepth, setReasoningDepth] = useState<"AUTO" | "QUICK" | "STANDARD" | "DEEP">("AUTO");
  const [nativeReasoning, setNativeReasoning] = useState(false);
  const [modelCatalogState, setModelCatalogState] = useState<"LOADING" | "READY" | "FAILED">("LOADING");
  const [modelSettingsOpen, setModelSettingsOpen] = useState(false);
  const modelAccount = useRef<string | null>(null);
  const [frozenResume, setFrozenResume] = useState<string | null>(null);
  const [confirmations, setConfirmations] = useState<PendingConfirmation[]>([]);
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
  const openSeq = useRef(0);

  const loadProjects = useCallback(async (pick?: string) => {
    const list = await api<Project[]>("/api/latex/projects");
    setProjects(list);
    setProjectId((current) => pick ?? current ?? list[0]?.id ?? null);
    return list;
  }, []);

  const uploadFrozen = useCallback(async (name: string, blob: Blob) => {
    const body = new FormData();
    body.append("file", new File([blob], name, { type: "application/pdf" }));
    const item = await api<ResumeItem>("/api/resumes", { method: "POST", body });
    setFrozenResume(name);
    return item;
  }, []);

  const ensureFrozenResume = useCallback(async (project: Project) => {
    if (project.frozenRevision == null) { setFrozenResume(null); return; }
    const resumes = await api<ResumeItem[]>("/api/resumes");
    // Display filenames collide across workspaces that share a title and revision,
    // so only the id returned by our own upload proves this PDF was synced.
    const stored = readStoredFrozen(project.id);
    if (stored && stored.revision === project.frozenRevision && resumes.some((item) => item.id === stored.resumeId)) {
      setFrozenResume(stored.name);
      return;
    }
    const name = resumeFileName(project.title, project.frozenRevision);
    const blob = await blobApi(`/latex-api/${project.id}?revision=${project.frozenRevision}`);
    const uploaded = await uploadFrozen(name, blob);
    storeFrozen(project.id, project.frozenRevision, name, uploaded.id);
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
      if (seq === openSeq.current) setError(cause instanceof Error ? cause.message : t("Could not open this resume", "无法打开这份简历"));
    }
  }, [ensureFrozenResume, t]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await loadProjects();
        if (!cancelled && list[0]) await openProject(list[0]);
      } catch (cause) {
        if (!cancelled) setError(cause instanceof Error ? cause.message : t("Could not load resumes", "无法读取简历"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, [loadProjects, openProject, t]);

  useEffect(() => {
    let active = true;
    void api<{ id: string; admin: boolean }>("/api/auth/me").then(account => {
      if (!active) return;
      modelAccount.current = account.id;
      void api<ModelOption[]>("/api/agent/models").then(items => {
        if (!active || !Array.isArray(items)) return;
        setModelOptions(items);
        setModelCatalogState("READY");
        const saved = window.sessionStorage.getItem(`ardor:model-choice:${account.id}`);
        const usable = (item: ModelOption) => item.available && item.visible !== false;
        const initial = items.find(item => item.id === saved && usable(item))
          ?? items.find(item => item.id === "PRIMARY" && usable(item))
          ?? items.find(usable);
        if (initial) {
          setModelChoice(initial.id);
          setNativeReasoning(initial.nativeControl);
          setReasoningDepth(initial.thinkingEffort ?? "AUTO");
        }
      }).catch(() => { if (active) setModelCatalogState("FAILED"); });
    }).catch(() => { if (active) setModelCatalogState("FAILED"); });
    return () => { active = false; };
  }, []);

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
      setError(cause instanceof Error ? cause.message : t("Could not create this resume", "新建简历失败"));
    }
  }

  async function renameProject() {
    if (!snapshot || renameBusy) return;
    const title = renameDraft.trim();
    if (!title || title === snapshot.title) { setRenaming(false); return; }
    setRenameBusy(true);
    setError("");
    try {
      const updated = await api<Project>(`/api/latex/projects/${snapshot.projectId}`, {
        method: "PATCH", body: JSON.stringify({ title }),
      });
      setSnapshot((current) => current ? { ...current, title: updated.title } : current);
      setProjects((current) => current.map((item) => item.id === updated.id ? { ...item, title: updated.title } : item));
      setRenaming(false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not rename this resume", "改名失败"));
    } finally {
      setRenameBusy(false);
    }
  }

  async function archiveProject() {
    if (!snapshot || archiving) return;
    if (!window.confirm(t("Archive this resume? It leaves the workspace list, but saved revisions stay.",
        "归档这份简历？它会从写简历列表里移除，历史修订仍会保留。"))) return;
    setArchiving(true);
    setError("");
    setNotice("");
    try {
      await api<void>(`/api/latex/projects/${snapshot.projectId}`, { method: "DELETE" });
      const list = await api<Project[]>("/api/latex/projects");
      setProjects(list);
      if (list[0]) {
        setProjectId(list[0].id);
        await openProject(list[0]);
      } else {
        setProjectId(null);
        setSnapshot(null);
        setMessages([]);
        setConversationId(null);
        setFrozenResume(null);
      }
      setNotice(t("Archived. It no longer appears in the list.", "已归档，不会再出现在列表里。"));
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.body.message ?? cause.message
        : cause instanceof Error ? cause.message : t("Could not archive this resume", "归档失败"));
    } finally {
      setArchiving(false);
    }
  }

  async function openArchived() {
    setArchivedOpen(true);
    setArchivedLoading(true);
    setError("");
    try {
      setArchivedProjects(await api<Project[]>("/api/latex/projects/archived"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not load archived resumes", "无法读取已归档简历"));
    } finally {
      setArchivedLoading(false);
    }
  }

  async function restoreProject(project: Project) {
    if (restoringId) return;
    setRestoringId(project.id);
    setError("");
    try {
      await api<Project>(`/api/latex/projects/${project.id}/restore`, { method: "POST" });
      setArchivedOpen(false);
      const list = await loadProjects(project.id);
      const restored = list.find((item) => item.id === project.id);
      if (restored) await openProject(restored);
      setArchivedProjects((current) => current.filter((item) => item.id !== project.id));
      setNotice(t("Restored. It is back in the list.", "已恢复，重新出现在列表里。"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t("Could not restore this resume", "恢复失败"));
    } finally {
      setRestoringId(null);
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
        // Never dedupe by filename: another workspace may have frozen the same
        // title and revision, and that PDF is a different document.
        const uploaded = await uploadFrozen(name, blob);
        storeFrozen(project.id, project.frozenRevision, name, uploaded.id);
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

  /* Business confirmations arrive after the run; the button calls the same
     authenticated endpoint the corresponding page would call. */
  async function confirmDeletion(pending: PendingConfirmation) {
    setConfirmations((current) => current.map((item) => confirmationKey(item) === confirmationKey(pending)
      ? { ...item, state: "DELETING", error: undefined } : item));
    try {
      await api<void>(pending.endpoint, { method: "DELETE", ...(pending.targetIds ? { body: JSON.stringify({ ids: pending.targetIds }) } : {}) });
      setConfirmations((current) => current.filter((item) => confirmationKey(item) !== confirmationKey(pending)));
      setNotice(t(`Deleted “${pending.label}”.`, `已删除「${pending.label}」。`));
    } catch (cause) {
      setConfirmations((current) => current.map((item) => confirmationKey(item) === confirmationKey(pending)
        ? { ...item, state: "FAILED", error: cause instanceof Error ? cause.message : t("Deletion failed", "删除失败") } : item));
    }
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
    const sentSeq = openSeq.current;
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
        // The turn belongs to the resume that was open when it started; a switch
        // during streaming must not install its conversation on another resume.
        window.localStorage.setItem(conversationKey(snapshot.projectId), created.id);
        if (openSeq.current === sentSeq) setConversationId(created.id);
      }
      let streamFailure: string | null = null;
      await streamApi<StreamEvent>("/api/agent/messages/stream", {
        requestId,
        conversationId: activeConversation,
        message: content,
        modelChoice,
        reasoningDepth,
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
        } else if (event.type === "confirm" && event.confirmation && event.confirmation.kind !== "learning_plan") {
          const proposal = event.confirmation;
          setConfirmations((current) => current.some((item) => confirmationKey(item) === confirmationKey(proposal))
            ? current
            : [...current, { ...proposal, state: "PENDING" }]);
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
      // Switching resumes mid-turn opens the other snapshot; refreshing the
      // finished turn's resume here would show its content under the wrong title.
      if (openSeq.current === sentSeq) await refreshWorkspace();
    }
  }

  const frozen = snapshot != null && snapshot.frozenRevision === snapshot.revisionNo;
  const previewProjectId = snapshot?.projectId;
  const previewRevision = snapshot?.revisionNo;
  const pageUrl = useCallback((page: number) => previewProjectId != null && previewRevision != null
    ? `/latex-api/${previewProjectId}?revision=${previewRevision}&page=${page}`
    : "", [previewProjectId, previewRevision]);
  const suggestions = [
    t("Make the project bullets more concrete and measurable.", "把项目经历写得更具体，补上量化结果"),
    t("Tighten the whole resume onto one page.", "压缩篇幅，保持在一页以内"),
    t("Rewrite the summary for a backend internship.", "按后端实习岗位调整这份简历"),
  ];
  const saveModelSettings = useCallback(async (updates: ModelSettingsUpdate[]) => {
    const items = await api<ModelOption[]>("/api/agent/models/settings", {
      method: "PUT", body: JSON.stringify({ settings: updates }),
    });
    if (!Array.isArray(items)) throw new Error(t("Could not reload the model catalog", "无法重新加载模型目录"));
    setModelOptions(items);
    setModelCatalogState("READY");
    // The saved settings replace the catalog; keep the active selection and effort on a model
    // that is still usable instead of sending a hidden model with a stale depth next turn.
    const current = items.find((item) => item.id === modelChoice);
    const next = current && current.available && current.visible !== false
      ? current
      : items.find((item) => item.available && item.visible !== false);
    if (next) {
      setModelChoice(next.id);
      setNativeReasoning(next.nativeControl);
      setReasoningDepth(next.thinkingEffort ?? "AUTO");
    }
  }, [modelChoice, t]);
  const modelPicker = <ReasoningControl value={reasoningDepth} disabled={modelCatalogState !== "READY"}
    nativeControl={nativeReasoning} models={modelOptions} model={modelChoice}
    onManageModels={() => setModelSettingsOpen(true)}
    onModelChange={(value) => {
      const selected = modelOptions.find(item => item.id === value);
      if (!selected || !selected.available || selected.visible === false) return;
      setModelChoice(value);
      setNativeReasoning(selected.nativeControl);
      setReasoningDepth(selected.thinkingEffort ?? "AUTO");
      try {
        if (modelAccount.current) window.sessionStorage.setItem(`ardor:model-choice:${modelAccount.current}`, value);
      } catch { /* The current selection remains usable when browser storage is unavailable. */ }
    }} />;

  if (loading) return <main className="ardor-workbench flex h-full items-center justify-center text-sm text-stone-400">{t("Opening resume…", "正在打开简历…")}</main>;

  return <main className="ardor-workbench flex h-full flex-col overflow-hidden text-[#1d1d1f]">
    <header className="flex flex-wrap items-center gap-3 px-4 py-3 lg:px-6">
      <FileText className="size-4 text-violet-600" />
      <h1 className="text-sm font-semibold">{t("Write resume", "写简历")}</h1>
      {snapshot && (renaming ? <form onSubmit={(event) => { event.preventDefault(); void renameProject(); }} className="flex items-center gap-1">
        <input aria-label={t("Resume name", "简历名称")} value={renameDraft} onChange={(event) => setRenameDraft(event.target.value)} autoFocus maxLength={160} className="w-44 rounded-full border border-stone-200 px-3 py-1 text-xs outline-none focus:border-violet-300" />
        <button type="submit" disabled={renameBusy} className="rounded-full bg-[#1d1d1f] px-2.5 py-1 text-[11px] text-white hover:bg-black disabled:opacity-40">{t("Save", "保存")}</button>
        <button type="button" onClick={() => setRenaming(false)} className="rounded-full px-2 py-1 text-[11px] text-stone-500 hover:bg-stone-100">{t("Cancel", "取消")}</button>
      </form> : <button type="button" onClick={() => { setRenameDraft(snapshot.title); setRenaming(true); }} aria-label={t("Rename resume", "重命名简历")} className="group inline-flex items-center gap-1 text-xs text-stone-400 hover:text-stone-700">
        {snapshot.title}<Pencil className="size-3 opacity-50 transition group-hover:opacity-100" />
      </button>)}
      <div className="ml-auto flex items-center gap-2">
        {projects.length > 1 && <select aria-label={t("Switch resume", "切换简历")} value={projectId ?? ""} onChange={(event) => { const next = projects.find((item) => item.id === event.target.value); if (next) { setProjectId(next.id); void openProject(next); } }} className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs outline-none">
          {projects.map((project) => <option key={project.id} value={project.id}>{project.title}</option>)}
        </select>}
        <button onClick={() => void openArchived()} aria-label={t("Archived resumes", "已归档简历")} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:bg-stone-50"><ArchiveRestore className="size-3.5" />{t("Archived", "已归档")}</button>
        {snapshot && <button onClick={() => void archiveProject()} disabled={archiving} aria-label={t("Archive resume", "归档简历")} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-700 hover:bg-stone-50 disabled:opacity-40"><Archive className="size-3.5" />{t("Archive", "归档")}</button>}
        <button onClick={() => void createProject()} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-700 hover:bg-stone-50"><Plus className="size-3.5" />{t("New", "新建")}</button>
      </div>
    </header>

    {(error || notice) && <div className="mx-4 mb-2 flex items-center justify-between gap-3 rounded-2xl px-4 py-2 text-xs lg:mx-6" role={error ? "alert" : "status"}>
      <span className={`min-w-0 break-words ${error ? "text-red-600" : "text-emerald-700"}`}>{error || notice}</span>
      <button onClick={() => { setError(""); setNotice(""); }} className="shrink-0 text-stone-400 hover:text-stone-600">{t("Dismiss", "关闭")}</button>
    </div>}

    {!snapshot ? <section className="m-auto max-w-md rounded-[2rem] bg-white px-8 py-14 text-center shadow-[0_18px_70px_rgba(0,0,0,0.06)]">
      <FileText className="mx-auto size-8 text-stone-300" />
      <p className="mt-4 text-base font-medium">{t("No resume yet", "还没有简历")}</p>
      <p className="mt-2 text-sm text-stone-500">{t("Start from a clean layout, then let Ardor rewrite it.", "从一个干净的版式开始，之后交给 Ardor 修改。")}</p>
      <button onClick={() => void createProject()} className="mt-6 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm text-white hover:bg-black">{t("Create one", "新建简历")}</button>
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

        {confirmations.length > 0 && <div className="border-t border-stone-100 px-3 pt-3">
          <DeletionPermissions items={confirmations}
            onKeep={(pending) => setConfirmations((current) => current.filter((item) => confirmationKey(item) !== confirmationKey(pending)))}
            onDelete={(pending) => void confirmDeletion(pending)} />
        </div>}

        <form onSubmit={(event) => { event.preventDefault(); void send(draft); }} className="border-t border-stone-100 p-3">
          {activeRunId && <OperationPermissions runId={activeRunId} onConfirming={() => undefined} onConfirmed={() => undefined} onFailed={() => undefined} />}
          <div className="rounded-2xl border border-stone-200 transition focus-within:border-violet-300">
            <textarea aria-label={t("Message Ardor", "给 Ardor 的消息")} value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => { if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) { event.preventDefault(); void send(draft); } }} rows={2} placeholder={t("Tell Ardor what to change…", "告诉 Ardor 要怎么改…")} className="max-h-40 min-h-[60px] w-full resize-none bg-transparent px-3 pt-2.5 text-sm leading-6 outline-none" />
            <div className="flex items-center justify-between gap-1 px-1 pb-1">
              <p className="truncate px-2 text-[10px] text-stone-400">{t("Ardor edits this resume", "Ardor 直接改这份简历")}</p>
              <div className="flex min-w-0 items-center justify-end gap-1">
                {modelPicker}
                {sending ? <button type="button" onClick={() => void stop()} aria-label={t("Stop", "停止")} className="grid size-9 place-items-center rounded-full bg-stone-950 text-white hover:bg-stone-800"><Square className="size-3 fill-current" /></button>
                  : <button type="submit" disabled={!draft.trim() || modelCatalogState !== "READY"} aria-label={t("Send", "发送")} className="grid size-9 place-items-center rounded-full bg-stone-950 text-white hover:bg-stone-800 disabled:opacity-30"><ArrowUp className="size-4" /></button>}
              </div>
            </div>
          </div>
        </form>
      </section>

      <aside aria-label={t("Resume preview", "简历预览")} className="latex-preview-aside flex w-full shrink-0 flex-col">
        <section className="flex min-h-0 flex-1 flex-col rounded-3xl border border-stone-200/70 bg-white p-2">
          <div className="flex items-center justify-between px-2 pb-2">
            <div className="flex items-baseline gap-2">
              <p className="text-[11px] uppercase tracking-[0.14em] text-stone-400">{frozen ? t("Final version", "定稿版本") : t("Editing", "编辑中")}</p>
              {frozen && <p className="text-sm font-semibold tabular-nums">v{snapshot.revisionNo}</p>}
            </div>
            {frozen ? <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[11px] text-emerald-700">{t("Frozen", "已定稿")}</span>
              : <button onClick={() => void freeze()} disabled={freezing} className="inline-flex items-center gap-1 rounded-full bg-violet-600 px-3 py-1 text-[11px] font-medium text-white hover:bg-violet-700 disabled:opacity-50"><Lock className="size-3" />{freezing ? "…" : t("Freeze", "定稿")}</button>}
          </div>
          <div className="min-h-[360px] flex-1 overflow-hidden rounded-2xl">
            <PdfPagePreview
              key={`${snapshot.projectId}:${snapshot.revisionNo}:${previewReload}`}
              pageUrl={pageUrl}
              alt={t("Current resume", "当前简历")}
              className="h-full"
              bodyClassName="bg-stone-100/70 p-2"
              errorAction={<a href={`/latex-api/${snapshot.projectId}?revision=${snapshot.revisionNo}`} target="_blank" rel="noreferrer" className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-4 py-2 text-sm font-medium text-white hover:bg-black"><ArrowUpRight className="size-4" />{t("Open PDF", "打开 PDF")}</a>}
            />
          </div>
          <div className="flex items-center justify-between gap-2 px-2 pt-2 text-xs">
            <div className="flex items-center gap-3">
              <button onClick={() => setPreviewReload((value) => value + 1)} className="inline-flex items-center gap-1 text-stone-500 hover:text-stone-800"><RefreshCw className="size-3" />{t("Refresh preview", "刷新预览")}</button>
              <a href={`/latex-api/${snapshot.projectId}?revision=${snapshot.revisionNo}`} target="_blank" rel="noreferrer" aria-label={t("Open PDF", "打开 PDF")} className="text-stone-400 hover:text-stone-700"><ArrowUpRight className="size-3.5" /></a>
            </div>
            {frozenResume ? <a href="/app/resumes" className="inline-flex items-center gap-1 text-emerald-700 hover:text-emerald-800">{t("In Resume analysis", "已在简历分析")}<ArrowUpRight className="size-3" /></a>
              : snapshot.frozenRevision != null ? <button onClick={() => void syncFrozen(snapshot.projectId)} className="text-violet-600 hover:text-violet-700">{t("Sync to analysis", "同步到简历分析")}</button> : null}
          </div>
        </section>
      </aside>
    </div>}
    {archivedOpen && <AppDialog title={t("Archived resumes", "已归档简历")} onClose={() => setArchivedOpen(false)}>
      <div className="space-y-2">
        {archivedLoading ? <p className="py-6 text-center text-sm text-stone-400">{t("Opening archived resumes…", "正在读取已归档简历…")}</p>
          : archivedProjects.length === 0 ? <p className="py-6 text-center text-sm text-stone-500">{t("No archived resumes.", "没有已归档的简历。")}</p>
            : archivedProjects.map((item) => <div key={item.id} className="flex items-center justify-between gap-3 rounded-2xl border border-stone-200/70 px-4 py-3">
              <div className="min-w-0"><p className="truncate text-sm font-medium">{item.title}</p><p className="mt-0.5 text-[11px] text-stone-400">{new Date(item.updatedAt).toLocaleDateString(locale === "en" ? "en-US" : "zh-CN")}</p></div>
              <button onClick={() => void restoreProject(item)} disabled={restoringId !== null} className="shrink-0 rounded-full bg-[#1d1d1f] px-3 py-1.5 text-xs text-white hover:bg-black disabled:opacity-40">{restoringId === item.id ? "…" : t("Restore", "恢复")}</button>
            </div>)}
      </div>
    </AppDialog>}
    {modelSettingsOpen && <ModelSettingsDialog models={modelOptions} onClose={() => setModelSettingsOpen(false)} onSave={saveModelSettings} />}
  </main>;
}
