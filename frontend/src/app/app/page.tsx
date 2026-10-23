"use client";

import {
  Archive, ArrowUp, BookOpenText, Brain, BrainCircuit, Check, ChevronDown, FileSearch, FileText,
  MoreHorizontal, PanelLeftOpen, Paperclip, Pencil, Pin, Plus, RotateCcw, Search, ShieldCheck, Target, Trash2, Upload, X,
} from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useCallback, useEffect, useEffectEvent, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { type AiJob } from "@/lib/ai-jobs";

import { Button } from "@/components/ui/button";
import { AppDialog } from "@/components/ui/app-dialog";
import { ChatNotifications } from "@/components/ardor/chat-notifications";
import { ChatVoiceButton } from "@/components/ardor/chat-voice-button";
import { WorkspaceBrand } from "@/components/ardor/workspace-brand";
import { ReasoningControl, type ModelOption } from "@/components/ardor/reasoning-control";
import { ServicePicker } from "@/components/ardor/service-picker";
import { type ToolMeasurement } from "@/components/ardor/tool-duration";
import { ExecutionTrace } from "@/components/ardor/execution-trace";
import { DeletionPermissions } from "@/components/ardor/deletion-permissions";
import { OperationPermissions } from "@/components/ardor/operation-permissions";
import { LearningPermissions } from "@/components/ardor/learning-permissions";
import type { PlanContext } from "@/components/ardor/learning-context";
import { QueuedMessages } from "@/components/ardor/queued-messages";
import type { AgentFeature } from "@/lib/agent-features";
import { featureHref } from "@/lib/agent-features";
import { useDismiss } from "@/components/ardor/overlay";
import { ApiError, api, streamApi } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { useTypewriter } from "@/lib/use-typewriter";

type SelectedContext = { type: "RESUME" | "RECAP" | "KNOWLEDGE" | "LEARNING" | "SERVICE"; id: string; label: string };
type AgentMessage = { id: string; requestId?: string; runId?: string | null; conversationId: string; role: "USER" | "ASSISTANT"; content: string; contextReferences?: SelectedContext[]; runTrace?: string | null; proposalResolutions?: Record<string, string>; createdAt: string };
type Conversation = { id: string; title: string; pinned: boolean; titleManuallySet?: boolean; createdAt: string; updatedAt: string };
type AgentMemory = { content: string; updatedAt: string | null };
type AgentMemoryItem = { id: string; content: string; kind: string; sourceKind: string; confidence: number; valid: boolean; validUntil?: string | null };
type RetryFailure = { label: string; detail: string };
type AgentStreamEvent = ToolMeasurement & { type: "status" | "phase" | "thinking" | "delta" | "tool_start" | "tool_end" | "confirm" | "done" | "error"; content: string | null; toolName: string | null; label: string | null; elapsedMs: number | null; conversationId: string | null; messageId: string | null; retryable: boolean | null; errorCode: string | null; confirmation: AgentConfirmation | null; toolStatus?: string };
type StreamProgress = { phase: string; label: string; thinkingOutput: string; answerPartial: string; firstThinkingMs: number | null; firstOutputMs: number | null; silentSeconds: number; truncated: boolean; steps?: (ToolMeasurement & { label: string; elapsedMs: number; status: string })[]; elapsedMs?: number };
type AgentRun = { id: string; conversationId: string; message: string; status: "RUNNING" | "COMPLETED" | "FAILED" | "INTERRUPTED"; label: string; errorCode: string | null; errorMessage: string | null; retryable: boolean; createdAt: string; updatedAt: string };
/* A deletion Ardor has proposed. Nothing is gone until the user presses the
   button, and pressing it is an ordinary authenticated DELETE from here — the
   model never gets to destroy anything on its own say-so. */
type LearningPlanProposal = PlanContext & { concept: string; reason: string | null; scheduledAt: string | null; requestId: string };
type AgentConfirmation = { kind: string; targetId: string | null; label: string; detail: string; endpoint: string; targetIds?: string[] | null; sourceMessageId?: string; learningPlan?: LearningPlanProposal | null };
type PendingConfirmation = AgentConfirmation & { state: "PENDING" | "DELETING" | "GENERATING" | "DONE" | "DISMISSED" | "FAILED" | "EXPIRED" | "REMOVED"; error?: string; createdPlanId?: string };
type RunStep = ToolMeasurement & { key: string; label: string; elapsedMs: number; done: boolean };
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
  try { return JSON.parse(raw) as { elapsedMs: number; status: string; steps: Array<ToolMeasurement & { label: string; elapsedMs: number; status: string }>; proposals?: AgentConfirmation[]; thinkingOutput?: string; firstOutputMs?: number }; }
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

function AutoResizeTextarea({ value, onChange, maxLength }: { value: string; onChange: (value: string) => void; maxLength?: number }) {
  const ref = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element) return;
    const resize = () => { element.style.height = "auto"; element.style.height = `${element.scrollHeight}px`; };
    resize();
    window.addEventListener("resize", resize);
    return () => window.removeEventListener("resize", resize);
  }, [value]);
  return <textarea ref={ref} rows={1} value={value} onChange={(event) => onChange(event.target.value)} maxLength={maxLength} className="mt-1.5 w-full resize-none overflow-hidden bg-transparent p-0 text-sm leading-6 text-stone-700 outline-none placeholder:text-stone-400" />;
}

function formatMessageTimestamp(value: string, locale: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  const now = new Date();
  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  return date.toLocaleString(locale, sameDay
    ? { hour: "2-digit", minute: "2-digit" }
    : { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function fullMessageTimestamp(value: string, locale: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleString(locale, {
    year: "numeric", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", second: "2-digit",
  });
}

function conversationTitle(message: string) {  const normalized = message.trim().replace(/\s+/g, " ");
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
  const [queuedWakeId, setQueuedWakeId] = useState<string | null>(null);
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [archivedConversations, setArchivedConversations] = useState<Conversation[]>([]);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [terminalRunId, setTerminalRunId] = useState<string | null>(null);
  const [streamPhase, setStreamPhase] = useState("WAITING_DATA");
  const [feedback, setFeedback] = useState("");
  const stopNotices = useRef(new Set<string>());
  const confirmedOperations = useRef(new Set<string>());
  const notifyStopped = useCallback((id: string) => {
    if (stopNotices.current.has(id)) return;
    stopNotices.current.add(id);
    setFeedback(t("Stopped. Any operation already started may still finish; check its result.", "已停止；已开始的操作仍可能完成，请核验结果。"));
  }, [t]);
  useEffect(() => {
    if (!feedback) return;
    const timer = window.setTimeout(() => setFeedback(""), 5000);
    return () => window.clearTimeout(timer);
  }, [feedback]);
  const [reasoningDepth, setReasoningDepth] = useState<ReasoningDepth>("AUTO");
  // Global quick level: what Ardor may do without asking. Module overrides live in settings.
  const [permissionLevel, setPermissionLevel] = useState(0);
  const [permissionOpen, setPermissionOpen] = useState(false);
  const [nativeReasoning, setNativeReasoning] = useState(false);
  const reasoningAccount = useRef<string | null>(null);
  const pendingDraftParams = useRef<URLSearchParams | null>(null);
  const proposalSources = useRef(new Map<string, string>());
  const draftStorageKey = useCallback((conversationId?: string | null) =>
    `ardor:conversation-draft:${reasoningAccount.current ?? "unavailable"}:${conversationId ?? "new"}`, []);
  const deletionResolutionKey = useCallback((item: AgentConfirmation, conversationId: string | null) =>
    `ardor:deletion-result:${reasoningAccount.current}:${conversationId}:${proposalSources.current.get(item.sourceMessageId ?? "") ?? item.sourceMessageId ?? "live"}:${confirmationKey(item)}`, []);
  function rememberDeletion(item: AgentConfirmation, resolution: "DONE" | "DISMISSED" | "ACCEPTED") {
    try { window.localStorage.setItem(deletionResolutionKey(item, state?.conversationId ?? null), resolution); } catch { /* Snapshot-bound APIs remain safe when local storage is unavailable. */ }
    const source = proposalSources.current.get(item.sourceMessageId ?? "") ?? item.sourceMessageId;
    if (source) void api<void>(`/api/agent/messages/${encodeURIComponent(source)}/proposal-resolution`, {
      method: "PUT", body: JSON.stringify({ key: confirmationKey(item), resolution }),
    }).catch(() => undefined); // A decision made before the message commits is retried on stream completion/load.
  }
  const readSavedDraft = useCallback((conversationId?: string | null) =>
    window.sessionStorage.getItem(draftStorageKey(conversationId)) ?? "", [draftStorageKey]);
  const readDraftReferences = useCallback((conversationId?: string | null): SelectedContext[] => {
    try {
      const value: unknown = JSON.parse(window.sessionStorage.getItem(`${draftStorageKey(conversationId)}:references`) ?? "[]");
      if (!Array.isArray(value)) return [];
      return value.filter((item): item is SelectedContext => item && ["RESUME", "RECAP", "KNOWLEDGE", "LEARNING"].includes(item.type)
        && typeof item.id === "string" && typeof item.label === "string").slice(0, 5);
    } catch { return []; }
  }, [draftStorageKey]);
  const [reasoningReady, setReasoningReady] = useState(false);
  const [modelOptions, setModelOptions] = useState<ModelOption[]>([]);
  const [modelChoice, setModelChoice] = useState("PRIMARY");
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
  const [runElapsed, setRunElapsed] = useState(0);
  const [leavingEmptyState, setLeavingEmptyState] = useState(false);
  const [error, setError] = useState("");
  const [sidebarOpen, setSidebarOpen] = useState<boolean | null>(null);
  const [recentOpen, setRecentOpen] = useState(true);
  const [searchOpen, setSearchOpen] = useState(false);
  const [chatSearch, setChatSearch] = useState("");
  const typedHint = useTypewriter(locale === "en" ? typewriterExamples : typewriterExamplesZh, messages.length === 0);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryItems, setMemoryItems] = useState<AgentMemoryItem[] | null>(null);
  const [showStaleMemory, setShowStaleMemory] = useState(false);
  const [memoryEdits, setMemoryEdits] = useState<Record<string, string>>({});
  const [newMemory, setNewMemory] = useState("");
  const [memorySaving, setMemorySaving] = useState<string | null>(null);
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
  const previousLearningStates = useRef(new Map<string, string>());
  useEffect(() => {
    for (const item of confirmations) {
      if (!item.learningPlan) continue;
      const key = confirmationKey(item);
      if (previousLearningStates.current.get(key) === "GENERATING" && item.state === "DONE") {
        setFeedback(t(`Learning plan created: ${item.label}. Open it from the work record or Learning.`, `学习计划已创建：${item.label}。可从工作记录或学习模块打开。`));
      }
      previousLearningStates.current.set(key, item.state);
    }
  }, [confirmations, t]);

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
      void api<ModelOption[]>("/api/agent/models").then((items) => {
        if (!active) return;
        setModelOptions(items);
        const saved = window.sessionStorage.getItem(`ardor:model-choice:${account.id}`);
        const initial = items.find((item) => item.id === saved && item.available)
          ?? items.find((item) => item.id === "PRIMARY" && item.available)
          ?? items.find((item) => item.available);
        if (initial) {
          setModelChoice(initial.id);
          setNativeReasoning(initial.nativeControl);
        }
      }).catch(() => undefined);
    }).catch(() => undefined);
    void api<{ globalLevel: number }>("/api/agent/permissions")
      .then((result) => { if (active) setPermissionLevel(result.globalLevel ?? 0); }).catch(() => undefined);
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!memoryOpen) return;
    let active = true;
    void api<AgentMemoryItem[]>("/api/agent/memory/items")
      .then((items) => {
        if (!active) return;
        setMemoryItems(items);
        setMemoryEdits(Object.fromEntries(items.map((item) => [item.id, item.content])));
      })
      .catch(() => { if (active) setMemoryItems([]); });
    return () => { active = false; };
  }, [memoryOpen]);

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
      models={modelOptions} model={modelChoice}
      onChange={(value) => {
        setReasoningDepth(value);
        try { if (reasoningAccount.current) window.sessionStorage.setItem(`ardor:reasoning-depth:${reasoningAccount.current}`, value); }
        catch { /* The current selection remains usable when browser storage is unavailable. */ }
      }}
      onModelChange={(value) => {
        const selected = modelOptions.find((item) => item.id === value);
        if (!selected || !selected.available) return;
        setModelChoice(value);
        setNativeReasoning(selected.nativeControl);
        try { if (reasoningAccount.current) window.sessionStorage.setItem(`ardor:model-choice:${reasoningAccount.current}`, value); }
        catch { /* The current selection remains usable when browser storage is unavailable. */ }
      }} />;
  }

  async function choosePermission(level: number) {
    const previous = permissionLevel;
    setPermissionLevel(level);
    setPermissionOpen(false);
    try { await api<void>("/api/agent/permissions/*", { method: "PUT", body: JSON.stringify({ level }) }); }
    catch { setPermissionLevel(previous); }
  }

  function permissionPicker() {
    const labels = [t("View only", "仅查看"), t("View & add", "查 + 增"), t("View, add & edit", "查 + 增 + 改"), t("Full access", "完全权限")];
    return <div className="relative">
      <button type="button" aria-haspopup="menu" aria-expanded={permissionOpen} onClick={() => setPermissionOpen((value) => !value)} title={t("What Ardor may do without asking", "Ardor 可以不经询问直接执行的范围")} className="inline-flex items-center gap-1.5 rounded-full px-3 py-2 text-xs text-stone-500 transition hover:bg-stone-100">
        <ShieldCheck className="size-3.5" aria-hidden="true" />{labels[permissionLevel] ?? labels[0]}<ChevronDown className="size-3.5" aria-hidden="true" />
      </button>
      {permissionOpen && <>
        <button type="button" aria-label={t("Close permission menu", "关闭权限菜单")} className="fixed inset-0 z-10 cursor-default bg-transparent" onClick={() => setPermissionOpen(false)} />
        <div role="menu" className="absolute bottom-full left-0 z-20 mb-2 w-56 rounded-2xl border border-stone-200 bg-white p-1 shadow-xl">
          {labels.map((label, level) => <button key={level} role="menuitemradio" aria-checked={permissionLevel === level} type="button" onClick={() => void choosePermission(level)} className={`flex w-full items-center justify-between rounded-xl px-3 py-2 text-left text-xs transition ${permissionLevel === level ? "bg-violet-50 font-medium text-violet-700" : "hover:bg-stone-50"}`}><span>{label}</span>{permissionLevel === level && <Check className="size-3.5" />}</button>)}
          <p className="px-3 pb-1 pt-2 text-[11px] leading-4 text-stone-400">{t("Anything above this level still asks first; deleting always asks.", "超出该等级的操作仍会先询问；删除始终需要确认。")}</p>
        </div>
      </>}
    </div>;
  }

  const selectChatFromRail = useEffectEvent((event: Event) => {
    const id = (event as CustomEvent<unknown>).detail;
    if (typeof id === "string" && id) void selectConversation(id);
  });

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
    window.addEventListener("ardor:select-chat", selectChatFromRail);
    return () => {
      window.removeEventListener("ardor:open-memory", openMemory);
      window.removeEventListener("ardor:close-chat-drawer", closeDrawer);
      window.removeEventListener("ardor:toggle-chat-sidebar", toggleSidebar);
      window.removeEventListener("ardor:new-chat", newChat);
      window.removeEventListener("ardor:select-chat", selectChatFromRail);
    };
  }, []);

  useEffect(() => {
    const desktop = window.matchMedia("(min-width: 768px)");
    const publish = () => window.dispatchEvent(new CustomEvent("ardor:chat-sidebar-state", { detail: sidebarOpen ?? desktop.matches }));
    desktop.addEventListener("change", publish);
    window.addEventListener("ardor:request-chat-sidebar-state", publish);
    publish();
    return () => { desktop.removeEventListener("change", publish); window.removeEventListener("ardor:request-chat-sidebar-state", publish); };
  }, [sidebarOpen]);

  const applyState = useCallback((result: AgentState) => {
    // An authenticated conversation response proves ownership of this UUID, so
    // its legacy draft can be migrated. The shared old "new" slot has no owner.
    if (result.conversationId) {
      const legacyKey = `ardor:conversation-draft:${result.conversationId}`;
      const legacyDraft = window.sessionStorage.getItem(legacyKey);
      if (legacyDraft != null && window.sessionStorage.getItem(draftStorageKey(result.conversationId)) == null) {
        window.sessionStorage.setItem(draftStorageKey(result.conversationId), legacyDraft);
        window.sessionStorage.removeItem(legacyKey);
      }
      const legacyServiceKey = `${legacyKey}:service:${reasoningAccount.current}`;
      const legacyService = window.sessionStorage.getItem(legacyServiceKey);
      if (legacyService) {
        window.sessionStorage.setItem(`${draftStorageKey(result.conversationId)}:service:${reasoningAccount.current}`, legacyService);
        window.sessionStorage.removeItem(legacyServiceKey);
      }
    }
    setState(result);
    // A committed response owns its process record. Discard the transient copy.
    setRunSteps([]); setRunThinking(""); setStreamProgress(null); setRetryFailures([]); setRunElapsed(0);
    const outgoing = readOutgoing(reasoningAccount.current, result.conversationId);
    const outgoingSaved = outgoing && result.messages.some((item) => item.id === outgoing.id || item.role === "USER" && item.requestId &&
      (outgoing.requestId ? item.requestId === outgoing.requestId : item.content === outgoing.content));
    if (outgoingSaved && result.conversationId) window.sessionStorage.removeItem(outgoingKey(reasoningAccount.current, result.conversationId));
    setMessages(outgoing && !outgoingSaved ? [...result.messages, outgoing] : result.messages);
    const proposals = [...new Map<string, AgentConfirmation>(result.messages.flatMap((message) => (savedRunTrace(message.runTrace)?.proposals ?? []).map(item => ({ ...item, sourceMessageId: message.id })))
      .map((item): [string, AgentConfirmation] => [confirmationKey(item), item])).values()].slice(-50);
    const restored = proposals.map((item) => {
      let resolution: string | null = null;
      try { resolution = window.localStorage.getItem(deletionResolutionKey(item, result.conversationId)); } catch { /* No automatic permission. */ }
      const source = result.messages.find(message => message.id === item.sourceMessageId);
      resolution = source?.proposalResolutions?.[confirmationKey(item)] ?? resolution;
      if (resolution === "DONE" || resolution === "DISMISSED" || resolution === "ACCEPTED") {
        void api<void>(`/api/agent/messages/${encodeURIComponent(item.sourceMessageId!)}/proposal-resolution`, {
          method: "PUT", body: JSON.stringify({ key: confirmationKey(item), resolution }),
        }).catch(() => undefined);
      }
      const unsafeLegacyBulk = ["all_memories", "all_memory_cards"].includes(item.kind) && !Array.isArray(item.targetIds);
      // Historical proposals are records, not fresh requests. Never reopen an old
      // prompt whose legacy decision cannot be recovered (including another device).
      const stale = !source || !Number.isFinite(Date.parse(source.createdAt)) || Date.now() - Date.parse(source.createdAt) > 30 * 60 * 1000
        || source.id !== result.messages.at(-1)?.id;
      return { ...item, state: item.kind === "learning_plan" && window.sessionStorage.getItem(dismissedProposalKey(item)) === "1"
        ? "DISMISSED" as const : resolution === "DONE" ? "DONE" as const : resolution === "DISMISSED" ? "DISMISSED" as const : resolution === "ACCEPTED" && item.kind === "learning_plan" ? "GENERATING" as const : unsafeLegacyBulk || proposalExpired(item) || stale || resolution === "EXPIRED" ? "EXPIRED" as const : "PENDING" as const };
    });
    // Queued calls have no open browser stream: recover their proposals without executing them.
    setConfirmations((current) => {
      const messageIds = new Set(result.messages.map(message => message.id));
      const merged = new Map(current.filter((item) => item.sourceMessageId && messageIds.has(item.sourceMessageId)).map((item) => [confirmationKey(item), item]));
      for (const item of restored) {
        const existing = merged.get(confirmationKey(item));
        const settled = ["DONE", "DISMISSED", "EXPIRED", "REMOVED"].includes(item.state);
        merged.set(confirmationKey(item), existing && existing.sourceMessageId === item.sourceMessageId && !settled ? { ...item, ...existing } : item);
      }
      return [...merged.values()];
    });
    const learningProposals = proposals.filter((item) => item.kind === "learning_plan" && item.learningPlan?.requestId);
    if (learningProposals.length) {
      const query = new URLSearchParams();
      learningProposals.forEach((item) => query.append("requestIds", item.learningPlan!.requestId));
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
    setDraft(readSavedDraft(result.conversationId));
    const serviceId = window.sessionStorage.getItem(`${draftStorageKey(result.conversationId)}:service:${reasoningAccount.current}`);
    const feature = featuresRef.current.find((item) => item.id === serviceId);
    setSelectedContexts([...readDraftReferences(result.conversationId), ...(feature ? [{ type: "SERVICE" as const, id: feature.id, label: feature.label }] : [])].slice(-5));
  }, [draftStorageKey, readSavedDraft, readDraftReferences, deletionResolutionKey]);

  const load = useCallback(async (conversationId?: string | null) => {
    const query = conversationId ? `?conversationId=${encodeURIComponent(conversationId)}` : "";
    applyState(await api<AgentState>(`/api/agent${query}`));
  }, [applyState]);

  useEffect(() => {
    let cancelled = false;
    // Consume one-time draft links before any asynchronous work. Updating history
    // after loading could overwrite a module navigation the user just started.
    const incoming = new URLSearchParams(window.location.search);
    const hasDraftLink = incoming.get("new") === "1"
      && (incoming.has("prefill") || incoming.has("feature") || incoming.has("contextId"));
    if (hasDraftLink) pendingDraftParams.current = incoming;
    // React's development double-invoke runs this effect again after the URL has
    // already been cleaned. Keep the one-time draft link in a ref so the second
    // pass still receives the prefill instead of silently dropping it.
    const params = hasDraftLink ? incoming : pendingDraftParams.current ?? incoming;
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
          if ((contextType === "RESUME" || contextType === "RECAP" || contextType === "KNOWLEDGE" || contextType === "LEARNING") && contextId && contextLabel) window.sessionStorage.setItem(`${draftStorageKey(null)}:references`, JSON.stringify([{ type: contextType, id: contextId, label: contextLabel }]));
        }
        if (params.get("notice") === "recap-queued") setNotice("原始转录已保存。请到面试纪要中趁记忆新鲜校正初稿；你确认后才会生成详细复盘。");
        applyState(result);
        pendingDraftParams.current = null;
      })
      .catch((reason) => {
        if (cancelled) return;
        if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
        setError(reason instanceof Error ? reason.message : "无法载入 Agent");
      });
    return () => { cancelled = true; };
  }, [applyState, router, draftStorageKey]);

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
          setTerminalRunId(null);
          runStartedAt.current = new Date(run.createdAt).getTime();
          setRunningRun(run);
          const outgoing = readOutgoing(reasoningAccount.current, conversationId);
          // The outbox can belong to a stopped predecessor. Never reuse its text for a new run.
          const matchesRun = outgoing?.requestId ? outgoing.requestId === run.id : outgoing?.content === run.message;
          const recovered: AgentMessage = outgoing && matchesRun ? outgoing : {
            id: `pending-user-message-${run.id}`, conversationId, role: "USER", content: run.message, createdAt: run.createdAt,
          };
          setMessages((current) => current.some((item) => item.id === recovered.id) ? current : [...current, recovered]);
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
          setTerminalRunId(run.id);
          if (run.status !== "COMPLETED") {
            if (run.errorCode !== "AGENT_RUN_CANCELLED") setError(run.errorMessage ?? "请求未完成，请检查操作结果后再试");
            try {
              const progress = await api<StreamProgress>(`/api/agent/runs/${encodeURIComponent(run.id)}/progress`);
              if (!stopped) {
                setStreamProgress(progress); setRunThinking(progress.thinkingOutput);
                setRunElapsed(progress.elapsedMs ?? 0);
                setRunSteps((progress.steps ?? []).map((step, index) => ({ ...step, key: `recovered-${index}`, done: true })));
              }
            } catch { /* Old runs may predate durable snapshots. Their status remains visible. */ }
          }
          if (previousStatus === "RUNNING") {
            previousStatus = run.status;
            if (run.status === "COMPLETED") {
              window.sessionStorage.removeItem(outgoingKey(reasoningAccount.current, conversationId));
              await load(conversationId);
            } else {
              if (run.errorCode === "AGENT_RUN_CANCELLED") notifyStopped(run.id);
              else setError(run.errorMessage ?? "请求未完成，请检查操作结果后再试");
              await load(conversationId);
            }
          } else if (readSavedDraft(conversationId) === run.message) {
            if (run.status === "COMPLETED") {
              window.sessionStorage.removeItem(draftStorageKey(conversationId));
              setDraft((current) => current === run.message ? "" : current);
            } else if (run.errorCode !== "AGENT_RUN_CANCELLED") setError(run.errorMessage ?? "请求未完成，请检查操作结果后再试");
          }
        }
      } catch (reason) {
        if (reason instanceof ApiError && reason.status === 404 && !stopped) setRunningRun(null);
        else if (!stopped && previousStatus === "RUNNING") timer = window.setTimeout(() => void check(), 5000);
      }
    };
    void check();
    return () => { stopped = true; if (timer) window.clearTimeout(timer); };
  }, [state?.conversationId, busy, load, runningRunId, queuedWakeId, draftStorageKey, readSavedDraft, notifyStopped]);
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
    const serviceId = window.sessionStorage.getItem(`${draftStorageKey(null)}:service:${reasoningAccount.current}`);
    const feature = featuresRef.current.find((item) => item.id === serviceId);
    setSelectedContexts([...readDraftReferences(null), ...(feature ? [{ type: "SERVICE" as const, id: feature.id, label: feature.label }] : [])].slice(-5));
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
      await api<void>(pending.endpoint, { method: "DELETE", ...(pending.targetIds ? { body: JSON.stringify({ ids: pending.targetIds }) } : {}) });
      rememberDeletion(pending, "DONE");
      setFeedback(t(`Deleted “${pending.label}”.`, `已删除「${pending.label}」。`));
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
      if (job.status !== "FAILED") rememberDeletion(pending, job.status === "COMPLETED" ? "DONE" : "ACCEPTED");
      if (job.status === "FAILED" && pending.state === "FAILED") job = await api<AiJob>(`/api/ai-jobs/${job.id}/retry`, { method: "POST" });
      setConfirmations((current) => current.map((item) => confirmationKey(item) === key ? {
        ...item, state: job.status === "COMPLETED" ? "DONE" : job.status === "FAILED" ? "FAILED" : "GENERATING",
        error: job.status === "FAILED" ? job.errorMessage ?? "学习生成未完成" : undefined,
        createdPlanId: job.status === "COMPLETED" && job.resultPath ? new URL(job.resultPath, window.location.origin).searchParams.get("id") ?? undefined : undefined,
      } : item));
      window.dispatchEvent(new Event("ardor:background-refresh"));
      setFeedback(job.status === "COMPLETED" ? t("Learning plan created. Details are in the work record.", "学习计划已创建，详情保留在工作记录中。")
        : job.status === "FAILED" ? t("Lesson generation failed. Check the suggestion before retrying.", "学习生成失败，请检查建议后重试。")
        : t("Confirmed. Preparing the lesson in the background; you can continue chatting.", "已确认，正在后台准备学习内容，可以继续聊天。"));
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
      const payload = { conversationId: state.conversationId, message, reasoningDepth, modelChoice,
        contextReferences: selectedContexts.map(({ type, id }) => ({ type, id })) };
      const slot = `ardor:queue-submit:${reasoningAccount.current}:${state.conversationId}`;
      try {
        const stored = JSON.parse(window.sessionStorage.getItem(slot) ?? "null");
        const sameSubmission = stored?.fingerprint === JSON.stringify(payload);
        const requestId = sameSubmission ? stored.requestId : crypto.randomUUID();
        const interruptedRunId = sameSubmission && "interruptedRunId" in stored ? stored.interruptedRunId : runningRun?.id ?? approvalRunId;
        window.sessionStorage.setItem(slot, JSON.stringify({ requestId, fingerprint: JSON.stringify(payload), interruptedRunId }));
        await api("/api/agent/messages/steer", { method: "POST", body: JSON.stringify({ ...payload, requestId, interruptedRunId }) });
        window.sessionStorage.removeItem(slot);
        const unchanged = readSavedDraft(state.conversationId).trim() === message;
        if (unchanged) {
          window.sessionStorage.removeItem(draftStorageKey(state.conversationId));
        }
        setDraft((current) => current.trim() === message ? "" : current);
        if (unchanged) setSelectedContexts((current) => {
          if (JSON.stringify(current.map(({ type, id }) => ({ type, id }))) !== JSON.stringify(payload.contextReferences)) return current;
          window.sessionStorage.removeItem(`${draftStorageKey(state.conversationId)}:service:${reasoningAccount.current}`);
          window.sessionStorage.removeItem(`${draftStorageKey(state.conversationId)}:references`);
          return [];
        });
        setHasQueuedMessages(true);
        window.dispatchEvent(new Event("ardor:queue-refresh"));
      } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not confirm queue submission. Retry uses the same request ID.", "暂时无法确认排队结果，重试会复用请求标识。")); }
      finally { queueSubmitting.current = false; }
      return;
    }
    const optimisticId = `pending-user-message-${crypto.randomUUID()}`;
    let requestId = crypto.randomUUID();
    const sentContexts = [...selectedContexts];
    let conversationId = state?.conversationId ?? null;
    let acceptedRun = false;
    const sendControl = new AbortController();
    activeSend.current = sendControl;
    let savedDraftKey = draftStorageKey(conversationId);
    // The sent payload has a separate outbox. Never keep it in the editable next-message draft.
    window.sessionStorage.removeItem(savedDraftKey);
    window.sessionStorage.removeItem(`${savedDraftKey}:service:${reasoningAccount.current}`);
    window.sessionStorage.removeItem(`${savedDraftKey}:references`);
    setDraft("");
    setSelectedContexts([]);
    setError(""); setRetryFailures([]); setRunSteps([]); setRunThinking(""); setStreamProgress(null); setTerminalRunId(null); setStreamPhase("WAITING_DATA");
    setConfirmations((current) => current.filter((item) => item.kind === "learning_plan"));
    setRunElapsed(0); runStartedAt.current = Date.now(); setStreamAccepted(false); setBusy(true);
    try {
      if (!conversationId) {
        const conversation = await api<Conversation>("/api/agent/conversations", { method: "POST" });
        conversationId = conversation.id;
        const conversationDraftKey = draftStorageKey(conversationId);
        const nextDraft = readSavedDraft(null);
        if (nextDraft) window.sessionStorage.setItem(conversationDraftKey, nextDraft);
        const nextReferences = readDraftReferences(null);
        if (nextReferences.length) window.sessionStorage.setItem(`${conversationDraftKey}:references`, JSON.stringify(nextReferences));
        window.sessionStorage.removeItem(`${savedDraftKey}:references`);
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
      const optimistic: AgentMessage = { id: optimisticId, requestId, conversationId: activeConversationId, role: "USER", content: message, contextReferences: sentContexts, createdAt: "" };
      if (messages.length === 0) {
        setLeavingEmptyState(true);
        await new Promise((resolve) => window.setTimeout(resolve, 260));
      }
      window.sessionStorage.setItem(outgoingKey(reasoningAccount.current, activeConversationId), JSON.stringify(optimistic));
      setMessages((current) => [...current, optimistic]);
      setLeavingEmptyState(false);
      const pendingAssistantId = `pending-assistant-${crypto.randomUUID()}`;
      let assistant: AgentMessage | null = null;
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
          await streamApi<AgentStreamEvent>("/api/agent/messages/stream", { requestId, conversationId: activeConversationId, message, reasoningDepth, modelChoice, contextReferences: sentContexts.map(({ type, id }) => ({ type, id })) }, (event) => {
            // A stop can race with initial acceptance. Only a real server event
            // proves this run exists; never send cancellation to a future UUID.
            if (sendControl.signal.aborted && event.type !== "error" && !stopDispatched) {
              stopDispatched = true;
              void stopRunOnce(requestId).catch((reason) => setError(reason instanceof Error ? reason.message : t("Could not confirm stop request", "暂时无法确认停止请求")));
            }
            if (event.type !== "error") setStreamAccepted(true);
            if (event.type === "thinking" && event.content) {
              setStreamPhase("THINKING");
              setRunThinking((current) => (current + event.content).slice(0, 32_000));
            } else if (event.type === "delta" && event.content) {
              setStreamPhase("OUTPUT");
              receivedDelta = true;
              setMessages((current) => current.map((item) => item.id === pendingAssistantId ? { ...item, content: item.content + event.content } : item));
            } else if (event.type === "phase" && event.label) {
              setStreamPhase(event.content ?? "PLAN");
              setRunSteps((current) => [...current, { key: `phase-${event.content ?? "phase"}-${current.length}`, label: event.label!, elapsedMs: event.elapsedMs ?? 0, done: true }]);
            } else if (event.type === "status" && event.label) {
              setStreamPhase("WAITING_DATA");
              setRunSteps((current) => current.some((step) => step.key === "status") ? current.map((step) => step.key === "status" ? { ...step, label: event.label! } : step) : [...current, { key: "status", label: event.label!, elapsedMs: 0, done: false }]);
            } else if (event.type === "tool_start" && event.toolName) {
              setStreamPhase("TOOL");
              receivedToolEvent = true;
              setRunSteps((current) => [...current, { key: `${event.toolName}-${current.length}`, label: event.label ?? "调用工具", elapsedMs: 0, done: false }]);
            } else if (event.type === "tool_end" && event.toolName) {
              setStreamPhase("WAITING_DATA");
              if (confirmedOperations.current.delete(event.toolName)) setFeedback(event.toolStatus === "COMPLETED"
                ? t("Operation completed. Details are in the work record.", "操作已完成，详情保留在工作记录中。")
                : t("The operation did not complete successfully. Check the work record.", "操作未成功完成，请查看工作记录。"));
              setRunSteps((current) => { const index = current.findLastIndex((step) => step.key.startsWith(`${event.toolName}-`) && !step.done); return index < 0 ? current : current.map((step, i) => i === index ? { ...step, label: event.label ?? step.label, elapsedMs: event.elapsedMs ?? 0, approvalWaitMs: event.approvalWaitMs, businessMs: event.businessMs, status: event.toolStatus, done: true } : step); });
              if (["analyze_resume", "organize_interview_recap", "research_knowledge_from_web"].includes(event.toolName)) window.dispatchEvent(new Event("ardor:background-refresh"));
            } else if (event.type === "done" && event.messageId && event.conversationId) {
              const currentConversation = conversations.find((item) => item.id === event.conversationId);
              if (!currentConversation?.titleManuallySet) setTitleWatch({ id: event.conversationId, title: currentConversation?.title ?? conversationTitle(message) });
              assistant = { id: event.messageId, conversationId: event.conversationId, role: "ASSISTANT", content: event.content ?? "", createdAt: new Date().toISOString() };
              proposalSources.current.set(requestId, event.messageId);
              setConfirmations((current) => current.map((item) => {
                if (item.sourceMessageId !== requestId) return item;
                const saved = { ...item, sourceMessageId: event.messageId! };
                if (item.state === "DONE" || item.state === "DISMISSED" || item.state === "GENERATING") {
                  const resolution = item.state === "GENERATING" ? "ACCEPTED" : item.state;
                  try { window.localStorage.setItem(deletionResolutionKey(saved, event.conversationId), resolution); } catch { /* Snapshot APIs remain bounded. */ }
                  void api<void>(`/api/agent/messages/${encodeURIComponent(event.messageId!)}/proposal-resolution`, {
                    method: "PUT", body: JSON.stringify({ key: confirmationKey(saved), resolution }),
                  }).catch(() => undefined);
                }
                return saved;
              }));
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
              const proposal = { ...event.confirmation, sourceMessageId: requestId };
              setConfirmations((current) => current.some((item) => confirmationKey(item) === confirmationKey(proposal))
                ? current.map((item) => confirmationKey(item) === confirmationKey(proposal) ? item.sourceMessageId === requestId ? item : { ...proposal, state: "PENDING" } : item)
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
            if (recorded?.errorMessage) throw Object.assign(new Error(recorded.errorMessage), { code: recorded.errorCode });
            throw reason;
          }
          // A 404 can race with a slow first POST. Reuse its ID so a late arrival cannot execute twice.
          if (recorded?.status === "FAILED") {
            requestId = crypto.randomUUID(); setApprovalRunId(requestId);
            window.sessionStorage.setItem(outgoingKey(reasoningAccount.current, activeConversationId), JSON.stringify({ ...optimistic, requestId }));
            setMessages((current) => current.map((item) => item.id === optimisticId ? { ...item, requestId } : item));
          }
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
        setSelectedContexts((current) => {
          const restored = current.length ? current : sentContexts;
          window.sessionStorage.setItem(`${savedDraftKey}:references`, JSON.stringify(restored.filter(item => item.type !== "SERVICE")));
          return restored;
        });
      }
      if (sendControl.signal.aborted || (reason as { code?: string }).code === "AGENT_RUN_CANCELLED") {
        setTerminalRunId(requestId);
        setError(""); notifyStopped(requestId);
        if (conversationId) await load(conversationId);
      } else setError(reason instanceof Error ? reason.message : "Agent 暂时无法回复");
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
      notifyStopped(requestId);
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
      setSelectedContexts((items) => {
        const next = [...items.filter((item) => !(item.type === context.type && item.id === context.id)), context].slice(-5);
        window.sessionStorage.setItem(`${draftStorageKey(state?.conversationId)}:references`, JSON.stringify(next.filter(item => item.type !== "SERVICE")));
        return next;
      });
      setAttachmentOpen(false); setNotice("简历已加入资料库，并附加到这条消息。你可以让 Ardor 分析或管理它。");
    } catch (reason) { setError(reason instanceof Error ? reason.message : "简历上传失败"); }
    finally { setBusy(false); if (uploadRef.current) uploadRef.current.value = ""; }
  }

  function toggleContext(context: SelectedContext) {
    setSelectedContexts((items) => {
      const next = items.some((item) => item.type === context.type && item.id === context.id)
      ? items.filter((item) => !(item.type === context.type && item.id === context.id))
      : [...items, context].slice(-5);
      window.sessionStorage.setItem(`${draftStorageKey(state?.conversationId)}:references`, JSON.stringify(next.filter(item => item.type !== "SERVICE")));
      return next;
    });
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

  async function refreshMemoryItems() {
    const items = await api<AgentMemoryItem[]>("/api/agent/memory/items");
    setMemoryItems(items);
    setMemoryEdits(Object.fromEntries(items.map((item) => [item.id, item.content])));
  }

  async function restoreMemoryItem(content: string) {
    setMemorySaving("restore"); setError("");
    try { await api("/api/agent/memory/items", { method: "POST", body: JSON.stringify({ content }) }); await refreshMemoryItems(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法恢复这条记忆"); }
    finally { setMemorySaving(null); }
  }

  async function saveMemoryItem(id: string) {
    const content = (memoryEdits[id] ?? "").trim();
    if (!content) return;
    setMemorySaving(id); setError("");
    try { await api(`/api/agent/memory/items/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify({ content }) }); await refreshMemoryItems(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法保存这条记忆"); }
    finally { setMemorySaving(null); }
  }

  async function deleteMemoryItem(id: string) {
    setMemorySaving(id); setError("");
    try { await api(`/api/agent/memory/items/${encodeURIComponent(id)}`, { method: "DELETE" }); await refreshMemoryItems(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法删除这条记忆"); }
    finally { setMemorySaving(null); }
  }

  async function addMemoryItem() {
    const content = newMemory.trim();
    if (!content) return;
    setMemorySaving("new"); setError("");
    try { await api("/api/agent/memory/items", { method: "POST", body: JSON.stringify({ content }) }); setNewMemory(""); await refreshMemoryItems(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法新增记忆"); }
    finally { setMemorySaving(null); }
  }

  async function clearMemory() {
    if (!window.confirm("清空所有长期记忆？")) return;
    setBusy(true);
    try {
      await api<void>("/api/agent/memory", { method: "DELETE" });
      setMemoryItems([]);
      setMemoryEdits({});
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法清空记忆"); }
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
      setSelectedContexts((current) => {
        const references = current.filter((item) => item.type !== "SERVICE").slice(-4);
        window.sessionStorage.setItem(`${draftStorageKey(state?.conversationId)}:references`, JSON.stringify(references));
        return [...references, { type: "SERVICE", id: feature.id, label: feature.label }];
      });
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
  const dockedDeletions = confirmations.filter((item) => item.kind !== "learning_plan" && ["PENDING", "FAILED", "DELETING"].includes(item.state));
  const deletionDock = <DeletionPermissions items={dockedDeletions}
    onKeep={(pending) => { rememberDeletion(pending, "DISMISSED"); setConfirmations((current) => current.map((item) => confirmationKey(item) === confirmationKey(pending) ? { ...item, state: "DISMISSED" } : item)); }}
    onDelete={(pending) => void confirmDeletion(pending)} />;
  const visibleError = error && !retryFailures.some((failure) =>
    failure.detail === error || failure.detail.endsWith(` · ${error}`)
  ) ? error : "";
  const liveAssistant = !runningRun && busy ? messages.findLast((message) => message.role === "ASSISTANT") : undefined;
  const terminalInHistory = !!terminalRunId && messages.some((message) => message.role === "ASSISTANT" && message.requestId === terminalRunId);
  const showLiveTrace = !terminalInHistory && !liveAssistant?.runTrace && (runSteps.length > 0 || !!runThinking || busy || !!runningRun || retryFailures.length > 0);
  const phase = runningRun ? streamProgress?.phase : streamPhase;
  const liveTitle = !(busy || runningRun) ? t("Execution stopped", "执行已停止") : phase === "THINKING" ? t("Thinking", "思考中") : phase === "PLAN" ? t("Planning", "正在规划") : phase === "GATHER" ? t("Gathering evidence", "正在收集证据") : phase === "REFLECT" ? t("Reviewing evidence", "正在审查证据") : phase === "ANSWER" ? t("Writing reply", "正在生成回复") : phase === "TOOL" ? t("Using tools", "正在调用工具") : phase === "OUTPUT" ? t("Writing reply", "正在生成回复") : t("Waiting for model", "等待模型回应");
  const liveTrace = showLiveTrace ? <ExecutionTrace title={liveTitle}
    active={(busy || !!runningRun) && phase !== "OUTPUT"}
    elapsed={formatElapsed(runElapsed)} thinking={runThinking} steps={runSteps}
    action={(runningRun || approvalRunId) && <button type="button" disabled={cancelPending} onClick={() => void cancelExecution()} className="shrink-0 py-1 text-xs text-stone-500 hover:text-rose-700 disabled:opacity-50">{cancelPending ? t("Stopping…", "正在停止…") : t("Stop", "停止")}</button>}>
    {runningRun && <p>{streamProgress?.label ?? runningRun.label} · {t("You can refresh to resume", "可刷新页面继续查看")}</p>}
    {retryFailures.map((failure, index) => <p key={index} className="text-amber-700">{failure.label} · {failure.detail}</p>)}
    {busy && retryAttempt > 0 && <p>{t(`Reconnecting · retry ${retryAttempt}/${MAX_MESSAGE_RETRIES}`, `正在重新连接 · 第 ${retryAttempt}/${MAX_MESSAGE_RETRIES} 次重试`)}</p>}
  </ExecutionTrace> : null;
  const proposalResults = (message: AgentMessage) => (savedRunTrace(message.runTrace)?.proposals ?? []).map((proposal) => {
    const item = { ...proposal, sourceMessageId: message.id };
    const current = confirmations.find((value) => value.sourceMessageId === message.id && confirmationKey(value) === confirmationKey(item));
    if (item.kind === "learning_plan") {
      const status = current?.state;
      const label = status === "DONE" ? t("Learning plan created", "学习计划已创建") : status === "GENERATING" ? t("Preparing lesson in background", "正在后台准备学习内容") : status === "DISMISSED" ? t("Creation declined", "已拒绝创建") : status === "REMOVED" ? t("Plan removed", "计划已移除") : status === "EXPIRED" ? t("Suggested time has passed", "建议时间已过") : status === "FAILED" ? t("Lesson generation failed", "学习生成失败") : t("Learning suggestion", "学习建议");
      return <div key={confirmationKey(item)}><p>{label} · {item.label}</p>{current?.createdPlanId && <Link className="text-violet-600 underline" href={`/app/learning?id=${encodeURIComponent(current.createdPlanId)}`}>{t("Open lesson", "打开学习")}</Link>}{current?.error && <p className="text-rose-600">{current.error}</p>}</div>;
    }
    let resolution = current?.state as string | undefined;
    try { resolution = window.localStorage.getItem(deletionResolutionKey(item, message.conversationId)) ?? resolution; } catch { /* Device storage can be unavailable. */ }
    return resolution && ["DONE", "DISMISSED", "EXPIRED"].includes(resolution) ? <p key={confirmationKey(item)}>{resolution === "DONE" ? t("Deleted", "已删除") : resolution === "DISMISSED" ? t("Kept", "已保留") : t("Expired proposal", "建议已过期")} · {item.label}</p> : null;
  });
  const feedbackBanner = feedback && <div role="status" className="mx-auto mb-2 flex max-w-3xl items-center gap-3 px-3 py-1 text-xs text-stone-500"><span className="min-w-0 flex-1">{feedback}</span><button type="button" aria-label={t("Dismiss notice", "关闭提示")} onClick={() => setFeedback("")}><X className="size-3.5" /></button></div>;

  return (
    <main className="ardor-workbench h-full overflow-hidden text-stone-900">
      <div className="flex h-full">
        {sidebarOpen === true && <button aria-label={t("Close conversations", "关闭会话栏")} className="fixed inset-0 z-20 bg-black/20 md:hidden" onClick={() => setSidebarOpen(false)} />}
        <aside aria-label={t("Conversations", "对话栏")} aria-hidden={sidebarOpen === false} inert={sidebarOpen === false} className={`fixed inset-y-0 left-16 z-40 shrink-0 flex-col border-r border-stone-200/70 bg-[#f8f6f2] p-3 shadow-2xl md:static md:z-20 md:shadow-none ${sidebarOpen === false ? "hidden" : sidebarOpen === true ? "flex" : "hidden md:flex"}`} style={{ width: "min(18rem, calc(100vw - 4rem))" }}>
          <div className="flex h-12 items-center justify-between px-2">
            <WorkspaceBrand admin={isAdmin} />
            <div className="flex items-center gap-0.5"><ChatNotifications /><button aria-label={t("Search chats", "搜索对话")} onClick={() => { setSearchOpen((value) => !value); setRecentOpen(true); setChatSearch(""); }} className="rounded-lg p-2 text-stone-500 hover:bg-white"><Search className="size-4" /></button></div>
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

          <div className="relative flex-1 overflow-y-auto" style={{ maskImage: "linear-gradient(to bottom, transparent 0px, black 12px)", WebkitMaskImage: "linear-gradient(to bottom, transparent 0px, black 12px)" }}>
            {messages.length === 0 ? (
              <div className={`relative grid min-h-full place-items-center overflow-hidden px-4 pb-12 ${leavingEmptyState ? "ardor-empty-exit" : "ardor-empty-enter"}`}>
                <div className="pointer-events-none absolute inset-x-[6%] bottom-[-28%] h-[82%] rounded-[50%] bg-[radial-gradient(circle_at_28%_45%,rgba(255,107,177,0.58),transparent_43%),radial-gradient(circle_at_72%_38%,rgba(83,125,255,0.58),transparent_47%),radial-gradient(circle_at_50%_76%,rgba(149,92,246,0.46),transparent_58%)] blur-3xl" />
                <div className="relative z-10 mx-auto w-full max-w-3xl text-center">
                  <h2 className="text-4xl font-semibold tracking-[-0.04em] text-stone-950 md:text-5xl">What should we build{state?.displayName?.trim() ? `, ${state.displayName.trim()}` : ""}?</h2>
                  {runningRun && <div role="status" className="mx-auto mt-5 max-w-2xl rounded-2xl border border-violet-100 bg-white/75 px-4 py-3 text-left text-sm text-violet-700">{t(`Ardor is working on your last message · ${runningRun.label}. It will appear when ready.`, `Ardor 正在处理上一条消息 · ${runningRun.label}。完成后会自动显示。`)}</div>}
                  {(visibleError || retryFailures.length > 0) && <div className="mx-auto mt-5 max-w-2xl rounded-2xl bg-red-50/90 px-4 py-3 text-left text-sm text-red-700 shadow-sm">{visibleError && <p>{visibleError}</p>}{retryFailures.length > 0 && <ul className={visibleError ? "mt-2 space-y-1 text-xs text-red-600" : "space-y-1 text-xs text-red-600"}>{retryFailures.map((failure, index) => <li key={`${failure.label}-${index}`}>{failure.label}：{failure.detail}</li>)}</ul>}</div>}
                  <form onSubmit={submit} className="mx-auto mt-8 rounded-[1.75rem] border border-white/70 bg-white/88 p-2 text-left shadow-[0_20px_70px_rgba(42,35,27,0.16)] backdrop-blur-xl transition-[border-color,box-shadow] duration-200 focus-within:border-stone-300/80 focus-within:shadow-[0_22px_76px_rgba(42,35,27,0.18),0_0_0_4px_rgba(255,255,255,0.42)]">
                    {selectedContexts.some((context) => context.type !== "SERVICE") && <div className="mx-3 mt-2 flex flex-wrap gap-1.5">{selectedContexts.filter((context) => context.type !== "SERVICE").map((context) => <div key={`${context.type}-${context.id}`} className="inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{context.label}</span><button type="button" aria-label={t("Remove attachment", "移除资料")} onClick={() => toggleContext(context)}><X className="size-3.5" /></button></div>)}</div>}
              {servicePicker()}
                    <textarea aria-label={t("Message Ardor", "给 Ardor 发消息")} value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={!state?.llmConfigured} rows={3} placeholder={state?.llmConfigured ? typedHint : t("Set up a model first", "请先完成模型设置")} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
                    <div className="flex items-center justify-between gap-3 px-2 pb-1"><div className="flex items-center gap-2">{attachmentPicker()}{permissionPicker()}{reasoningPicker()}</div><div className="flex items-center gap-3">{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}<Button aria-label={t("Send", "发送")} disabled={(busy && !streamAccepted) || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div></div>
                  </form>
                </div>
              </div>
            ) : (
              <div aria-live="polite" className="ardor-chat-enter mx-auto w-full max-w-3xl space-y-8 px-4 py-10 md:px-8">
                {messages.map((message) => (
                  <article key={message.id} className={`flex gap-3 ${message.role === "USER" ? "justify-end" : "justify-start"}`}>
                    {message.role === "USER" ? (
                      <div className="flex max-w-[86%] flex-col items-end"><div className="rounded-3xl bg-stone-200/65 px-5 py-3 text-sm leading-7">{message.contextReferences && message.contextReferences.length > 0 && <div className="mb-2 flex flex-wrap gap-1.5">{message.contextReferences.map((context) => <span key={`${context.type}-${context.id}`} className="inline-flex max-w-full items-center gap-1 rounded-full bg-white/70 px-2.5 py-1 text-[11px] text-stone-600"><Paperclip className="size-3" />{context.type === "SERVICE" ? <Link href={featureHref(features.find((feature) => feature.id === context.id)?.key)} className="truncate text-violet-700 underline underline-offset-2">{locale === "en" ? features.find((feature) => feature.id === context.id)?.englishLabel ?? context.label : context.label}</Link> : <span className="truncate">{context.label}</span>}</span>)}</div>}<div className="whitespace-pre-wrap">{message.content}</div></div><time dateTime={message.createdAt} title={fullMessageTimestamp(message.createdAt, locale)} className="mt-1 px-1 text-[10px] text-stone-400">{formatMessageTimestamp(message.createdAt, locale)}</time></div>
                    ) : (
                      <div className="max-w-full py-0.5 text-sm leading-7 text-stone-800">
                        {message === liveAssistant && liveTrace}
                        {savedRunTrace(message.runTrace) && (() => { const trace = savedRunTrace(message.runTrace)!; return <ExecutionTrace title={trace.status === "COMPLETED" ? t("Completed", "已完成") : t("Execution stopped", "执行已停止")} elapsed={formatElapsed(trace.elapsedMs)} thinking={trace.thinkingOutput} steps={trace.steps} traceHref={message.runId ? `/app/agent-runs/${message.runId}` : undefined}>{proposalResults(message)}</ExecutionTrace>; })()}
                        <div className="ardor-markdown"><ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown></div><time dateTime={message.createdAt} title={fullMessageTimestamp(message.createdAt, locale)} className="mt-1 block text-[10px] text-stone-400">{formatMessageTimestamp(message.createdAt, locale)}</time>
                      </div>
                    )}
                  </article>
                ))}
                {!liveAssistant && liveTrace}
                {runningRun && streamProgress?.answerPartial && <div aria-label={t("Reply in progress", "正在生成的回复")} className="ardor-markdown max-w-full text-sm leading-7 text-stone-800"><ReactMarkdown remarkPlugins={[remarkGfm]}>{streamProgress.answerPartial}</ReactMarkdown></div>}
                {!terminalInHistory && !runningRun && !busy && streamProgress?.answerPartial && streamProgress.phase !== "COMPLETED" && <details className="max-w-xl rounded-2xl border border-stone-200/70 bg-white/50 p-4"><summary className="cursor-pointer text-xs text-stone-500">{t("Incomplete reply · saved before execution stopped", "未完成回复 · 停止前保留的片段")}</summary><div className="ardor-markdown mt-3 text-sm leading-7"><ReactMarkdown remarkPlugins={[remarkGfm]}>{streamProgress.answerPartial}</ReactMarkdown></div></details>}
                <QueuedMessages key={state?.conversationId} conversationId={state?.conversationId ?? null} activeRunId={runningRun?.id ?? approvalRunId} onPendingChange={setHasQueuedMessages} onCompleted={load} onStarted={setQueuedWakeId} />
                <div ref={endRef} />
              </div>
            )}
          </div>

          {messages.length > 0 && <div className="ardor-composer-enter relative z-10 shrink-0 px-4 pb-5 md:px-8">
            {feedbackBanner}
            <div className="mx-auto max-h-[45dvh] max-w-3xl overflow-y-auto">
            {(runningRun?.id ?? approvalRunId) && <OperationPermissions key={runningRun?.id ?? approvalRunId} runId={(runningRun?.id ?? approvalRunId)!}
              onConfirming={tool => { confirmedOperations.current.add(tool); setFeedback(t("Submitting your confirmation…", "正在提交确认…")); }}
              onConfirmed={tool => { if (confirmedOperations.current.has(tool)) setFeedback(t("Confirmed. Waiting for the operation result…", "已确认，正在等待执行结果…")); }}
              onFailed={tool => { if (confirmedOperations.current.delete(tool)) setFeedback(t("Confirmation failed. Nothing has been confirmed.", "确认未成功提交，本次尚未确认。")); }} />}
            {deletionDock}
            <LearningPermissions items={confirmations.filter(item => item.kind === "learning_plan" && ["PENDING", "FAILED", "DELETING"].includes(item.state))}
              onReject={pending => { rememberDeletion(pending, "DISMISSED"); window.sessionStorage.setItem(dismissedProposalKey(pending), "1"); setConfirmations(current => current.map(item => confirmationKey(item) === confirmationKey(pending) ? { ...item, state: "DISMISSED" } : item)); setFeedback(t("Creation declined. No learning plan was created.", "已拒绝创建，未新建学习计划。")); }}
              onConfirm={pending => void confirmLearning(pending)} />
            </div>
            {visibleError && <div className="mx-auto mb-3 max-w-3xl rounded-2xl bg-red-50 px-4 py-3 text-sm text-red-700 shadow-sm"><p>{visibleError}</p></div>}
            <form onSubmit={submit} className="mx-auto max-w-3xl rounded-[1.75rem] border border-stone-200/80 bg-white p-2 shadow-[0_20px_70px_rgba(42,35,27,0.14)] transition-[border-color,box-shadow] duration-200 focus-within:border-stone-300/90 focus-within:shadow-[0_22px_76px_rgba(42,35,27,0.16),0_0_0_4px_rgba(255,255,255,0.38)]">
              {selectedContexts.some((context) => context.type !== "SERVICE") && <div className="mx-3 mt-2 flex flex-wrap gap-1.5">{selectedContexts.filter((context) => context.type !== "SERVICE").map((context) => <div key={`${context.type}-${context.id}`} className="inline-flex max-w-[90%] items-center gap-2 rounded-full bg-violet-50 px-3 py-1.5 text-xs text-violet-700"><Paperclip className="size-3.5 shrink-0" /><span className="truncate">{context.label}</span><button type="button" aria-label="移除资料" onClick={() => toggleContext(context)}><X className="size-3.5" /></button></div>)}</div>}
              {servicePicker()}
              <textarea aria-label={t("Message Ardor", "给 Ardor 发消息")} value={draft} onChange={(event) => updateDraft(event.target.value)} onKeyDown={handleKeyDown} disabled={!state?.llmConfigured} rows={2} placeholder={state?.llmConfigured ? "Message Ardor…" : "请先完成模型设置"} className="w-full resize-none bg-transparent px-4 pb-1 pt-3 text-[15px] leading-6 outline-none focus-visible:!outline-none placeholder:text-stone-400" />
              <div className="flex items-center justify-between gap-2 px-2 pb-1"><div className="flex min-w-0 items-center gap-2">{attachmentPicker()}{permissionPicker()}{draft.length >= 40_000 && <span className={`text-[11px] ${draft.length > MAX_MESSAGE_LENGTH ? "text-red-600" : "text-stone-400"}`}>{draft.length.toLocaleString()} / {MAX_MESSAGE_LENGTH.toLocaleString()}</span>}</div><div className="flex shrink-0 items-center gap-1.5">{reasoningPicker()}<ChatVoiceButton disabled={!state?.llmConfigured} onTranscript={(text) => { setDraft((current) => current.trim() ? `${current.trim()} ${text}` : text); setError(""); }} onError={setError} /><Button aria-label={t("Send", "发送")} disabled={(busy && !streamAccepted) || !draft.trim() || draft.length > MAX_MESSAGE_LENGTH || !state?.llmConfigured} className="size-9 rounded-full bg-stone-950 p-0 text-white hover:bg-stone-800"><ArrowUp className="size-4" /></Button></div></div>
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
          <section className="max-h-[90dvh] w-full max-w-3xl overflow-y-auto rounded-[1.75rem] border border-stone-200 bg-[#fffefa] p-5 shadow-2xl md:p-6">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="flex items-center gap-2 text-base font-semibold"><Brain className="size-4 text-violet-500" />长期记忆</h2>
                <p className="mt-1 text-xs text-stone-400">跨对话共享，可随时修改或删除。</p>
              </div>
              <button aria-label="关闭" onClick={() => setMemoryOpen(false)} className="rounded-full p-1.5 text-stone-400 hover:bg-stone-100"><X className="size-4" /></button>
            </div>
            {error && <p role="alert" className="mt-3 rounded-xl bg-red-50 px-3 py-2 text-xs text-red-700">{error}</p>}
            <div className="mt-5 grid gap-2 sm:grid-cols-2">
              {!memoryItems ? <p className="py-8 text-sm text-stone-400">加载中…</p>
                : memoryItems.filter((item) => item.valid || showStaleMemory).length === 0
                  ? <p className="py-10 text-center text-sm text-stone-400">还没有长期记忆。</p>
                  : memoryItems.filter((item) => item.valid || showStaleMemory).map((item) => item.valid ? (
                    <div key={item.id} className="rounded-2xl border border-stone-200/70 bg-white/80 p-3.5 transition hover:border-violet-200">
                      <div className="flex items-center gap-1.5 text-[10px] text-stone-400">
                        <span>{item.sourceKind === "INFERRED" ? "推断" : "你说过"}</span><span>·</span>
                        <span>{item.kind === "preference" ? "偏好" : item.kind === "goal" ? "目标" : item.kind === "profile" ? "背景" : item.kind === "constraint" ? "约束" : "事实"}</span>
                      </div>
                      <AutoResizeTextarea value={memoryEdits[item.id] ?? item.content} onChange={(value) => setMemoryEdits((current) => ({ ...current, [item.id]: value }))} maxLength={1000} />
                      <div className="mt-1 flex items-center justify-end gap-3 text-[11px]">
                        <button type="button" disabled={memorySaving !== null} onClick={() => void deleteMemoryItem(item.id)} className="text-stone-300 transition hover:text-rose-600 disabled:opacity-40">删除</button>
                        {(memoryEdits[item.id] ?? item.content) !== item.content && <button type="button" disabled={memorySaving !== null} onClick={() => void saveMemoryItem(item.id)} className="font-medium text-violet-700 hover:underline disabled:opacity-40">{memorySaving === item.id ? "保存中…" : "保存"}</button>}
                      </div>
                    </div>
                  ) : (
                    <div key={item.id} className="rounded-2xl border border-dashed border-stone-200 bg-stone-50/60 p-3.5">
                      <div className="flex items-center gap-1.5 text-[10px] text-stone-400"><span className="rounded-full bg-stone-100 px-1.5 py-0.5">已过时</span><span>{item.validUntil ? `失效于 ${new Date(item.validUntil).toLocaleDateString()}` : ""}</span></div>
                      <p className="mt-1.5 text-sm leading-6 text-stone-400 line-through">{item.content}</p>
                      <button type="button" disabled={memorySaving !== null} onClick={() => void restoreMemoryItem(item.content)} className="mt-1 text-[11px] text-violet-700 hover:underline disabled:opacity-40">恢复</button>
                    </div>
                  ))}
            </div>
            <div className="mt-4 flex items-center gap-2 border-t border-stone-100 pt-4">
              <input value={newMemory} onChange={(event) => setNewMemory(event.target.value)} maxLength={1000} placeholder="新增一条记忆…" className="min-w-0 flex-1 bg-transparent text-sm text-stone-700 outline-none placeholder:text-stone-400" />
              <button type="button" disabled={memorySaving !== null || !newMemory.trim()} onClick={() => void addMemoryItem()} className="rounded-full bg-stone-950 px-3 py-1.5 text-xs font-medium text-white disabled:opacity-40">添加</button>
            </div>
            <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-[11px] text-stone-400">
              <div className="flex items-center gap-3">
                {memoryItems && memoryItems.some((item) => !item.valid) && <label className="flex cursor-pointer items-center gap-1.5"><input type="checkbox" checked={showStaleMemory} onChange={(event) => setShowStaleMemory(event.target.checked)} className="size-3" />已过时（{memoryItems.filter((item) => !item.valid).length}）</label>}
              </div>
              <div className="flex items-center gap-3">
                <button type="button" onClick={() => void clearMemory()} disabled={busy} className="text-rose-600 hover:underline disabled:opacity-40">清空全部</button>
                <button type="button" onClick={() => setMemoryOpen(false)} className="rounded-full bg-stone-950 px-3 py-1.5 text-xs font-medium text-white hover:bg-stone-800">完成</button>
              </div>
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
