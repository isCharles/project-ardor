"use client";

import {
  Archive, ArrowUp, BookOpenText, Brain, BrainCircuit, Check, CheckCircle2, ChevronDown, ChevronRight, FileSearch, FileText, Flame,
  MoreHorizontal, PanelLeftOpen, Paperclip, Pencil, Pin, Plus, RotateCcw, Target, Trash2, Upload, X,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { Button } from "@/components/ui/button";
import { ApiError, api, streamApi } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { useTypewriter } from "@/lib/use-typewriter";

type SelectedContext = { type: "RESUME" | "RECAP" | "KNOWLEDGE" | "LEARNING"; id: string; label: string };
type AgentMessage = { id: string; conversationId: string; role: "USER" | "ASSISTANT"; content: string; contextReferences?: SelectedContext[]; runTrace?: string | null; createdAt: string };
type Conversation = { id: string; title: string; pinned: boolean; createdAt: string; updatedAt: string };
type AgentMemory = { content: string; updatedAt: string | null };
type RetryFailure = { label: string; detail: string };
type AgentStreamEvent = { type: "status" | "delta" | "tool_start" | "tool_end" | "confirm" | "done" | "error"; content: string | null; toolName: string | null; label: string | null; elapsedMs: number | null; conversationId: string | null; messageId: string | null; retryable: boolean | null; errorCode: string | null; confirmation: AgentConfirmation | null };
type AgentRun = { id: string; conversationId: string; message: string; status: "RUNNING" | "COMPLETED" | "FAILED" | "INTERRUPTED"; label: string; errorCode: string | null; errorMessage: string | null; retryable: boolean; createdAt: string; updatedAt: string };
/* A deletion Ardor has proposed. Nothing is gone until the user presses the
   button, and pressing it is an ordinary authenticated DELETE from here — the
   model never gets to destroy anything on its own say-so. */
type AgentConfirmation = { kind: string; targetId: string | null; label: string; detail: string; endpoint: string };
type PendingConfirmation = AgentConfirmation & { state: "PENDING" | "DELETING" | "DONE" | "DISMISSED" | "FAILED"; error?: string };
type RunStep = { key: string; label: string; elapsedMs: number; done: boolean };
type ResumeOption = { id: string; originalFilename: string };
type RecapOption = { id: string; title: string };
type KnowledgeOption = { id: string; title: string };
type LearningOption = { id: string; concept: string };
type ApplicationReminder = { weeklyCount: number; weeklyGoal: number; reminderDue: boolean };
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
const typewriterExamplesZh = [
  "请 Ardor 分析我最新的简历",
  "告诉 Ardor 我在找 Java 后端岗位",
  "请 Ardor 开始一场模拟面试",
  "告诉 Ardor 我想改进什么",
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
  const { locale, t } = useLocale();
  const [state, setState] = useState<AgentState | null>(null);
  const [messages, setMessages] = useState<AgentMessage[]>([]);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [archivedConversations, setArchivedConversations] = useState<Conversation[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [runningRun, setRunningRun] = useState<AgentRun | null>(null);
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [retryFailures, setRetryFailures] = useState<RetryFailure[]>([]);
  const [runSteps, setRunSteps] = useState<RunStep[]>([]);
  const [confirmations, setConfirmations] = useState<PendingConfirmation[]>([]);
  const [traceOpen, setTraceOpen] = useState(true);
  const [runElapsed, setRunElapsed] = useState(0);
  const [leavingEmptyState, setLeavingEmptyState] = useState(false);
  const [error, setError] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const typedHint = useTypewriter(locale === "en" ? typewriterExamples : typewriterExamplesZh, messages.length === 0);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryDraft, setMemoryDraft] = useState("");
  const [selectedContexts, setSelectedContexts] = useState<SelectedContext[]>([]);
  const [attachmentOpen, setAttachmentOpen] = useState(false);
  const [resumeOptions, setResumeOptions] = useState<ResumeOption[]>([]);
  const [recapOptions, setRecapOptions] = useState<RecapOption[]>([]);
  const [knowledgeOptions, setKnowledgeOptions] = useState<KnowledgeOption[]>([]);
  const [learningOptions, setLearningOptions] = useState<LearningOption[]>([]);
  const [notice, setNotice] = useState("");
  const [applicationReminder, setApplicationReminder] = useState<ApplicationReminder | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);
  const [showArchived, setShowArchived] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);
  const runStartedAt = useRef(0);
  const uploadRef = useRef<HTMLInputElement>(null);
  const runningRunId = runningRun?.id;

  useEffect(() => {
    if (new URLSearchParams(window.location.search).has("memory")) setMemoryOpen(true);
    const openMemory = () => setMemoryOpen(true);
    const closeDrawer = () => setSidebarOpen(false);
    window.addEventListener("ardor:open-memory", openMemory);
    window.addEventListener("ardor:close-chat-drawer", closeDrawer);
    return () => {
      window.removeEventListener("ardor:open-memory", openMemory);
      window.removeEventListener("ardor:close-chat-drawer", closeDrawer);
    };
  }, []);

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
          result = { ...result, conversationId: null, messages: [] };
          const prefill = params.get("prefill") ?? "";
          if (prefill) window.sessionStorage.setItem(draftStorageKey(null), prefill);
          const contextType = params.get("contextType"); const contextId = params.get("contextId"); const contextLabel = params.get("contextLabel");
          if ((contextType === "RESUME" || contextType === "RECAP" || contextType === "KNOWLEDGE" || contextType === "LEARNING") && contextId && contextLabel) setSelectedContexts([{ type: contextType, id: contextId, label: contextLabel }]);
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
    let active = true;
    const refresh = () => {
      if (document.visibilityState === "hidden") return;
      void api<ApplicationReminder>("/api/applications/rhythm")
        .then((result) => { if (active) setApplicationReminder(result); })
        .catch(() => undefined);
    };
    refresh();
    const timer = window.setInterval(refresh, 60_000);
    window.addEventListener("focus", refresh);
    return () => { active = false; window.clearInterval(timer); window.removeEventListener("focus", refresh); };
  }, []);

  async function dismissApplicationReminder() {
    try { setApplicationReminder(await api<ApplicationReminder>("/api/applications/rhythm/reminder/dismiss", { method: "POST" })); }
    catch { /* Keep the reminder visible if the dismissal was not saved. */ }
  }

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: "smooth" }); }, [messages, busy]);
  useEffect(() => {
    const conversationId = state?.conversationId;
    if (!conversationId || busy) return;
    let stopped = false;
    let timer: number | undefined;
    let previousStatus: AgentRun["status"] | null = runningRunId ? "RUNNING" : null;
    const check = async () => {
      try {
        const run = await api<AgentRun>(`/api/agent/runs/latest?conversationId=${encodeURIComponent(conversationId)}`);
        if (stopped) return;
        if (run.status === "RUNNING") {
          runStartedAt.current = new Date(run.createdAt).getTime();
          setRunningRun(run);
          previousStatus = "RUNNING";
          timer = window.setTimeout(() => void check(), 3000);
        } else {
          setRunningRun(null);
          if (previousStatus === "RUNNING") {
            previousStatus = run.status;
            if (run.status === "COMPLETED") {
              window.sessionStorage.removeItem(draftStorageKey(conversationId));
              await load(conversationId);
            } else {
              setError(run.errorMessage ?? "请求未完成，请检查操作结果后再试");
              setDraft((current) => current || readSavedDraft(conversationId));
            }
          } else if (readSavedDraft(conversationId) === run.message) {
            if (run.status === "COMPLETED") {
              window.sessionStorage.removeItem(draftStorageKey(conversationId));
              setDraft((current) => current === run.message ? "" : current);
            } else {
              setError(run.errorMessage ?? "请求未完成，请检查操作结果后再试");
            }
          }
        }
      } catch (reason) {
        if (reason instanceof ApiError && reason.status === 404 && !stopped) setRunningRun(null);
        else if (!stopped && previousStatus === "RUNNING") timer = window.setTimeout(() => void check(), 5000);
      }
    };
    void check();
    return () => { stopped = true; if (timer) window.clearTimeout(timer); };
  }, [state?.conversationId, busy, load, runningRunId]);
  useEffect(() => {
    if (!busy && !runningRunId) return;
    const timer = window.setInterval(() => setRunElapsed(Date.now() - runStartedAt.current), 250);
    return () => window.clearInterval(timer);
  }, [busy, runningRunId]);

  async function selectConversation(conversationId: string) {
    if (busy) return;
    if (conversationId === state?.conversationId) { setSidebarOpen(false); return; }
    setError(""); setRunningRun(null); setConfirmations([]); setMenuId(null); setSidebarOpen(false);
    try { await load(conversationId); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法载入会话"); }
  }

  async function newConversation() {
    if (busy) return;
    setError("");
    setRunningRun(null);
    setRetryFailures([]);
    setRunSteps([]);
    setConfirmations([]);
    setRunElapsed(0);
    setMessages([]);
    setSelectedContexts([]);
    setState((current) => current ? { ...current, conversationId: null, messages: [] } : current);
    setDraft(readSavedDraft(null));
    window.history.replaceState(null, "", "/app");
    setSidebarOpen(false);
  }

  /* The button calls the same REST endpoint the corresponding page would call.
     No agent round trip, no confirmation phrase to retype. */
  async function confirmDeletion(pending: PendingConfirmation) {
    setConfirmations((current) => current.map((item) => item.endpoint === pending.endpoint ? { ...item, state: "DELETING", error: undefined } : item));
    try {
      await api<void>(pending.endpoint, { method: "DELETE" });
      setConfirmations((current) => current.map((item) => item.endpoint === pending.endpoint ? { ...item, state: "DONE" } : item));
    } catch (reason) {
      setConfirmations((current) => current.map((item) => item.endpoint === pending.endpoint
        ? { ...item, state: "FAILED", error: reason instanceof Error ? reason.message : "删除失败" }
        : item));
    }
  }

  async function send(text: string) {
    const message = text.trim();
    if (!message || busy || runningRun) return;
    if (message.length > MAX_MESSAGE_LENGTH) {
      setError(`这条消息有 ${message.length.toLocaleString()} 个字符，单条最多 ${MAX_MESSAGE_LENGTH.toLocaleString()} 个字符。请删减或分段发送。`);
      return;
    }
    const optimisticId = `pending-user-message-${crypto.randomUUID()}`;
    let conversationId = state?.conversationId ?? null;
    let acceptedRun = false;
    let savedDraftKey = draftStorageKey(conversationId);
    window.sessionStorage.setItem(savedDraftKey, message);
    setError(""); setRetryFailures([]); setRunSteps([]); setConfirmations([]); setTraceOpen(true); setRunElapsed(0); runStartedAt.current = Date.now(); setBusy(true);
    try {
      if (!conversationId) {
        const conversation = await api<Conversation>("/api/agent/conversations", { method: "POST" });
        conversationId = conversation.id;
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
      const sentContexts = [...selectedContexts];
      const optimistic: AgentMessage = { id: optimisticId, conversationId: activeConversationId, role: "USER", content: message, contextReferences: sentContexts, createdAt: "" };
      if (messages.length === 0) {
        setLeavingEmptyState(true);
        await new Promise((resolve) => window.setTimeout(resolve, 260));
      }
      setDraft("");
      setMessages((current) => [...current, optimistic]);
      setLeavingEmptyState(false);
      const pendingAssistantId = `pending-assistant-${crypto.randomUUID()}`;
      let assistant: AgentMessage | null = null;
      let requestId = crypto.randomUUID();
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
          await streamApi<AgentStreamEvent>("/api/agent/messages/stream", { requestId, conversationId: activeConversationId, message, contextReferences: sentContexts.map(({ type, id }) => ({ type, id })) }, (event) => {
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
            } else if (event.type === "confirm" && event.confirmation) {
              const proposal = event.confirmation;
              setConfirmations((current) => current.some((item) => item.endpoint === proposal.endpoint)
                ? current
                : [...current, { ...proposal, state: "PENDING" }]);
            } else if (event.type === "error") streamFailure = event;
          });
          if (streamFailure) {
            const failure = streamFailure as AgentStreamEvent;
            throw Object.assign(new Error(failure.content ?? "Agent 暂时无法回复"), { retryable: failure.retryable, code: failure.errorCode });
          }
          if (!assistant) throw new Error("连接结束但未收到完整回复");
          break;
        } catch (reason) {
          let recorded: AgentRun | null = null;
          try { recorded = await api<AgentRun>(`/api/agent/runs/${requestId}`); }
          catch (lookupError) {
            if (!(lookupError instanceof ApiError && lookupError.status === 404)) {
              throw new Error("暂时无法确认请求是否仍在执行。请刷新查看状态，不要重复发送。", { cause: lookupError });
            }
          }
          if (recorded?.status === "COMPLETED") {
            window.sessionStorage.removeItem(savedDraftKey);
            await load(activeConversationId);
            return;
          }
          if (recorded?.status === "RUNNING") {
            acceptedRun = true;
            setRunningRun(recorded);
            setMessages((current) => current.filter((item) => item.id !== pendingAssistantId));
            return;
          }
          const serverRetryable = recorded ? recorded.retryable : reason instanceof ApiError ? reason.body.retryable === true : (reason as { retryable?: boolean }).retryable !== false;
          const retryable = serverRetryable && !receivedToolEvent && !receivedDelta && recorded?.status !== "INTERRUPTED";
          const label = retry === 0 ? "首次请求" : `第 ${retry}/${MAX_MESSAGE_RETRIES} 次重试`;
          const detail = reason instanceof ApiError
            ? `${reason.body.code ?? `HTTP ${reason.status}`} · ${reason.message}`
            : reason instanceof Error ? reason.message : "浏览器无法连接 Ardor 服务";
          setRetryFailures((current) => [...current, { label, detail }]);
          if (!retryable || retry === MAX_MESSAGE_RETRIES) {
            if (recorded?.errorMessage) throw new Error(recorded.errorMessage);
            throw reason;
          }
          // A 404 can race with a slow first POST. Reuse its ID so a late arrival cannot execute twice.
          if (recorded?.status === "FAILED") requestId = crypto.randomUUID();
        }
      }
      const completedAssistant = assistant as AgentMessage | null;
      if (!completedAssistant) throw new Error("Agent 暂时无法回复");
      window.sessionStorage.removeItem(savedDraftKey);
      setSelectedContexts([]);
      await load(completedAssistant.conversationId);
      setRunSteps([]);
      setRetryFailures([]);
      setRunElapsed(0);
    } catch (reason) {
      // An empty conversation may still have an in-flight run. Never delete it on transport failure.
      setMessages((current) => current.filter((item) => item.id !== optimisticId && !item.id.startsWith("pending-assistant-")));
      setDraft(message);
      window.sessionStorage.setItem(savedDraftKey, message);
      setError(reason instanceof Error ? reason.message : "Agent 暂时无法回复");
    } finally { setRetryAttempt(0); setLeavingEmptyState(false); setBusy(false); if (acceptedRun) setError(""); }
  }

  function updateDraft(value: string) {
    setDraft(value);
    const key = draftStorageKey(state?.conversationId);
    if (value) window.sessionStorage.setItem(key, value);
    else window.sessionStorage.removeItem(key);
  }

  async function openAttachments() {
    setAttachmentOpen((open) => !open);
    if (resumeOptions.length || recapOptions.length || knowledgeOptions.length || learningOptions.length) return;
    try {
      const [resumes, recaps, knowledge, learning] = await Promise.all([
        api<ResumeOption[]>("/api/resumes"), api<RecapOption[]>("/api/interview-recaps"),
        api<KnowledgeOption[]>("/api/knowledge/documents"), api<LearningOption[]>("/api/learning-plans"),
      ]);
      setResumeOptions(resumes); setRecapOptions(recaps); setKnowledgeOptions(knowledge); setLearningOptions(learning);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法载入资料"); }
  }

  async function uploadResume(file?: File) {
    if (!file) return;
    const body = new FormData(); body.append("file", file);
    setBusy(true); setError("");
    try {
      const resume = await api<ResumeOption>("/api/resumes", { method: "POST", body });
      setResumeOptions((items) => [resume, ...items]);
      const context: SelectedContext = { type: "RESUME", id: resume.id, label: resume.originalFilename };
      setSelectedContexts((items) => [...items.filter((item) => !(item.type === context.type && item.id === context.id)), context].slice(-5));
      setAttachmentOpen(false); setNotice("简历已加入资料库，并附加到这条消息。你可以让 Ardor 分析或管理它。");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "简历上传失败"); }
    finally { setBusy(false); if (uploadRef.current) uploadRef.current.value = ""; }
  }

  function toggleContext(context: SelectedContext) {
    setSelectedContexts((items) => items.some((item) => item.type === context.type && item.id === context.id)
      ? items.filter((item) => !(item.type === context.type && item.id === context.id))
      : [...items, context].slice(-5));
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
    const option = (context: SelectedContext, icon: React.ReactNode) => <button type="button" key={`${context.type}-${context.id}`} onClick={() => toggleContext(context)} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left hover:bg-stone-100">{icon}<span className="min-w-0 flex-1 truncate">{context.label}</span>{selectedContexts.some((item) => item.type === context.type && item.id === context.id) && <Check className="size-3.5 text-violet-600" />}</button>;
    return <div className="relative"><input ref={uploadRef} type="file" accept=".pdf,.docx" className="hidden" onChange={(event) => void uploadResume(event.target.files?.[0])} /><button type="button" aria-label="添加资料" title="添加资料" onClick={() => void openAttachments()} className="grid size-9 place-items-center rounded-full text-stone-500 hover:bg-stone-100"><Plus className="size-4" /></button>{attachmentOpen && <div className="absolute bottom-11 left-0 z-30 max-h-96 w-80 overflow-y-auto rounded-2xl border border-stone-200 bg-white p-2 text-sm shadow-2xl"><button type="button" onClick={() => uploadRef.current?.click()} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 hover:bg-stone-100"><Upload className="size-4" />上传简历</button>{resumeOptions.map((resume) => option({ type: "RESUME", id: resume.id, label: resume.originalFilename }, <FileSearch className="size-4 shrink-0 text-blue-500" />))}{recapOptions.map((recap) => option({ type: "RECAP", id: recap.id, label: recap.title }, <FileText className="size-4 shrink-0 text-violet-500" />))}{knowledgeOptions.map((document) => option({ type: "KNOWLEDGE", id: document.id, label: document.title }, <BookOpenText className="size-4 shrink-0 text-cyan-600" />))}{learningOptions.map((plan) => option({ type: "LEARNING", id: plan.id, label: plan.concept }, <BrainCircuit className="size-4 shrink-0 text-rose-500" />))}</div>}</div>;
  }

  if (!state && !error) return <main className="ardor-workbench grid min-h-screen place-items-center text-sm text-stone-500">{t("Waking Ardor…", "正在唤醒 Ardor…")}</main>;
  const selected = conversations.find((item) => item.id === state?.conversationId);
  const visibleError = error && !retryFailures.some((failure) =>
    failure.detail === error || failure.detail.endsWith(` · ${error}`)
  ) ? error : "";

  return (
    <main className="ardor-workbench h-screen overflow-hidden text-stone-900">
      <div className="flex h-full">
        {sidebarOpen && <button aria-label={t("Close conversations", "关闭会话栏")} className="fixed inset-0 z-20 bg-black/20" onClick={() => setSidebarOpen(false)} />}
        <aside aria-hidden={!sidebarOpen} inert={!sidebarOpen} className={`fixed inset-y-0 left-16 z-40 flex w-72 flex-col border-r border-stone-200/70 bg-[#f8f6f2] p-3 shadow-2xl transition-transform duration-200 md:left-[4.25rem] ${sidebarOpen ? "translate-x-0" : "-translate-x-[calc(100%+5rem)]"}`}>
          <div className="flex h-12 items-center justify-between px-2">
            <Link href="/app" className="flex items-center gap-2.5 text-sm font-semibold tracking-tight">
              <span className="grid size-8 place-items-center rounded-xl bg-stone-950 text-white"><Flame className="size-4" /></span>Project Ardor
            </Link>
            <button aria-label={t("Close conversations", "关闭会话栏")} className="rounded-lg p-2 text-stone-500 hover:bg-white" onClick={() => setSidebarOpen(false)}><X className="size-4" /></button>
          </div>
          <button type="button" onClick={newConversation} disabled={busy} className="mt-3 flex h-11 items-center gap-2 rounded-xl border border-stone-200 bg-white px-3 text-sm font-medium shadow-sm transition hover:border-stone-300 hover:shadow disabled:opacity-50"><Plus className="size-4" />{t("New chat", "新对话")}</button>

          <div className="mt-4 min-h-0 flex-1 overflow-y-auto">
            <p className="px-2 pb-2 text-[11px] font-medium uppercase tracking-[0.14em] text-stone-400">{t("Recent chats", "最近对话")}</p>
            <div className="space-y-1">
              {conversations.map((conversation) => (
                <div key={conversation.id} className={`group relative rounded-xl ${conversation.id === state?.conversationId ? "bg-white shadow-sm" : "hover:bg-white/70"}`}>
                  <button type="button" onClick={() => selectConversation(conversation.id)} className="flex w-full items-center gap-2 px-3 py-2.5 pr-10 text-left text-sm">{conversation.pinned && <Pin className="size-3.5 shrink-0 fill-stone-700 text-stone-700" />}<span className="truncate">{conversation.title}</span></button>
                  <button type="button" aria-label={t("Chat actions", "会话操作")} onClick={() => setMenuId(menuId === conversation.id ? null : conversation.id)} className="absolute right-1.5 top-1.5 rounded-lg p-2 text-stone-400 opacity-70 hover:bg-stone-100 hover:text-stone-700 md:opacity-0 md:group-hover:opacity-100"><MoreHorizontal className="size-4" /></button>
                  {menuId === conversation.id && (
                    <div className="absolute right-2 top-10 z-20 w-36 rounded-xl border border-stone-200 bg-white p-1.5 text-sm shadow-xl">
                      <button onClick={() => togglePin(conversation)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Pin className={`size-3.5 ${conversation.pinned ? "fill-current" : ""}`} />{conversation.pinned ? t("Unpin", "取消置顶") : t("Pin", "置顶")}</button>
                      <button onClick={() => renameConversation(conversation)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Pencil className="size-3.5" />{t("Rename", "重命名")}</button>
                      <button onClick={() => removeConversation(conversation.id, false)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Archive className="size-3.5" />{t("Archive", "归档")}</button>
                      <button onClick={() => removeConversation(conversation.id, true)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-red-600 hover:bg-red-50"><Trash2 className="size-3.5" />{t("Delete permanently", "永久删除")}</button>
                    </div>
                  )}
                </div>
              ))}
              {conversations.length === 0 && <p className="px-2 py-3 text-xs leading-5 text-stone-400">{t("New chats will appear here.", "新对话会出现在这里。")}</p>}
            </div>
            {archivedConversations.length > 0 && (
              <div className="mt-4 border-t border-stone-200/70 pt-3">
                <button onClick={() => setShowArchived((current) => !current)} className="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-xs text-stone-500 hover:bg-white/70"><Archive className="size-3.5" />{t("Archived", "已归档")}<span className="ml-auto text-stone-400">{archivedConversations.length}</span></button>
                {showArchived && <div className="mt-1 space-y-1">
                  {archivedConversations.map((conversation) => (
                    <div key={conversation.id} className="group flex items-center gap-1 rounded-xl px-2 py-1 hover:bg-white/70">
                      <span className="min-w-0 flex-1 truncate px-1 text-xs text-stone-500">{conversation.title}</span>
                      <button title={t("Restore", "恢复")} onClick={() => restoreConversation(conversation.id)} className="rounded-lg p-2 text-stone-400 hover:bg-white hover:text-stone-800"><RotateCcw className="size-3.5" /></button>
                      <button title={t("Delete permanently", "永久删除")} onClick={() => removeConversation(conversation.id, true)} className="rounded-lg p-2 text-stone-400 hover:bg-red-50 hover:text-red-600"><Trash2 className="size-3.5" /></button>
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
              <button aria-label={t("Open conversations", "打开会话栏")} title={t("Recent chats", "最近对话")} onClick={() => setSidebarOpen(true)} className="rounded-xl p-2 text-stone-500 hover:bg-white"><PanelLeftOpen className="size-5" /></button>
              <button type="button" onClick={newConversation} disabled={busy} className="rounded-xl p-2 text-stone-500 hover:bg-white disabled:opacity-50" aria-label={t("New chat", "新对话")} title={t("New chat", "新对话")}><Plus className="size-5" /></button>
              <h1 className="truncate text-sm font-medium text-stone-600">{selected?.title ?? "Ardor"}</h1>
            </div>
          </header>

          {!state?.llmConfigured && <div className="mx-4 mt-2 flex items-center justify-between gap-4 rounded-2xl border border-amber-200 bg-amber-50/90 px-4 py-3 text-sm text-amber-900 md:mx-8"><span>{t("Connect a tool-capable model to get started.", "配置一个支持 Tool Calling 的模型后，Ardor 才能开始工作。")}</span><Link href="/app/settings" className="shrink-0 font-medium underline underline-offset-4">{t("Settings", "去设置")}</Link></div>}
          {notice && <button type="button" onClick={() => setNotice("")} className="mx-4 mt-2 rounded-2xl bg-emerald-50/90 px-4 py-3 text-left text-sm text-emerald-800 md:mx-8">{notice}</button>}
          {applicationReminder?.reminderDue && <div className="mx-4 mt-2 flex items-center gap-3 rounded-2xl border border-violet-100 bg-white/80 px-4 py-3 text-sm text-stone-700 shadow-sm md:mx-8"><Target className="size-4 shrink-0 text-violet-500" /><span className="flex-1">{t(`How many applications today? ${applicationReminder.weeklyCount}/${applicationReminder.weeklyGoal} this week`, `今天投了多少份？本周 ${applicationReminder.weeklyCount}/${applicationReminder.weeklyGoal}`)}</span><Link href="/app/applications" className="shrink-0 font-medium text-violet-700 hover:underline">{t("Log it", "记一下")}</Link><button type="button" onClick={() => void dismissApplicationReminder()} className="shrink-0 text-xs text-stone-400 hover:text-stone-700">{t("Skip today", "今天跳过")}</button></div>}

          <div className="relative flex-1 overflow-y-auto">
            {messages.length === 0 ? (
              <div className={`relative grid min-h-full place-items-center overflow-hidden px-4 pb-12 ${leavingEmptyState ? "ardor-empty-exit" : "ardor-empty-enter"}`}>
                <div className="pointer-events-none absolute inset-x-[6%] bottom-[-28%] h-[82%] rounded-[50%] bg-[radial-gradient(circle_at_28%_45%,rgba(255,107,177,0.58),transparent_43%),radial-gradient(circle_at_72%_38%,rgba(83,125,255,0.58),transparent_47%),radial-gradient(circle_at_50%_76%,rgba(149,92,246,0.46),transparent_58%)] blur-3xl" />
                <div className="relative z-10 mx-auto w-full max-w-3xl text-center">
                  <h2 className="text-4xl font-semibold tracking-[-0.04em] text-stone-950 md:text-5xl">What should we build{state?.displayName?.trim() ? `, ${state.displayName.trim()}` : ""}?</h2>
                  {runningRun && <div role="status" className="mx-auto mt-5 max-w-2xl rounded-2xl border border-violet-100 bg-white/75 px-4 py-3 text-left text-sm text-violet-700">{t(`Ardor is working on your last message · ${runningRun.label}. It will appear when ready.`, `Ardor 正在处理上一条消息 · ${runningRun.label}。完成后会自动显示。`)}</div>}
                  {(visibleError || retryFailures.length > 0) && <div className="mx-auto mt-5 max-w-2xl rounded-2xl bg-red-50/90 px-4 py-3 text-left text-sm text-red-700 shadow-sm">{visibleError && <p>{visibleError}</p>}{retryFailures.length > 0 && <ul className={visibleError ? "mt-2 space-y-1 text-xs text-red-600" : "space-y-1 text-xs text-red-600"}>{retryFailures.map((failure, index) => <li key={`${failure.label}-${index}`}>{failure.label}：{failure.detail}</li>)}</ul>}</div>}
                  <form onSubmit={submit} className="mx-auto mt-10 rounded-[1.75rem] border border-white/70 bg-white/88 p-2 text-left shadow-[0_20px_70px_rgba(42,35,27,0.16)] backdrop-blur-xl transition-[border-color,box-shadow] duration-200 focus-within:border-stone-300/80 focus-within:shadow-[0_22px_76px_rgba(42,35,27,0.18),0_0_0_4px_rgba(255,255,255,0.42)]">
                    {selectedContexts.length > 0 && <div className="mx-3 mt-2 flex flex-wrap gap-1.5">{selectedContexts.map((context) => <div key={`${context.type}-${context.id}`} className="inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{context.label}</span><button type="button" aria-label={t("Remove attachment", "移除资料")} onClick={() => toggleContext(context)}><X className="size-3.5" /></button></div>)}</div>}
                    <textarea aria-label={t("Message Ardor", "给 Ardor 发消息")} value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={busy || !!runningRun || !state?.llmConfigured} rows={3} placeholder={state?.llmConfigured ? typedHint : t("Set up a model first", "请先完成模型设置")} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
                    <div className="flex items-center justify-between gap-3 px-2 pb-1">{attachmentPicker()}<div className="flex items-center gap-3">{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}<Button aria-label={t("Send", "发送")} disabled={busy || !!runningRun || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div></div>
                  </form>
                </div>
              </div>
            ) : (
              <div aria-live="polite" className="ardor-chat-enter mx-auto w-full max-w-3xl space-y-8 px-4 py-10 md:px-8">
                {messages.map((message) => (
                  <article key={message.id} className={`flex gap-3 ${message.role === "USER" ? "justify-end" : "justify-start"}`}>
                    {message.role === "USER" ? (
                      <div className="max-w-[86%] rounded-3xl bg-stone-200/65 px-5 py-3 text-sm leading-7">{message.contextReferences && message.contextReferences.length > 0 && <div className="mb-2 flex flex-wrap gap-1.5">{message.contextReferences.map((context) => <span key={`${context.type}-${context.id}`} className="inline-flex max-w-full items-center gap-1 rounded-full bg-white/70 px-2.5 py-1 text-[11px] text-stone-600"><Paperclip className="size-3" /><span className="truncate">{context.label}</span></span>)}</div>}<div className="whitespace-pre-wrap">{message.content}</div></div>
                    ) : (
                      <div className="max-w-full py-0.5 text-sm leading-7 text-stone-800">
                        <div className="ardor-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown></div>
                        {savedRunTrace(message.runTrace) && (() => { const trace = savedRunTrace(message.runTrace)!; return <details className="mt-3 max-w-xl rounded-2xl border border-white/70 bg-white/45 px-4 py-3 text-stone-600 shadow-sm"><summary className="cursor-pointer list-none text-xs font-medium">Ardor 已完成 <span className="float-right font-normal tabular-nums text-stone-400">{formatElapsed(trace.elapsedMs)}</span></summary><div className="mt-3 space-y-2 border-l border-stone-200 pl-4">{trace.steps.map((step, index) => <div key={`${step.label}-${index}`} className="flex items-center gap-2 text-xs"><CheckCircle2 className={`size-3.5 ${step.status === "FAILED" ? "text-rose-500" : "text-emerald-500"}`} /><span>{step.label}</span><span className="ml-auto tabular-nums text-stone-400">{formatElapsed(step.elapsedMs)}</span></div>)}</div></details>; })()}
                      </div>
                    )}
                  </article>
                ))}
                {confirmations.length > 0 && <section className="space-y-2">
                  {confirmations.map((pending) => <div key={pending.endpoint} className={`flex items-start gap-3 rounded-2xl border px-4 py-3 text-sm shadow-sm ${pending.state === "DONE" ? "border-emerald-100 bg-emerald-50/80" : pending.state === "DISMISSED" ? "border-stone-200 bg-white/50" : "border-rose-100 bg-white/80"}`}>
                    <span className={`mt-0.5 grid size-8 shrink-0 place-items-center rounded-xl ${pending.state === "DONE" ? "bg-emerald-100 text-emerald-600" : "bg-rose-50 text-rose-500"}`}>{pending.state === "DONE" ? <Check className="size-4" /> : <Trash2 className="size-4" />}</span>
                    <div className="min-w-0 flex-1">
                      <p className="font-medium text-stone-800">{pending.state === "DONE" ? "已删除" : pending.state === "DISMISSED" ? "已保留" : pending.state === "DELETING" ? "正在删除" : "确认删除"}<span className="ml-1 font-normal">「{pending.label}」</span></p>
                      {pending.detail && pending.state === "PENDING" && <p className="mt-1 text-xs leading-5 text-stone-500">{pending.detail}</p>}
                      {pending.error && <p className="mt-1 text-xs leading-5 text-rose-600">{pending.error}</p>}
                    </div>
                    {(pending.state === "PENDING" || pending.state === "FAILED") && <div className="flex shrink-0 items-center gap-2">
                      <button type="button" onClick={() => setConfirmations((current) => current.map((item) => item.endpoint === pending.endpoint ? { ...item, state: "DISMISSED" } : item))} className="rounded-full px-3 py-1.5 text-xs font-medium text-stone-500 transition hover:bg-white hover:text-stone-800">保留</button>
                      <button type="button" onClick={() => void confirmDeletion(pending)} className="rounded-full bg-rose-600 px-3.5 py-1.5 text-xs font-medium text-white transition hover:bg-rose-700">{pending.state === "FAILED" ? "重试删除" : "删除"}</button>
                    </div>}
                  </div>)}
                </section>}
                {(runSteps.length > 0 || busy || runningRun || retryFailures.length > 0) && <section className="max-w-xl rounded-2xl border border-white/70 bg-white/55 px-4 py-3 text-sm text-stone-600 shadow-sm backdrop-blur-md">
                  <button type="button" onClick={() => setTraceOpen((open) => !open)} className="flex w-full items-center gap-2 text-left">
                    {traceOpen ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                    <span className="font-medium text-stone-700">{busy || runningRun ? "Ardor 正在工作" : error ? "Ardor 未完成" : "Ardor 已完成"}</span>
                    <span className="ml-auto text-xs tabular-nums text-stone-400">{formatElapsed(runElapsed)}</span>
                  </button>
                  {traceOpen && <div className="mt-3 space-y-2 border-l border-stone-200 pl-4">
                    {runningRun && <div className="flex items-center gap-2 text-xs text-violet-600"><RotateCcw className="size-3.5 animate-spin" /><span>{runningRun.label} · 可刷新页面继续查看</span></div>}
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
              {selectedContexts.length > 0 && <div className="mx-3 mt-2 flex flex-wrap gap-1.5">{selectedContexts.map((context) => <div key={`${context.type}-${context.id}`} className="inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{context.label}</span><button type="button" aria-label="移除资料" onClick={() => toggleContext(context)}><X className="size-3.5" /></button></div>)}</div>}
              <textarea aria-label="给 Ardor 发消息" value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={busy || !!runningRun || !state?.llmConfigured} rows={2} placeholder={state?.llmConfigured ? "Message Ardor…" : "请先完成模型设置"} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
              <div className="flex items-center justify-between gap-3 px-2 pb-1"><div className="flex items-center gap-2">{attachmentPicker()}{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}</div><Button aria-label="发送" disabled={busy || !!runningRun || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div>
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
