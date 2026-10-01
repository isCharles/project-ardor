"use client";

import { ArrowLeft, ArrowRight, BookOpen, CircleHelp, RotateCcw, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { ApiError, api } from "@/lib/api";

type Question = {
  id: string;
  questionText: string;
  candidateAnswer: string | null;
  weaknessReason: string | null;
  performance: "STRONG" | "MIXED" | "WEAK" | "UNKNOWN";
  tags: string[];
};
type Recap = {
  id: string;
  title: string;
  company: string | null;
  occurredAt: string | null;
  createdAt: string;
  questions: Question[];
};
type Card = {
  id: string;
  recapQuestionId: string | null;
  front: string;
  repetitions: number;
  nextReviewAt: string;
  status: "NEW" | "LEARNING" | "REVIEW" | "SUSPENDED";
};
type Plan = {
  id: string;
  concept: string;
  sourceType: string;
  sourceId: string | null;
  status: "SCHEDULED" | "IN_PROGRESS" | "NEEDS_REVIEW" | "COMPLETED";
  attemptCount: number;
  lastScore: number | null;
};

function agentHref(recap: Recap, question: Question) {
  const params = new URLSearchParams({
    new: "1",
    prefill: `根据这份面经中「${question.questionText}」的实际表现，帮我创建一张能检验理解的记忆卡，sourceType 用 INTERVIEW，recapQuestionId 用 ${question.id}。不要直接照抄原题；先想清楚要考察什么。`,
    contextType: "RECAP",
    contextId: recap.id,
    contextLabel: recap.title,
  });
  return `/app?${params.toString()}`;
}

function learningHref(recap: Recap, question: Question) {
  const params = new URLSearchParams({
    new: "1",
    prefill: `针对这份面经里「${question.questionText}」暴露的薄弱点，帮我安排一个学习计划。sourceType 用 RECAP，sourceId 用面经问题 ID ${question.id}。先根据原文确认薄弱点，不要凭空猜测。`,
    contextType: "RECAP",
    contextId: recap.id,
    contextLabel: recap.title,
  });
  return `/app?${params.toString()}`;
}

export default function EvidencePage() {
  const router = useRouter();
  const [recaps, setRecaps] = useState<Recap[]>([]);
  const [cards, setCards] = useState<Card[]>([]);
  const [plans, setPlans] = useState<Plan[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([
      api<Recap[]>("/api/interview-recaps"),
      api<Card[]>("/api/memory-cards"),
      api<Plan[]>("/api/learning-plans"),
    ]).then(([loadedRecaps, loadedCards, loadedPlans]) => {
      if (!active) return;
      setRecaps(loadedRecaps);
      setCards(loadedCards);
      setPlans(loadedPlans);
    }).catch((reason) => {
      if (!active) return;
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "无法加载能力证据");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [router]);

  const tracks = useMemo(() => recaps.flatMap((recap) => recap.questions
    .filter((question) => question.performance === "WEAK" || question.performance === "MIXED")
    .map((question) => ({ recap, question })))
    .sort((left, right) => Date.parse(right.recap.occurredAt ?? right.recap.createdAt) - Date.parse(left.recap.occurredAt ?? left.recap.createdAt)), [recaps]);
  const linkedCards = useMemo(() => new Map(cards.filter((card) => card.recapQuestionId)
    .map((card) => [card.recapQuestionId, card])), [cards]);

  return (
    <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
      <div className="mx-auto max-w-6xl">
        <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-white/70 hover:text-stone-900"><ArrowLeft className="size-4" />返回 Ardor</Link>
        <header className="pb-9 pt-12 md:pt-16">
          <p className="text-xs font-medium tracking-[0.16em] text-violet-600">你的成长证据</p>
          <h1 className="mt-3 text-4xl font-semibold tracking-[-0.06em] md:text-6xl">知道进步从哪来。</h1>
          <p className="mt-4 text-sm text-stone-500">真实面试留下问题；后续行动留下证据。</p>
        </header>

        {error && <div role="alert" className="mb-6 rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}
        {loading ? <div className="ardor-panel h-72 animate-pulse rounded-[2.5rem]" aria-label="正在加载能力证据" /> : tracks.length === 0 ? (
          <section className="ardor-panel grid min-h-80 place-items-center rounded-[2.5rem] p-8 text-center">
            <div><CircleHelp className="mx-auto size-9 text-violet-500" /><h2 className="mt-5 text-2xl font-semibold">从一次真实复盘开始</h2><p className="mt-2 text-sm text-stone-500">面经里有待补强的问题时，这里会出现它的后续轨迹。</p><Link href="/app/recaps" className="mt-6 inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm text-white">查看面经<ArrowRight className="size-4" /></Link></div>
          </section>
        ) : (
          <div className="space-y-5">
            <p className="text-sm text-stone-500">{tracks.length} 个有待补强的真实问题</p>
            {tracks.map(({ recap, question }) => {
              const card = linkedCards.get(question.id);
              const exactPlans = plans.filter((plan) => plan.sourceType === "RECAP" && plan.sourceId === question.id);
              const broadPlans = plans.filter((plan) => plan.sourceType === "RECAP" && plan.sourceId === recap.id);
              const plan = exactPlans[0];
              return <article key={question.id} className="relative overflow-hidden rounded-[2.2rem] border border-white/85 bg-white/75 p-6 shadow-[0_18px_60px_rgba(80,55,115,0.07)] backdrop-blur-xl md:p-9">
                <div className="pointer-events-none absolute -right-20 -top-28 size-72 rounded-full bg-[radial-gradient(circle,rgba(255,167,125,0.26),rgba(172,148,255,0.15)_48%,transparent_73%)] blur-2xl" />
                <div className="relative">
                  <div className="flex flex-wrap items-center gap-2 text-xs text-stone-500"><span className={`rounded-full px-2.5 py-1 ${question.performance === "WEAK" ? "bg-rose-50 text-rose-600" : "bg-amber-50 text-amber-700"}`}>{question.performance === "WEAK" ? "薄弱" : "需巩固"}</span><span>{recap.company ?? recap.title}</span></div>
                  <h2 className="mt-4 max-w-4xl text-xl font-semibold leading-snug tracking-tight md:text-2xl">{question.questionText}</h2>
                  <div className="mt-6 grid gap-3 md:grid-cols-3">
                    <div className="rounded-3xl bg-rose-50/65 p-5"><span className="text-xs font-medium text-rose-600">当时的表现</span><p className="mt-3 line-clamp-4 text-sm leading-6 text-stone-700">{question.weaknessReason || question.candidateAnswer || "原始材料不足，暂不能判断具体原因。"}</p><Link href={`/app/recaps?selected=${recap.id}`} className="mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-stone-600 hover:text-stone-950">看原始面经<ArrowRight className="size-3.5" /></Link></div>
                    <div className="rounded-3xl bg-violet-50/70 p-5"><span className="inline-flex items-center gap-1.5 text-xs font-medium text-violet-700"><BookOpen className="size-3.5" />学习</span>{plan ? <><p className="mt-3 text-sm font-medium text-stone-800">{plan.concept}</p><p className="mt-1 text-xs text-stone-500">{plan.attemptCount ? `练习 ${plan.attemptCount} 次${plan.lastScore === null ? "" : ` · 最近 ${plan.lastScore} 分`}` : "尚未练习"}</p><Link href={`/app/learning?id=${plan.id}`} className="mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-violet-700">继续学习<ArrowRight className="size-3.5" /></Link></> : <><p className="mt-3 text-sm leading-6 text-stone-500">{broadPlans.length ? "已有整场面试的学习计划，尚未关联到这道题。" : "尚无针对这道题的学习记录。"}</p><Link href={learningHref(recap, question)} className="mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-violet-700">制定补强计划<ArrowRight className="size-3.5" /></Link></>}</div>
                    <div className="rounded-3xl bg-blue-50/70 p-5"><span className="inline-flex items-center gap-1.5 text-xs font-medium text-blue-700"><RotateCcw className="size-3.5" />记忆卡</span>{card ? <><p className="mt-3 line-clamp-2 text-sm font-medium text-stone-800">{card.front}</p><p className="mt-1 text-xs text-stone-500">{card.repetitions ? `自评复习 ${card.repetitions} 次` : "尚未复习"} · {card.status === "SUSPENDED" ? "已暂停" : "等待下次复习"}</p><Link href={`/app/cards?card=${card.id}`} className="mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-blue-700">打开卡片<ArrowRight className="size-3.5" /></Link></> : <><p className="mt-3 text-sm text-stone-500">还没有关联的记忆卡。</p><Link href={agentHref(recap, question)} className="mt-4 inline-flex items-center gap-1.5 text-xs font-medium text-blue-700"><Sparkles className="size-3.5" />让 Ardor 设计<ArrowRight className="size-3.5" /></Link></>}</div>
                  </div>
                </div>
              </article>;
            })}
            <p className="px-2 text-xs leading-5 text-stone-500">复习次数与练习分数是过程记录，不等于已经掌握；后续需要延迟变式复测。</p>
          </div>
        )}
      </div>
    </main>
  );
}
