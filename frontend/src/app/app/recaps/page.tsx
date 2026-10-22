"use client";

import { ArrowLeft, ArrowRight, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useCallback, useEffect, useState } from "react";

import { AppDialog } from "@/components/ui/app-dialog";
import { api, ApiError } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { useReadVisibleResult } from "@/lib/background-tasks";
import { FinalMinutesWorkspace } from "./final-minutes-workspace";
import { InterviewContextFields, type InterviewReference } from "./interview-context-fields";

type Performance = "STRONG" | "MIXED" | "WEAK" | "UNKNOWN";
type Question = { id: string; sequenceNumber: number; questionText: string; candidateAnswer: string | null; followUps: string[]; assessment: string; performance: Performance; weaknessReason: string | null; betterAnswer: string | null; tags: string[] };
type Recap = { id: string; title: string; overview: string; strengths: string[]; weaknesses: string[]; createdAt: string; questions: Question[] };
type Opportunity = InterviewReference;
type Task = { jobId: string | null; recapId: string | null; status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED"; errorMessage?: string | null };

const performanceStyle: Record<Performance, string> = {
  STRONG: "bg-emerald-100 text-emerald-700",
  MIXED: "bg-amber-100 text-amber-700",
  WEAK: "bg-rose-100 text-rose-700",
  UNKNOWN: "bg-stone-100 text-stone-500",
};

export default function RecapsPage() {
  return <Suspense fallback={<div role="status" className="p-8 text-sm text-stone-500">…</div>}><RecapsContent /></Suspense>;
}

function RecapsContent() {
  const router = useRouter();
  const search = useSearchParams();
  const { locale, t } = useLocale();
  const [recaps, setRecaps] = useState<Recap[]>([]);
  const [opportunities, setOpportunities] = useState<Opportunity[]>([]);
  const selectedId = search.get("selected");
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    try {
      const [items, projects] = await Promise.all([api<Recap[]>("/api/interview-recaps"), api<Opportunity[]>("/api/opportunities")]);
      setRecaps(items);
      setOpportunities(projects);
      return items;
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : t("Could not load interview notes", "无法加载面试记录"));
      return [];
    }
  }, [router, t]);

  // eslint-disable-next-line react-hooks/set-state-in-effect
  useEffect(() => { void load(); }, [load, selectedId]);

  async function organize(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const content = String(data.get("content") ?? "");
    const jobOpportunityId = String(data.get("jobOpportunityId") ?? "");
    if (!jobOpportunityId) { setError(t("Choose the interview this record belongs to", "请选择这份记录所属的面试")); return; }
    setBusy(true);
    setError("");
    try {
      const task = await api<Task>("/api/interview-recaps/final-minutes", { method: "POST", body: JSON.stringify({ content, jobOpportunityId, interviewRoundId: data.get("interviewRoundId") || null }) });
      setShowForm(false);
      if (task.jobId) router.push(`/app/recaps?minutes=${task.jobId}`);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not organize these notes", "整理失败"));
    } finally {
      setBusy(false);
    }
  }

  async function remove(recap: Recap) {
    if (!window.confirm(t(`Delete “${recap.title}”?`, `删除“${recap.title}”？`))) return;
    try {
      await api<void>(`/api/interview-recaps/${recap.id}`, { method: "DELETE" });
      if (selectedId === recap.id) router.push("/app/recaps");
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not delete", "删除失败"));
    }
  }

  const selected = recaps.find(item => item.id === selectedId) ?? null;
  useReadVisibleResult("RECAP_ORGANIZE", selected?.id, !!selected);

  return <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-4xl">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs font-medium uppercase tracking-[0.16em] text-violet-600">INTERVIEW NOTES</p>
        <button type="button" onClick={() => setShowForm(true)} className="inline-flex items-center gap-2 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white hover:bg-black"><Plus className="size-4" />{t("Organize a recap", "整理面试经过")}</button>
      </div>
      <header className="pb-8 pt-6">
        <h1 className="text-3xl font-semibold tracking-tight md:text-5xl">{t("Interview notes", "面试记录")}</h1>
        <p className="mt-3 max-w-2xl text-sm leading-6 text-stone-500">{t("Save your final minutes. Ardor organizes their structure and wording in the background, without inventing details.", "保存你的最终纪要，Ardor 在后台规范化结构与表达，不补造细节。")}</p>
      </header>
      {error && <div role="alert" className="mb-5 rounded-2xl bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      <FinalMinutesWorkspace key={search.get("minutes") ?? "list"} id={search.get("minutes")} />

      {selected ? <RecapReport recap={selected} onBack={() => router.push("/app/recaps")} onDelete={() => void remove(selected)} /> : !search.get("minutes") && (
        <section className="overflow-hidden rounded-[2rem] border border-white/80 bg-white/70">
          {recaps.length === 0 ? <div className="px-6 py-16 text-center">
            <p className="text-sm text-stone-500">{t("No interview notes yet.", "还没有面试记录。")}</p>
            <button type="button" onClick={() => setShowForm(true)} className="mt-5 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white">{t("Organize the first one", "整理第一份")}</button>
          </div> : recaps.map(recap => <Link key={recap.id} href={`/app/recaps?selected=${recap.id}`} className="flex w-full items-center justify-between gap-3 border-b border-stone-100 px-5 py-4 text-left last:border-b-0 hover:bg-violet-50/40">
            <span className="min-w-0 truncate text-sm font-medium">{recap.title}</span>
            <span className="shrink-0 text-xs text-stone-400">{new Date(recap.createdAt).toLocaleDateString(locale === "en" ? "en-US" : "zh-CN", { year: "numeric", month: "short", day: "numeric" })}</span>
            <ArrowRight className="size-4 shrink-0 text-stone-300" />
          </Link>)}
        </section>
      )}

      {showForm && <AppDialog title={t("Organize an interview", "整理一份面试记录")} onClose={() => setShowForm(false)}>
        <form onSubmit={organize} className="space-y-4">
          <InterviewContextFields opportunities={opportunities} />
          <label className="block text-sm">{t("Final interview minutes", "最终面试纪要")}
            <textarea name="content" required minLength={30} maxLength={50000} rows={14} className="field mt-2 resize-y" placeholder={t("Paste your final questions, answers and follow-ups. Ardor only organizes what you provide; no transcript or second confirmation is needed.", "粘贴最终的问题、回答和追问纪要。Ardor 仅整理你提供的内容，无需原始转录或再次确认。")} />
          </label>
          <div className="flex justify-end gap-2 pt-2">
            <button type="button" onClick={() => setShowForm(false)} className="rounded-full px-4 py-2 text-sm text-stone-500 hover:bg-stone-100">{t("Cancel", "取消")}</button>
            <button disabled={busy || opportunities.length === 0} className="rounded-full bg-[#1d1d1f] px-5 py-2 text-sm font-medium text-white disabled:opacity-40">{busy ? t("Submitting…", "提交中…") : t("Organize", "整理")}</button>
          </div>
        </form>
      </AppDialog>}
    </div>
  </main>;
}

function RecapReport({ recap, onBack, onDelete }: { recap: Recap; onBack: () => void; onDelete: () => void }) {
  const { t } = useLocale();
  return <div>
    <div className="mb-5 flex items-center justify-between gap-3">
      <button type="button" onClick={onBack} className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />{t("All interview notes", "所有面试记录")}</button>
      <button type="button" onClick={onDelete} aria-label={t("Delete recap", "删除面试记录")} className="rounded-full p-2 text-stone-300 hover:bg-rose-50 hover:text-rose-500"><Trash2 className="size-4" /></button>
    </div>
    <section className="rounded-[2rem] border border-white/80 bg-white/75 p-6 shadow-[0_16px_50px_rgba(49,39,70,0.05)] md:p-8">
      <h2 className="text-2xl font-semibold tracking-tight">{recap.title}</h2>
      <p className="mt-4 whitespace-pre-wrap text-sm leading-7 text-stone-600">{recap.overview}</p>
      {(recap.strengths.length > 0 || recap.weaknesses.length > 0) && <div className="mt-6 grid gap-3 md:grid-cols-2">
        {recap.strengths.length > 0 && <div className="rounded-2xl bg-emerald-50/70 p-4"><h3 className="text-sm font-semibold text-emerald-800">{t("What worked", "做得不错")}</h3><ul className="mt-2 space-y-1 text-sm leading-6 text-emerald-950/80">{recap.strengths.map(item => <li key={item}>· {item}</li>)}</ul></div>}
        {recap.weaknesses.length > 0 && <div className="rounded-2xl bg-rose-50/70 p-4"><h3 className="text-sm font-semibold text-rose-800">{t("Weak spots", "薄弱点")}</h3><ul className="mt-2 space-y-1 text-sm leading-6 text-rose-950/80">{recap.weaknesses.map(item => <li key={item}>· {item}</li>)}</ul></div>}
      </div>}
    </section>
    <div className="mt-5 space-y-4">{recap.questions.map(question => <article key={question.id} className="rounded-[1.6rem] border border-white/80 bg-white/70 p-5 md:p-6">
      <div className="flex items-start justify-between gap-3">
        <h3 className="text-base font-semibold leading-6">{question.questionText}</h3>
        <span className={`shrink-0 rounded-full px-2.5 py-1 text-[11px] ${performanceStyle[question.performance]}`}>{question.performance}</span>
      </div>
      {question.tags.length > 0 && <div className="mt-2 flex flex-wrap gap-1.5">{question.tags.map(tag => <span key={tag} className="rounded-full bg-stone-100 px-2.5 py-1 text-[11px] text-stone-500">{tag}</span>)}</div>}
      <p className="mt-3 whitespace-pre-wrap text-sm leading-6 text-stone-600">{question.assessment}</p>
      {question.weaknessReason && <p className="mt-2 rounded-xl bg-rose-50/70 px-3 py-2 text-sm leading-6 text-rose-800"><strong className="font-semibold">{t("Weak spot", "薄弱点")}：</strong>{question.weaknessReason}</p>}
      {(question.candidateAnswer || question.betterAnswer) && <details className="mt-3 text-sm">
        <summary className="cursor-pointer text-xs font-medium text-violet-700">{t("Original answer and stronger direction", "原始回答与更好思路")}</summary>
        {question.candidateAnswer && <p className="mt-2 whitespace-pre-wrap text-stone-500">{question.candidateAnswer}</p>}
        {question.betterAnswer && <p className="mt-2 whitespace-pre-wrap rounded-xl bg-violet-50/70 p-3 text-violet-950/80">{question.betterAnswer}</p>}
      </details>}
    </article>)}</div>
  </div>;
}
