"use client";

import {
  Archive, ArrowUp, BookOpenText, Brain, BrainCircuit, CalendarDays, CheckCircle2, ChevronDown, ChevronRight, FileSearch, FileText, Flame, MessageSquareText,
  LayoutGrid, MoreHorizontal, PanelLeftClose, PanelLeftOpen, Paperclip, Pencil, Pin, Plus, RotateCcw, Settings2, Trash2, Upload, X,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { Button } from "@/components/ui/button";
import { ApiError, api, streamApi } from "@/lib/api";
import { useTypewriter } from "@/lib/use-typewriter";

type AgentMessage = { id: string; conversationId: string; role: "USER" | "ASSISTANT"; content: string; runTrace?: string | null; createdAt: string };
type Conversation = { id: string; title: string; pinned: boolean; createdAt: string; updatedAt: string };
type AgentMemory = { content: string; updatedAt: string | null };
type RetryFailure = { label: string; detail: string };
type AgentStreamEvent = { type: "status" | "delta" | "tool_start" | "tool_end" | "done" | "error"; content: string | null; toolName: string | null; label: string | null; elapsedMs: number | null; conversationId: string | null; messageId: string | null; retryable: boolean | null; errorCode: string | null };
type RunStep = { key: string; label: string; elapsedMs: number; done: boolean };
type SelectedContext = { type: "RESUME" | "RECAP"; id: string; label: string };
type ResumeOption = { id: string; originalFilename: string };
type RecapOption = { id: string; title: string };
type BackgroundModule = "resumes" | "recaps" | "knowledge";
type ResumeBackgroundStatus = { analysisId: string | null; analysisStatus: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | null };
type RecapBackgroundStatus = { status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" };
type KnowledgeBackgroundStatus = { pendingChunks: number };
type AgentState = {
  conversationId: string | null;
  llmConfigured: boolean;
  displayName: string | null;
  messages: AgentMessage[];
  conversations: Conversation[];
  archivedConversations: Conversation[];
  memory: AgentMemory;
};

function formatElapsed(milliseconds: number) {
  if (milliseconds <= 0) return "即时";
  if (milliseconds < 1000) return `${Math.max(1, Math.round(milliseconds))} ms`;
  return `${(milliseconds / 1000).toFixed(1)} 秒`;
}

function savedRunTrace(raw?: string | null) {
  if (!raw) return null;
  try { return JSON.parse(raw) as { elapsedMs: number; status: string; steps: Array<{ label: string; elapsedMs: number; status: string }> }; }
  catch { return null; }
}

const typewriterExamples = [
  "Ask Ardor to analyze my latest resume",
  "Tell Ardor I’m targeting Java backend roles",
  "Ask Ardor to start a mock interview",
  "Tell Ardor what I want to improve",
];

function conversationTitle(message: string) {
  const normalized = message.trim().replace(/\s+/g, " ");
  const firstThought = normalized.split(/[。！？!?；;\n]/, 1)[0]?.trim() || normalized;
  const characters = Array.from(firstThought);
  return characters.length > 24 ? `${characters.slice(0, 24).join("")}…` : firstThought;
}

function sortConversations(items: Conversation[]) {
  return [...items].sort((left, right) => {
    if (left.pinned !== right.pinned) return left.pinned ? -1 : 1;
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime();
  });
}

const MAX_MESSAGE_RETRIES = 5;
const MAX_MESSAGE_LENGTH = 50_000;

function draftStorageKey(conversationId?: string | null) {
  return `ardor:conversation-draft:${conversationId ?? "new"}`;
}

function readSavedDraft(conversationId?: string | null) {
  return window.sessionStorage.getItem(draftStorageKey(conversationId)) ?? "";
}

export default function AgentHomePage() {
  const router = useRouter();
  const [state, setState] = useState<AgentState | null>(null);
  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [archivedConversations, setArchivedConversations] = useState<Conversation[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [retryFailures, setRetryFailures] = useState<RetryFailure[]>([]);
  const [runSteps, setRunSteps] = useState<RunStep[]>([]);
  const [traceOpen, setTraceOpen] = useState(true);
  const [runElapsed, setRunElapsed] = useState(0);
  const [leavingEmptyState, setLeavingEmptyState] = useState(false);
  const [error, setError] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const typedHint = useTypewriter(typewriterExamples, messages.length === 0);
  const [workspaceOpen, setWorkspaceOpen] = useState(false);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryDraft, setMemoryDraft] = useState("");
  const [selectedContext, setSelectedContext] = useState<SelectedContext | null>(null);
  const [attachmentOpen, setAttachmentOpen] = useState(false);
  const [resumeOptions, setResumeOptions] = useState<ResumeOption[]>([]);
  const [recapOptions, setRecapOptions] = useState<RecapOption[]>([]);
  const [notice, setNotice] = useState("");
  const [menuId, setMenuId] = useState<string | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const [backgroundReady, setBackgroundReady] = useState<Record<BackgroundModule, boolean>>({ resumes: false, recaps: false, knowledge: false });
  const endRef = useRef<HTMLDivElement>(null);
  const runStartedAt = useRef(0);
  const uploadRef = useRef<HTMLInputElement>(null);

  const applyState = useCallback((result: AgentState) => {
    setState(result);
    setMessages(result.messages);
    setConversations(result.conversations);
    setArchivedConversations(result.archivedConversations);
    setMemoryDraft(result.memory.content);
    setDraft(readSavedDraft(result.conversationId));
    window.history.replaceState(null, "", result.conversationId ? `/app?conversation=${result.conversationId}` : "/app");
  }, []);

  const load = useCallback(async (conversationId?: string | null) => {
    const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : "";
    applyState(await api<AgentState>(`/api/agent${query}`));
  }, [applyState]);

  useEffect(() => {
    let cancelled = false;
    const requested = new URLSearchParams(window.location.search).get("conversation");
    const query = requested ? `?conversationId=${encodeURIComponent(requested)}` : "";
    api<AgentState>(`/api/agent${query}`)
      .then(async (result) => {
        if (cancelled) return;
        const params = new URLSearchParams(window.location.search);
        if (params.get("new") === "1") {
          const conversation = await api<Conversation>("/api/agent/conversations", { method: "POST" });
          result = { ...result, conversationId: conversation.id, messages: [], conversations: [conversation, ...result.conversations] };
          const prefill = params.get("prefill") ?? "";
          if (prefill) window.sessionStorage.setItem(draftStorageKey(conversation.id), prefill);
          const contextType = params.get("contextType"); const contextId = params.get("contextId"); const contextLabel = params.get("contextLabel");
          if ((contextType === "RESUME" || contextType === "RECAP") && contextId && contextLabel) setSelectedContext({ type: contextType, id: contextId, label: contextLabel });
        }
        if (params.get("notice") === "recap-queued") setNotice("面经已提交后台整理，你可以继续使用 Ardor。完成后会出现在面经列表里。");
        applyState(result);
      })
      .catch((reason) => {
        if (cancelled) return;
        if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
        setError(reason instanceof Error ? reason.message : "无法载入 Agent");
      });
    return () => { cancelled = true; };
  }, [applyState, router]);

  useEffect(() => {
    let cancelled = false;
    const modules: BackgroundModule[] = ["resumes", "recaps", "knowledge"];
    const refresh = async () => {
      try {
        const [resumes, recaps, knowledge] = await Promise.all([
          api<ResumeBackgroundStatus[]>("/api/resumes"),
          api<RecapBackgroundStatus[]>("/api/interview-recaps/jobs"),
          api<KnowledgeBackgroundStatus>("/api/knowledge/index-status"),
        ]);
        const active = {
          resumes: resumes.some((item) => item.analysisStatus === "QUEUED" || item.analysisStatus === "RUNNING"),
          recaps: recaps.some((item) => item.status === "QUEUED" || item.status === "RUNNING"),
          knowledge: knowledge.pendingChunks > 0,
        } satisfies Record<BackgroundModule, boolean>;
        for (const itemModule of modules) {
          const pendingKey = `ardor:background-pending:${itemModule}`;
          const readyKey = `ardor:background-ready:${itemModule}`;
          if (active[itemModule]) window.localStorage.setItem(pendingKey, "1");
          else if (window.localStorage.getItem(pendingKey) === "1") {
            window.localStorage.removeItem(pendingKey);
            window.localStorage.setItem(readyKey, "1");
          }
        }
        if (!cancelled) setBackgroundReady(Object.fromEntries(modules.map((itemModule) => [itemModule, window.localStorage.getItem(`ardor:background-ready:${itemModule}`) === "1"])) as Record<BackgroundModule, boolean>);
      } catch {
        // Background badges are best-effort and must never interrupt the workspace.
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 10_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, []);

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages, busy]);
  useEffect(() => {
    if (!busy) return;
    const timer = window.setInterval(() => setRunElapsed(Date.now() - runStartedAt.current), 250);
    return () => window.clearInterval(timer);
  }, [busy]);

  async function selectConversation(conversationId: string) {
    if (busy || conversationId === state?.conversationId) return;
    setError(""); setMenuId(null); setSidebarOpen(false);
    try { await load(conversationId); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法载入会话"); }
  }

  async function newConversation() {
    if (busy) return;
    setError("");
    setRetryFailures([]);
    setRunSteps([]);
    setRunElapsed(0);
    setMessages([]);
    setSelectedContext(null);
    setState((current) => current ? { ...current, conversationId: null, messages: [] } : current);
    setDraft(readSavedDraft(null));
    window.history.replaceState(null, "", "/app");
    setSidebarOpen(false);
  }

  async function send(text: string) {
    const message = text.trim();
    if (!message || busy) return;
    if (message.length > MAX_MESSAGE_LENGTH) {
      setError(`这条消息有 ${message.length.toLocaleString()} 个字符，单条最多 ${MAX_MESSAGE_LENGTH.toLocaleString()} 个字符。请删减或分段发送。`);
      return;
    }
    const optimisticId = `pending-user-message-${crypto.randomUUID()}`;
    let conversationId = state?.conversationId ?? null;
    let conversationCreatedForThisMessage: string | null = null;
    let savedDraftKey = draftStorageKey(conversationId);
    window.sessionStorage.setItem(savedDraftKey, message);
    setError(""); setRetryFailures([]); setRunSteps([]); setTraceOpen(true); setRunElapsed(0); runStartedAt.current = Date.now(); setBusy(true);
    try {
      if (!conversationId) {
        const conversation = await api<Conversation>("/api/agent/conversations", { method: "POST" });
        conversationId = conversation.id;
        conversationCreatedForThisMessage = conversation.id;
        const conversationDraftKey = draftStorageKey(conversationId);
        window.sessionStorage.setItem(conversationDraftKey, message);
        window.sessionStorage.removeItem(savedDraftKey);
        savedDraftKey = conversationDraftKey;
        setConversations((current) => sortConversations([conversation, ...current]));
        setState((current) => current ? { ...current, conversationId: conversation.id, messages: [] } : current);
        window.history.replaceState(null, "", `/app?conversation=${conversation.id}`);
      }
      const activeConversationId = conversationId;
      if (!activeConversationId) throw new Error("无法创建新对话");
      const activeConversation = conversations.find((item) => item.id === activeConversationId);
      if (messages.length === 0 && (!activeConversation || activeConversation.title === "新对话")) {
        const title = conversationTitle(message);
        setConversations((current) => current.map((item) => item.id === activeConversationId ? { ...item, title } : item));
      }
      const optimistic: AgentMessage = { id: optimisticId, conversationId: activeConversationId, role: "USER", content: message, createdAt: "" };
      if (messages.length === 0) {
        setLeavingEmptyState(true);
        await new Promise((resolve) => window.setTimeout(resolve, 260));
      }
      setDraft("");
      setMessages((current) => [...current, optimistic]);
      setLeavingEmptyState(false);
      const pendingAssistantId = `pending-assistant-${crypto.randomUUID()}`;
      let assistant: AgentMessage | null = null;
      setMessages((current) => [...current, { id: pendingAssistantId, conversationId: activeConversationId, role: "ASSISTANT", content: "", createdAt: "" }]);
      for (let retry = 0; retry <= MAX_MESSAGE_RETRIES; retry += 1) {
        if (retry > 0) {
          setRetryAttempt(retry);
          await new Promise((resolve) => window.setTimeout(resolve, Math.min(1500 * (2 ** (retry - 1)), 8000)));
        }
        let receivedToolEvent = false;
        let receivedDelta = false;
        try {
          let streamFailure: AgentStreamEvent | null = null;
          if (retry > 0) setMessages((current) => current.map((item) => item.id === pendingAssistantId ? { ...item, content: "" } : item));
          await streamApi<AgentStreamEvent>("/api/agent/messages/stream", { conversationId: activeConversationId, message, contextType: selectedContext?.type ?? null, contextId: selectedContext?.id ?? null }, (event) => {
            if (event.type === "delta" && event.content) {
              receivedDelta = true;
              setMessages((current) => current.map((item) => item.id === pendingAssistantId ? { ...item, content: item.content + event.content } : item));
            } else if (event.type === "status" && event.label) {
              setRunSteps((current) => current.some((step) => step.key === "status") ? current : [...current, { key: "status", label: event.label!, elapsedMs: 0, done: false }]);
            } else if (event.type === "tool_start" && event.toolName) {
              receivedToolEvent = true;
              setRunSteps((current) => [...current, { key: `${event.toolName}-${current.length}`, label: event.label ?? "调用工具", elapsedMs: 0, done: false }]);
            } else if (event.type === "tool_end" && event.toolName) {
              setRunSteps((current) => { const index = current.findLastIndex((step) => step.key.startsWith(`${event.toolName}-`) && !step.done); return index < 0 ? current : current.map((step, i) => i === index ? { ...step, label: event.label ?? step.label, elapsedMs: event.elapsedMs ?? 0, done: true } : step); });
            } else if (event.type === "done" && event.messageId && event.conversationId) {
              assistant = { id: event.messageId, conversationId: event.conversationId, role: "ASSISTANT", content: event.content ?? "", createdAt: new Date().toISOString() };
              setMessages((current) => current.map((item) => item.id === pendingAssistantId ? assistant! : item));
              const totalElapsed = event.elapsedMs ?? Date.now() - runStartedAt.current;
              setRunElapsed(totalElapsed);
              setRunSteps((current) => {
                const toolElapsed = current.filter((step) => step.key !== "status")
                  .reduce((sum, step) => sum + step.elapsedMs, 0);
                const modelElapsed = Math.max(1, totalElapsed - toolElapsed);
                return current.map((step) => step.key === "status"
                  ? { ...step, label: "理解请求与生成回复", elapsedMs: modelElapsed, done: true }
                  : step);
              });
            } else if (event.type === "error") streamFailure = event;
          });
          if (streamFailure) {
            const failure = streamFailure as AgentStreamEvent;
            throw Object.assign(new Error(failure.content ?? "Agent 暂时无法回复"), { retryable: failure.retryable, code: failure.errorCode });
          }
          break;
        } catch (reason) {
          const serverRetryable = reason instanceof ApiError ? reason.body.retryable === true : (reason as { retryable?: boolean }).retryable !== false;
          const retryable = serverRetryable && !receivedToolEvent && !receivedDelta;
          const label = retry === 0 ? "首次请求" : `第 ${retry}/${MAX_MESSAGE_RETRIES} 次重试`;
          const detail = reason instanceof ApiError
            ? `${reason.body.code ?? `HTTP ${reason.status}`} · ${reason.message}`
            : reason instanceof Error ? reason.message : "浏览器无法连接 Ardor 服务";
          setRetryFailures((current) => [...current, { label, detail }]);
          if (!retryable || retry === MAX_MESSAGE_RETRIES) throw reason;
        }
      }
      const completedAssistant = assistant as AgentMessage | null;
      if (!completedAssistant) throw new Error("Agent 暂时无法回复");
      window.sessionStorage.removeItem(savedDraftKey);
      setSelectedContext(null);
      await load(completedAssistant.conversationId);
      setRunSteps([]);
      setRetryFailures([]);
      setRunElapsed(0);
    } catch (reason) {
      if (conversationCreatedForThisMessage) {
        try {
          const current = await api<AgentState>(`/api/agent?conversationId=${conversationCreatedForThisMessage}`);
          if (current.messages.length === 0) {
            await api<void>(`/api/agent/conversations/${conversationCreatedForThisMessage}`, { method: "DELETE" });
            setConversations((items) => items.filter((item) => item.id !== conversationCreatedForThisMessage));
            window.history.replaceState(null, "", "/app");
          }
        } catch {
          // Preserve the original send error; an uncertain conversation is never deleted blindly.
        }
      }
      setMessages((current) => current.filter((item) => item.id !== optimisticId && !item.id.startsWith("pending-assistant-")));
      setDraft(message);
      window.sessionStorage.setItem(savedDraftKey, message);
      setError(reason instanceof Error ? reason.message : "Agent 暂时无法回复");
    } finally { setRetryAttempt(0); setLeavingEmptyState(false); setBusy(false); }
  }

  function updateDraft(value: string) {
    setDraft(value);
    const key = draftStorageKey(state?.conversationId);
    if (value) window.sessionStorage.setItem(key, value);
    else window.sessionStorage.removeItem(key);
  }

  async function openAttachments() {
    setAttachmentOpen((open) => !open);
    if (resumeOptions.length || recapOptions.length) return;
    try {
      const [resumes, recaps] = await Promise.all([api<ResumeOption[]>("/api/resumes"), api<RecapOption[]>("/api/interview-recaps")]);
      setResumeOptions(resumes); setRecapOptions(recaps);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法载入资料"); }
  }

  async function uploadResume(file?: File) {
    if (!file) return;
    const body = new FormData(); body.append("file", file);
    setBusy(true); setError("");
    try {
      const resume = await api<ResumeOption>("/api/resumes", { method: "POST", body });
      setResumeOptions((items) => [resume, ...items]);
      setSelectedContext({ type: "RESUME", id: resume.id, label: resume.originalFilename });
      setAttachmentOpen(false); setNotice("简历已加入资料库，并附加到这条消息。你可以让 Ardor 分析或管理它。");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "简历上传失败"); }
    finally { setBusy(false); if (uploadRef.current) uploadRef.current.value = ""; }
  }

  async function renameConversation(conversation: Conversation) {
    const title = window.prompt("修改会话名称", conversation.title)?.trim();
    if (!title || title === conversation.title) return;
    try {
      const updated = await api<Conversation>(`/api/agent/conversations/${conversation.id}`, { method: "PATCH", body: JSON.stringify({ title }) });
      setConversations((current) => current.map((item) => item.id === updated.id ? updated : item));
      setMenuId(null);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法重命名会话"); }
  }

  async function togglePin(conversation: Conversation) {
    setMenuId(null);
    try {
      const updated = await api<Conversation>(`/api/agent/conversations/${conversation.id}/pin`, { method: "PATCH", body: JSON.stringify({ pinned: !conversation.pinned }) });
      setConversations((current) => sortConversations(current.map((item) => item.id === updated.id ? updated : item)));
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法更新置顶状态"); }
  }

  async function removeConversation(conversationId: string, permanent: boolean) {
    if (permanent && !window.confirm("永久删除这段对话？删除后无法恢复。")) return;
    setMenuId(null);
    try {
      const path = permanent ? `/api/agent/conversations/${conversationId}` : `/api/agent/conversations/${conversationId}/archive`;
      await api<void>(path, { method: permanent ? "DELETE" : "POST" });
      const remaining = conversations.filter((item) => item.id !== conversationId);
      setConversations(remaining);
      setArchivedConversations((current) => current.filter((item) => item.id !== conversationId));
      if (state?.conversationId === conversationId) await load(remaining[0]?.id ?? null);
      else await load(state?.conversationId);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法处理会话"); }
  }

  async function restoreConversation(conversationId: string) {
    setMenuId(null);
    try {
      const restored = await api<Conversation>(`/api/agent/conversations/${conversationId}/restore`, { method: "POST" });
      await load(restored.id);
      setShowArchived(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法恢复会话"); }
  }

  async function saveMemory() {
    setBusy(true); setError("");
    try {
      const memory = await api<AgentMemory>("/api/agent/memory", { method: "PUT", body: JSON.stringify({ content: memoryDraft }) });
      setState((current) => current ? { ...current, memory } : current);
      setMemoryOpen(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法保存总体记忆"); }
    finally { setBusy(false); }
  }

  async function clearMemory() {
    if (!memoryDraft || !window.confirm("清空所有跨会话总体记忆？")) return;
    setBusy(true);
    try {
      await api<void>("/api/agent/memory", { method: "DELETE" });
      setMemoryDraft("");
      setState((current) => current ? { ...current, memory: { content: "", updatedAt: null } } : current);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法清空总体记忆"); }
    finally { setBusy(false); }
  }

  function submit(event: FormEvent<HTMLFormElement>) { event.preventDefault(); void send(draft); }
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); }
  }

  function attachmentPicker() {
    return <div className="relative"><input ref={uploadRef} type="file" accept=".pdf,.docx" className="hidden" onChange={(event) => void uploadResume(event.target.files?.[0])} /><button type="button" aria-label="添加资料" title="添加资料" onClick={() => void openAttachments()} className="grid size-9 place-items-center rounded-full text-stone-500 hover:bg-stone-100"><Plus className="size-4" /></button>{attachmentOpen && <div className="absolute bottom-11 left-0 z-30 max-h-80 w-72 overflow-y-auto rounded-2xl border border-stone-200 bg-white p-2 text-sm shadow-2xl"><button type="button" onClick={() => uploadRef.current?.click()} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 hover:bg-stone-100"><Upload className="size-4" />上传简历</button>{resumeOptions.map((resume) => <button type="button" key={resume.id} onClick={() => { setSelectedContext({ type: "RESUME", id: resume.id, label: resume.originalFilename }); setAttachmentOpen(false); }} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left hover:bg-stone-100"><FileSearch className="size-4 shrink-0 text-blue-500" /><span className="truncate">{resume.originalFilename}</span></button>)}{recapOptions.map((recap) => <button type="button" key={recap.id} onClick={() => { setSelectedContext({ type: "RECAP", id: recap.id, label: recap.title }); setAttachmentOpen(false); }} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left hover:bg-stone-100"><FileText className="size-4 shrink-0 text-violet-500" /><span className="truncate">{recap.title}</span></button>)}</div>}</div>;
  }

  function acknowledgeBackground(module: BackgroundModule) {
    window.localStorage.removeItem(`ardor:background-ready:${module}`);
    setBackgroundReady((current) => ({ ...current, [module]: false }));
  }

  if (!state && !error) return <main className="ardor-workbench grid min-h-screen place-items-center text-sm text-stone-500">正在唤醒 Ardor…</main>;
  const selected = conversations.find((item) => item.id === state?.conversationId);
  const visibleError = error && !retryFailures.some((failure) =>
    failure.detail === error || failure.detail.endsWith(` · ${error}`)
  ) ? error : "";

  return (
    <main className="ardor-workbench h-screen overflow-hidden text-stone-900">
      <div className="flex h-full">
        {sidebarOpen && <button aria-label="关闭会话栏" className="fixed inset-0 z-30 bg-black/20 md:hidden" onClick={() => setSidebarOpen(false)} />}
        <aside className={`fixed inset-y-0 left-0 z-40 flex w-72 flex-col border-r border-stone-200/70 bg-[#f4f2ed] p-3 transition-[width,transform,padding] duration-200 md:static md:z-auto ${sidebarOpen ? "translate-x-0" : "-translate-x-full"} ${sidebarCollapsed ? "md:w-0 md:-translate-x-full md:overflow-hidden md:border-0 md:p-0" : "md:w-72 md:translate-x-0"}`}>
          <div className="flex h-12 items-center justify-between px-2">
            <Link href="/" className="flex items-center gap-2.5 text-sm font-semibold tracking-tight">
              <span className="grid size-8 place-items-center rounded-xl bg-stone-950 text-white"><Flame className="size-4" /></span>Project Ardor
            </Link>
            <button aria-label="关闭会话栏" className="rounded-lg p-2 text-stone-500 hover:bg-white md:hidden" onClick={() => setSidebarOpen(false)}><X className="size-4" /></button>
            <button aria-label="收起会话栏" title="收起侧栏" className="hidden rounded-lg p-2 text-stone-400 hover:bg-white hover:text-stone-800 md:block" onClick={() => setSidebarCollapsed(true)}><PanelLeftClose className="size-4" /></button>
          </div>
          <button type="button" onClick={newConversation} disabled={busy} className="mt-3 flex h-11 items-center gap-2 rounded-xl border border-stone-200 bg-white px-3 text-sm font-medium shadow-sm transition hover:border-stone-300 hover:shadow disabled:opacity-50"><Plus className="size-4" />新对话</button>

          <div className="mt-5 flex-1 overflow-y-auto">
            <p className="px-2 pb-2 text-[11px] font-medium uppercase tracking-[0.14em] text-stone-400">最近对话</p>
            <div className="space-y-1">
              {conversations.map((conversation) => (
                <div key={conversation.id} className={`group relative rounded-xl ${conversation.id === state?.conversationId ? "bg-white shadow-sm" : "hover:bg-white/70"}`}>
                  <button type="button" onClick={() => selectConversation(conversation.id)} className="flex w-full items-center gap-2 px-3 py-2.5 pr-10 text-left text-sm">{conversation.pinned && <Pin className="size-3.5 shrink-0 fill-stone-700 text-stone-700" />}<span className="truncate">{conversation.title}</span></button>
                  <button type="button" aria-label="会话操作" onClick={() => setMenuId(menuId === conversation.id ? null : conversation.id)} className="absolute right-1.5 top-1.5 rounded-lg p-2 text-stone-400 opacity-70 hover:bg-stone-100 hover:text-stone-700 md:opacity-0 md:group-hover:opacity-100"><MoreHorizontal className="size-4" /></button>
                  {menuId === conversation.id && (
                    <div className="absolute right-2 top-10 z-20 w-36 rounded-xl border border-stone-200 bg-white p-1.5 text-sm shadow-xl">
                      <button onClick={() => togglePin(conversation)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Pin className={`size-3.5 ${conversation.pinned ? "fill-current" : ""}`} />{conversation.pinned ? "取消置顶" : "置顶"}</button>
                      <button onClick={() => renameConversation(conversation)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Pencil className="size-3.5" />重命名</button>
                      <button onClick={() => removeConversation(conversation.id, false)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Archive className="size-3.5" />归档</button>
                      <button onClick={() => removeConversation(conversation.id, true)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-red-600 hover:bg-red-50"><Trash2 className="size-3.5" />永久删除</button>
                    </div>
                  )}
                </div>
              ))}
              {conversations.length === 0 && <p className="px-2 py-3 text-xs leading-5 text-stone-400">新对话会出现在这里。</p>}
            </div>
            {archivedConversations.length > 0 && (
              <div className="mt-4 border-t border-stone-200/70 pt-3">
                <button onClick={() => setShowArchived((current) => !current)} className="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-xs text-stone-500 hover:bg-white/70"><Archive className="size-3.5" />已归档<span className="ml-auto text-stone-400">{archivedConversations.length}</span></button>
                {showArchived && <div className="mt-1 space-y-1">
                  {archivedConversations.map((conversation) => (
                    <div key={conversation.id} className="group flex items-center gap-1 rounded-xl px-2 py-1 hover:bg-white/70">
                      <span className="min-w-0 flex-1 truncate px-1 text-xs text-stone-500">{conversation.title}</span>
                      <button title="恢复" onClick={() => restoreConversation(conversation.id)} className="rounded-lg p-2 text-stone-400 hover:bg-white hover:text-stone-800"><RotateCcw className="size-3.5" /></button>
                      <button title="永久删除" onClick={() => removeConversation(conversation.id, true)} className="rounded-lg p-2 text-stone-400 hover:bg-red-50 hover:text-red-600"><Trash2 className="size-3.5" /></button>
                    </div>
                  ))}
                </div>}
              </div>
            )}
          </div>

        </aside>

        <section className="relative flex min-w-0 flex-1 flex-col">
          <header className="relative z-10 flex h-16 shrink-0 items-center justify-between px-4 md:px-6">
            <div className="flex min-w-0 items-center gap-2">
              <button aria-label="打开会话栏" onClick={() => setSidebarOpen(true)} className="rounded-xl p-2 text-stone-500 hover:bg-white md:hidden"><PanelLeftOpen className="size-5" /></button>
              {sidebarCollapsed && <button aria-label="展开会话栏" title="展开侧栏" onClick={() => setSidebarCollapsed(false)} className="hidden rounded-xl p-2 text-stone-500 hover:bg-white md:block"><PanelLeftOpen className="size-5" /></button>}
              <h1 className="truncate text-sm font-medium text-stone-600">{selected?.title ?? "Ardor"}</h1>
            </div>
            <div className="relative">
              <button type="button" aria-expanded={workspaceOpen} onClick={() => setWorkspaceOpen((open) => !open)} className={`relative flex h-9 items-center gap-2 rounded-full border px-3 text-xs font-medium transition ${workspaceOpen ? "border-stone-300 bg-white text-stone-900 shadow-sm" : "border-stone-200/80 bg-white/55 text-stone-600 hover:bg-white"}`}><LayoutGrid className="size-3.5" />工作区<ChevronDown className={`size-3.5 transition-transform ${workspaceOpen ? "rotate-180" : ""}`} />{Object.values(backgroundReady).some(Boolean) && <span className="absolute -right-0.5 -top-0.5 size-2 rounded-full bg-red-500 ring-2 ring-white" />}</button>
              {workspaceOpen && <div className="absolute right-0 top-12 z-30 w-64 rounded-2xl border border-white/80 bg-[#f8f6f1]/95 p-2.5 shadow-[0_24px_70px_rgba(42,35,27,0.18)] backdrop-blur-xl">
                <div className="mb-2 flex items-center justify-between px-1"><span className="text-[10px] font-medium uppercase tracking-[0.14em] text-stone-400">工作区</span><button type="button" aria-label="收起工作区" onClick={() => setWorkspaceOpen(false)} className="grid size-7 place-items-center rounded-lg text-stone-400 hover:bg-white hover:text-stone-700"><X className="size-3.5" /></button></div>
                <div className="grid grid-cols-2 gap-1.5">
                  <Link href="/app/resumes" onClick={() => acknowledgeBackground("resumes")} className="relative flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-xs text-stone-700 transition hover:bg-white"><FileSearch className="size-4 text-blue-500" /><span>简历分析</span>{backgroundReady.resumes && <span className="absolute right-2.5 top-2.5 size-2 rounded-full bg-red-500" />}</Link>
                  <Link href="/app/interviews" className="flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-xs text-stone-700 transition hover:bg-white"><MessageSquareText className="size-4 text-orange-500" /><span>模拟面试</span></Link>
                  <Link href="/app/recaps" onClick={() => acknowledgeBackground("recaps")} className="relative flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-xs text-stone-700 transition hover:bg-white"><FileText className="size-4 text-emerald-600" /><span>面经</span>{backgroundReady.recaps && <span className="absolute right-2.5 top-2.5 size-2 rounded-full bg-red-500" />}</Link>
                  <Link href="/app/cards" className="flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-xs text-stone-700 transition hover:bg-white"><BrainCircuit className="size-4 text-violet-500" /><span>记忆卡</span></Link>
                  <Link href="/app/calendar" className="flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-xs text-stone-700 transition hover:bg-white"><CalendarDays className="size-4 text-rose-500" /><span>日历</span></Link>
                  <Link href="/app/knowledge" onClick={() => acknowledgeBackground("knowledge")} className="relative flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-xs text-stone-700 transition hover:bg-white"><BookOpenText className="size-4 text-cyan-600" /><span>知识库</span>{backgroundReady.knowledge && <span className="absolute right-2.5 top-2.5 size-2 rounded-full bg-red-500" />}</Link>
                  <button onClick={() => { setWorkspaceOpen(false); setMemoryOpen(true); }} className="relative flex min-h-16 flex-col justify-between rounded-xl bg-white/65 p-2.5 text-left text-xs text-stone-700 transition hover:bg-white"><Brain className="size-4 text-violet-500" /><span>总体记忆</span><span className={`absolute right-2.5 top-2.5 size-1.5 rounded-full ${memoryDraft ? "bg-violet-500" : "bg-stone-300"}`} /></button>
                  <Link href="/app/settings" className="col-span-2 flex items-center gap-2 rounded-xl px-2.5 py-2 text-xs text-stone-500 hover:bg-white/70 hover:text-stone-800"><Settings2 className="size-3.5" />设置</Link>
                </div>
              </div>}
            </div>
          </header>

          {!state?.llmConfigured && <div className="mx-4 mt-2 flex items-center justify-between gap-4 rounded-2xl border border-amber-200 bg-amber-50/90 px-4 py-3 text-sm text-amber-900 md:mx-8"><span>配置一个支持 Tool Calling 的模型后，Ardor 才能开始工作。</span><Link href="/app/settings" className="shrink-0 font-medium underline underline-offset-4">去设置</Link></div>}
          {notice && <button type="button" onClick={() => setNotice("")} className="mx-4 mt-2 rounded-2xl bg-emerald-50/90 px-4 py-3 text-left text-sm text-emerald-800 md:mx-8">{notice}</button>}

          <div className="relative flex-1 overflow-y-auto">
            {messages.length === 0 ? (
              <div className={`relative grid min-h-full place-items-center overflow-hidden px-4 pb-12 ${leavingEmptyState ? "ardor-empty-exit" : "ardor-empty-enter"}`}>
                <div className="pointer-events-none absolute inset-x-[6%] bottom-[-28%] h-[82%] rounded-[50%] bg-[radial-gradient(circle_at_28%_45%,rgba(255,107,177,0.58),transparent_43%),radial-gradient(circle_at_72%_38%,rgba(83,125,255,0.58),transparent_47%),radial-gradient(circle_at_50%_76%,rgba(149,92,246,0.46),transparent_58%)] blur-3xl" />
                <div className="relative z-10 mx-auto w-full max-w-3xl text-center">
                  <h2 className="text-4xl font-semibold tracking-[-0.04em] text-stone-950 md:text-5xl">What should we build{state?.displayName?.trim() ? `, ${state.displayName.trim()}` : ""}?</h2>
                  {(visibleError || retryFailures.length > 0) && <div className="mx-auto mt-5 max-w-2xl rounded-2xl bg-red-50/90 px-4 py-3 text-left text-sm text-red-700 shadow-sm">{visibleError && <p>{visibleError}</p>}{retryFailures.length > 0 && <ul className={visibleError ? "mt-2 space-y-1 text-xs text-red-600" : "space-y-1 text-xs text-red-600"}>{retryFailures.map((failure, index) => <li key={`${failure.label}-${index}`}>{failure.label}：{failure.detail}</li>)}</ul>}</div>}
                  <form onSubmit={submit} className="mx-auto mt-10 rounded-[1.75rem] border border-white/70 bg-white/88 p-2 text-left shadow-[0_20px_70px_rgba(42,35,27,0.16)] backdrop-blur-xl transition-[border-color,box-shadow] duration-200 focus-within:border-stone-300/80 focus-within:shadow-[0_22px_76px_rgba(42,35,27,0.18),0_0_0_4px_rgba(255,255,255,0.42)]">
                    {selectedContext && <div className="mx-3 mt-2 inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{selectedContext.label}</span><button type="button" aria-label="移除资料" onClick={() => setSelectedContext(null)}><X className="size-3.5" /></button></div>}
                    <textarea aria-label="给 Ardor 发消息" value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={busy || !state?.llmConfigured} rows={3} placeholder={state?.llmConfigured ? typedHint : "请先完成模型设置"} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
                    <div className="flex items-center justify-between gap-3 px-2 pb-1">{attachmentPicker()}<div className="flex items-center gap-3">{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}<Button aria-label="发送" disabled={busy || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div></div>
                  </form>
                </div>
              </div>
            ) : (
              <div aria-live="polite" className="ardor-chat-enter mx-auto w-full max-w-3xl space-y-8 px-4 py-10 md:px-8">
                {messages.map((message) => (
                  <article key={message.id} className={`flex gap-3 ${message.role === "USER" ? "justify-end" : "justify-start"}`}>
                    {message.role === "USER" ? (
                      <div className="max-w-[86%] whitespace-pre-wrap rounded-3xl bg-stone-200/65 px-5 py-3 text-sm leading-7">{message.content}</div>
                    ) : (
                      <div className="max-w-full py-0.5 text-sm leading-7 text-stone-800">
                        <div className="ardor-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown></div>
                        {savedRunTrace(message.runTrace) && (() => { const trace = savedRunTrace(message.runTrace)!; return <details className="mt-3 max-w-xl rounded-2xl border border-white/70 bg-white/45 px-4 py-3 text-stone-600 shadow-sm"><summary className="cursor-pointer list-none text-xs font-medium">Ardor 已完成 <span className="float-right font-normal tabular-nums text-stone-400">{formatElapsed(trace.elapsedMs)}</span></summary><div className="mt-3 space-y-2 border-l border-stone-200 pl-4">{trace.steps.map((step, index) => <div key={`${step.label}-${index}`} className="flex items-center gap-2 text-xs"><CheckCircle2 className={`size-3.5 ${step.status === "FAILED" ? "text-rose-500" : "text-emerald-500"}`} /><span>{step.label}</span><span className="ml-auto tabular-nums text-stone-400">{formatElapsed(step.elapsedMs)}</span></div>)}</div></details>; })()}
                      </div>
                    )}
                  </article>
                ))}
                {(runSteps.length > 0 || busy || retryFailures.length > 0) && <section className="max-w-xl rounded-2xl border border-white/70 bg-white/55 px-4 py-3 text-sm text-stone-600 shadow-sm backdrop-blur-md">
                  <button type="button" onClick={() => setTraceOpen((open) => !open)} className="flex w-full items-center gap-2 text-left">
                    {traceOpen ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                    <span className="font-medium text-stone-700">{busy ? "Ardor 正在工作" : error ? "Ardor 未完成" : "Ardor 已完成"}</span>
                    <span className="ml-auto text-xs tabular-nums text-stone-400">{formatElapsed(runElapsed)}</span>
                  </button>
                  {traceOpen && <div className="mt-3 space-y-2 border-l border-stone-200 pl-4">
                    {runSteps.map((step) => <div key={step.key} className="flex items-center gap-2 text-xs"><CheckCircle2 className={`size-3.5 ${step.done ? "text-emerald-500" : "animate-pulse text-violet-500"}`} /><span>{step.label}</span><span className="ml-auto tabular-nums text-stone-400">{step.done ? formatElapsed(step.elapsedMs) : "进行中"}</span></div>)}
                    {retryFailures.map((failure, index) => <div key={`${failure.label}-${index}`} className="flex items-start gap-2 text-xs text-amber-700"><RotateCcw className="mt-0.5 size-3.5 shrink-0" /><span><span className="font-medium">{failure.label}</span> · {failure.detail}</span></div>)}
                    {busy && retryAttempt > 0 && <div className="flex items-center gap-2 text-xs text-violet-600"><RotateCcw className="size-3.5 animate-spin" /><span>连接波动，正在进行第 {retryAttempt}/{MAX_MESSAGE_RETRIES} 次重试</span></div>}
                  </div>}
                </section>}
                <div ref={endRef} />
              </div>
            )}
          </div>

          {messages.length > 0 && <div className="ardor-composer-enter relative z-10 shrink-0 px-4 pb-5 md:px-8">
            {visibleError && <div className="mx-auto mb-3 max-w-3xl rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700 shadow-sm"><p>{visibleError}</p></div>}
            <form onSubmit={submit} className="mx-auto max-w-3xl rounded-[1.75rem] border border-stone-200/80 bg-white p-2 shadow-[0_20px_70px_rgba(42,35,27,0.14)] transition-[border-color,box-shadow] duration-200 focus-within:border-stone-300/90 focus-within:shadow-[0_22px_76px_rgba(42,35,27,0.16),0_0_0_4px_rgba(255,255,255,0.38)]">
              {selectedContext && <div className="mx-3 mt-2 inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{selectedContext.label}</span><button type="button" aria-label="移除资料" onClick={() => setSelectedContext(null)}><X className="size-3.5" /></button></div>}
              <textarea aria-label="给 Ardor 发消息" value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={busy || !state?.llmConfigured} rows={2} placeholder={state?.llmConfigured ? "Message Ardor…" : "请先完成模型设置"} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
              <div className="flex items-center justify-between gap-3 px-2 pb-1"><div className="flex items-center gap-2">{attachmentPicker()}{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}</div><Button aria-label="发送" disabled={busy || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div>
            </form>
          </div>}
        </section>
      </div>

      {memoryOpen && (
        <div className="fixed inset-0 z-50 grid place-items-center bg-black/25 p-4 backdrop-blur-sm" onMouseDown={(event) => { if (event.target === event.currentTarget) setMemoryOpen(false); }}>
          <section className="w-full max-w-xl rounded-3xl border border-stone-200 bg-[#fffefa] p-5 shadow-2xl md:p-6">
            <div className="flex items-start justify-between gap-4">
              <div><div className="flex items-center gap-2 text-lg font-semibold"><Brain className="size-5 text-violet-500" />总体记忆</div><p className="mt-1 text-sm leading-6 text-stone-500">每行一条，跨所有对话共享。Ardor 新增或删除时会在对话里告诉你。</p></div>
              <button aria-label="关闭" onClick={() => setMemoryOpen(false)} className="rounded-xl p-2 text-stone-400 hover:bg-stone-100"><X className="size-5" /></button>
            </div>
            <textarea value={memoryDraft} onChange={(event) => setMemoryDraft(event.target.value)} maxLength={4000} rows={12} placeholder={"例如：\n- 目标岗位：Java 后端工程师\n- 偏好简洁、直接的反馈\n- 近期目标：两个月内完成换工作"} className="mt-5 w-full resize-none rounded-2xl border border-stone-200 bg-white p-4 text-sm leading-6 outline-none transition focus:border-violet-300 focus:ring-4 focus:ring-violet-100" />
            <div className="mt-2 flex items-center justify-between text-xs text-stone-400"><span>不应保存 API Key、密码等敏感信息</span><span>{memoryDraft.length}/4000</span></div>
            <div className="mt-5 flex items-center justify-between gap-3">
              <button onClick={clearMemory} disabled={busy || !memoryDraft} className="rounded-xl px-3 py-2 text-sm text-red-600 hover:bg-red-50 disabled:opacity-40">清空记忆</button>
              <div className="flex gap-2"><button onClick={() => setMemoryOpen(false)} className="rounded-xl px-4 py-2 text-sm hover:bg-stone-100">取消</button><button onClick={saveMemory} disabled={busy} className="rounded-xl bg-stone-950 px-5 py-2 text-sm font-medium text-white hover:bg-stone-800 disabled:opacity-50">保存</button></div>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
