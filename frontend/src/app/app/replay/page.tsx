"use client";

import { ArrowLeft, ArrowRight, CalendarDays, RotateCcw, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { useLocale, type Locale } from "@/lib/locale";

type Attempt = {
  id: string;
  requestId: string;
  answerText: string;
  verdict: "CLEARER" | "SIMILAR" | "NEEDS_WORK" | "UNKNOWN";
  comparison: string;
  improvements: string[];
  remainingGaps: string[];
  nextChallenge: string | null;
  challengeText: string | null;
  createdAt: string;
};
type Replay = {
  questionId: string;
  recapId: string;
  recapTitle: string;
  questionText: string;
  originalAnswer: string | null;
  originalAssessment: string | null;
  weaknessReason: string | null;
  attempts: Attempt[];
};
type Draft = { answer: string; requestId: string | null };
type ScheduledRetest = { id: string; attemptId: string; dueAt: string; challenge: string };
type RetestOverview = { scheduled: ScheduledRetest | null };
type ReplayError = { reason: unknown; english: string; chinese: string };

function replayError(reason: unknown, locale: Locale, english: string, chinese: string) {
  if (locale === "zh-CN") return reason instanceof Error ? reason.message : chinese;
  if (reason instanceof ApiError) {
    const messages: Record<string, string> = {
      NOT_FOUND: "This interview question or replay is no longer available.",
      INVALID_REQUEST: "Please check your answer or retest date and try again.",
      INVALID_STATE: "This retest can no longer be changed.",
      QUOTA_EXCEEDED: "You have reached your current usage limit.",
      EXTERNAL_DNS_TEMPORARY: "The model provider is temporarily unreachable. Please try again.",
    };
    return reason.body.code && messages[reason.body.code] ? messages[reason.body.code] : english;
  }
  return reason instanceof Error && !/[\u3400-\u9fff]/u.test(reason.message) ? reason.message : english;
}

function draftKey(questionId: string, retestId: string | null) {
  return `ardor:replay-draft:${questionId}:${retestId ?? "practice"}`;
}
function dateInputValue(date: Date) {
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}
function defaultRetestDate() {
  const date = new Date();
  date.setDate(date.getDate() + 3);
  date.setHours(9, 0, 0, 0);
  return dateInputValue(date);
}

export default function ReplayPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const verdictLabel: Record<Attempt["verdict"], string> = {
    CLEARER: t("Clearer this time", "这次更清楚"), SIMILAR: t("Similar to last time", "与上次相近"),
    NEEDS_WORK: t("Still needs work", "仍需补强"), UNKNOWN: t("Not enough to compare", "无法可靠比较"),
  };
  const [replay, setReplay] = useState<Replay | null>(null);
  const [answer, setAnswer] = useState("");
  const [requestId, setRequestId] = useState<string | null>(null);
  const [requestedRetestId, setRequestedRetestId] = useState<string | null>(null);
  const [scheduledRetest, setScheduledRetest] = useState<ScheduledRetest | null>(null);
  const [retestDate, setRetestDate] = useState(defaultRetestDate);
  const [scheduling, setScheduling] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ReplayError | null>(null);
  const errorText = error ? replayError(error.reason, locale, error.english, error.chinese) : "";

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      const questionId = new URLSearchParams(window.location.search).get("question");
      const retestId = new URLSearchParams(window.location.search).get("retest");
      if (!questionId) { setError({ reason: null, english: "No interview question was selected. Open a replay from Growth evidence.", chinese: "没有指定面经问题。请从成长证据页进入。" }); setLoading(false); return; }
      setRequestedRetestId(retestId);
      let draft: Draft | null = null;
      try {
        const saved = window.sessionStorage.getItem(draftKey(questionId, retestId));
        if (saved) {
          draft = JSON.parse(saved) as Draft;
          setAnswer(typeof draft.answer === "string" ? draft.answer : "");
          setRequestId(typeof draft.requestId === "string" ? draft.requestId : null);
        }
      } catch { window.sessionStorage.removeItem(draftKey(questionId, retestId)); }
      Promise.all([
        api<Replay>(`/api/interview-replays/questions/${questionId}`),
        api<RetestOverview>(`/api/interview-replays/questions/${questionId}/retest`),
      ])
        .then(([value, overview]) => {
          if (!active) return;
          setReplay(value);
          setScheduledRetest(overview.scheduled);
          if (draft?.requestId && value.attempts.some((item) => item.requestId === draft?.requestId)) {
            setAnswer(""); setRequestId(null);
            window.sessionStorage.removeItem(draftKey(questionId, retestId));
          }
        })
        .catch((reason) => {
          if (!active) return;
          if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
          else setError({ reason, english: "Could not load the interview replay", chinese: "无法加载面试回放" });
        })
        .finally(() => { if (active) setLoading(false); });
    }, 0);
    return () => { active = false; window.clearTimeout(timer); };
  }, [router]);

  function changeAnswer(value: string) {
    setAnswer(value);
    setRequestId(null);
    if (replay) window.sessionStorage.setItem(draftKey(replay.questionId, requestedRetestId), JSON.stringify({ answer: value, requestId: null }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!replay || !answer.trim() || busy) return;
    setBusy(true);
    setError(null);
    const id = requestId ?? crypto.randomUUID();
    setRequestId(id);
    window.sessionStorage.setItem(draftKey(replay.questionId, requestedRetestId), JSON.stringify({ answer, requestId: id }));
    const activeRetestId = scheduledRetest?.id === requestedRetestId ? requestedRetestId : null;
    try {
      const saved = await api<Attempt>(`/api/interview-replays/questions/${replay.questionId}`, {
        method: "POST", body: JSON.stringify({ requestId: id, answer, retestTaskId: activeRetestId }),
      });
      setReplay((current) => current ? {
        ...current, attempts: [saved, ...current.attempts.filter((item) => item.id !== saved.id)],
      } : current);
      setAnswer("");
      setRequestId(null);
      window.sessionStorage.removeItem(draftKey(replay.questionId, requestedRetestId));
      if (activeRetestId) setScheduledRetest(null);
    } catch (reason) {
      setError({ reason, english: "Submission failed. Please retry; your answer is saved in this session.", chinese: "提交失败，请稍后重试；你的回答已保留。" });
    } finally { setBusy(false); }
  }

  async function scheduleRetest() {
    if (!replay || !latest?.nextChallenge || scheduling) return;
    setScheduling(true); setError(null);
    try {
      const scheduled = await api<ScheduledRetest>(`/api/interview-replays/questions/${replay.questionId}/retest`, {
        method: "POST", body: JSON.stringify({ attemptId: latest.id, dueAt: new Date(retestDate).toISOString() }),
      });
      setScheduledRetest(scheduled);
    } catch (reason) { setError({ reason, english: "Could not schedule the retest", chinese: "安排复测失败" }); }
    finally { setScheduling(false); }
  }

  const latest = replay?.attempts[0];
  const activeRetest = scheduledRetest?.id === requestedRetestId ? scheduledRetest : null;

  return <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-5xl">
      <Link href="/app/evidence" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-white/70"><ArrowLeft className="size-4" />{t("Back to Growth evidence", "返回成长证据")}</Link>
      <header className="pb-9 pt-12 md:pt-16"><p className="text-xs font-medium tracking-[0.16em] text-violet-600">INTERVIEW REPLAY</p><h1 className="mt-3 text-4xl font-semibold tracking-[-0.06em] md:text-6xl">{t("Answer it again.", "再回答一次。")}</h1></header>
      {errorText && !replay && <div role="alert" className="rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{errorText}</div>}
      {loading ? <div className="ardor-panel h-72 animate-pulse rounded-[2.5rem]" aria-label={t("Loading interview replay", "正在加载面试回放")} /> : replay && <>
        <section className="relative overflow-hidden rounded-[2.5rem] border border-white/80 bg-white/75 p-6 shadow-[0_25px_80px_rgba(89,58,138,0.08)] backdrop-blur-xl md:p-10">
          <div className="pointer-events-none absolute -right-24 -top-40 size-[32rem] rounded-full bg-[radial-gradient(circle,rgba(251,153,151,0.38),rgba(168,143,255,0.18)_46%,transparent_72%)] blur-3xl" />
          <div className="relative"><p className="text-xs text-stone-500">{replay.recapTitle}</p>{activeRetest && <p className="mt-4 text-xs font-medium text-violet-600">{t("Variation retest", "变式复测")} · {new Date(activeRetest.dueAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" })}</p>}<h2 className="mt-4 max-w-3xl text-2xl font-semibold leading-snug tracking-tight md:text-4xl">{activeRetest?.challenge ?? replay.questionText}</h2><p className="mt-5 text-sm text-stone-500">{activeRetest ? t(`Original question: ${replay.questionText}`, `原题：${replay.questionText}`) : t("Answer on your own first, then compare it with your previous response.", "先独立回答，再看当时与现在的差别。")}</p></div>
        </section>

        {requestedRetestId && !activeRetest && <p className="mt-4 text-sm text-stone-500">{t("This retest was completed or canceled. You can keep practicing freely.", "这项复测已完成或取消；现在可以继续自由练习。")}</p>}

        <form onSubmit={submit} className="mt-6 rounded-[2rem] border border-white/80 bg-white/80 p-5 shadow-[0_16px_55px_rgba(73,57,122,0.06)] md:p-7">
          <label htmlFor="replay-answer" className="text-sm font-medium">{t("How would you answer this time?", "这次你会怎么回答？")}</label>
          <textarea id="replay-answer" value={answer} onChange={(event) => changeAnswer(event.target.value)} maxLength={12000} rows={7} className="mt-4 w-full resize-y rounded-2xl border border-stone-200/80 bg-white/75 p-4 text-sm leading-7 outline-none transition focus:border-violet-300 focus:ring-2 focus:ring-violet-100" placeholder={t("Answer in your own words—no scripted response needed…", "用自己的话回答，不必追求标准话术…")} />
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3"><span className="text-xs text-stone-400">{t("Draft saved for this browser session", "草稿会保留在这个浏览器会话中")} · {answer.length.toLocaleString(locale === "en" ? "en-US" : "zh-CN")} / 12,000</span><button type="submit" disabled={busy || !answer.trim()} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white transition hover:bg-stone-800 disabled:opacity-45">{busy ? t("Comparing…", "正在对比…") : t("Submit & compare", "提交并对比")}<ArrowRight className="size-4" /></button></div>
          {errorText && <p role="alert" className="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700">{errorText}</p>}
        </form>

        {latest && <section className="mt-8 space-y-5" aria-label={t("Latest replay result", "最新回放结果")}>
          <div className="flex flex-wrap items-center gap-3"><span className="rounded-full bg-violet-100 px-3 py-1.5 text-xs font-medium text-violet-700">{verdictLabel[latest.verdict]}</span><span className="text-xs text-stone-500">{t("AI comparison · not a skills certification", "AI 对比 · 不是能力认证")}</span></div>
          <p className="max-w-3xl text-lg leading-8 text-stone-800">{latest.comparison}</p>
          <div className="grid gap-4 md:grid-cols-2"><article className="rounded-3xl bg-white/65 p-6"><h3 className="text-xs font-medium text-stone-500">{t("Your earlier answer", "当时的回答")}</h3><p className="mt-3 whitespace-pre-wrap text-sm leading-7 text-stone-700">{replay.originalAnswer || t("The original notes did not record an answer, so progress cannot be assessed.", "原始材料未记录回答，无法判断是否进步。")}</p></article><article className="rounded-3xl bg-white/65 p-6"><h3 className="text-xs font-medium text-violet-600">{latest.challengeText ? t("Retest answer", "复测回答") : t("Your answer now", "现在的回答")}</h3>{latest.challengeText && <p className="mt-3 text-xs leading-5 text-violet-700">{latest.challengeText}</p>}<p className="mt-3 whitespace-pre-wrap text-sm leading-7 text-stone-700">{latest.answerText}</p></article></div>
          <div className="grid gap-4 md:grid-cols-2"><FeedbackList title={t("What improved", "有进步的地方")} items={latest.improvements} tone="emerald" /><FeedbackList title={t("Still to improve", "仍需补强")} items={latest.remainingGaps} tone="rose" /></div>
          {latest.nextChallenge && <div className="flex items-start gap-3 rounded-2xl bg-violet-50/80 p-5"><Sparkles className="mt-0.5 size-4 shrink-0 text-violet-600" /><p className="text-sm leading-6 text-violet-900">{t("Try answering this next time: ", "下一次可以试着回答：")}{latest.nextChallenge}</p></div>}
          {scheduledRetest ? <div className="flex flex-wrap items-center gap-3 rounded-2xl bg-white/75 px-5 py-4 text-sm"><CalendarDays className="size-4 text-violet-600" /><span>{t("Scheduled for ", "已安排 ")}{new Date(scheduledRetest.dueAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN", { month: "long", day: "numeric", hour: "2-digit", minute: "2-digit" })}</span><Link href="/app/calendar" className="ml-auto text-violet-700 hover:underline">{t("View calendar", "查看日历")}</Link></div> : latest.nextChallenge && <div className="flex flex-wrap items-center gap-3 rounded-2xl bg-white/75 px-5 py-4"><label htmlFor="retest-date" className="text-sm font-medium">{t("Try again in three days?", "三天后再试？")}</label><input id="retest-date" type="datetime-local" value={retestDate} onChange={(event) => setRetestDate(event.target.value)} className="rounded-xl border border-stone-200 bg-white px-3 py-2 text-sm" /><button type="button" onClick={scheduleRetest} disabled={scheduling || !retestDate} className="rounded-full bg-stone-950 px-4 py-2 text-sm text-white disabled:opacity-45">{scheduling ? t("Scheduling…", "安排中…") : t("Schedule retest", "安排复测")}</button></div>}
        </section>}

        {replay.attempts.length > 1 && <section className="mt-10"><h2 className="mb-4 text-lg font-semibold">{t("Earlier attempts", "之前的回放")}</h2><div className="space-y-2">{replay.attempts.slice(1).map((item) => <details key={item.id} className="rounded-2xl bg-white/65 px-5 py-4"><summary className="cursor-pointer text-sm font-medium text-stone-700">{new Date(item.createdAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN")} · {verdictLabel[item.verdict]}</summary>{item.challengeText && <p className="mt-4 text-sm text-violet-700">{t("Retest question: ", "复测题：")}{item.challengeText}</p>}<p className="mt-4 whitespace-pre-wrap text-sm leading-6 text-stone-600">{item.answerText}</p><p className="mt-3 text-sm leading-6 text-stone-500">{item.comparison}</p></details>)}</div></section>}
        <Link href={`/app/recaps?selected=${replay.recapId}`} className="mt-10 inline-flex items-center gap-2 text-sm text-stone-500 hover:text-stone-900"><RotateCcw className="size-4" />{t("Back to interview notes", "回到原始面经")}</Link>
      </>}
    </div>
  </main>;
}

function FeedbackList({ title, items, tone }: { title: string; items: string[]; tone: "emerald" | "rose" }) {
  const { t } = useLocale();
  return <div className={`rounded-3xl p-6 ${tone === "emerald" ? "bg-emerald-50/80" : "bg-rose-50/80"}`}><h3 className={`text-xs font-medium ${tone === "emerald" ? "text-emerald-700" : "text-rose-700"}`}>{title}</h3>{items.length ? <ul className="mt-3 space-y-2 text-sm leading-6 text-stone-700">{items.map((item, index) => <li key={`${index}-${item}`}>· {item}</li>)}</ul> : <p className="mt-3 text-sm text-stone-500">{t("No reliable conclusion yet", "暂无可靠结论")}</p>}</div>;
}
