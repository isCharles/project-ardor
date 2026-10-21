"use client";

import { ArrowRight, RotateCcw, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Question = { id: string; questionText: string };
type Recap = { id: string; title: string };
type Plan = { id: string; concept: string; status: string; attemptCount: number; lastScore: number | null; targetInterviewId: string | null; targetRoundId: string | null };
type Track = {
  question: Question & { recapId: string; recapTitle: string; observation: string | null; performance: string; interviewId: string | null; roundId: string | null };
  learningPlans: Plan[]; interviewLearningPlans: Plan[];
  cards: { id: string; status: string; repetitions: number; nextReviewAt: string | null }[];
  replay: { attemptCount: number; latestVerdict: string | null; latestAt: string | null } | null;
};
type Progress = {
  page: number; hasNext: boolean; totalQuestions: number; unresolvedSources: number; tracks: Track[];
  accountSummary: { learningPlans: number; recordedLearningAttempts: number; flashcards: number; replayAttempts: number };
};

function customPlanHref(recap: Recap, question: Question, observation: string | null, locale: string) {
  const params = new URLSearchParams({
    new: "1",
    prefill: locale === "en"
      ? `In interview “${recap.title}” I answered “${question.questionText}”. The weakness I want to fix: ${observation || "not assessed yet"}. Decide what will help me most: a learning course, flashcards, or both. Use originKind INTERVIEW_QUESTION and sourceId ${question.id}. Do not copy the original question; check the source before choosing.`
      : `我在面经「${recap.title}」里回答「${question.questionText}」时暴露的薄弱点是：${observation || "材料不足，暂未判断"}。请先判断最合适的补强方式：生成一门学习课、准备记忆卡，或两者结合。originKind 用 INTERVIEW_QUESTION，sourceId 用问题 ID ${question.id}。不要照抄原题，先核对来源再决定。`,
    contextType: "RECAP",
    contextId: recap.id,
    contextLabel: recap.title,
  });
  return `/app?${params.toString()}`;
}


export default function EvidencePage() {
  return <Suspense fallback={<div role="status" className="p-8">…</div>}><ProgressScope /></Suspense>;
}

function ProgressScope() {
  const search = useSearchParams();
  const source = search.get("sourceInterviewId");
  return <ProgressContent key={source ?? ""} source={source} />;
}

function ProgressContent({ source }: { source: string | null }) {
  const router = useRouter();
  const { locale, t } = useLocale();
  const [data, setData] = useState<Progress | null>(null);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState("");
  const activeRequest = useRef<{ active: boolean } | null>(null);
  const [refresh, setRefresh] = useState(0);

  useEffect(() => {
    const ticket = { active: true };
    activeRequest.current = ticket;
    const query = new URLSearchParams({ page: "0", size: "20" });
    if (source) query.set("sourceInterviewId", source);
    api<Progress>("/api/learning-progress?" + query).then(value => {
      if (ticket.active) setData(value);
    }).catch(reason => {
      if (!ticket.active) return;
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "Unknown error");
    }).finally(() => { if (ticket.active) setBusy(false); });
    return () => { ticket.active = false; };
  }, [router, source, refresh]);

  async function more() {
    if (!data || busy || !data.hasNext) return;
    const ticket = activeRequest.current;
    setBusy(true); setError("");
    const query = new URLSearchParams({ page: String(data.page + 1), size: "20" });
    if (source) query.set("sourceInterviewId", source);
    try {
      const next = await api<Progress>("/api/learning-progress?" + query);
      if (!ticket?.active) return;
      setData(previous => previous ? { ...next, unresolvedSources: previous.unresolvedSources + next.unresolvedSources,
        tracks: [...new Map([...previous.tracks, ...next.tracks].map(track => [track.question.id, track])).values()] } : next);
    } catch (reason) {
      if (!ticket?.active) return;
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "Unknown error");
    } finally { if (ticket?.active) setBusy(false); }
  }

  const verdicts: Record<string, string> = {
    CLEARER: t("Clearer", "比原来更清楚"), SIMILAR: t("Similar", "与原来相近"),
    NEEDS_WORK: t("Needs work", "仍需练习"), UNKNOWN: t("Not enough evidence", "证据不足"),
  };

  return <main className="ardor-workbench min-h-screen min-w-0 px-5 pb-20 pt-8 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-6xl">
      <header className="flex flex-wrap items-end justify-between gap-4 pb-8 pt-6">
        <div><p className="text-xs tracking-widest text-violet-600">{t("LEARNING · PROGRESS", "学习 · 补强进度")}</p>
          <h1 className="mt-3 text-3xl font-semibold tracking-tight md:text-5xl">{t("Your next steps, connected.", "看见每一步的进展。")}</h1>
          <p className="mt-3 text-sm text-stone-500">{t("Practice and review records, not a mastery score.", "串起学习、重答与复习记录，不把次数当成掌握程度。")}</p>
        </div>
        <button disabled={busy} onClick={() => { setBusy(true); setError(""); setData(null); setRefresh(value => value + 1); }} className="inline-flex items-center gap-2 rounded-full border border-stone-200 bg-white/70 px-4 py-2 text-sm disabled:opacity-50">
          <RotateCcw className={"size-4 " + (busy ? "animate-spin motion-reduce:animate-none" : "")} />{t("Refresh", "刷新")}
        </button>
      </header>
      {error && <div role="alert" className="mb-6 rounded-2xl bg-red-50 p-4 text-sm text-red-700">
        <p>{t("Could not load progress. Try refreshing.", "无法加载进度，请刷新重试。")}</p>
        <details className="mt-2"><summary>{t("View details", "查看详情")}</summary><pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-all">{error}</pre></details>
      </div>}
      {data && <>
        <section aria-label={t("Account activity", "账户活动")} className="mb-9 grid grid-cols-2 gap-3 md:grid-cols-4">
          {[
            [t("Learning plans", "学习计划"), data.accountSummary.learningPlans, "/app/learning"],
            [t("Recorded learning attempts", "已保存的学习尝试"), data.accountSummary.recordedLearningAttempts, "/app/learning"],
            [t("Flashcards", "记忆卡"), data.accountSummary.flashcards, "/app/learning/review"],
            [t("Question practice attempts", "重答次数"), data.accountSummary.replayAttempts, null],
          ].map(([label, count, href]) => <div key={String(label)} className="min-w-0 rounded-2xl border border-white/80 bg-white/65 p-4">
            {href ? <Link href={String(href)} className="block"><span className="block text-2xl font-semibold">{count}</span><span className="mt-1 block text-xs text-stone-500">{label}</span></Link>
              : <><span className="block text-2xl font-semibold">{count}</span><span className="mt-1 block text-xs text-stone-500">{label}</span></>}
          </div>)}
        </section>
        <div className="mb-5 flex flex-wrap items-center justify-between gap-2 text-sm text-stone-500">
          <p>{t("Interview questions to improve", "有待补强的面试问题")} · {data.totalQuestions}</p>
          {source && <Link href="/app/learning/progress" className="underline">{t("Show all interviews", "查看全部面试")}</Link>}
        </div>
        {data.unresolvedSources > 0 && <p role="status" className="mb-4 text-sm text-amber-700">{t("Some legacy sources could not be resolved. They were not guessed or linked.", "部分旧来源无法可靠解析，未猜测或强行关联。")}</p>}
        {!data.tracks.length && <section className="rounded-3xl bg-white/65 p-8">
          <h2 className="text-lg font-semibold">{t("No interview gaps recorded here yet.", "这里还没有面试补强记录。")}</h2>
          <p className="mt-2 text-sm text-stone-500">{t("Independent learning and review still count in your account activity above.", "自主学习和复习仍保留在上方账户活动中，不需要先有一场面试。")}</p>
          <Link className="mt-4 inline-flex items-center gap-2 text-sm text-violet-700" href="/app/job-search/notes">{t("Open interview notes", "查看面试记录")}<ArrowRight className="size-4" /></Link>
        </section>}
        <div className="space-y-6">{data.tracks.map(track => {
          const q = track.question, recap = { id: q.recapId, title: q.recapTitle };
          return <article key={q.id} data-progress-question={q.id} className="min-w-0 rounded-3xl border border-white/80 bg-white/70 p-5 md:p-8">
            <div className="flex flex-wrap items-center gap-2 text-xs text-stone-500">
              <span className="rounded-full bg-amber-50 px-2.5 py-1 text-amber-700">{q.performance === "WEAK" ? t("Needs work", "薄弱") : t("Practice more", "需巩固")}</span>
              <Link href={"/app/recaps?selected=" + q.recapId} className="min-w-0 truncate underline">{q.recapTitle}</Link>
              {q.roundId && <span>{t("Round-linked record", "已关联面试轮次")}</span>}
            </div>
            <h2 className="mt-4 break-words text-xl font-semibold">{q.questionText}</h2>
            <p className="mt-3 line-clamp-4 break-words text-sm leading-6 text-stone-600">{q.observation || t("Source material is insufficient to assess the gap.", "材料不足，暂不能判断具体原因。")}</p>
            <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-stone-100 pt-4">
              <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-stone-500">
                {track.learningPlans.length > 0 && <Link href={"/app/learning?id=" + track.learningPlans[0].id} className="font-medium text-violet-700">{t(`Learning plan (${track.learningPlans.length})`, `学习计划（${track.learningPlans.length}）`)}</Link>}
                {track.interviewLearningPlans.length > 0 && <span>{t(`Interview-wide plans ${track.interviewLearningPlans.length}`, `整场学习计划 ${track.interviewLearningPlans.length}`)}</span>}
                {track.cards.length > 0 && <Link href={"/app/cards?card=" + track.cards[0].id} className="font-medium text-violet-700">{t(`Flashcards (${track.cards.length})`, `记忆卡（${track.cards.length}）`)}</Link>}
                {track.replay?.attemptCount ? <span>{t(`Retried ${track.replay.attemptCount}×`, `重答 ${track.replay.attemptCount} 次`)}{track.replay.latestVerdict && ` · ${verdicts[track.replay.latestVerdict] ?? ""}`}</span> : null}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                <Link href={"/app/learning/practice/questions?question=" + q.id} className="rounded-full border border-stone-200 bg-white/80 px-4 py-2 text-sm font-medium text-stone-700 hover:bg-white">{t("Try again", "重新回答")}</Link>
                <div className="group relative">
                  <button type="button" aria-label={t("Ask Ardor for a tailored plan", "让 Ardor 定制补强计划")} aria-haspopup="menu" className="grid size-9 place-items-center rounded-full bg-violet-50 text-violet-700 transition hover:bg-violet-100"><Sparkles className="size-4" /></button>
                  <div role="menu" className="invisible absolute right-0 top-full z-20 mt-1 w-48 rounded-xl border border-stone-200 bg-white p-1.5 opacity-0 shadow-xl transition group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100">
                    <Link href={customPlanHref(recap, q, q.observation, locale)} className="block rounded-lg px-3 py-2 text-sm text-stone-700 hover:bg-violet-50">{t("Build a tailored plan", "帮我定制补强计划")}</Link>
                  </div>
                </div>
              </div>
            </div>
          </article>;
        })}</div>
        {data.hasNext && <button disabled={busy} onClick={() => void more()} className="mx-auto mt-8 block rounded-full bg-stone-950 px-6 py-2.5 text-sm text-white disabled:opacity-50">{busy ? t("Loading…", "加载中…") : t("Load more", "加载更多")}</button>}
      </>}
      {busy && !data && <p role="status" className="py-12 text-sm text-stone-500">{t("Loading progress…", "正在加载进度…")}</p>}
    </div>
  </main>;
}
