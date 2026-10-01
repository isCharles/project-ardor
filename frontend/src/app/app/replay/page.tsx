"use client";

import { ArrowLeft, ArrowRight, RotateCcw, Sparkles } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";

type Attempt = {
  id: string;
  requestId: string;
  answerText: string;
  verdict: "CLEARER" | "SIMILAR" | "NEEDS_WORK" | "UNKNOWN";
  comparison: string;
  improvements: string[];
  remainingGaps: string[];
  nextChallenge: string | null;
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

const verdictLabel: Record<Attempt["verdict"], string> = {
  CLEARER: "这次更清楚", SIMILAR: "与上次相近", NEEDS_WORK: "仍需补强", UNKNOWN: "无法可靠比较",
};

function draftKey(questionId: string) { return `ardor:replay-draft:${questionId}`; }

export default function ReplayPage() {
  const router = useRouter();
  const [replay, setReplay] = useState<Replay | null>(null);
  const [answer, setAnswer] = useState("");
  const [requestId, setRequestId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    const timer = window.setTimeout(() => {
      const questionId = new URLSearchParams(window.location.search).get("question");
      if (!questionId) { setError("没有指定面经问题。请从成长证据页进入。"); setLoading(false); return; }
      try {
        const saved = window.sessionStorage.getItem(draftKey(questionId));
        if (saved) {
          const draft = JSON.parse(saved) as Draft;
          setAnswer(typeof draft.answer === "string" ? draft.answer : "");
          setRequestId(typeof draft.requestId === "string" ? draft.requestId : null);
        }
      } catch { window.sessionStorage.removeItem(draftKey(questionId)); }
      api<Replay>(`/api/interview-replays/questions/${questionId}`)
        .then((value) => { if (active) setReplay(value); })
        .catch((reason) => {
          if (!active) return;
          if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
          else setError(reason instanceof Error ? reason.message : "无法加载面试回放");
        })
        .finally(() => { if (active) setLoading(false); });
    }, 0);
    return () => { active = false; window.clearTimeout(timer); };
  }, [router]);

  function changeAnswer(value: string) {
    setAnswer(value);
    setRequestId(null);
    if (replay) window.sessionStorage.setItem(draftKey(replay.questionId), JSON.stringify({ answer: value, requestId: null }));
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!replay || !answer.trim() || busy) return;
    setBusy(true);
    setError("");
    const id = requestId ?? crypto.randomUUID();
    setRequestId(id);
    window.sessionStorage.setItem(draftKey(replay.questionId), JSON.stringify({ answer, requestId: id }));
    try {
      const saved = await api<Attempt>(`/api/interview-replays/questions/${replay.questionId}`, {
        method: "POST", body: JSON.stringify({ requestId: id, answer }),
      });
      setReplay((current) => current ? {
        ...current, attempts: [saved, ...current.attempts.filter((item) => item.id !== saved.id)],
      } : current);
      setAnswer("");
      setRequestId(null);
      window.sessionStorage.removeItem(draftKey(replay.questionId));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "提交失败，请稍后重试；你的回答已保留。");
    } finally { setBusy(false); }
  }

  const latest = replay?.attempts[0];

  return <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-5xl">
      <Link href="/app/evidence" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-white/70"><ArrowLeft className="size-4" />返回成长证据</Link>
      <header className="pb-9 pt-12 md:pt-16"><p className="text-xs font-medium tracking-[0.16em] text-violet-600">INTERVIEW REPLAY</p><h1 className="mt-3 text-4xl font-semibold tracking-[-0.06em] md:text-6xl">再回答一次。</h1></header>
      {error && !replay && <div role="alert" className="rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}
      {loading ? <div className="ardor-panel h-72 animate-pulse rounded-[2.5rem]" aria-label="正在加载面试回放" /> : replay && <>
        <section className="relative overflow-hidden rounded-[2.5rem] border border-white/80 bg-white/75 p-6 shadow-[0_25px_80px_rgba(89,58,138,0.08)] backdrop-blur-xl md:p-10">
          <div className="pointer-events-none absolute -right-24 -top-40 size-[32rem] rounded-full bg-[radial-gradient(circle,rgba(251,153,151,0.38),rgba(168,143,255,0.18)_46%,transparent_72%)] blur-3xl" />
          <div className="relative"><p className="text-xs text-stone-500">{replay.recapTitle}</p><h2 className="mt-4 max-w-3xl text-2xl font-semibold leading-snug tracking-tight md:text-4xl">{replay.questionText}</h2><p className="mt-5 text-sm text-stone-500">先独立回答，再看当时与现在的差别。</p></div>
        </section>

        <form onSubmit={submit} className="mt-6 rounded-[2rem] border border-white/80 bg-white/80 p-5 shadow-[0_16px_55px_rgba(73,57,122,0.06)] md:p-7">
          <label htmlFor="replay-answer" className="text-sm font-medium">这次你会怎么回答？</label>
          <textarea id="replay-answer" value={answer} onChange={(event) => changeAnswer(event.target.value)} maxLength={12000} rows={7} className="mt-4 w-full resize-y rounded-2xl border border-stone-200/80 bg-white/75 p-4 text-sm leading-7 outline-none transition focus:border-violet-300 focus:ring-2 focus:ring-violet-100" placeholder="用自己的话回答，不必追求标准话术…" />
          <div className="mt-3 flex flex-wrap items-center justify-between gap-3"><span className="text-xs text-stone-400">草稿会保留在这个浏览器会话中 · {answer.length.toLocaleString()} / 12,000</span><button type="submit" disabled={busy || !answer.trim()} className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white transition hover:bg-stone-800 disabled:opacity-45">{busy ? "正在对比…" : "提交并对比"}<ArrowRight className="size-4" /></button></div>
          {error && <p role="alert" className="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</p>}
        </form>

        {latest && <section className="mt-8 space-y-5" aria-label="最新回放结果">
          <div className="flex flex-wrap items-center gap-3"><span className="rounded-full bg-violet-100 px-3 py-1.5 text-xs font-medium text-violet-700">{verdictLabel[latest.verdict]}</span><span className="text-xs text-stone-500">AI 对比 · 不是能力认证</span></div>
          <p className="max-w-3xl text-lg leading-8 text-stone-800">{latest.comparison}</p>
          <div className="grid gap-4 md:grid-cols-2"><article className="rounded-3xl bg-white/65 p-6"><h3 className="text-xs font-medium text-stone-500">当时的回答</h3><p className="mt-3 whitespace-pre-wrap text-sm leading-7 text-stone-700">{replay.originalAnswer || "原始材料未记录回答，无法判断是否进步。"}</p></article><article className="rounded-3xl bg-white/65 p-6"><h3 className="text-xs font-medium text-violet-600">现在的回答</h3><p className="mt-3 whitespace-pre-wrap text-sm leading-7 text-stone-700">{latest.answerText}</p></article></div>
          <div className="grid gap-4 md:grid-cols-2"><FeedbackList title="有进步的地方" items={latest.improvements} tone="emerald" /><FeedbackList title="仍需补强" items={latest.remainingGaps} tone="rose" /></div>
          {latest.nextChallenge && <div className="flex items-start gap-3 rounded-2xl bg-violet-50/80 p-5"><Sparkles className="mt-0.5 size-4 shrink-0 text-violet-600" /><p className="text-sm leading-6 text-violet-900">下一次可以试着回答：{latest.nextChallenge}</p></div>}
        </section>}

        {replay.attempts.length > 1 && <section className="mt-10"><h2 className="mb-4 text-lg font-semibold">之前的回放</h2><div className="space-y-2">{replay.attempts.slice(1).map((item) => <details key={item.id} className="rounded-2xl bg-white/65 px-5 py-4"><summary className="cursor-pointer text-sm font-medium text-stone-700">{new Date(item.createdAt).toLocaleString("zh-CN")} · {verdictLabel[item.verdict]}</summary><p className="mt-4 whitespace-pre-wrap text-sm leading-6 text-stone-600">{item.answerText}</p><p className="mt-3 text-sm leading-6 text-stone-500">{item.comparison}</p></details>)}</div></section>}
        <Link href={`/app/recaps?selected=${replay.recapId}`} className="mt-10 inline-flex items-center gap-2 text-sm text-stone-500 hover:text-stone-900"><RotateCcw className="size-4" />回到原始面经</Link>
      </>}
    </div>
  </main>;
}

function FeedbackList({ title, items, tone }: { title: string; items: string[]; tone: "emerald" | "rose" }) {
  return <div className={`rounded-3xl p-6 ${tone === "emerald" ? "bg-emerald-50/80" : "bg-rose-50/80"}`}><h3 className={`text-xs font-medium ${tone === "emerald" ? "text-emerald-700" : "text-rose-700"}`}>{title}</h3>{items.length ? <ul className="mt-3 space-y-2 text-sm leading-6 text-stone-700">{items.map((item, index) => <li key={`${index}-${item}`}>· {item}</li>)}</ul> : <p className="mt-3 text-sm text-stone-500">暂无可靠结论</p>}</div>;
}
