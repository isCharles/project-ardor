"use client";

import { ArrowLeft, ArrowRight, BookOpen, CheckCircle2, CircleAlert, Plus, Sparkles, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";

type Lesson = { summary?: string; keyPoints?: string[]; explanation?: string; example?: string; pitfalls?: string[] };
type Evaluation = { feedback?: string; strengths?: string[]; gaps?: string[]; nextFocus?: string };
type LearningPlan = {
  id: string; concept: string; reason: string | null; status: "SCHEDULED" | "IN_PROGRESS" | "NEEDS_REVIEW" | "COMPLETED";
  lesson: Lesson; exercises: Array<{ question: string }>; lastEvaluation: Evaluation | null; lastScore: number | null;
  attemptCount: number; scheduledAt: string; nextReviewAt: string | null; completedAt: string | null; createdAt: string;
};

const statusLabel: Record<LearningPlan["status"], string> = {
  SCHEDULED: "待学习", IN_PROGRESS: "学习中", NEEDS_REVIEW: "待巩固", COMPLETED: "已掌握",
};

export default function LearningPage() {
  const router = useRouter();
  const [plans, setPlans] = useState<LearningPlan[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [answers, setAnswers] = useState<string[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async (preferred?: string | null) => {
    try {
      const items = await api<LearningPlan[]>("/api/learning-plans");
      setPlans(items);
      const nextId = preferred ?? selectedId ?? new URLSearchParams(window.location.search).get("id") ?? items[0]?.id ?? null;
      const next = items.find((item) => item.id === nextId) ?? items[0] ?? null;
      setSelectedId(next?.id ?? null);
      setAnswers(next?.exercises.map(() => "") ?? []);
      if (next) window.history.replaceState(null, "", `/app/learning?id=${next.id}`);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "无法加载学习计划");
    }
  }, [router, selectedId]);

  useEffect(() => {
    let active = true;
    api<LearningPlan[]>("/api/learning-plans").then((items) => {
      if (!active) return;
      const requested = new URLSearchParams(window.location.search).get("id");
      const next = items.find((item) => item.id === requested) ?? items[0] ?? null;
      setPlans(items); setSelectedId(next?.id ?? null); setAnswers(next?.exercises.map(() => "") ?? []);
      if (next) window.history.replaceState(null, "", `/app/learning?id=${next.id}`);
    }).catch((reason) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "无法加载学习计划");
    });
    return () => { active = false; };
  }, [router]);
  const selected = plans.find((plan) => plan.id === selectedId) ?? null;

  function selectPlan(plan: LearningPlan) {
    setSelectedId(plan.id); setAnswers(plan.exercises.map(() => ""));
    window.history.replaceState(null, "", `/app/learning?id=${plan.id}`);
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    setBusy(true); setError("");
    try {
      const created = await api<LearningPlan>("/api/learning-plans", { method: "POST", body: JSON.stringify({ concept: data.get("concept"), reason: data.get("reason"), sourceType: "MANUAL" }) });
      setShowCreate(false); await load(created.id);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "无法生成学习内容"); }
    finally { setBusy(false); }
  }

  async function begin() {
    if (!selected || selected.status !== "SCHEDULED") return;
    try { const updated = await api<LearningPlan>(`/api/learning-plans/${selected.id}/begin`, { method: "POST" }); setPlans((items) => items.map((item) => item.id === updated.id ? updated : item)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "无法开始学习"); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!selected) return;
    setBusy(true); setError("");
    try {
      const updated = await api<LearningPlan>(`/api/learning-plans/${selected.id}/attempts`, { method: "POST", body: JSON.stringify({ answers }) });
      setPlans((items) => items.map((item) => item.id === updated.id ? updated : item));
      setAnswers(updated.exercises.map(() => ""));
    } catch (reason) { setError(reason instanceof Error ? reason.message : "练习评分失败"); }
    finally { setBusy(false); }
  }

  return <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10"><div className="mx-auto max-w-7xl">
    <div className="flex items-center justify-between"><Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 hover:bg-white/70 hover:text-stone-900"><ArrowLeft className="size-4" />返回 Ardor</Link><button onClick={() => setShowCreate(true)} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white"><Plus className="size-4" />新学习</button></div>
    <header className="pb-10 pt-12 md:pt-16"><p className="text-sm font-medium text-violet-600">LEARNING</p><h1 className="mt-3 text-5xl font-semibold tracking-[-0.055em] md:text-7xl">把不会的，真正学会。</h1></header>
    {error && <div className="mb-5 rounded-2xl bg-red-50/90 px-5 py-4 text-sm text-red-700">{error}</div>}
    {plans.length === 0 ? <section className="ardor-panel grid min-h-96 place-items-center rounded-[2.5rem] text-center"><div><BookOpen className="mx-auto size-9 text-violet-500" /><h2 className="mt-5 text-2xl font-semibold">还没有学习计划</h2><p className="mt-2 text-sm text-stone-500">告诉 Ardor 薄弱点，或自己添加一个主题。</p></div></section> : <div className="grid gap-6 lg:grid-cols-[18rem_minmax(0,1fr)]">
      <aside className="ardor-soft-panel h-fit rounded-[2rem] p-3"><div className="space-y-1">{plans.map((plan) => <button key={plan.id} onClick={() => selectPlan(plan)} className={`w-full rounded-2xl p-4 text-left transition ${plan.id === selectedId ? "bg-white shadow-sm" : "hover:bg-white/65"}`}><div className="flex items-center gap-2"><span className={`size-2 rounded-full ${plan.status === "COMPLETED" ? "bg-emerald-500" : plan.status === "NEEDS_REVIEW" ? "bg-amber-500" : "bg-violet-500"}`} /><strong className="min-w-0 flex-1 truncate text-sm">{plan.concept}</strong></div><p className="mt-2 text-xs text-stone-400">{statusLabel[plan.status]} · {new Date(plan.nextReviewAt ?? plan.scheduledAt).toLocaleDateString("zh-CN", { month: "short", day: "numeric" })}</p></button>)}</div></aside>
      {selected && <article className="space-y-6">
        <section className="ardor-panel rounded-[2.5rem] p-7 md:p-10"><div className="flex flex-wrap items-start justify-between gap-4"><div><span className="rounded-full bg-violet-100 px-3 py-1 text-xs font-medium text-violet-700">{statusLabel[selected.status]}</span><h2 className="mt-5 text-4xl font-semibold tracking-tight">{selected.concept}</h2>{selected.reason && <p className="mt-3 max-w-2xl text-sm leading-6 text-stone-500">{selected.reason}</p>}</div>{selected.status === "SCHEDULED" && <button onClick={() => void begin()} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white">开始<ArrowRight className="size-4" /></button>}</div>
          <p className="mt-10 text-xl font-medium leading-8">{selected.lesson.summary}</p>
          {selected.lesson.keyPoints && <div className="mt-7 grid gap-3 sm:grid-cols-2">{selected.lesson.keyPoints.map((point) => <div key={point} className="rounded-2xl bg-white/65 p-4 text-sm leading-6"><Sparkles className="mb-3 size-4 text-violet-500" />{point}</div>)}</div>}
          <div className="mt-9 whitespace-pre-wrap text-[15px] leading-8 text-stone-700">{selected.lesson.explanation}</div>
          {selected.lesson.example && <div className="mt-7 rounded-2xl bg-blue-50/65 p-5"><p className="text-xs font-medium text-blue-600">例子</p><p className="mt-2 whitespace-pre-wrap text-sm leading-7 text-stone-700">{selected.lesson.example}</p></div>}
          {selected.lesson.pitfalls && selected.lesson.pitfalls.length > 0 && <div className="mt-5 rounded-2xl bg-amber-50/70 p-5"><p className="flex items-center gap-2 text-xs font-medium text-amber-700"><CircleAlert className="size-4" />容易踩坑</p><ul className="mt-3 space-y-2 text-sm leading-6 text-stone-700">{selected.lesson.pitfalls.map((item) => <li key={item}>· {item}</li>)}</ul></div>}
        </section>
        <section className="ardor-panel rounded-[2.5rem] p-7 md:p-10"><div className="flex items-end justify-between"><div><p className="text-xs font-medium text-violet-600">PRACTICE</p><h2 className="mt-2 text-2xl font-semibold">检验理解</h2></div>{selected.lastScore !== null && <strong className="text-4xl tracking-tight">{selected.lastScore}<span className="text-base text-stone-400"> / 100</span></strong>}</div>
          {selected.lastEvaluation && <div className={`mt-6 rounded-2xl p-5 ${selected.status === "COMPLETED" ? "bg-emerald-50/75" : "bg-amber-50/75"}`}><p className="flex items-center gap-2 font-medium">{selected.status === "COMPLETED" && <CheckCircle2 className="size-5 text-emerald-600" />}{selected.lastEvaluation.feedback}</p>{selected.lastEvaluation.nextFocus && <p className="mt-2 text-sm text-stone-600">下一步：{selected.lastEvaluation.nextFocus}</p>}
          </div>}
          {selected.status === "COMPLETED" ? <div className="mt-7 flex items-center gap-3 rounded-2xl bg-emerald-50/75 p-5 text-sm text-emerald-800"><CheckCircle2 className="size-5" />本次学习已完成，日历任务也已同步完成。</div> : <form onSubmit={submit} className="mt-7 space-y-6">{selected.exercises.map((exercise, index) => <label key={`${exercise.question}-${index}`} className="block"><span className="text-sm font-medium">{index + 1}. {exercise.question}</span><textarea required rows={4} value={answers[index] ?? ""} onChange={(event) => setAnswers((items) => items.map((item, answerIndex) => answerIndex === index ? event.target.value : item))} className="field mt-3 resize-y" placeholder="用自己的话回答" /></label>)}<button disabled={busy || answers.some((answer) => !answer.trim())} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? "Ardor 正在评估…" : "提交练习"}</button></form>}
        </section>
      </article>}
    </div>}
  </div>{showCreate && <div className="fixed inset-0 z-50 grid place-items-center bg-black/20 p-4 backdrop-blur-sm"><section className="ardor-panel w-full max-w-lg rounded-[2rem] p-7"><div className="flex items-center justify-between"><h2 className="text-2xl font-semibold">学什么？</h2><button aria-label="关闭" onClick={() => setShowCreate(false)} className="rounded-full p-2 text-stone-400 hover:bg-white"><X className="size-5" /></button></div><form onSubmit={create} className="mt-6 space-y-4"><input name="concept" required maxLength={160} className="field" placeholder="例如 JVM、数据库索引" autoFocus /><textarea name="reason" maxLength={4000} rows={3} className="field resize-y" placeholder="为什么想学（可选）" /><button disabled={busy} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? "正在生成…" : "生成学习计划"}</button></form></section></div>}</main>;
}
