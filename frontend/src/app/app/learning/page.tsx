"use client";

import { ArrowRight, BookOpen, CheckCircle2, CircleAlert, Ellipsis, Plus, Sparkles, Trash2, X } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useRef, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { MarkdownContent } from "@/components/ardor/markdown-content";
import { clearLearningDraft, readLearningDraft, saveLearningDraft } from "@/lib/learning-drafts";
import { type AiJob, jobPending, useAiJob } from "@/lib/ai-jobs";
import { AiJobStatus } from "@/components/ardor/ai-job-status";
import { useAcknowledgeResourceTasks } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";
import { agentCreationHref } from "@/lib/agent-features";
import { AppDialog } from "@/components/ui/app-dialog";
import { LearningContext, type PlanContext } from "@/components/ardor/learning-context";

type Lesson = { summary?: string; keyPoints?: string[]; explanation?: string; example?: string; pitfalls?: string[] };
type Exercise = { question: string; type?: "SHORT_ANSWER" | "MULTIPLE_CHOICE" | "TRUE_FALSE" | "SCENARIO"; options?: string[] };
type LearningAttempt = { requestId?: string; items: Array<Exercise & { answer: string }>; score: number; submittedAt: string; feedback?: string; questionFeedback?: Array<{ question: string; feedback: string }> };
type Evaluation = { feedback?: string; strengths?: string[]; gaps?: string[]; nextFocus?: string; questionFeedback?: Array<{ question: string; feedback: string }>; attempts?: LearningAttempt[] };
type LearningPlan = PlanContext & {
  id: string;
  requestId: string | null;
  concept: string;
  reason: string | null;
  sourceType: "MANUAL" | "AGENT" | "RESUME" | "RECAP" | "KNOWLEDGE";
  sourceId: string | null;
  status: "SCHEDULED" | "IN_PROGRESS" | "NEEDS_REVIEW" | "COMPLETED";
  lesson: Lesson;
  exercises: Exercise[];
  lastEvaluation: Evaluation | null;
  lastScore: number | null;
  attemptCount: number;
  scheduledAt: string;
  nextReviewAt: string | null;
  completedAt: string | null;
  createdAt: string;
};

const statusLabel: Record<LearningPlan["status"], { en: string; zh: string }> = {
  SCHEDULED: { en: "Scheduled", zh: "待学习" }, IN_PROGRESS: { en: "In progress", zh: "学习中" },
  NEEDS_REVIEW: { en: "Needs review", zh: "待巩固" }, COMPLETED: { en: "Mastered", zh: "已掌握" },
};

function nextPlan(items: LearningPlan[]) {
  return [...items].filter((item) => item.status !== "COMPLETED")
    .sort((left, right) => new Date(left.nextReviewAt ?? left.scheduledAt).getTime()
      - new Date(right.nextReviewAt ?? right.scheduledAt).getTime())[0] ?? items[0] ?? null;
}

function planDate(value: string, locale: "en" | "zh-CN") {
  return new Date(value).toLocaleDateString(locale, { month: "long", day: "numeric" });
}

function journeySteps(plan: LearningPlan, t: (en: string, zh: string) => string) {
  return [
    { label: t("Scheduled", "已安排"), done: true },
    { label: t("Started", "已开始"), done: plan.status !== "SCHEDULED" },
    { label: plan.attemptCount > 0 ? t(`${plan.attemptCount} attempts`, `练习 ${plan.attemptCount} 次`) : t("Practice", "练习"), done: plan.attemptCount > 0 },
    { label: t("Mastered", "已掌握"), done: plan.status === "COMPLETED" },
  ];
}

function answersForPlan(plan: LearningPlan | null, userId: string | null) {
  if (!plan) return [];
  const draft = readLearningDraft(userId, plan);
  if (draft) return draft;
  const previous = plan.lastEvaluation?.attempts?.at(-1)?.items ?? [];
  return plan.exercises.map((exercise, index) =>
    previous[index]?.question === exercise.question && previous[index]?.type === (exercise.type ?? "SHORT_ANSWER")
      ? previous[index].answer : "");
}

const createRequestKey = "ardor:learning-create-request";

function clearCompletedCreateRequest(items: LearningPlan[]) {
  try {
    const saved = JSON.parse(window.sessionStorage.getItem(createRequestKey) ?? "null") as { requestId?: string } | null;
    if (saved?.requestId && items.some((plan) => plan.requestId === saved.requestId)) {
      window.sessionStorage.removeItem(createRequestKey);
      return saved.requestId;
    }
  } catch { /* A damaged draft must not block loading. */ }
  return null;
}

export default function LearningPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const [plans, setPlans] = useState<LearningPlan[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [answers, setAnswers] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [busy, setBusy] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<LearningPlan | null>(null);
  const [deleteError, setDeleteError] = useState("");
  const [error, setError] = useState("");
  // The role behind a plan's source recap, remembered per plan so switching plans never shows a stale name.
  const [sourceName, setSourceName] = useState<{ planId: string; name: string; company: string | null } | null>(null);
  const pendingCreate = useRef<{ fingerprint: string; requestId: string } | null>(null);
  const userId = useRef<string | null>(null);
  const selectedRef = useRef<string | null>(null);
  const assessment = useAiJob("LEARNING_EVALUATE", selectedId, !!selectedId);
  const creation = useAiJob("LEARNING_CREATE", null);
  const handledJobs = useRef(new Set<string>());
  const watchedCreations = useRef(new Set<string>());
  const submittedPlan = useRef<{ jobId: string; plan: LearningPlan } | null>(null);

  const load = useCallback(async (preferred?: string | null) => {
    try {
      const items = await api<LearningPlan[]>("/api/learning-plans");
      if (clearCompletedCreateRequest(items)) pendingCreate.current = null;
      setPlans(items);
      const requested = selectedRef.current ?? preferred ?? new URLSearchParams(window.location.search).get("id");
      const next = items.find((item) => item.id === requested) ?? nextPlan(items);
      setSelectedId(next?.id ?? null);
      selectedRef.current = next?.id ?? null;
      setAnswers(answersForPlan(next, userId.current));
      window.history.replaceState(null, "", next ? `/app/learning?id=${next.id}` : "/app/learning");
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : t("Could not load learning plans", "无法加载学习计划"));
    } finally {
      setLoading(false);
    }
  }, [router, t]);

  useEffect(() => {
    let active = true;
    void Promise.all([api<LearningPlan[]>("/api/learning-plans"), api<{ id: string }>("/api/auth/me")])
      .then(([items, account]) => {
        if (!active) return;
        userId.current = account.id;
        if (clearCompletedCreateRequest(items)) pendingCreate.current = null;
        setPlans(items);
        const requested = new URLSearchParams(window.location.search).get("id");
        const next = items.find((item) => item.id === requested) ?? nextPlan(items);
        setSelectedId(next?.id ?? null);
        selectedRef.current = next?.id ?? null;
        setAnswers(answersForPlan(next, account.id));
        window.history.replaceState(null, "", next ? `/app/learning?id=${next.id}` : "/app/learning");
      })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : "Could not load learning plans");
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [router]);

  const selected = plans.find((plan) => plan.id === selectedId) ?? null;

  async function deletePlan() {
    if (!deleteTarget || deleting) return;
    const target = deleteTarget;
    setDeleting(true); setDeleteError("");
    try {
      await api<void>(`/api/learning-plans/${target.id}`, { method: "DELETE" });
      clearLearningDraft(userId.current, target);
      selectedRef.current = null;
      setDeleteTarget(null);
      await load();
      window.dispatchEvent(new Event("ardor:background-refresh"));
    } catch (reason) {
      setDeleteError(reason instanceof Error ? reason.message : t("Could not delete learning plan", "删除学习计划失败"));
    } finally { setDeleting(false); }
  }
  const assessing = jobPending(assessment.job);
  // Reviews live in the attempt history only; the newest attempt is expanded.
  const previousAttempts = selected?.lastEvaluation?.attempts ?? [];
  useAcknowledgeResourceTasks(["LEARNING_CREATE", "LEARNING_EVALUATE"], selected?.id, !!selected,
    `${selected?.attemptCount ?? 0}:${assessment.job?.id ?? ""}:${assessment.job?.status ?? ""}`);
  const activeCount = plans.filter((plan) => plan.status !== "COMPLETED").length;
  const orderedPlans = [...plans].sort((left, right) => {
    if ((left.status === "COMPLETED") !== (right.status === "COMPLETED")) return left.status === "COMPLETED" ? 1 : -1;
    return new Date(left.nextReviewAt ?? left.scheduledAt).getTime()
      - new Date(right.nextReviewAt ?? right.scheduledAt).getTime();
  });

  useEffect(() => {
    const planId = selected?.id;
    const sourceId = selected?.originRecordId ?? (selected?.originKind === "INTERVIEW_RECORD" ? selected.originResourceId : selected?.sourceId);
    if (!planId || selected?.sourceType !== "RECAP" || !sourceId) return;
    if (selected.originKind === "LEGACY_RECAP" || (selected.originKind === "INTERVIEW_QUESTION" && !selected.originRecordId)) return;
    let active = true;
    type RecapSummary = { id: string; title: string; jobOpportunityId: string | null; questions?: Array<{ id: string }> };
    // Older plans stored a recap question id as the source, so fall back to
    // searching the recap list when the id is not a recap id itself.
    const resolveRecap = async (): Promise<RecapSummary | null> => {
      try {
        return await api<RecapSummary>(`/api/interview-recaps/${sourceId}`);
      } catch (reason) {
        if (selected.originKind) return null; // Canonical sources never probe a second table or scan unrelated reports.
        if (!(reason instanceof ApiError) || reason.status !== 404) return null;
        const recaps = await api<RecapSummary[]>("/api/interview-recaps");
        return recaps.find((recap) => recap.questions?.some((question) => question.id === sourceId)) ?? null;
      }
    };
    void resolveRecap()
      .then(async (recap) => {
        if (!recap) return null;
        if (selected.originKind) return { planId, name: recap.title, company: null as string | null };
        if (!recap.jobOpportunityId) return { planId, name: recap.title, company: null as string | null };
        const opportunity = await api<{ targetRole: string | null; company: string | null }>(`/api/opportunities/${recap.jobOpportunityId}`);
        return { planId, name: opportunity.targetRole || recap.title, company: opportunity.company };
      })
      .then((resolved) => { if (active && resolved) setSourceName(resolved); })
      .catch(() => { /* fall back to the generic source label */ });
    return () => { active = false; };
  }, [selected?.id, selected?.sourceType, selected?.sourceId, selected?.originKind, selected?.originRecordId, selected?.originResourceId]);

  useEffect(() => {
    for (const job of [assessment.job, creation.job]) {
      if (job?.type === "LEARNING_CREATE" && jobPending(job)) watchedCreations.current.add(job.id);
      if (job?.status !== "COMPLETED" || handledJobs.current.has(job.id)) continue;
      handledJobs.current.add(job.id);
      if (job.type === "LEARNING_CREATE" && !watchedCreations.current.has(job.id)) continue;
      if (submittedPlan.current?.jobId === job.id) {
        clearLearningDraft(userId.current, submittedPlan.current.plan);
        submittedPlan.current = null;
      }
      const planId = job.type === "LEARNING_CREATE" && job.resultPath
        ? new URL(job.resultPath, window.location.origin).searchParams.get("id") : selectedId;
      void load(planId);
    }
  }, [assessment.job, creation.job, selectedId, load]);

  function selectPlan(plan: LearningPlan) {
    selectedRef.current = plan.id;
    setSelectedId(plan.id);
    setAnswers(answersForPlan(plan, userId.current));
    setError("");
    window.history.replaceState(null, "", `/app/learning?id=${plan.id}`);
  }

  function changeAnswer(index: number, value: string) {
    if (!selected) return;
    const next = answers.map((answer, answerIndex) => answerIndex === index ? value : answer);
    setAnswers(next);
    saveLearningDraft(userId.current, selected, next);
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const input = { concept: String(data.get("concept") ?? ""), reason: String(data.get("reason") ?? ""), sourceType: "MANUAL" };
    const draftKey = createRequestKey;
    const fingerprint = JSON.stringify(input);
    let pending = pendingCreate.current;
    try {
      const saved = JSON.parse(window.sessionStorage.getItem(draftKey) ?? "null") as { fingerprint?: string; requestId?: string } | null;
      if (!pending && saved?.fingerprint === fingerprint && typeof saved.requestId === "string"
        && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(saved.requestId)) {
        pending = { fingerprint, requestId: saved.requestId };
      }
    } catch { /* A damaged draft must not prevent creation. */ }
    if (pending?.fingerprint !== fingerprint) pending = { fingerprint, requestId: crypto.randomUUID() };
    pendingCreate.current = pending;
    try { window.sessionStorage.setItem(draftKey, JSON.stringify(pending)); } catch { /* In-memory retry still works. */ }
    setBusy(true); setError("");
    try {
      const created = await api<AiJob>("/api/learning-plans/jobs", {
        method: "POST",
        body: JSON.stringify({ ...input, requestId: pending.requestId }),
      });
      setShowCreate(false);
      watchedCreations.current.add(created.id);
      creation.setJob(created);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not create the learning plan", "无法生成学习内容")); }
    finally { setBusy(false); }
  }

  async function begin() {
    if (!selected || selected.status !== "SCHEDULED" || busy) return;
    setBusy(true); setError("");
    try {
      const updated = await api<LearningPlan>(`/api/learning-plans/${selected.id}/begin`, { method: "POST" });
      setPlans((items) => items.map((item) => item.id === updated.id ? updated : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not start learning", "无法开始学习")); }
    finally { setBusy(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || busy || assessing) return;
    setBusy(true); setError("");
    try {
      const requestKey = `ardor:learning-evaluation:${userId.current}:${selected.id}:${selected.attemptCount}`;
      const fingerprint = JSON.stringify(answers);
      let requestId = crypto.randomUUID();
      try {
        const saved = JSON.parse(window.sessionStorage.getItem(requestKey) ?? "null");
        if (saved?.fingerprint === fingerprint && typeof saved.requestId === "string") requestId = saved.requestId;
        window.sessionStorage.setItem(requestKey, JSON.stringify({ fingerprint, requestId }));
      } catch { /* Server-side round and job constraints still prevent duplicate grading. */ }
      const queued = await api<AiJob>(`/api/learning-plans/${selected.id}/evaluation-jobs`, {
        method: "POST", body: JSON.stringify({ answers, requestId, expectedAttemptCount: selected.attemptCount }),
      });
      submittedPlan.current = { jobId: queued.id, plan: selected };
      assessment.setJob(queued);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not review the answers", "练习评分失败")); }
    finally { setBusy(false); }
  }

  return (
    <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10">
      <div className="mx-auto max-w-7xl">
        <div className="flex items-center justify-end">
          
          <div className="flex items-center gap-2">
            {selected && <details className="relative">
              <summary aria-label={t("More actions", "更多操作")} className="grid size-11 cursor-pointer list-none place-items-center rounded-full border border-white/80 bg-white/65 text-stone-500 shadow-sm transition hover:bg-white hover:text-stone-800 [&::-webkit-details-marker]:hidden"><Ellipsis className="size-4" /></summary>
              <div className="absolute right-0 z-20 mt-1 w-44 rounded-2xl border border-stone-200 bg-white p-1 shadow-xl">
                <button type="button" onClick={(event) => { (event.currentTarget.closest("details") as HTMLDetailsElement | null)?.removeAttribute("open"); setDeleteTarget(selected); setDeleteError(""); }} className="flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-sm text-rose-600 hover:bg-rose-50"><Trash2 className="size-4" />{t("Delete plan", "删除计划")}</button>
              </div>
            </details>}
            {!loading && plans.length > 0 && <button type="button" onClick={() => router.push(agentCreationHref("LEARNING", locale))} className="inline-flex items-center gap-2 rounded-full border border-white/80 bg-white/65 px-4 py-2.5 text-sm font-medium text-stone-700 shadow-sm transition hover:bg-white">
            <Plus className="size-4" />{t("New topic", "新学习")}
          </button>}</div>
        </div>
        {/* A finished creation job is history: the plan list already shows the lesson. */}
        {creation.job && creation.job.status !== "COMPLETED" && <AiJobStatus job={creation.job} retry={creation.retry} pollFailed={creation.pollFailed} />}

        {!loading && !selected && <header className="pb-6 pt-8 md:pb-8 md:pt-10">
          <h1 className="text-3xl font-semibold tracking-[-0.05em] md:text-4xl">{t("From uncertain to confident.", "从薄弱，到自如。")}</h1>
        </header>}
        {error && !showCreate && <div role="alert" className="mb-5 rounded-2xl bg-red-50/90 px-5 py-4 text-sm text-red-700">{error}</div>}

        {loading ? (
          <div className="ardor-panel h-80 animate-pulse rounded-[2.5rem]" aria-label={t("Loading learning plans", "正在加载学习计划")} />
        ) : plans.length === 0 ? (
          <section className="ardor-panel grid min-h-96 place-items-center rounded-[2.5rem] px-6 text-center">
            <div><BookOpen className="mx-auto size-9 text-violet-500" /><h2 className="mt-5 text-2xl font-semibold">{t("Start with one concept", "从一个概念开始")}</h2><p className="mt-2 text-sm text-stone-500">{t("Choose one yourself, or let Ardor suggest one from your interviews.", "自己选择，或让 Ardor 根据面试情况安排。")}</p><button type="button" onClick={() => router.push(agentCreationHref("LEARNING", locale))} className="mt-7 rounded-full bg-stone-950 px-6 py-3 text-sm font-medium text-white">{t("New topic", "新学习")}<ArrowRight className="ml-2 inline size-4" /></button></div>
          </section>
        ) : selected && (
          <>
            <section className="relative mb-7 overflow-hidden rounded-[2.5rem] border border-white/75 bg-white/65 px-6 py-8 shadow-[0_22px_70px_rgba(54,38,88,0.08)] backdrop-blur-xl md:px-10 md:py-10">
              <div className="pointer-events-none absolute -bottom-64 right-20 size-[34rem] rounded-full bg-[radial-gradient(circle,rgba(111,146,255,0.32),transparent_68%)] blur-3xl" />
              <div className="relative grid gap-8 md:grid-cols-[minmax(0,1fr)_13rem] md:items-center">
                <div>
                  <p className="text-xs font-medium tracking-wide text-violet-700">
                    {t(statusLabel[selected.status].en, statusLabel[selected.status].zh)}
                    {" · "}
                    {selected.status === "COMPLETED"
                      ? t(`Completed ${planDate(selected.completedAt ?? selected.scheduledAt, locale)}`, `完成于 ${planDate(selected.completedAt ?? selected.scheduledAt, locale)}`)
                      : t(`Next review ${planDate(selected.nextReviewAt ?? selected.scheduledAt, locale)}`, `下次复习 ${planDate(selected.nextReviewAt ?? selected.scheduledAt, locale)}`)}
                  </p>
                  <h2 className="mt-2 max-w-3xl break-words text-2xl font-semibold tracking-[-0.04em] md:text-3xl">{selected.concept}</h2>
                  <div className="mt-5 flex flex-wrap items-center gap-3">
                    {selected.status === "SCHEDULED" && <button type="button" onClick={() => void begin()} disabled={busy} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-6 py-3 text-sm font-medium text-white transition hover:bg-stone-800 disabled:opacity-50">{busy ? t("Starting…", "正在开始…") : t("Start learning", "开始学习")}<ArrowRight className="size-4" /></button>}
                    {(selected.status === "IN_PROGRESS" || selected.status === "NEEDS_REVIEW") && <button type="button" onClick={() => document.getElementById("learning-practice")?.scrollIntoView({ behavior: "smooth", block: "start" })} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-6 py-3 text-sm font-medium text-white transition hover:bg-stone-800">{t("Practice", "去练习")}<ArrowRight className="size-4" /></button>}
                    <LearningContext plan={selected} sourceName={sourceName?.planId === selected.id ? sourceName.name : undefined} />
                  </div>
                </div>
                <div className="relative mx-auto grid size-24 place-items-center rounded-full border border-white/70 bg-white/35 shadow-[0_14px_40px_rgba(114,93,179,0.12)] md:size-28" aria-label={selected.lastScore !== null ? t(`Latest score ${selected.lastScore}`, `最近得分 ${selected.lastScore} 分`) : t("Not scored yet", "尚未评分")}>
                  <div className="absolute inset-2 rounded-full border border-white/70" />
                  {selected.lastScore !== null ? <div className="text-center"><strong className="text-4xl font-semibold tracking-[-0.06em]">{selected.lastScore}</strong><span className="block text-[10px] text-stone-500">{t("Latest score", "最近得分")}</span></div> : <BookOpen className="size-7 text-violet-600" strokeWidth={1.3} />}
                </div>
              </div>
              <div className="relative mt-8 border-t border-stone-200/70 pt-4" aria-label={t("Learning progress", "学习进程")}>
                {(() => {
                  const steps = journeySteps(selected, t);
                  const doneCount = steps.filter((step) => step.done).length;
                  const active = steps[Math.max(0, Math.min(doneCount - 1, steps.length - 1))];
                  return <>
                    <div className="mb-2 flex items-center justify-between text-xs text-stone-500"><span>{t(`Step ${Math.max(1, doneCount)} of ${steps.length}`, `第 ${Math.max(1, doneCount)} 步 / ${steps.length}`)}</span><span className="font-medium text-stone-700">{active.label}</span></div>
                    <div className="flex gap-1.5">{steps.map((step, index) => <span key={index} className={`h-1.5 flex-1 rounded-full ${step.done ? "bg-violet-600" : "bg-stone-200"}`} />)}</div>
                  </>;
                })()}
              </div>
            </section>

            <div className="grid gap-6 lg:grid-cols-[18rem_minmax(0,1fr)]">
              <aside className="ardor-soft-panel h-fit rounded-[2rem] p-3">
                <div className="flex items-center justify-between px-3 pb-3 pt-2"><h2 className="text-sm font-semibold">{t("Learning path", "学习轨道")}</h2><span className="text-xs text-stone-500">{t(`${activeCount} active`, `${activeCount} 项进行中`)}</span></div>
                <nav aria-label={t("Learning plans", "学习计划")} className="space-y-1">
                  {orderedPlans.map((plan) => <button key={plan.id} type="button" aria-pressed={plan.id === selectedId} onClick={() => selectPlan(plan)} className={`w-full rounded-2xl p-4 text-left transition ${plan.id === selectedId ? "bg-white shadow-sm" : "hover:bg-white/65"}`}><div className="flex items-center gap-2"><span className={`size-2 shrink-0 rounded-full ${plan.status === "COMPLETED" ? "bg-emerald-500" : plan.status === "NEEDS_REVIEW" ? "bg-amber-500" : "bg-violet-500"}`} /><strong className="min-w-0 flex-1 truncate text-sm">{plan.concept}</strong>{plan.lastScore !== null && <span className="text-xs tabular-nums text-stone-500">{plan.lastScore}</span>}</div><p className="mt-2 pl-4 text-xs text-stone-500">{t(statusLabel[plan.status].en, statusLabel[plan.status].zh)} · {planDate(plan.completedAt ?? plan.nextReviewAt ?? plan.scheduledAt, locale)}</p></button>)}
                </nav>
              </aside>

              <article className="space-y-6">
                <section className="ardor-panel rounded-[2.5rem] p-7 md:p-10" aria-labelledby="lesson-title">
                  <h2 id="lesson-title" className="text-2xl font-semibold">{t("Understand", "理解")}</h2>
                  {selected.reason && <p className="mt-3 max-w-2xl text-sm leading-6 text-stone-600">{selected.reason}</p>}
                  {selected.lesson.summary && <MarkdownContent className="mt-8 text-xl font-medium leading-8">{selected.lesson.summary}</MarkdownContent>}
                  {selected.lesson.keyPoints && selected.lesson.keyPoints.length > 0 && <div className="mt-7 grid gap-3 sm:grid-cols-2">{selected.lesson.keyPoints.map((point) => <div key={point} className="rounded-2xl bg-white/65 p-4 text-sm leading-6"><Sparkles className="mb-3 size-4 text-violet-500" /><MarkdownContent>{point}</MarkdownContent></div>)}</div>}
                  {selected.lesson.explanation && <MarkdownContent className="mt-9 text-[15px] leading-8 text-stone-700">{selected.lesson.explanation}</MarkdownContent>}
                  {selected.lesson.example && <details className="mt-7 rounded-2xl bg-blue-50/65 p-5"><summary className="cursor-pointer text-xs font-medium text-blue-600">{t("Example", "例子")}</summary><MarkdownContent className="mt-3 text-sm leading-7 text-stone-700">{selected.lesson.example}</MarkdownContent></details>}
                  {selected.lesson.pitfalls && selected.lesson.pitfalls.length > 0 && <details className="mt-5 rounded-2xl bg-amber-50/70 p-5"><summary className="flex cursor-pointer items-center gap-2 text-xs font-medium text-amber-700 [&::-webkit-details-marker]:hidden"><CircleAlert className="size-4" />{t("Common pitfalls", "容易踩坑")}</summary><ul className="mt-3 list-disc space-y-2 pl-5 text-sm leading-6 text-stone-700">{selected.lesson.pitfalls.map((item) => <li key={item}><MarkdownContent>{item}</MarkdownContent></li>)}</ul></details>}
                </section>

                <section id="learning-practice" className="ardor-panel scroll-mt-6 rounded-[2.5rem] p-7 md:p-10" aria-labelledby="practice-title">
                  <AiJobStatus job={assessment.job} retry={assessment.retry} pollFailed={assessment.pollFailed} />
                  <div className="flex items-end justify-between gap-4"><h2 id="practice-title" className="text-2xl font-semibold">{t("Practice", "检验理解")}</h2>{selected.lastScore !== null && <strong className="text-3xl tracking-tight">{selected.lastScore}<span className="text-base text-stone-400"> / 100</span></strong>}</div>
                  {previousAttempts.length > 0 && <div className="mt-5 space-y-2">{[...previousAttempts].reverse().map((attempt, index) => <details key={`${attempt.submittedAt}-${index}`} open={index === 0} className="rounded-2xl border border-stone-200/75 bg-white/60 p-4">
                    <summary className="cursor-pointer text-sm font-medium">{t(`Attempt ${previousAttempts.length - index} · ${attempt.score} points`, `第 ${previousAttempts.length - index} 次回答 · ${attempt.score} 分`)}</summary>
                    <div className="mt-4 space-y-4">{attempt.feedback && <MarkdownContent className="text-sm leading-6 text-stone-700">{attempt.feedback}</MarkdownContent>}
                      {index === 0 && (selected.lastEvaluation?.nextFocus || (selected.lastEvaluation?.gaps?.length ?? 0) > 0) && <div className="rounded-xl bg-amber-50/70 px-3 py-2 text-sm text-stone-700">
                        {selected.lastEvaluation?.nextFocus && <p><span className="font-medium">{t("Next focus", "下一步")}：</span>{selected.lastEvaluation.nextFocus}</p>}
                        {selected.lastEvaluation?.gaps && selected.lastEvaluation.gaps.length > 0 && <div className={selected.lastEvaluation?.nextFocus ? "mt-2" : ""}>
                          <span className="font-medium">{t("Gaps", "薄弱点")}：</span>
                          <ul className="mt-1 list-disc space-y-1 pl-5">{selected.lastEvaluation.gaps.map((gap, gapIndex) => <li key={gapIndex}><MarkdownContent>{gap}</MarkdownContent></li>)}</ul>
                        </div>}
                      </div>}
                      {attempt.items.map((item, itemIndex) => <div key={itemIndex} className="text-sm"><p className="font-medium">{itemIndex + 1}. {item.question}</p><p className="mt-1 whitespace-pre-wrap leading-6 text-stone-600">{t("Your answer", "你的回答")}：{item.answer}</p>{attempt.questionFeedback?.filter((feedback) => feedback.question === item.question).map((feedback, feedbackIndex) => <MarkdownContent key={feedbackIndex} className="mt-2 rounded-xl bg-violet-50/70 px-3 py-2 leading-6 text-violet-900">{feedback.feedback}</MarkdownContent>)}</div>)}
                    </div></details>)}</div>}
                  {selected.status !== "COMPLETED" && previousAttempts.length > 0 && <p className="mt-7 text-sm font-medium text-violet-700">{t("Next practice", "下一轮练习")}</p>}
                  {selected.status === "COMPLETED" ? <div className="mt-7 flex items-center gap-3 rounded-2xl bg-emerald-50/75 p-5 text-sm text-emerald-800"><CheckCircle2 className="size-5" />{t("Topic complete. The calendar task is complete too.", "本次学习已完成，日历任务也已同步完成。")}</div> : <form onSubmit={submit} className="mt-7 space-y-6">
                    {selected.exercises.map((exercise, index) => <fieldset key={`${exercise.question}-${index}`} disabled={busy || assessing} className="block disabled:opacity-75">
                      <legend className="text-sm font-medium">{index + 1}. {exercise.question}</legend>
                      {exercise.type === "MULTIPLE_CHOICE" || exercise.type === "TRUE_FALSE" ? exercise.options && exercise.options.length > 0 ? <div className="mt-3 grid gap-2">{exercise.options.map((option) => <label key={option} className={`flex cursor-pointer items-center gap-3 rounded-2xl border px-4 py-3 text-sm transition ${answers[index] === option ? "border-violet-400 bg-violet-50/80" : "border-stone-200 bg-white/60 hover:bg-white"}`}><input type="radio" name={`exercise-${index}`} value={option} checked={answers[index] === option} onChange={() => changeAnswer(index, option)} required />{option}</label>)}</div> : <textarea required rows={2} value={answers[index] ?? ""} onChange={(event) => changeAnswer(index, event.target.value)} className="field mt-3 resize-y" placeholder={t("Answer in your own words", "用自己的话回答")} /> : <textarea required rows={exercise.type === "SCENARIO" ? 5 : 2} value={answers[index] ?? ""} onChange={(event) => changeAnswer(index, event.target.value)} className="field mt-3 resize-y" placeholder={exercise.type === "SCENARIO" ? t("Explain your approach and steps", "说明你的思路和步骤") : t("Answer in your own words", "用自己的话回答")} />}
                    </fieldset>)}
                    <p className="text-xs text-stone-400">{t("Draft saved in this browser session", "草稿已保留在当前浏览器会话")}</p>
                    <button disabled={busy || assessing || selected.status === "SCHEDULED" || answers.some((answer) => !answer.trim())} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Submitting…", "正在提交…") : assessing ? t("Submitted for assessment", "已提交后台评估") : selected.status === "SCHEDULED" ? t("Start learning to practice", "开始学习后可练习") : t("Submit answers", "提交练习")}</button>
                  </form>}
                </section>
              </article>
            </div>
          </>
        )}
      </div>

      {showCreate && <div className="fixed inset-0 z-50 grid place-items-center bg-black/20 p-4 backdrop-blur-sm" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowCreate(false); }}><section role="dialog" aria-modal="true" aria-labelledby="create-learning-title" className="ardor-panel w-full max-w-lg rounded-[2rem] p-7"><div className="flex items-center justify-end"><h2 id="create-learning-title" className="text-2xl font-semibold">{t("What would you like to learn?", "学什么？")}</h2><button type="button" aria-label={t("Close", "关闭")} onClick={() => setShowCreate(false)} className="rounded-full p-2 text-stone-500 hover:bg-white"><X className="size-5" /></button></div><form onSubmit={create} className="mt-6 space-y-4"><input name="concept" required maxLength={160} className="field" placeholder={t("For example, JVM or database indexes", "例如 JVM、数据库索引")} autoFocus /><textarea name="reason" maxLength={4000} rows={3} className="field resize-y" placeholder={t("Why this topic? (optional)", "为什么想学（可选）")} />{error && <p role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}<button disabled={busy} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Generating…", "正在生成…") : t("Create learning plan", "生成学习计划")}</button></form></section></div>}
      {deleteTarget && <AppDialog title={t("Delete learning plan?", "删除学习计划？")} onClose={() => { if (!deleting) setDeleteTarget(null); }}>
        <p className="break-words font-medium">{deleteTarget.concept}</p>
        <p className="mt-3 text-sm leading-6 text-stone-500">{t("This permanently removes the lesson, practice history and its calendar task. Interview notes and other plans stay unchanged.", "将永久删除讲解、练习记录及关联日程，不影响来源面经或其他学习计划。")}</p>
        {deleteError && <p role="alert" className="mt-3 break-words text-sm text-rose-700">{deleteError}</p>}
        <div className="mt-6 flex justify-end gap-3"><button type="button" disabled={deleting} onClick={() => setDeleteTarget(null)} className="rounded-full px-4 py-2 text-sm">{t("Cancel", "取消")}</button><button type="button" disabled={deleting} onClick={() => void deletePlan()} className="rounded-full bg-rose-600 px-4 py-2 text-sm text-white disabled:opacity-50">{deleting ? t("Deleting…", "正在删除…") : t("Delete permanently", "永久删除")}</button></div>
      </AppDialog>}
    </main>
  );
}
