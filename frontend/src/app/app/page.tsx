"use client";

import {
  Archive, ArrowUp, BookOpenText, Brain, BrainCircuit, Check, CheckCircle2, ChevronDown, ChevronRight, FileSearch, FileText,
  MoreHorizontal, PanelLeftOpen, Paperclip, Pencil, Pin, Plus, RotateCcw, Search, Target, Trash2, Upload, X,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { type AiJob } from "@/lib/ai-jobs";

import { Button } from "@/components/ui/button";
import { AppDialog } from "@/components/ui/app-dialog";
import { ChatNotifications } from "@/components/ardor/chat-notifications";
import { WorkspaceBrand } from "@/components/ardor/workspace-brand";
import { ReasoningControl } from "@/components/ardor/reasoning-control";
import { ServicePicker } from "@/components/ardor/service-picker";
import { PublicThinking } from "@/components/ardor/public-thinking";
import { ToolApprovals } from "@/components/ardor/tool-approvals";
import { QueuedMessages } from "@/components/ardor/queued-messages";
import type { AgentFeature } from "@/lib/agent-features";
import { featureHref } from "@/lib/agent-features";
import { useDismiss } from "@/components/ardor/overlay";
import { ApiError, api, streamApi } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { useTypewriter } from "@/lib/use-typewriter";

type SelectedContext = { type: "RESUME" | "RECAP" | "KNOWLEDGE" | "LEARNING" | "SERVICE"; id: string; label: string };
type AgentMessage = { id: string; conversationId: string; role: "USER" | "ASSISTANT"; content: string; contextReferences?: SelectedContext[]; runTrace?: string | null; createdAt: string };
type Conversation = { id: string; title: string; pinned: boolean; titleManuallySet?: boolean; createdAt: string; updatedAt: string };
type AgentMemory = { content: string; updatedAt: string | null };
type RetryFailure = { label: string; detail: string };
type AgentStreamEvent = { type: "status" | "thinking" | "delta" | "tool_start" | "tool_end" | "confirm" | "done" | "error"; content: string | null; toolName: string | null; label: string | null; elapsedMs: number | null; conversationId: string | null; messageId: string | null; retryable: boolean | null; errorCode: string | null; confirmation: AgentConfirmation | null };
type StreamProgress = { phase: string; label: string; thinkingOutput: string; answerPartial: string; firstThinkingMs: number | null; firstOutputMs: number | null; silentSeconds: number; truncated: boolean; steps?: { label: string; elapsedMs: number; status: string }[]; elapsedMs?: number };
type AgentRun = { id: string; conversationId: string; message: string; status: "RUNNING" | "COMPLETED" | "FAILED" | "INTERRUPTED"; label: string; errorCode: string | null; errorMessage: string | null; retryable: boolean; createdAt: string; updatedAt: string };
/* A deletion Ardor has proposed. Nothing is gone until the user presses the
   button, and pressing it is an ordinary authenticated DELETE from here — the
   model never gets to destroy anything on its own say-so. */
type LearningPlanProposal = { concept: string; reason: string | null; sourceType: string; sourceId: string | null; scheduledAt: string | null; requestId: string };
type AgentConfirmation = { kind: string; targetId: string | null; label: string; detail: string; endpoint: string; learningPlan?: LearningPlanProposal | null };
type PendingConfirmation = AgentConfirmation & { state: "PENDING" | "DELETING" | "GENERATING" | "DONE" | "DISMISSED" | "FAILED" | "EXPIRED" | "REMOVED"; error?: string; createdPlanId?: string };
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
  try { return JSON.parse(raw) as { elapsedMs: number; status: string; steps: Array<{ label: string; elapsedMs: number; status: string }>; proposals?: AgentConfirmation[]; thinkingOutput?: string; firstOutputMs?: number }; }
  catch { return null; }
}

function confirmationKey(value: AgentConfirmation) {
  return value.learningPlan?.requestId ?? `${value.kind}:${value.endpoint}`;
}

function dismissedProposalKey(value: AgentConfirmation) {
  return `ardor:learning-proposal-dismissed:${confirmationKey(value)}`;
}

function proposalExpired(value: AgentConfirmation) {
  return !!value.learningPlan?.scheduledAt && Date.parse(value.learningPlan.scheduledAt) <= Date.now();
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
type ReasoningDepth = "AUTO" | "QUICK" | "STANDARD" | "DEEP";

function draftStorageKey(conversationId?: string | null) {
  return `ardor:conversation-draft:${conversationId ?? "new"}`;
}

function readSavedDraft(conversationId?: string | null) {
  return window.sessionStorage.getItem(draftStorageKey(conversationId)) ?? "";
}

function outgoingKey(accountId: string | null, conversationId: string) {
  return `ardor:outgoing:${accountId}:${conversationId}`;
}

function readOutgoing(accountId: string | null, conversationId: string | null): AgentMessage | null {
  if (!accountId || !conversationId) return null;
  try {
    const raw = window.sessionStorage.getItem(outgoingKey(accountId, conversationId));
    if (!raw) return null;
    const value = JSON.parse(raw) as AgentMessage;
    return value.role === "USER" && value.conversationId === conversationId && typeof value.content === "string" ? value : null;
  } catch { return null; }
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
  const [reasoningDepth, setReasoningDepth] = useState<ReasoningDepth>("AUTO");
  const [nativeReasoning, setNativeReasoning] = useState(false);
  const reasoningAccount = useRef<string | null>(null);
  const [reasoningReady, setReasoningReady] = useState(false);
  const [titleWatch, setTitleWatch] = useState<{ id: string; title: string } | null>(null);
  const [runningRun, setRunningRun] = useState<AgentRun | null>(null);
  const [approvalRunId, setApprovalRunId] = useState<string | null>(null);
  const [hasQueuedMessages, setHasQueuedMessages] = useState(false);
  const [streamAccepted, setStreamAccepted] = useState(false);
  const [cancelPending, setCancelPending] = useState(false);
  const queueSubmitting = useRef(false);
  const activeSend = useRef<AbortController | null>(null);
  const stopRequests = useRef(new Map<string, Promise<void>>());
  const [retryAttempt, setRetryAttempt] = useState(0);
  const [retryFailures, setRetryFailures] = useState<RetryFailure[]>([]);
  const [runSteps, setRunSteps] = useState<RunStep[]>([]);
  const [runThinking, setRunThinking] = useState("");
  const [streamProgress, setStreamProgress] = useState<StreamProgress | null>(null);
  const [confirmations, setConfirmations] = useState<PendingConfirmation[]>([]);
  const [traceOpen, setTraceOpen] = useState(true);
  const [runElapsed, setRunElapsed] = useState(0);
  const [leavingEmptyState, setLeavingEmptyState] = useState(false);
  const [error, setError] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState<boolean | null>(null);
  const [recentOpen, setRecentOpen] = useState(true);
  const [searchOpen, setSearchOpen] = useState(false);
  const [chatSearch, setChatSearch] = useState("");
  const typedHint = useTypewriter(locale === "en" ? typewriterExamples : typewriterExamplesZh, messages.length === 0);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryDraft, setMemoryDraft] = useState("");
  const [selectedContexts, setSelectedContexts] = useState<SelectedContext[]>([]);
  const [features, setFeatures] = useState<AgentFeature[]>([]);
  const featuresRef = useRef<AgentFeature[]>([]);
  const [attachmentOpen, setAttachmentOpen] = useState(false);
  const attachmentRef = useDismiss<HTMLDivElement>(attachmentOpen, () => setAttachmentOpen(false));
  const [isAdmin, setIsAdmin] = useState(false);
  const [resumeOptions, setResumeOptions] = useState<ResumeOption[]>([]);
  const [recapOptions, setRecapOptions] = useState<RecapOption[]>([]);
  const [knowledgeOptions, setKnowledgeOptions] = useState<KnowledgeOption[]>([]);
  const [learningOptions, setLearningOptions] = useState<LearningOption[]>([]);
  const [notice, setNotice] = useState("");
  const [applicationReminder, setApplicationReminder] = useState<ApplicationReminder | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<Conversation | null>(null);
  const [renameDraft, setRenameDraft] = useState("");
  const [renameError, setRenameError] = useState("");
  const [renamePending, setRenamePending] = useState(false);
  const [showArchived, setShowArchived] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);
  const runStartedAt = useRef(0);
  const uploadRef = useRef<HTMLInputElement>(null);
  const runningRunId = runningRun?.id;
  const learningRequestIds = confirmations.filter((item) => item.kind === "learning_plan" && ["PENDING", "GENERATING", "FAILED", "EXPIRED"].includes(item.state)).map((item) => item.learningPlan?.requestId).filter(Boolean).join(",");
  const generatingLearning = confirmations.some((item) => item.state === "GENERATING");

  useEffect(() => {
    if (!learningRequestIds) return;
    let active = true;
    const refresh = () => {
      void api<AiJob[]>("/api/ai-jobs?type=LEARNING_CREATE").then((jobs) => {
        if (!active) return;
        const byId = new Map(jobs.map((job) => [job.id, job]));
        setConfirmations((current) => current.map((item) => {
          if (!item.learningPlan || ["REMOVED", "DISMISSED", "DONE"].includes(item.state)) return item;
          const job = byId.get(item.learningPlan.requestId);
          if (!job) return item;
          if (job.status === "COMPLETED" && job.resultPath) return { ...item, state: "DONE", error: undefined,
            createdPlanId: new URL(job.resultPath, window.location.origin).searchParams.get("id") ?? undefined };
          if (job.status === "FAILED") return { ...item, state: "FAILED", error: job.errorMessage ?? "学习生成未完成，可以重试" };
          return { ...item, state: "GENERATING", error: undefined };
        }));
      }).catch(() => undefined);
    };
    refresh();
    const timer = generatingLearning ? window.setInterval(refresh, 3000) : null;
    return () => { active = false; if (timer) window.clearInterval(timer); };
  }, [learningRequestIds, generatingLearning]);

  useEffect(() => {
    let active = true;
    void api<{ id: string; admin: boolean }>("/api/auth/me").then((account) => {
      if (!active) return;
      setIsAdmin(account.admin === true);
      reasoningAccount.current = account.id;
      try {
        const saved = window.sessionStorage.getItem(`ardor:reasoning-depth:${account.id}`);
        if (saved && ["AUTO", "QUICK", "STANDARD", "DEEP"].includes(saved)) setReasoningDepth(saved as ReasoningDepth);
      } finally { setReasoningReady(true); }
    }).catch(() => undefined);
    void api<{ nativeControl: boolean }>("/api/agent/reasoning")
      .then((result) => { if (active) setNativeReasoning(result.nativeControl); }).catch(() => undefined);
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (busy || !titleWatch) return;
    let active = true;
    let remaining = 12;
    const timer = window.setInterval(() => {
      if (--remaining <= 0) window.clearInterval(timer);
      void api<Conversation[]>("/api/agent/conversations")
        .then((items) => {
          if (!active) return;
          setConversations(sortConversations(items));
          const updated = items.find((item) => item.id === titleWatch.id);
          if (!updated || updated.titleManuallySet || updated.title !== titleWatch.title || remaining <= 0) setTitleWatch(null);
        }).catch(() => { if (active && remaining <= 0) setTitleWatch(null); });
    }, 5000);
    return () => { active = false; window.clearInterval(timer); };
  }, [busy, titleWatch]);

  function reasoningPicker() {
    return <ReasoningControl value={reasoningDepth} disabled={!reasoningReady} nativeControl={nativeReasoning}
      onChange={(value) => {
        setReasoningDepth(value);
        try { if (reasoningAccount.current) window.sessionStorage.setItem(`ardor:reasoning-depth:${reasoningAccount.current}`, value); }
        catch { /* The current selection remains usable when browser storage is unavailable. */ }
      }} />;
  }

  useEffect(() => {
    if (window.sessionStorage.getItem("ardor:open-memory") === "1") {
      window.sessionStorage.removeItem("ardor:open-memory");
      setMemoryOpen(true);
    }
    const openMemory = () => setMemoryOpen(true);
    const closeDrawer = () => { if (!window.matchMedia("(min-width: 768px)").matches) setSidebarOpen(false); };
    const toggleSidebar = () => setSidebarOpen((value) => !(value ?? window.matchMedia("(min-width: 768px)").matches));
    const newChat = () => { document.querySelector<HTMLButtonElement>('button[data-new-chat]')?.click(); };
    window.addEventListener("ardor:open-memory", openMemory);
    window.addEventListener("ardor:close-chat-drawer", closeDrawer);
    window.addEventListener("ardor:toggle-chat-sidebar", toggleSidebar);
    window.addEventListener("ardor:new-chat", newChat);
    return () => {
      window.removeEventListener("ardor:open-memory", openMemory);
      window.removeEventListener("ardor:close-chat-drawer", closeDrawer);
      window.removeEventListener("ardor:toggle-chat-sidebar", toggleSidebar);
      window.removeEventListener("ardor:new-chat", newChat);
    };
  }, []);

  const applyState = useCallback((result: AgentState) => {
    setState(result);
    const outgoing = readOutgoing(reasoningAccount.current, result.conversationId);
    setMessages(outgoing ? [...result.messages, outgoing] : result.messages);
    const proposals = [...new Map<string, AgentConfirmation>(result.messages.flatMap((message) => savedRunTrace(message.runTrace)?.proposals ?? [])
      .filter((item) => item.kind === "learning_plan" && item.learningPlan?.requestId)
      .map((item): [string, AgentConfirmation] => [confirmationKey(item), item])).values()].slice(-50);
    const restoredLearning = proposals.map((item) => ({ ...item, state: window.sessionStorage.getItem(dismissedProposalKey(item)) === "1"
      ? "DISMISSED" as const : proposalExpired(item) ? "EXPIRED" as const : "PENDING" as const }));
    // A completed stream may immediately reload state. Keep its live deletion buttons;
    // only learning proposals are recoverable from the saved assistant trace.
    setConfirmations((current) => [...current.filter((item) => item.kind !== "learning_plan"), ...restoredLearning]);
    if (proposals.length) {
      const query = new URLSearchParams();
      proposals.forEach((item) => query.append("requestIds", item.learningPlan!.requestId));
      void api<Array<{ requestId: string; status: "CREATED" | "DELETED" | "MISSING"; planId: string | null }>>(`/api/learning-plans/request-status?${query}`)
        .then((statuses) => {
          const byId = new Map(statuses.map((status) => [status.requestId, status]));
          setConfirmations((current) => current.map((item) => {
            const status = item.learningPlan && byId.get(item.learningPlan.requestId);
            return status?.status === "CREATED" ? { ...item, state: "DONE", createdPlanId: status.planId ?? undefined }
              : status?.status === "DELETED" ? { ...item, state: "REMOVED", createdPlanId: undefined } : item;
          }));
        }).catch(() => undefined);
    }
    setConversations(result.conversations);
    setArchivedConversations(result.archivedConversations);
    setMemoryDraft(result.memory.content);
    setDraft(readSavedDraft(result.conversationId));
    const serviceId = window.sessionStorage.getItem(`${draftStorageKey(result.conversationId)}:service:${reasoningAccount.current}`);
    const feature = featuresRef.current.find((item) => item.id === serviceId);
    setSelectedContexts((current) => [...current.filter((item) => item.type !== "SERVICE"), ...(feature ? [{ type: "SERVICE" as const, id: feature.id, label: feature.label }] : [])]);
  }, []);

  const load = useCallback(async (conversationId?: string | null) => {
    const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : "";
    applyState(await api<AgentState>(`/api/agent${query}`));
  }, [applyState]);

  useEffect(() => {
    let cancelled = false;
    // Consume one-time draft links before any asynchronous work. Updating history
    // after loading could overwrite a module navigation the user just started.
    const params = new URLSearchParams(window.location.search);
    const requested = params.get("conversation");
    if (params.get("new") === "1" && (params.has("prefill") || params.has("feature") || params.has("contextId"))) {
      window.history.replaceState(null, "", "/app?new=1");
    }
    const query = requested ? `?conversationId=${encodeURIComponent(requested)}` : "";
    api<AgentState>(`/api/agent${query}`)
      .then(async (result) => {
        if (cancelled) return;
        const account = await api<{ id: string }>("/api/auth/me");
        reasoningAccount.current = account.id;
        const response = await api<AgentFeature[]>("/api/agent/features").catch(() => []);
        const catalog = Array.isArray(response) ? response : [];
        if (cancelled) return;
        setFeatures(catalog);
        featuresRef.current = catalog;
        if (params.get("new") === "1") {
          result = { ...result, conversationId: null, messages: [] };
          const prefill = params.get("prefill") ?? "";
          if (prefill) window.sessionStorage.setItem(draftStorageKey(null), prefill);
          const feature = catalog.find((item) => item.key === params.get("feature"));
          if (feature) window.sessionStorage.setItem(`${draftStorageKey(null)}:service:${account.id}`, feature.id);
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
          setMessages((current) => current.some((item) => item.role === "USER" && item.content === run.message && item.id.startsWith("pending-user-message-"))
            ? current : [...current, readOutgoing(reasoningAccount.current, conversationId) ?? {
              id: `pending-user-message-${run.id}`, conversationId, role: "USER", content: run.message, createdAt: run.createdAt,
            }]);
          previousStatus = "RUNNING";
          try {
            const progress = await api<StreamProgress>(`/api/agent/runs/${encodeURIComponent(run.id)}/progress`);
            if (!stopped) {
              setStreamProgress(progress);
              setRunThinking(progress.thinkingOutput);
            }
          } catch { /* Older servers or a restarted worker may not retain live output. Keep polling the durable run. */ }
          timer = window.setTimeout(() => void check(), 3000);
        } else {
          setRunningRun(null);
          if (run.status !== "COMPLETED") {
            setError(run.errorMessage ?? "请求未完成，请检查操作结果后再试");
            try {
              const progress = await api<StreamProgress>(`/api/agent/runs/${encodeURIComponent(run.id)}/progress`);
              if (!stopped) {
                setStreamProgress(progress); setRunThinking(progress.thinkingOutput);
                setRunElapsed(progress.elapsedMs ?? 0);
                setRunSteps((progress.steps ?? []).map((step, index) => ({ key: `recovered-${index}`, label: step.label, elapsedMs: step.elapsedMs, done: true })));
              }
            } catch { /* Old runs may predate durable snapshots. Their status remains visible. */ }
          }
          if (previousStatus === "RUNNING") {
            previousStatus = run.status;
            if (run.status === "COMPLETED") {
              window.sessionStorage.removeItem(outgoingKey(reasoningAccount.current, conversationId));
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
    if (!window.matchMedia("(min-width: 768px)").matches) setSidebarOpen(false);
    if (conversationId === state?.conversationId) return;
    window.history.replaceState(null, "", `/app?conversation=${conversationId}`);
    setError(""); setRunningRun(null); setHasQueuedMessages(false); setRunThinking(""); setStreamProgress(null); setConfirmations([]); setMenuId(null);
    try { await load(conversationId); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法载入会话"); }
  }

  async function newConversation() {
    if (busy) return;
    setError("");
    setRunningRun(null);
    setHasQueuedMessages(false);
    setRetryFailures([]);
    setRunThinking(""); setStreamProgress(null);
    setRunSteps([]);
    setConfirmations([]);
    setRunElapsed(0);
    setMessages([]);
    setSelectedContexts([]);
    setState((current) => current ? { ...current, conversationId: null, messages: [] } : current);
    setDraft(readSavedDraft(null));
    window.history.replaceState(null, "", "/app?new=1");
    if (!window.matchMedia("(min-width: 768px)").matches) setSidebarOpen(false);
  }

  /* The button calls the same REST endpoint the corresponding page would call.
     No agent round trip, no confirmation phrase to retype. */
  async function confirmDeletion(pending: PendingConfirmation) {
    const key = confirmationKey(pending);
    setConfirmations((current) => current.map((item) => confirmationKey(item) === key ? { ...item, state: "DELETING", error: undefined } : item));
    try {
      await api<void>(pending.endpoint, { method: "DELETE" });
      setConfirmations((current) => current.map((item) => confirmationKey(item) === key ? { ...item, state: "DONE" } : item));
    } catch (reason) {
      setConfirmations((current) => current.map((item) => confirmationKey(item) === key
        ? { ...item, state: "FAILED", error: reason instanceof Error ? reason.message : "删除失败" }
        : item));
    }
  }

  async function confirmLearning(pending: PendingConfirmation) {
    if (!pending.learningPlan) return;
    const key = confirmationKey(pending);
    if (proposalExpired(pending)) {
      setConfirmations((current) => current.map((item) => confirmationKey(item) === key ? { ...item, state: "EXPIRED" } : item));
      return;
    }
    setConfirmations((current) => current.map((item) => confirmationKey(item) === key ? { ...item, state: "DELETING", error: undefined } : item));
    try {
      let job = await api<AiJob>("/api/learning-plans/jobs", { method: "POST", body: JSON.stringify(pending.learningPlan) });
      if (job.status === "FAILED" && pending.state === "FAILED") job = await api<AiJob>(`/api/ai-jobs/${job.id}/retry`, { method: "POST" });
      setConfirmations((current) => current.map((item) => confirmationKey(item) === key ? {
        ...item, state: job.status === "COMPLETED" ? "DONE" : job.status === "FAILED" ? "FAILED" : "GENERATING",
        error: job.status === "FAILED" ? job.errorMessage ?? "学习生成未完成" : undefined,
        createdPlanId: job.status === "COMPLETED" && job.resultPath ? new URL(job.resultPath, window.location.origin).searchParams.get("id") ?? undefined : undefined,
      } : item));
      window.dispatchEvent(new Event("ardor:background-refresh"));
    } catch (reason) {
      setConfirmations((current) => current.map((item) => confirmationKey(item) === key
        ? { ...item, state: "FAILED", error: reason instanceof Error ? reason.message : "创建学习计划失败" }
        : item));
    }
  }

  async function send(text: string) {
    const message = text.trim();
    if (!message) return;
    if (message.length > MAX_MESSAGE_LENGTH) {
      setError(`这条消息有 ${message.length.toLocaleString()} 个字符，单条最多 ${MAX_MESSAGE_LENGTH.toLocaleString()} 个字符。请删减或分段发送。`);
      return;
    }
    if (busy || runningRun || hasQueuedMessages) {
      if (busy && !streamAccepted || queueSubmitting.current || !state?.conversationId) return;
      queueSubmitting.current = true;
      const payload = { conversationId: state.conversationId, message, reasoningDepth,
        contextReferences: selectedContexts.map(({ type, id }) => ({ type, id })) };
      const slot = `ardor:queue-submit:${reasoningAccount.current}:${state.conversationId}`;
      try {
        const stored = JSON.parse(window.sessionStorage.getItem(slot) ?? "null");
        const requestId = stored?.fingerprint === JSON.stringify(payload) ? stored.requestId : crypto.randomUUID();
        window.sessionStorage.setItem(slot, JSON.stringify({ requestId, fingerprint: JSON.stringify(payload) }));
        await api("/api/agent/queue", { method: "POST", body: JSON.stringify({ ...payload, requestId }) });
        window.sessionStorage.removeItem(slot);
        const unchanged = readSavedDraft(state.conversationId).trim() === message;
        if (unchanged) {
          window.sessionStorage.removeItem(draftStorageKey(state.conversationId));
          window.sessionStorage.removeItem(`${draftStorageKey(state.conversationId)}:service:${reasoningAccount.current}`);
        }
        setDraft((current) => current.trim() === message ? "" : current);
        if (unchanged) setSelectedContexts((current) => JSON.stringify(current.map(({ type, id }) => ({ type, id }))) === JSON.stringify(payload.contextReferences) ? [] : current);
        setHasQueuedMessages(true);
        window.dispatchEvent(new Event("ardor:queue-refresh"));
      } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not confirm queue submission. Retry uses the same request ID.", "暂时无法确认排队结果，重试会复用请求标识。")); }
      finally { queueSubmitting.current = false; }
      return;
    }
    const optimisticId = `pending-user-message-${crypto.randomUUID()}`;
    const sentContexts = [...selectedContexts];
    let conversationId = state?.conversationId ?? null;
    let acceptedRun = false;
    const sendControl = new AbortController();
    activeSend.current = sendControl;
    let savedDraftKey = draftStorageKey(conversationId);
    // The sent payload has a separate outbox. Never keep it in the editable next-message draft.
    window.sessionStorage.removeItem(savedDraftKey);
    window.sessionStorage.removeItem(`${savedDraftKey}:service:${reasoningAccount.current}`);
    setDraft("");
    setSelectedContexts([]);
    setError(""); setRetryFailures([]); setRunSteps([]); setRunThinking(""); setStreamProgress(null);
    setConfirmations((current) => current.filter((item) => item.kind === "learning_plan"));
    setTraceOpen(true); setRunElapsed(0); runStartedAt.current = Date.now(); setStreamAccepted(false); setBusy(true);
    try {
      if (!conversationId) {
        const conversation = await api<Conversation>("/api/agent/conversations", { method: "POST" });
        conversationId = conversation.id;
        const conversationDraftKey = draftStorageKey(conversationId);
        const nextDraft = readSavedDraft(null);
        if (nextDraft) window.sessionStorage.setItem(conversationDraftKey, nextDraft);
        const serviceId = window.sessionStorage.getItem(`${savedDraftKey}:service:${reasoningAccount.current}`);
        if (serviceId) window.sessionStorage.setItem(`${conversationDraftKey}:service:${reasoningAccount.current}`, serviceId);
        window.sessionStorage.removeItem(`${savedDraftKey}:service:${reasoningAccount.current}`);
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
      const optimistic: AgentMessage = { id: optimisticId, conversationId: activeConversationId, role: "USER", content: message, contextReferences: sentContexts, createdAt: "" };
      if (messages.length === 0) {
        setLeavingEmptyState(true);
        await new Promise((resolve) => window.setTimeout(resolve, 260));
      }
      window.sessionStorage.setItem(outgoingKey(reasoningAccount.current, activeConversationId), JSON.stringify(optimistic));
      setMessages((current) => [...current, optimistic]);
      setLeavingEmptyState(false);
      const pendingAssistantId = `pending-assistant-${crypto.randomUUID()}`;
      let assistant: AgentMessage | null = null;
      let requestId = crypto.randomUUID();
      setApprovalRunId(requestId);
      setMessages((current) => [...current, { id: pendingAssistantId, conversationId: activeConversationId, role: "ASSISTANT", content: "", createdAt: "" }]);
      for (let retry = 0; retry <= MAX_MESSAGE_RETRIES; retry += 1) {
        sendControl.signal.throwIfAborted();
        if (retry > 0) {
          setRetryAttempt(retry);
          await new Promise<void>((resolve, reject) => {
            const abort = () => { window.clearTimeout(timer); reject(sendControl.signal.reason); };
            const timer = window.setTimeout(() => { sendControl.signal.removeEventListener("abort", abort); resolve(); }, Math.min(1500 * (2 ** (retry - 1)), 8000));
            sendControl.signal.addEventListener("abort", abort, { once: true });
            if (sendControl.signal.aborted) abort();
          });
        }
        let receivedToolEvent = false;
        let receivedDelta = false;
        try {
          sendControl.signal.throwIfAborted();
          let streamFailure: AgentStreamEvent | null = null;
          let stopDispatched = false;
          if (retry > 0) setMessages((current) => current.map((item) => item.id === pendingAssistantId ? { ...item, content: "" } : item));
          await streamApi<AgentStreamEvent>("/api/agent/messages/stream", { requestId, conversationId: activeConversationId, message, reasoningDepth, contextReferences: sentContexts.map(({ type, id }) => ({ type, id })) }, (event) => {
            // A stop can race with initial acceptance. Only a real server event
            // proves this run exists; never send cancellation to a future UUID.
            if (sendControl.signal.aborted && event.type !== "error" && !stopDispatched) {
              stopDispatched = true;
              void stopRunOnce(requestId).catch((reason) => setError(reason instanceof Error ? reason.message : t("Could not confirm stop request", "暂时无法确认停止请求")));
            }
            if (event.type !== "error") setStreamAccepted(true);
            if (event.type === "thinking" && event.content) {
              setRunThinking((current) => (current + event.content).slice(0, 32_000));
            } else if (event.type === "delta" && event.content) {
              receivedDelta = true;
              setMessages((current) => current.map((item) => item.id === pendingAssistantId ? { ...item, content: item.content + event.content } : item));
            } else if (event.type === "status" && event.label) {
              setRunSteps((current) => current.some((step) => step.key === "status") ? current.map((step) => step.key === "status" ? { ...step, label: event.label! } : step) : [...current, { key: "status", label: event.label!, elapsedMs: 0, done: false }]);
            } else if (event.type === "tool_start" && event.toolName) {
              receivedToolEvent = true;
              setRunSteps((current) => [...current, { key: `${event.toolName}-${current.length}`, label: event.label ?? "调用工具", elapsedMs: 0, done: false }]);
            } else if (event.type === "tool_end" && event.toolName) {
              setRunSteps((current) => { const index = current.findLastIndex((step) => step.key.startsWith(`${event.toolName}-`) && !step.done); return index < 0 ? current : current.map((step, i) => i === index ? { ...step, label: event.label ?? step.label, elapsedMs: event.elapsedMs ?? 0, done: true } : step); });
              if (["analyze_resume", "organize_interview_recap", "research_knowledge_from_web"].includes(event.toolName)) window.dispatchEvent(new Event("ardor:background-refresh"));
            } else if (event.type === "done" && event.messageId && event.conversationId) {
              const currentConversation = conversations.find((item) => item.id === event.conversationId);
              if (!currentConversation?.titleManuallySet) setTitleWatch({ id: event.conversationId, title: currentConversation?.title ?? conversationTitle(message) });
              assistant = { id: event.messageId, conversationId: event.conversationId, role: "ASSISTANT", content: event.content ?? "", createdAt: new Date().toISOString() };
              setMessages((current) => current.map((item) => item.id === pendingAssistantId ? assistant! : item));
              const totalElapsed = event.elapsedMs ?? Date.now() - runStartedAt.current;
              setRunElapsed(totalElapsed);
              setRunSteps((current) => {
                const toolElapsed = current.filter((step) => step.key !== "status")
                  .reduce((sum, step) => sum + step.elapsedMs, 0);
                const modelElapsed = Math.max(1, totalElapsed - toolElapsed);
                return current.map((step) => step.key === "status"
                  ? { ...step, label: t("Model output and upstream wait (not pure thinking)", "模型输出与上游等待（非纯思考时间）"), elapsedMs: modelElapsed, done: true }
                  : step);
              });
            } else if (event.type === "confirm" && event.confirmation) {
              const proposal = event.confirmation;
              setConfirmations((current) => current.some((item) => confirmationKey(item) === confirmationKey(proposal))
                ? current
                : [...current, { ...proposal, state: "PENDING" }]);
            } else if (event.type === "error") streamFailure = event;
          }, sendControl.signal);
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
            window.sessionStorage.removeItem(outgoingKey(reasoningAccount.current, activeConversationId));
            await load(activeConversationId);
            return;
          }
          if (recorded?.status === "RUNNING") {
            if (sendControl.signal.aborted) await stopRunOnce(requestId);
            acceptedRun = true;
            setRunningRun(recorded);
            setMessages((current) => current.filter((item) => item.id !== pendingAssistantId));
            return;
          }
          sendControl.signal.throwIfAborted();
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
          if (recorded?.status === "FAILED") { requestId = crypto.randomUUID(); setApprovalRunId(requestId); }
        }
      }
      const completedAssistant = assistant as AgentMessage | null;
      if (!completedAssistant) throw new Error("Agent 暂时无法回复");
      window.sessionStorage.removeItem(outgoingKey(reasoningAccount.current, completedAssistant.conversationId));
      await load(completedAssistant.conversationId);
      setRunSteps([]);
      setRetryFailures([]);
      setRunElapsed(0);
    } catch (reason) {
      // An empty conversation may still have an in-flight run. Never delete it on transport failure.
      setMessages((current) => current.filter((item) => !item.id.startsWith("pending-assistant-")));
      // Once dispatched, retain it in history even if transport status is uncertain.
      // A failure before the outbox was created may restore the original without overwriting new typing.
      if (!conversationId || !readOutgoing(reasoningAccount.current, conversationId)) {
        setDraft((current) => current || message);
        if (!readSavedDraft(conversationId)) window.sessionStorage.setItem(savedDraftKey, message);
      }
      setError(sendControl.signal.aborted ? t("Sending stopped. No further automatic retries will be sent.", "已停止发送，不会继续自动重试。") : reason instanceof Error ? reason.message : "Agent 暂时无法回复");
    } finally { if (activeSend.current === sendControl) activeSend.current = null; setApprovalRunId(null); setRetryAttempt(0); setLeavingEmptyState(false); setBusy(false); if (acceptedRun) setError(""); }
  }

  function stopRunOnce(requestId: string): Promise<void> {
    const existing = stopRequests.current.get(requestId);
    if (existing) return existing;
    const request = api<void>(`/api/agent/runs/${requestId}/cancel`, { method: "POST" }).catch((reason) => {
      stopRequests.current.delete(requestId);
      throw reason;
    });
    stopRequests.current.set(requestId, request);
    if (stopRequests.current.size > 64) stopRequests.current.delete(stopRequests.current.keys().next().value!);
    return request;
  }

  async function cancelExecution() {
    const requestId = runningRun?.id ?? approvalRunId;
    if (!requestId || cancelPending) return;
    activeSend.current?.abort();
    setCancelPending(true);
    try {
      // During retry backoff this ID may not exist yet. Stop the local loop
      // regardless; only cancel server-side execution after verifying acceptance.
      let run = runningRun;
      if (!run) {
        try { run = await api<AgentRun>(`/api/agent/runs/${requestId}`); }
        catch (reason) { if (!(reason instanceof ApiError && reason.status === 404)) throw reason; }
      }
      if (run?.status === "RUNNING") await stopRunOnce(requestId);
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not confirm stop request", "暂时无法确认停止请求")); }
    finally { setCancelPending(false); }
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

  function openRename(conversation: Conversation) {
    setRenaming(conversation); setRenameDraft(conversation.title); setRenameError(""); setMenuId(null);
  }

  async function renameConversation(event: FormEvent) {
    event.preventDefault();
    if (!renaming || renamePending) return;
    const title = renameDraft.trim();
    if (!title || title.length > 240) { setRenameError(t("Use 1–240 characters.", "请输入 1–240 个字符。")); return; }
    setRenamePending(true); setRenameError("");
    try {
      const updated = await api<Conversation>(`/api/agent/conversations/${renaming.id}`, { method: "PATCH", body: JSON.stringify({ title }) });
      setConversations((current) => current.map((item) => item.id === updated.id ? updated : item));
      setRenaming(null);
    } catch (reason) { setRenameError(reason instanceof Error ? reason.message : t("Could not rename chat.", "无法重命名会话。")); }
    finally { setRenamePending(false); }
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
    if (/^[@$]/.test(draft) && event.key === "ArrowDown") {
      const option = document.querySelector<HTMLButtonElement>("[data-service-option]");
      if (option) { event.preventDefault(); option.focus(); return; }
    }
    if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); event.currentTarget.form?.requestSubmit(); }
  }

  function servicePicker() {
    const selected = features.find((feature) => selectedContexts.some((context) => context.type === "SERVICE" && context.id === feature.id));
    return <ServicePicker features={features} selected={selected} draft={draft} choose={(feature) => {
      setSelectedContexts((current) => [...current.filter((item) => item.type !== "SERVICE"), { type: "SERVICE", id: feature.id, label: feature.label }]);
      window.sessionStorage.setItem(`${draftStorageKey(state?.conversationId)}:service:${reasoningAccount.current}`, feature.id);
      updateDraft(draft.replace(/^[@$][^\s]*\s*/, ""));
    }} remove={() => {
      setSelectedContexts((current) => current.filter((item) => item.type !== "SERVICE"));
      window.sessionStorage.removeItem(`${draftStorageKey(state?.conversationId)}:service:${reasoningAccount.current}`);
    }} />;
  }

  function attachmentPicker() {
    const option = (context: SelectedContext, icon: React.ReactNode) => <button type="button" key={`${context.type}-${context.id}`} onClick={() => toggleContext(context)} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 text-left hover:bg-stone-100">{icon}<span className="min-w-0 flex-1 truncate">{context.label}</span>{selectedContexts.some((item) => item.type === context.type && item.id === context.id) && <Check className="size-3.5 text-violet-600" />}</button>;
    return <div ref={attachmentRef} className="relative"><input ref={uploadRef} type="file" accept=".pdf,.docx" className="hidden" onChange={(event) => void uploadResume(event.target.files?.[0])} /><button type="button" aria-label={t("Add attachments", "添加资料")} title={t("Add attachments", "添加资料")} aria-expanded={attachmentOpen} onClick={() => void openAttachments()} className="grid size-9 place-items-center rounded-full text-stone-500 hover:bg-stone-100"><Plus className="size-4" /></button>{attachmentOpen && <div role="region" aria-label={t("Attachments", "附件")} className="absolute bottom-11 left-0 z-30 max-h-96 w-80 overflow-y-auto rounded-2xl border border-stone-200 bg-white p-2 text-sm shadow-2xl"><button type="button" onClick={() => uploadRef.current?.click()} className="flex w-full items-center gap-2 rounded-xl px-3 py-2.5 hover:bg-stone-100"><Upload className="size-4" />{t("Upload resume", "上传简历")}</button>{resumeOptions.map((resume) => option({ type: "RESUME", id: resume.id, label: resume.originalFilename }, <FileSearch className="size-4 shrink-0 text-blue-500" />))}{recapOptions.map((recap) => option({ type: "RECAP", id: recap.id, label: recap.title }, <FileText className="size-4 shrink-0 text-violet-500" />))}{knowledgeOptions.map((document) => option({ type: "KNOWLEDGE", id: document.id, label: document.title }, <BookOpenText className="size-4 shrink-0 text-cyan-600" />))}{learningOptions.map((plan) => option({ type: "LEARNING", id: plan.id, label: plan.concept }, <BrainCircuit className="size-4 shrink-0 text-rose-500" />))}</div>}</div>;
  }

  if (!state && !error) return <main className="ardor-workbench grid h-full place-items-center text-sm text-stone-500">{t("Waking Ardor…", "正在唤醒 Ardor…")}</main>;
  const selected = conversations.find((item) => item.id === state?.conversationId);
  const visibleError = error && !retryFailures.some((failure) =>
    failure.detail === error || failure.detail.endsWith(` · ${error}`)
  ) ? error : "";

  return (
    <main className="ardor-workbench h-full overflow-hidden text-stone-900">
      <div className="flex h-full">
        {sidebarOpen === true && <button aria-label={t("Close conversations", "关闭会话栏")} className="fixed inset-0 z-20 bg-black/20 md:hidden" onClick={() => setSidebarOpen(false)} />}
        <aside aria-label={t("Conversations", "对话栏")} aria-hidden={sidebarOpen === false} inert={sidebarOpen === false} className={`fixed inset-y-0 left-16 z-40 shrink-0 flex-col border-r border-stone-200/70 bg-[#f8f6f2] p-3 shadow-2xl md:static md:z-20 md:shadow-none ${sidebarOpen === false ? "hidden" : sidebarOpen === true ? "flex" : "hidden md:flex"}`} style={{ width: "min(18rem, calc(100vw - 4rem))" }}>
          <div className="flex h-12 items-center justify-between px-2">
            <WorkspaceBrand admin={isAdmin} />
            <div className="flex items-center"><ChatNotifications /><button aria-label={t("Search chats", "搜索对话")} onClick={() => { setSearchOpen((value) => !value); setRecentOpen(true); setChatSearch(""); }} className="rounded-lg p-2 text-stone-500 hover:bg-white"><Search className="size-4" /></button></div>
          </div>
          <button data-new-chat type="button" onClick={newConversation} disabled={busy} className="mt-3 flex h-11 items-center gap-2 rounded-xl border border-stone-200 bg-white px-3 text-sm font-medium shadow-sm transition hover:border-stone-300 hover:shadow disabled:opacity-50"><Plus className="size-4" />{t("New chat", "新对话")}</button>

          <div className="mt-4 min-h-0 flex-1 overflow-y-auto">
            {searchOpen && <input autoFocus aria-label={t("Search chat titles", "搜索对话标题")} value={chatSearch} onChange={(event) => setChatSearch(event.target.value)} placeholder={t("Search chat titles…", "搜索对话标题…")} className="mb-3 w-full rounded-xl border border-violet-100 bg-white px-3 py-2 text-sm outline-none focus:border-violet-300" />}
            <button type="button" aria-expanded={recentOpen} aria-controls="recent-conversations" onClick={() => setRecentOpen((value) => !value)} className="flex w-full items-center gap-2 px-2 pb-2 text-[11px] font-medium uppercase tracking-[0.14em] text-stone-400"><ChevronDown className={`size-3 transition-transform ${recentOpen ? "" : "-rotate-90"}`} />{t("Recent chats", "最近对话")}</button>
            {recentOpen && <div id="recent-conversations" className="space-y-1">
              {conversations.filter((item) => item.title.toLocaleLowerCase().includes(chatSearch.trim().toLocaleLowerCase())).map((conversation) => (
                <div key={conversation.id} className={`group relative rounded-xl ${conversation.id === state?.conversationId ? "bg-white shadow-sm" : "hover:bg-white/70"}`}>
                  <button type="button" onClick={() => selectConversation(conversation.id)} className="flex w-full items-center gap-2 px-3 py-2.5 pr-10 text-left text-sm">{conversation.pinned && <Pin className="size-3.5 shrink-0 fill-stone-700 text-stone-700" />}<span className="truncate">{conversation.title}</span></button>
                  <button type="button" aria-label={t("Chat actions", "会话操作")} onClick={() => setMenuId(menuId === conversation.id ? null : conversation.id)} className="absolute right-1.5 top-1.5 rounded-lg p-2 text-stone-400 opacity-70 hover:bg-stone-100 hover:text-stone-700 md:opacity-0 md:group-hover:opacity-100"><MoreHorizontal className="size-4" /></button>
                  {menuId === conversation.id && (
                    <div className="absolute right-2 top-10 z-20 w-36 rounded-xl border border-stone-200 bg-white p-1.5 text-sm shadow-xl">
                      <button onClick={() => togglePin(conversation)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Pin className={`size-3.5 ${conversation.pinned ? "fill-current" : ""}`} />{conversation.pinned ? t("Unpin", "取消置顶") : t("Pin", "置顶")}</button>
                      <button onClick={() => openRename(conversation)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Pencil className="size-3.5" />{t("Rename", "重命名")}</button>
                      <button onClick={() => removeConversation(conversation.id, false)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 hover:bg-stone-100"><Archive className="size-3.5" />{t("Archive", "归档")}</button>
                      <button onClick={() => removeConversation(conversation.id, true)} className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-red-600 hover:bg-red-50"><Trash2 className="size-3.5" />{t("Delete permanently", "永久删除")}</button>
                    </div>
                  )}
                </div>
              ))}
              {conversations.length === 0 && <p className="px-2 py-3 text-xs leading-5 text-stone-400">{t("New chats will appear here.", "新对话会出现在这里。")}</p>}
              {chatSearch.trim() && !conversations.some((item) => item.title.toLocaleLowerCase().includes(chatSearch.trim().toLocaleLowerCase())) && <p className="px-2 py-3 text-xs text-stone-400">{t("No matching chats.", "没有匹配的对话。")}</p>}
            </div>}
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
              <button aria-label={t("Toggle conversations", "折叠对话栏")} title={t("Recent chats", "最近对话")} onClick={() => setSidebarOpen((value) => !(value ?? window.matchMedia("(min-width: 768px)").matches))} className="ardor-chat-toggle rounded-xl p-2 text-stone-500 hover:bg-white"><PanelLeftOpen className="size-5" /></button>
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
                    {selectedContexts.some((context) => context.type !== "SERVICE") && <div className="mx-3 mt-2 flex flex-wrap gap-1.5">{selectedContexts.filter((context) => context.type !== "SERVICE").map((context) => <div key={`${context.type}-${context.id}`} className="inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{context.label}</span><button type="button" aria-label={t("Remove attachment", "移除资料")} onClick={() => toggleContext(context)}><X className="size-3.5" /></button></div>)}</div>}
              {servicePicker()}
                    <textarea aria-label={t("Message Ardor", "给 Ardor 发消息")} value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={!state?.llmConfigured} rows={3} placeholder={state?.llmConfigured ? typedHint : t("Set up a model first", "请先完成模型设置")} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
                    <div className="flex items-center justify-between gap-3 px-2 pb-1"><div className="flex items-center gap-2">{attachmentPicker()}{reasoningPicker()}</div><div className="flex items-center gap-3">{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}<Button aria-label={t("Send", "发送")} disabled={(busy && !streamAccepted) || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div></div>
                  </form>
                </div>
              </div>
            ) : (
              <div aria-live="polite" className="ardor-chat-enter mx-auto w-full max-w-3xl space-y-8 px-4 py-10 md:px-8">
                {messages.map((message) => (
                  <article key={message.id} className={`flex gap-3 ${message.role === "USER" ? "justify-end" : "justify-start"}`}>
                    {message.role === "USER" ? (
                      <div className="max-w-[86%] rounded-3xl bg-stone-200/65 px-5 py-3 text-sm leading-7">{message.contextReferences && message.contextReferences.length > 0 && <div className="mb-2 flex flex-wrap gap-1.5">{message.contextReferences.map((context) => <span key={`${context.type}-${context.id}`} className="inline-flex max-w-full items-center gap-1 rounded-full bg-white/70 px-2.5 py-1 text-[11px] text-stone-600"><Paperclip className="size-3" />{context.type === "SERVICE" ? <Link href={featureHref(features.find((feature) => feature.id === context.id)?.key)} className="truncate text-violet-700 underline underline-offset-2">{locale === "en" ? features.find((feature) => feature.id === context.id)?.englishLabel ?? context.label : context.label}</Link> : <span className="truncate">{context.label}</span>}</span>)}</div>}<div className="whitespace-pre-wrap">{message.content}</div></div>
                    ) : (
                      <div className="max-w-full py-0.5 text-sm leading-7 text-stone-800">
                        <div className="ardor-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown></div>
                        <PublicThinking content={savedRunTrace(message.runTrace)?.thinkingOutput} />
                        {savedRunTrace(message.runTrace) && (() => { const trace = savedRunTrace(message.runTrace)!; return <details className="mt-3 max-w-xl rounded-2xl border border-white/70 bg-white/45 px-4 py-3 text-stone-600 shadow-sm"><summary className="cursor-pointer list-none text-xs font-medium">Ardor 已完成 <span className="float-right font-normal tabular-nums text-stone-400">{formatElapsed(trace.elapsedMs)}</span></summary><div className="mt-3 space-y-2 border-l border-stone-200 pl-4">{trace.steps.map((step, index) => <div key={`${step.label}-${index}`} className="flex items-center gap-2 text-xs"><CheckCircle2 className={`size-3.5 ${step.status === "FAILED" ? "text-rose-500" : "text-emerald-500"}`} /><span>{step.label}</span><span className="ml-auto tabular-nums text-stone-400">{formatElapsed(step.elapsedMs)}</span></div>)}</div></details>; })()}
                      </div>
                    )}
                  </article>
                ))}
                {runningRun && streamProgress?.answerPartial && <div aria-label={t("Reply in progress", "正在生成的回复")} className="ardor-markdown max-w-full text-sm leading-7 text-stone-800"><ReactMarkdown remarkPlugins={[remarkGfm]}>{streamProgress.answerPartial}</ReactMarkdown></div>}
                {!runningRun && !busy && streamProgress?.answerPartial && streamProgress.phase !== "COMPLETED" && <details className="max-w-xl rounded-2xl border border-stone-200/70 bg-white/50 p-4"><summary className="cursor-pointer text-xs text-stone-500">{t("Incomplete reply · saved before execution stopped", "未完成回复 · 停止前保留的片段")}</summary><div className="ardor-markdown mt-3 text-sm leading-7"><ReactMarkdown remarkPlugins={[remarkGfm]}>{streamProgress.answerPartial}</ReactMarkdown></div></details>}
                <ToolApprovals key={runningRun?.id ?? approvalRunId} requestId={runningRun?.id ?? approvalRunId} />
                <QueuedMessages key={state?.conversationId} conversationId={state?.conversationId ?? null} activeRunId={runningRun?.id ?? approvalRunId} onPendingChange={setHasQueuedMessages} onCompleted={load} />
                {confirmations.length > 0 && <section className="space-y-2">
                  {confirmations.map((pending) => <div key={confirmationKey(pending)} className={`flex items-start gap-3 rounded-2xl border px-4 py-3 text-sm shadow-sm ${pending.state === "DONE" ? "border-emerald-100 bg-emerald-50/80" : ["DISMISSED", "REMOVED", "EXPIRED"].includes(pending.state) ? "border-stone-200 bg-white/50" : pending.kind === "learning_plan" ? "border-violet-100 bg-white/80" : "border-rose-100 bg-white/80"}`}>
                    <span className={`mt-0.5 grid size-8 shrink-0 place-items-center rounded-xl ${pending.state === "DONE" ? "bg-emerald-100 text-emerald-600" : pending.kind === "learning_plan" ? "bg-violet-50 text-violet-600" : "bg-rose-50 text-rose-500"}`}>{pending.state === "DONE" ? <Check className="size-4" /> : pending.kind === "learning_plan" ? <BookOpenText className="size-4" /> : <Trash2 className="size-4" />}</span>
                    <div className="min-w-0 flex-1">
                      <p className="font-medium text-stone-800">{pending.kind === "learning_plan"
                        ? pending.state === "DONE" ? (locale === "en" ? "Learning plan created" : "学习计划已创建") : pending.state === "GENERATING" ? (locale === "en" ? "Preparing lesson in the background" : "正在后台准备学习内容") : pending.state === "REMOVED" ? (locale === "en" ? "Plan removed" : "计划已移除") : pending.state === "EXPIRED" ? (locale === "en" ? "Suggested time has passed" : "建议时间已过") : pending.state === "DISMISSED" ? (locale === "en" ? "Not now" : "暂不安排") : pending.state === "DELETING" ? (locale === "en" ? "Submitting plan" : "正在提交计划") : (locale === "en" ? "Suggested learning" : "建议学习")
                        : pending.state === "DONE" ? "已删除" : pending.state === "DISMISSED" ? "已保留" : pending.state === "DELETING" ? "正在删除" : "确认删除"}<span className="ml-1 font-normal">「{pending.label}」</span></p>
                      {pending.detail && pending.state === "PENDING" && <p className="mt-1 text-xs leading-5 text-stone-500">{pending.detail}</p>}
                      {pending.state === "GENERATING" && <p className="mt-1 text-xs text-stone-500">{t("Usually 30–120 seconds. You can continue chatting or come back later.", "通常约 30–120 秒，可以继续聊天或稍后回来。")}</p>}
                      {pending.kind === "learning_plan" && pending.learningPlan?.scheduledAt && pending.state === "PENDING" && <p className="mt-1 text-xs text-stone-400">{new Date(pending.learningPlan.scheduledAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN")}</p>}
                      {pending.kind === "learning_plan" && pending.state === "EXPIRED" && <p className="mt-1 text-xs text-stone-500">{locale === "en" ? "Ask Ardor to suggest a new time." : "请 Ardor 重新建议一个时间。"}</p>}
                      {pending.error && <p className="mt-1 text-xs leading-5 text-rose-600">{pending.error}</p>}
                      {pending.kind === "learning_plan" && pending.createdPlanId && <Link className="mt-1 inline-block text-xs font-medium text-violet-600 hover:underline" href={`/app/learning?id=${pending.createdPlanId}`}>{locale === "en" ? "Open lesson" : "打开学习"}</Link>}
                    </div>
                    {(pending.state === "PENDING" || pending.state === "FAILED") && <div className="flex shrink-0 items-center gap-2">
                      <button type="button" onClick={() => { if (pending.kind === "learning_plan") window.sessionStorage.setItem(dismissedProposalKey(pending), "1"); setConfirmations((current) => current.map((item) => confirmationKey(item) === confirmationKey(pending) ? { ...item, state: "DISMISSED" } : item)); }} className="rounded-full px-3 py-1.5 text-xs font-medium text-stone-500 transition hover:bg-white hover:text-stone-800">{pending.kind === "learning_plan" ? (locale === "en" ? "Not now" : "暂不安排") : "保留"}</button>
                      <button type="button" onClick={() => void (pending.kind === "learning_plan" ? confirmLearning(pending) : confirmDeletion(pending))} className={`rounded-full px-3.5 py-1.5 text-xs font-medium text-white transition ${pending.kind === "learning_plan" ? "bg-violet-600 hover:bg-violet-700" : "bg-rose-600 hover:bg-rose-700"}`}>{pending.kind === "learning_plan" ? (locale === "en" ? "Create plan" : "生成计划") : pending.state === "FAILED" ? "重试删除" : "删除"}</button>
                    </div>}
                  </div>)}
                </section>}
                {(runSteps.length > 0 || runThinking || busy || runningRun || retryFailures.length > 0) && <section className="max-w-xl rounded-2xl border border-white/70 bg-white/55 px-4 py-3 text-sm text-stone-600 shadow-sm backdrop-blur-md">
                  <button type="button" onClick={() => setTraceOpen((open) => !open)} className="flex w-full items-center gap-2 text-left">
                    {traceOpen ? <ChevronDown className="size-4" /> : <ChevronRight className="size-4" />}
                    <span className="font-medium text-stone-700">{busy || runningRun ? "Ardor 正在工作" : error ? "Ardor 未完成" : "Ardor 已完成"}</span>
                    <span className="ml-auto text-xs tabular-nums text-stone-400">{formatElapsed(runElapsed)}</span>
                  </button>
                  {(runningRun || approvalRunId) && <div className="mt-2 flex justify-end"><button type="button" disabled={cancelPending} onClick={() => void cancelExecution()} className="rounded-full px-3 py-1 text-xs text-stone-500 hover:bg-rose-50 hover:text-rose-700 disabled:opacity-50">{cancelPending ? t("Stopping…", "正在停止…") : t("Stop", "停止")}</button></div>}
                  {traceOpen && <div className="mt-3 space-y-2 border-l border-stone-200 pl-4">
                    {runningRun && <div className="flex items-center gap-2 text-xs text-violet-600"><RotateCcw className="size-3.5 animate-spin motion-reduce:animate-none" /><span>{streamProgress?.phase === "WAITING_DATA" ? t("Waiting for new upstream data", "等待上游返回新数据") : streamProgress?.label ?? runningRun.label} · {t("You can refresh to resume", "可刷新页面继续查看")}</span></div>}
                    <PublicThinking content={runThinking} />
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
              {selectedContexts.some((context) => context.type !== "SERVICE") && <div className="mx-3 mt-2 flex flex-wrap gap-1.5">{selectedContexts.filter((context) => context.type !== "SERVICE").map((context) => <div key={`${context.type}-${context.id}`} className="inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{context.label}</span><button type="button" aria-label="移除资料" onClick={() => toggleContext(context)}><X className="size-3.5" /></button></div>)}</div>}
              {servicePicker()}
              <textarea aria-label={t("Message Ardor", "给 Ardor 发消息")} value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={!state?.llmConfigured} rows={2} placeholder={state?.llmConfigured ? "Message Ardor…" : "请先完成模型设置"} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
              <div className="flex items-center justify-between gap-3 px-2 pb-1"><div className="flex items-center gap-2">{attachmentPicker()}{reasoningPicker()}{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}</div><Button aria-label={t("Send", "发送")} disabled={(busy && !streamAccepted) || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div>
            </form>
          </div>}
        </section>
      </div>

      {renaming && <AppDialog title={t("Rename chat", "重命名对话")} onClose={() => { if (!renamePending) setRenaming(null); }}>
        <form onSubmit={renameConversation}>
          <label htmlFor="chat-title" className="text-sm text-stone-500">{t("Chat title", "对话名称")}</label>
          <input id="chat-title" autoFocus value={renameDraft} onFocus={(event) => event.target.select()} onChange={(event) => setRenameDraft(event.target.value)} disabled={renamePending} className="mt-2 w-full rounded-xl border border-stone-200 px-3 py-3 outline-none focus:border-violet-400" />
          {renameError && <p role="alert" className="mt-3 break-words text-sm text-red-600">{renameError}</p>}
          <div className="mt-5 flex justify-end gap-2"><Button type="button" variant="outline" disabled={renamePending} onClick={() => setRenaming(null)}>{t("Cancel", "取消")}</Button><Button disabled={renamePending || !renameDraft.trim()}>{renamePending ? t("Saving…", "保存中…") : t("Save", "保存")}</Button></div>
        </form>
      </AppDialog>}

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
