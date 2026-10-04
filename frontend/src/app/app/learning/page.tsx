"use client";

import { ArrowLeft, ArrowRight, BookOpen, Check, CheckCircle2, CircleAlert, Plus, Sparkles, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { LocaleSwitch } from "@/components/ardor/locale-switch";
import { useLocale } from "@/lib/locale";

type Lesson = { summary?: string; keyPoints?: string[]; explanation?: string; example?: string; pitfalls?: string[] };
type Exercise = { question: string; type?: "SHORT_ANSWER" | "MULTIPLE_CHOICE" | "SCENARIO"; options?: string[] };
type LearningAttempt = { items: Array<Exercise & { answer: string }>; score: number; submittedAt: string; feedback?: string };
type Evaluation = { feedback?: string; strengths?: string[]; gaps?: string[]; nextFocus?: string; questionFeedback?: Array<{ question: string; feedback: string }>; attempts?: LearningAttempt[] };
type LearningPlan = {
  id: string;
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

const sourceLabel: Record<LearningPlan["sourceType"], { en: string; zh: string }> = {
  MANUAL: { en: "Added by you", zh: "自己添加" }, AGENT: { en: "Suggested by Ardor", zh: "Ardor 建议" },
  RESUME: { en: "From a resume", zh: "来自简历" }, RECAP: { en: "From interview notes", zh: "来自面经" },
  KNOWLEDGE: { en: "From knowledge", zh: "来自知识库" },
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

function answersForPlan(plan: LearningPlan | null) {
  if (!plan) return [];
  const previous = plan.lastEvaluation?.attempts?.at(-1)?.items ?? [];
  return plan.exercises.map((exercise, index) =>
    previous[index]?.question === exercise.question && previous[index]?.type === (exercise.type ?? "SHORT_ANSWER")
      ? previous[index].answer : "");
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
  const [error, setError] = useState("");

  const load = useCallback(async (preferred?: string | null) => {
    try {
      const items = await api<LearningPlan[]>("/api/learning-plans");
      setPlans(items);
      const requested = preferred ?? new URLSearchParams(window.location.search).get("id");
      const next = items.find((item) => item.id === requested) ?? nextPlan(items);
      setSelectedId(next?.id ?? null);
      setAnswers(answersForPlan(next));
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
    void api<LearningPlan[]>("/api/learning-plans")
      .then((items) => {
        if (!active) return;
        setPlans(items);
        const requested = new URLSearchParams(window.location.search).get("id");
        const next = items.find((item) => item.id === requested) ?? nextPlan(items);
        setSelectedId(next?.id ?? null);
        setAnswers(answersForPlan(next));
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
  const previousAttempts = selected?.lastEvaluation?.attempts ?? [];
  const activeCount = plans.filter((plan) => plan.status !== "COMPLETED").length;
  const orderedPlans = [...plans].sort((left, right) => {
    if ((left.status === "COMPLETED") !== (right.status === "COMPLETED")) return left.status === "COMPLETED" ? 1 : -1;
    return new Date(left.nextReviewAt ?? left.scheduledAt).getTime()
      - new Date(right.nextReviewAt ?? right.scheduledAt).getTime();
  });

  function selectPlan(plan: LearningPlan) {
    setSelectedId(plan.id);
    setAnswers(answersForPlan(plan));
    setError("");
    window.history.replaceState(null, "", `/app/learning?id=${plan.id}`);
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true); setError("");
    try {
      const created = await api<LearningPlan>("/api/learning-plans", {
        method: "POST",
        body: JSON.stringify({ concept: data.get("concept"), reason: data.get("reason"), sourceType: "MANUAL" }),
      });
      setShowCreate(false);
      await load(created.id);
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
    if (!selected || busy) return;
    setBusy(true); setError("");
    try {
      const updated = await api<LearningPlan>(`/api/learning-plans/${selected.id}/attempts`, {
        method: "POST", body: JSON.stringify({ answers }),
      });
      setPlans((items) => items.map((item) => item.id === updated.id ? updated : item));
      const changed = updated.exercises.some((exercise, index) =>
        exercise.question !== selected.exercises[index]?.question)
        || updated.exercises.length !== selected.exercises.length;
      if (changed) setAnswers(updated.exercises.map(() => ""));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not review the answers", "练习评分失败")); }
    finally { setBusy(false); }
  }

  return (
    <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10">
      <div className="mx-auto max-w-7xl">
        <div className="flex items-center justify-between">
          <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-white/70 hover:text-stone-900">
            <ArrowLeft className="size-4" />{t("Back to Ardor", "返回 Ardor")}
          </Link>
          <div className="flex items-center gap-2"><LocaleSwitch />{!loading && plans.length > 0 && <button type="button" onClick={() => setShowCreate(true)} className="inline-flex items-center gap-2 rounded-full border border-white/80 bg-white/65 px-4 py-2.5 text-sm font-medium text-stone-700 shadow-sm transition hover:bg-white">
            <Plus className="size-4" />{t("New topic", "新学习")}
          </button>}</div>
        </div>

        <header className="pb-8 pt-12 md:pb-10 md:pt-16">
          <h1 className="text-4xl font-semibold tracking-[-0.055em] md:text-6xl">{t("From uncertain to confident.", "从薄弱，到自如。")}</h1>
        </header>
        {error && !showCreate && <div role="alert" className="mb-5 rounded-2xl bg-red-50/90 px-5 py-4 text-sm text-red-700">{error}</div>}

        {loading ? (
          <div className="ardor-panel h-80 animate-pulse rounded-[2.5rem]" aria-label={t("Loading learning plans", "正在加载学习计划")} />
        ) : plans.length === 0 ? (
          <section className="ardor-panel grid min-h-96 place-items-center rounded-[2.5rem] px-6 text-center">
            <div><BookOpen className="mx-auto size-9 text-violet-500" /><h2 className="mt-5 text-2xl font-semibold">{t("Start with one concept", "从一个概念开始")}</h2><p className="mt-2 text-sm text-stone-500">{t("Choose one yourself, or let Ardor suggest one from your interviews.", "自己选择，或让 Ardor 根据面试情况安排。")}</p><button type="button" onClick={() => setShowCreate(true)} className="mt-7 rounded-full bg-stone-950 px-6 py-3 text-sm font-medium text-white">{t("New topic", "新学习")}<ArrowRight className="ml-2 inline size-4" /></button></div>
          </section>
        ) : selected && (
          <>
            <section className="relative mb-7 overflow-hidden rounded-[2.5rem] border border-white/75 bg-white/65 px-6 py-8 shadow-[0_22px_70px_rgba(54,38,88,0.08)] backdrop-blur-xl md:px-10 md:py-10">
              <div className="pointer-events-none absolute -right-20 -top-44 size-[28rem] rounded-full bg-[radial-gradient(circle,rgba(255,169,112,0.58),rgba(250,134,180,0.31)_42%,transparent_72%)] blur-3xl" />
              <div className="pointer-events-none absolute -bottom-64 right-20 size-[34rem] rounded-full bg-[radial-gradient(circle,rgba(111,146,255,0.38),transparent_68%)] blur-3xl" />
              <div className="relative grid gap-8 md:grid-cols-[minmax(0,1fr)_13rem] md:items-center">
                <div>
                  <p className="text-xs font-medium tracking-wide text-violet-700">{t("Next", "接下来")} · {t(statusLabel[selected.status].en, statusLabel[selected.status].zh)}</p>
                  <h2 className="mt-3 max-w-3xl break-words text-4xl font-semibold tracking-[-0.05em] md:text-6xl">{selected.concept}</h2>
                  <p className="mt-3 text-sm text-stone-600">{selected.status === "NEEDS_REVIEW" ? t("Try a new round to confirm your understanding.", "再练一次，确认真正掌握。") : selected.status === "IN_PROGRESS" ? t("Explain it in your own words.", "学过之后，用自己的话回答。") : selected.status === "COMPLETED" ? t("This topic is complete.", "这一步已完成。") : t("Understand first, then practice.", "先理解，再检验。")}</p>
                  <div className="mt-7 flex flex-wrap items-center gap-3">
                    {selected.status === "SCHEDULED" && <button type="button" onClick={() => void begin()} disabled={busy} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-6 py-3 text-sm font-medium text-white transition hover:bg-stone-800 disabled:opacity-50">{busy ? t("Starting…", "正在开始…") : t("Start learning", "开始学习")}<ArrowRight className="size-4" /></button>}
                    {(selected.status === "IN_PROGRESS" || selected.status === "NEEDS_REVIEW") && <button type="button" onClick={() => document.getElementById("learning-practice")?.scrollIntoView({ behavior: "smooth", block: "start" })} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-6 py-3 text-sm font-medium text-white transition hover:bg-stone-800">{t("Practice", "去练习")}<ArrowRight className="size-4" /></button>}
                    {selected.sourceType === "RECAP" && selected.sourceId ? <Link href={`/app/recaps?source=${selected.sourceId}`} className="text-sm text-stone-600 underline decoration-stone-300 underline-offset-4 hover:text-stone-900">{t("View interview notes", "查看来源面经")}</Link> : <span className="text-xs text-stone-500">{t(sourceLabel[selected.sourceType].en, sourceLabel[selected.sourceType].zh)}</span>}
                  </div>
                </div>
                <div className="relative mx-auto grid size-40 place-items-center rounded-full border border-white/70 bg-white/35 shadow-[0_18px_55px_rgba(114,93,179,0.13)] md:size-48" aria-label={selected.lastScore !== null ? t(`Latest score ${selected.lastScore}`, `最近得分 ${selected.lastScore} 分`) : t("Not scored yet", "尚未评分")}>
                  <div className="absolute inset-3 rounded-full border border-white/70" />
                  {selected.lastScore !== null ? <div className="text-center"><strong className="text-6xl font-semibold tracking-[-0.06em]">{selected.lastScore}</strong><span className="block text-xs text-stone-500">{t("Latest score", "最近得分")}</span></div> : <BookOpen className="size-11 text-violet-600" strokeWidth={1.3} />}
                </div>
              </div>
              <ol className="relative mt-10 grid grid-cols-4 gap-2 border-t border-stone-200/70 pt-5" aria-label={t("Learning progress", "学习进程")}>
                {journeySteps(selected, t).map((step, index) => <li key={index} className="min-w-0"><div className={`mb-2 grid size-6 place-items-center rounded-full text-xs ${step.done ? "bg-violet-600 text-white" : "border border-stone-300 bg-white/70 text-stone-400"}`}>{step.done ? <Check className="size-3.5" /> : index + 1}</div><span className={`text-xs sm:text-sm ${step.done ? "font-medium text-stone-800" : "text-stone-400"}`}>{step.label}</span></li>)}
              </ol>
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
                  {selected.lesson.summary && <p className="mt-8 text-xl font-medium leading-8">{selected.lesson.summary}</p>}
                  {selected.lesson.keyPoints && selected.lesson.keyPoints.length > 0 && <div className="mt-7 grid gap-3 sm:grid-cols-2">{selected.lesson.keyPoints.map((point) => <div key={point} className="rounded-2xl bg-white/65 p-4 text-sm leading-6"><Sparkles className="mb-3 size-4 text-violet-500" />{point}</div>)}</div>}
                  {selected.lesson.explanation && <div className="mt-9 whitespace-pre-wrap text-[15px] leading-8 text-stone-700">{selected.lesson.explanation}</div>}
                  {selected.lesson.example && <div className="mt-7 rounded-2xl bg-blue-50/65 p-5"><p className="text-xs font-medium text-blue-600">{t("Example", "例子")}</p><p className="mt-2 whitespace-pre-wrap text-sm leading-7 text-stone-700">{selected.lesson.example}</p></div>}
                  {selected.lesson.pitfalls && selected.lesson.pitfalls.length > 0 && <div className="mt-5 rounded-2xl bg-amber-50/70 p-5"><p className="flex items-center gap-2 text-xs font-medium text-amber-700"><CircleAlert className="size-4" />{t("Common pitfalls", "容易踩坑")}</p><ul className="mt-3 space-y-2 text-sm leading-6 text-stone-700">{selected.lesson.pitfalls.map((item) => <li key={item}>· {item}</li>)}</ul></div>}
                </section>

                <section id="learning-practice" className="ardor-panel scroll-mt-6 rounded-[2.5rem] p-7 md:p-10" aria-labelledby="practice-title">
                  <div className="flex items-end justify-between gap-4"><h2 id="practice-title" className="text-2xl font-semibold">{t("Practice", "检验理解")}</h2>{selected.lastScore !== null && <strong className="text-3xl tracking-tight">{selected.lastScore}<span className="text-base text-stone-400"> / 100</span></strong>}</div>
                  {selected.lastEvaluation && <div className={`mt-6 rounded-2xl p-5 ${selected.status === "COMPLETED" ? "bg-emerald-50/75" : "bg-amber-50/75"}`}><p className="flex items-center gap-2 font-medium">{selected.status === "COMPLETED" && <CheckCircle2 className="size-5 text-emerald-600" />}{selected.lastEvaluation.feedback}</p>{selected.lastEvaluation.nextFocus && <p className="mt-2 text-sm text-stone-600">{t("Next focus", "下一步")}：{selected.lastEvaluation.nextFocus}</p>}{selected.lastEvaluation.gaps && selected.lastEvaluation.gaps.length > 0 && <ul className="mt-3 space-y-1 text-sm text-stone-700">{selected.lastEvaluation.gaps.map((gap, index) => <li key={index}>· {gap}</li>)}</ul>}{selected.lastEvaluation.questionFeedback && selected.lastEvaluation.questionFeedback.length > 0 && <div className="mt-4 space-y-3 border-t border-stone-200/70 pt-4">{selected.lastEvaluation.questionFeedback.map((item, index) => <div key={index} className="text-sm"><p className="font-medium">{item.question}</p><p className="mt-1 leading-6 text-stone-600">{item.feedback}</p></div>)}</div>}</div>}
                  {previousAttempts.length > 0 && <div className="mt-5 space-y-2">{[...previousAttempts].reverse().map((attempt, index) => <details key={`${attempt.submittedAt}-${index}`} className="rounded-2xl border border-stone-200/75 bg-white/60 p-4"><summary className="cursor-pointer text-sm font-medium">{t(`Attempt ${previousAttempts.length - index} · ${attempt.score} points`, `第 ${previousAttempts.length - index} 次回答 · ${attempt.score} 分`)}</summary><div className="mt-4 space-y-4">{attempt.feedback && <p className="text-sm leading-6 text-stone-700">{attempt.feedback}</p>}{attempt.items.map((item, itemIndex) => <div key={itemIndex} className="text-sm"><p className="font-medium">{itemIndex + 1}. {item.question}</p><p className="mt-1 whitespace-pre-wrap leading-6 text-stone-600">{t("Your answer", "你的回答")}：{item.answer}</p></div>)}</div></details>)}</div>}
                  {selected.status !== "COMPLETED" && previousAttempts.length > 0 && <p className="mt-7 text-sm font-medium text-violet-700">{t("Next practice", "下一轮练习")}</p>}
                  {selected.status === "COMPLETED" ? <div className="mt-7 flex items-center gap-3 rounded-2xl bg-emerald-50/75 p-5 text-sm text-emerald-800"><CheckCircle2 className="size-5" />{t("Topic complete. The calendar task is complete too.", "本次学习已完成，日历任务也已同步完成。")}</div> : <form onSubmit={submit} className="mt-7 space-y-6">{selected.exercises.map((exercise, index) => <fieldset key={`${exercise.question}-${index}`} className="block"><legend className="text-sm font-medium">{index + 1}. {exercise.question}</legend>{exercise.type === "MULTIPLE_CHOICE" && exercise.options && exercise.options.length > 0 ? <div className="mt-3 grid gap-2">{exercise.options.map((option) => <label key={option} className={`flex cursor-pointer items-center gap-3 rounded-2xl border px-4 py-3 text-sm transition ${answers[index] === option ? "border-violet-400 bg-violet-50/80" : "border-stone-200 bg-white/60 hover:bg-white"}`}><input type="radio" name={`exercise-${index}`} value={option} checked={answers[index] === option} onChange={() => setAnswers((items) => items.map((item, answerIndex) => answerIndex === index ? option : item))} required />{option}</label>)}</div> : <textarea required rows={exercise.type === "SCENARIO" ? 5 : 2} value={answers[index] ?? ""} onChange={(event) => setAnswers((items) => items.map((item, answerIndex) => answerIndex === index ? event.target.value : item))} className="field mt-3 resize-y" placeholder={exercise.type === "SCENARIO" ? t("Explain your approach and steps", "说明你的思路和步骤") : t("Answer in your own words", "用自己的话回答")} />}</fieldset>)}<button disabled={busy || selected.status === "SCHEDULED" || answers.some((answer) => !answer.trim())} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Ardor is reviewing…", "Ardor 正在评估…") : selected.status === "SCHEDULED" ? t("Start learning to practice", "开始学习后可练习") : t("Submit answers", "提交练习")}</button></form>}
                </section>
              </article>
            </div>
          </>
        )}
      </div>

      {showCreate && <div className="fixed inset-0 z-50 grid place-items-center bg-black/20 p-4 backdrop-blur-sm" onMouseDown={(event) => { if (event.target === event.currentTarget) setShowCreate(false); }}><section role="dialog" aria-modal="true" aria-labelledby="create-learning-title" className="ardor-panel w-full max-w-lg rounded-[2rem] p-7"><div className="flex items-center justify-between"><h2 id="create-learning-title" className="text-2xl font-semibold">{t("What would you like to learn?", "学什么？")}</h2><button type="button" aria-label={t("Close", "关闭")} onClick={() => setShowCreate(false)} className="rounded-full p-2 text-stone-500 hover:bg-white"><X className="size-5" /></button></div><form onSubmit={create} className="mt-6 space-y-4"><input name="concept" required maxLength={160} className="field" placeholder={t("For example, JVM or database indexes", "例如 JVM、数据库索引")} autoFocus /><textarea name="reason" maxLength={4000} rows={3} className="field resize-y" placeholder={t("Why this topic? (optional)", "为什么想学（可选）")} />{error && <p role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}<button disabled={busy} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Generating…", "正在生成…") : t("Create learning plan", "生成学习计划")}</button></form></section></div>}
    </main>
  );
}
