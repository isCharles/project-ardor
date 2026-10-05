"use client";

import { ArrowLeft, ArrowRight, ChevronLeft, ChevronRight, Plus, Sparkles, Trash2, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import React, { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { markBackgroundPending } from "@/lib/background-notifications";
import { useLocale } from "@/lib/locale";
import styles from "./recaps.module.css";

type Performance = "STRONG" | "MIXED" | "WEAK" | "UNKNOWN";
type Question = { id: string; sequenceNumber: number; questionText: string; candidateAnswer: string | null; followUps: string[]; assessment: string; performance: Performance; weaknessReason: string | null; betterAnswer: string | null; tags: string[] };
type Recap = { id: string; title: string; company: string | null; targetRole: string | null; occurredAt: string | null; overview: string; strengths: string[]; weaknesses: string[]; createdAt: string; questions: Question[] };
type RecapTask = { jobId: string | null; recapId: string | null; status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED"; attempts?: number; errorMessage?: string | null; createdAt?: string | null; finishedAt?: string | null };
const performanceStyle: Record<Performance, string> = { STRONG: "bg-emerald-100 text-emerald-700", MIXED: "bg-amber-100 text-amber-700", WEAK: "bg-rose-100 text-rose-700", UNKNOWN: "bg-stone-100 text-stone-500" };

/* Fans the recaps into a 3D stack around the focused one, draggable like a phone
   carousel.

   The source design fans four visually distinct cards around a glass centre card,
   so variant styling is keyed off the card's position rather than repeating one
   tile — the reference's own guardrail is "do not flatten the source into a generic
   card grid". Drag state is a fractional index offset, which lets the whole fan
   track the pointer continuously and then snap to the nearest card. */
/* Horizontal distance between neighbouring cards. The walk trigger and the fan
   transform must agree on this, or hovering a card selects a different one. */
const CARD_PITCH = 150;

const barHeight: Record<Performance, number> = { STRONG: 100, MIXED: 62, WEAK: 34, UNKNOWN: 20 };

function weakCount(recap: Recap) {
  return recap.questions.filter((q) => q.performance === "WEAK" || q.performance === "MIXED").length;
}

/* The interview date is the card's headline fact. occurredAt is only set when the
   material stated a date, so fall back to when the recap was filed and say so. */
function formatInterviewDate(recap: Recap, locale: "en" | "zh-CN") {
  const stated = Boolean(recap.occurredAt);
  const value = new Date((recap.occurredAt ?? recap.createdAt) as string);
  const text = value.toLocaleDateString(locale === "en" ? "en-US" : "zh-CN", { year: "numeric", month: "long", day: "numeric" });
  return stated ? text : locale === "en" ? `${text} · filed` : `${text}（整理于）`;
}

function RecapStack({
  recaps, selectedId, onSelect,
}: { recaps: Recap[]; selectedId: string | null; onSelect: (id: string) => void }) {
  const { locale, t } = useLocale();
  const index = Math.max(0, recaps.findIndex((item) => item.id === selectedId));

  /* Selection changes only on an explicit click (or an arrow key).

     Pointer-driven switching was tried twice and removed both times. Press-and-drag
     needed two sign conventions that disagreed, so the fan slid one way and the
     release snapped the other. Hover-to-walk replaced it, but any pointer resting
     off-centre kept stepping, so the stack drifted to one end on its own. Clicking is
     the only model here that never moves something the user did not ask to move. */

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const next = Math.min(recaps.length - 1, Math.max(0, index + (event.key === "ArrowRight" ? 1 : -1)));
    if (next !== index) onSelect(recaps[next].id);
  }

  return (
    <div
      className={styles.stage}
      role="group"
      aria-label={t("Interview notes. Click a card or use the arrow keys to switch.", "面经卡组，点击卡片或用左右方向键切换")}
      tabIndex={0}
      onKeyDown={onKeyDown}
    >
      <button type="button" aria-label={t("Previous interview notes", "上一份面经")} disabled={index === 0}
        onClick={() => onSelect(recaps[index - 1].id)}
        className={`${styles.navButton} ${styles.navPrev}`}>
        <ChevronLeft className="size-5" />
      </button>
      <button type="button" aria-label={t("Next interview notes", "下一份面经")} disabled={index === recaps.length - 1}
        onClick={() => onSelect(recaps[index + 1].id)}
        className={`${styles.navButton} ${styles.navNext}`}>
        <ChevronRight className="size-5" />
      </button>
      <div className={styles.rail}>
        {recaps.map((recap, position) => {
          const offset = position - index;
          const distance = Math.abs(offset);
          const active = offset === 0;

          return (
            <button
              type="button"
              key={recap.id}
              onClick={() => onSelect(recap.id)}
              aria-current={active}
              aria-label={t(`${recap.title}, ${recap.questions.length} questions`, `${recap.title}，${recap.questions.length} 题`)}
              tabIndex={distance > 2 ? -1 : 0}
              className={`${styles.card} ${active ? styles.cardActive : ""}`}
              style={{
                // The focused card sits at a positive Z: inside preserve-3d the browser
                // sorts by real depth and ignores z-index, so depth is what keeps it in front.
                transform: [
                  `translateX(${offset * CARD_PITCH}px)`,
                  `translateY(${active ? 0 : 8}px)`,
                  `translateZ(${active ? 60 : -140 * distance}px)`,
                  `rotateY(${offset * -11}deg)`,
                  `scale(${active ? 1 : 0.9})`,
                ].join(" "),
                opacity: distance > 2.4 ? 0 : 1,
                pointerEvents: distance > 2.4 ? "none" : "auto",
              }}
            >
              <div>
                <div className="flex items-start justify-between gap-3">
                  <span className="font-mono text-[11px] tabular-nums opacity-55">
                    #{recaps.length - position}
                  </span>
                  <span className="font-mono text-[11px] tabular-nums opacity-55">
                    {recap.questions.length} Q
                  </span>
                </div>
                <p className={`${styles.interviewDate} mt-3`}>{formatInterviewDate(recap, locale)}</p>
                <h3 className={`${styles.cardTitle} mt-2`}>{recap.title}</h3>
                <p className={`${styles.cardMeta} mt-2`}>
                  {[recap.company, recap.targetRole].filter(Boolean).join(" · ") || t("Company not specified", "未标注公司")}
                </p>
              </div>

              <div className={styles.bars}>
                {recap.questions.slice(0, 12).map((question, barIndex) => (
                  <span key={question.id} className={styles.bar} style={{
                    height: `${barHeight[question.performance]}%`,
                    background: "currentColor",
                    opacity: 0.25 + (barHeight[question.performance] / 100) * 0.6,
                    animationDelay: `${barIndex * 40}ms`,
                  }} />
                ))}
              </div>

              <div className="space-y-2">
                <div className={styles.statRow}><span>{t("Questions", "题目")}</span><span className="font-medium">{recap.questions.length}</span></div>
                <div className={styles.statRow}><span>{t("To improve", "待补强")}</span><span className="font-medium">{weakCount(recap)}</span></div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}

export default function RecapsPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const [recaps, setRecaps] = useState<Recap[]>([]);
  const [jobs, setJobs] = useState<RecapTask[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const load = useCallback(async () => { try { const [items, tasks] = await Promise.all([api<Recap[]>("/api/interview-recaps"), api<RecapTask[]>("/api/interview-recaps/jobs")]); setRecaps(items); setJobs(tasks); const params = new URLSearchParams(window.location.search); const requested = params.get("selected"); const source = params.get("source"); const sourceRecap = source ? items.find((item) => item.id === source || item.questions.some((question) => question.id === source)) : null; setSelectedId((id) => requested && items.some((x) => x.id === requested) ? requested : sourceRecap ? sourceRecap.id : id && items.some((x) => x.id === id) ? id : items[0]?.id ?? null); } catch (reason) { if (reason instanceof ApiError && reason.status === 401) router.replace("/login"); else setError(reason instanceof Error ? reason.message : t("Could not load interview notes", "无法加载面经")); } }, [router, t]);
  useEffect(() => { const first = window.setTimeout(() => void load(), 0); return () => window.clearTimeout(first); }, [load]);
  const selected = recaps.find((item) => item.id === selectedId) ?? null;
  const activeJobs = jobs.filter((job) => job.status === "QUEUED" || job.status === "RUNNING");
  const failedJobs = jobs.filter((job) => job.status === "FAILED");
  useEffect(() => {
    if (activeJobs.length === 0) return;
    const timer = window.setTimeout(() => void load(), 4000);
    return () => window.clearTimeout(timer);
  }, [activeJobs.length, load]);
  async function organize(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError(""); try { const task = await api<RecapTask>("/api/interview-recaps", { method: "POST", body: JSON.stringify({ content: data.get("content") }) }); setShowForm(false); if (task.status === "COMPLETED" && task.recapId) router.push(`/app/recaps?selected=${task.recapId}`); else { await markBackgroundPending("recaps"); router.push("/app?notice=recap-queued"); } } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not submit interview notes", "提交失败")); } finally { setBusy(false); } }
  async function remove(recap: Recap) { if (!window.confirm(t(`Delete “${recap.title}”?`, `删除“${recap.title}”？`))) return; try { await api<void>(`/api/interview-recaps/${recap.id}`, { method: "DELETE" }); await load(); } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not delete interview notes", "删除失败")); } }
  async function updateMetadata(recap: Recap, company: string, targetRole: string, occurredOn: string) { setBusy(true); setError(""); try { const updated = await api<Recap>(`/api/interview-recaps/${recap.id}/metadata`, { method: "PATCH", body: JSON.stringify({ company, targetRole, occurredAt: occurredOn ? new Date(`${occurredOn}T00:00:00`).toISOString() : null }) }); setRecaps((items) => items.map((item) => item.id === updated.id ? updated : item)); } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not save interview details", "保存面试信息失败")); } finally { setBusy(false); } }

  async function retryJob(jobId: string) {
    setBusy(true); setError("");
    try { await api<RecapTask>(`/api/interview-recaps/jobs/${jobId}/retry`, { method: "POST" }); await load(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("Retry failed", "重试失败")); }
    finally { setBusy(false); }
  }

  async function dismissJob(jobId: string) {
    setBusy(true); setError("");
    try { await api<void>(`/api/interview-recaps/jobs/${jobId}`, { method: "DELETE" }); await load(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not dismiss this task", "无法忽略该任务")); }
    finally { setBusy(false); }
  }

  return <main className={styles.shell}>
    <div className={styles.ambient} aria-hidden="true"><div className={styles.grain} /></div>
    <div className={`${styles.content} mx-auto max-w-7xl px-5 pb-20 pt-6 md:px-10`}>
      <div className="flex items-center justify-between">
        <Link href="/app" className="inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-slate-500 transition hover:bg-white/70 hover:text-slate-900"><ArrowLeft className="size-4" />{t("Back to Ardor", "返回 Ardor")}</Link>
        <button onClick={() => setShowForm(true)} className={`${styles.buttonPrimary} inline-flex items-center gap-2 px-5 py-2.5 text-sm`}><Plus className="size-4" />{t("Add interview notes", "整理面经")}</button>
      </div>

      <header className="pb-8 pt-12 text-center md:pt-16">
        <p className={`${styles.label} text-slate-500`}>{t("Interview notes", "面试复盘")}</p>
        <h1 className={`${styles.display} mt-3`}>{t("See how you grow.", "看清每一次进步。")}</h1>
        <p className="mx-auto mt-4 max-w-md text-sm leading-6 text-slate-600/85">{t("Review each interview question by question, then turn weak spots into a practice plan.", "逐题复盘每场面试，再将薄弱点转为练习计划。")}</p>
      </header>

      {error && <div className={`${styles.panel} mb-5 px-5 py-4 text-sm text-[#a33a32]`} role="status" aria-live="polite">{error}</div>}

      {(activeJobs.length > 0 || failedJobs.length > 0) && <div className="mx-auto mb-6 max-w-2xl space-y-2">
        {activeJobs.map((job) => <div key={job.jobId} className={`${styles.panel} flex items-center gap-3 px-5 py-4`}><span className="size-2 animate-pulse rounded-full bg-[#ED7B46]" /><span className="text-sm text-slate-600">{t("Organizing in the background…", "正在后台整理…")}</span></div>)}
        {failedJobs.map((job) => <div key={job.jobId} className={`${styles.panel} flex flex-wrap items-center gap-3 px-5 py-4 text-sm`}>
          <span className="min-w-0 flex-1 text-[#a33a32]">{t("Could not organize: ", "整理失败：")}{job.errorMessage ?? t("Please submit again", "请重新提交")}</span>
          {(job.finishedAt ?? job.createdAt) && <span className="font-mono text-[11px] text-slate-400">{new Date((job.finishedAt ?? job.createdAt) as string).toLocaleString(locale === "en" ? "en-US" : "zh-CN", { month: "numeric", day: "numeric", hour: "2-digit", minute: "2-digit" })}{job.attempts ? t(` · ${job.attempts} attempts`, ` · 已试 ${job.attempts} 次`) : ""}</span>}
          <span className="flex shrink-0 gap-2">
            <button type="button" disabled={busy || !job.jobId} onClick={() => job.jobId && void retryJob(job.jobId)} className={`${styles.buttonPrimary} px-4 py-1.5 text-xs`}>{t("Retry", "重试")}</button>
            <button type="button" disabled={busy || !job.jobId} onClick={() => job.jobId && void dismissJob(job.jobId)} className={`${styles.buttonQuiet} px-4 py-1.5 text-xs`}>{t("Dismiss", "忽略")}</button>
          </span>
        </div>)}
      </div>}

      {recaps.length === 0 ? (
        activeJobs.length === 0 && <button onClick={() => setShowForm(true)} className={`${styles.panel} mx-auto block w-full max-w-md border-dashed px-5 py-16 text-sm text-slate-400 transition hover:text-slate-600`}>{t("Paste your first interview notes", "粘贴第一次面试内容")}</button>
      ) : (
        <>
          <RecapStack recaps={recaps} selectedId={selectedId} onSelect={setSelectedId} />
          <div className="mt-2 flex justify-center gap-2.5">
            {recaps.map((recap) => (
              <button
                key={recap.id}
                type="button"
                aria-label={t(`Switch to ${recap.title}`, `切换到 ${recap.title}`)}
                aria-current={recap.id === selectedId}
                onClick={() => setSelectedId(recap.id)}
                className={`${styles.dot} ${recap.id === selectedId ? styles.dotActive : ""}`}
              />
            ))}
          </div>
          <section className={`${styles.panel} mt-10 p-6 md:p-9`}>
            {selected ? <Report recap={selected} busy={busy} onMetadata={(company, role, date) => updateMetadata(selected, company, role, date)} onDelete={() => remove(selected)} /> : <div className="grid h-64 place-items-center text-sm text-slate-400">{t("Select interview notes", "选择一份面经")}</div>}
          </section>
        </>
      )}
  </div>{showForm && <div className="fixed inset-0 z-50 grid place-items-center bg-black/20 p-4 backdrop-blur-sm"><section className="ardor-panel max-h-[92vh] w-full max-w-2xl overflow-y-auto rounded-[2rem] p-6 md:p-8"><div className="mb-6 flex items-center justify-between"><h2 className="text-2xl font-semibold">{t("Organize an interview", "整理一场面试")}</h2><button aria-label={t("Close", "关闭")} onClick={() => setShowForm(false)} className="rounded-full p-2 text-stone-400 hover:bg-white"><X className="size-5" /></button></div><form onSubmit={organize} className="space-y-4"><textarea name="content" required minLength={30} maxLength={50000} rows={16} className="field resize-y" placeholder={t("Paste a transcript, your recollection, or polished notes…", "粘贴转录、回忆或整理过的内容…")} /><button disabled={busy} className="w-full rounded-full bg-stone-950 px-5 py-3 text-sm font-medium text-white disabled:opacity-50">{busy ? t("Organizing questions…", "正在逐题整理…") : t("Create interview notes", "生成面经")}</button></form></section></div>}</main>;
}

function Report({ recap, busy, onMetadata, onDelete }: { recap: Recap; busy: boolean; onMetadata: (company: string, role: string, date: string) => void; onDelete: () => void }) {
  const { t } = useLocale();
  const missingMetadata = !recap.company || !recap.targetRole || !recap.occurredAt;
  if (missingMetadata) return <div><form key={`${recap.id}-${recap.company}-${recap.targetRole}-${recap.occurredAt}`} onSubmit={(event) => { event.preventDefault(); const data = new FormData(event.currentTarget); onMetadata(String(data.get("company") ?? ""), String(data.get("targetRole") ?? ""), String(data.get("occurredOn") ?? "")); }} className={`${styles.panel} mb-7 border border-amber-200/70 bg-amber-50/65 p-5`}><p className="text-sm font-medium text-amber-900">{t("Complete interview details", "补全面试信息")}</p><div className="mt-4 grid gap-3 md:grid-cols-3"><input name="company" defaultValue={recap.company ?? ""} maxLength={160} className="field" placeholder={t("Company", "公司")} /><input name="targetRole" defaultValue={recap.targetRole ?? ""} maxLength={160} className="field" placeholder={t("Role", "岗位")} /><input name="occurredOn" type="date" aria-label={t("Interview date", "面试日期")} defaultValue={recap.occurredAt ? recap.occurredAt.slice(0, 10) : ""} className="field" /></div><button disabled={busy} className={`${styles.buttonPrimary} mt-4 px-5 py-2 text-sm`}>{busy ? t("Saving…", "保存中…") : t("Save", "保存")}</button></form><CompleteReport recap={recap} onDelete={onDelete} /></div>;
  return <CompleteReport recap={recap} onDelete={onDelete} />;
}

function CompleteReport({ recap, onDelete }: { recap: Recap; onDelete: () => void }) {
  const { t } = useLocale();
  const performanceLabel: Record<Performance, string> = { STRONG: t("Strong", "稳"), MIXED: t("Developing", "需巩固"), WEAK: t("Weak", "薄弱"), UNKNOWN: t("Unrated", "待确认") };
  const optimizeHref = `/app?new=1&prefill=${encodeURIComponent(t("Create practice cards based on this interview", "根据我这次面试情况帮我生成记忆卡"))}&contextType=RECAP&contextId=${recap.id}&contextLabel=${encodeURIComponent(recap.title)}`;
  return <div><div className="flex items-start justify-between gap-4"><div><div className="flex flex-wrap gap-2">{recap.company && <span className="rounded-full bg-violet-100 px-3 py-1 text-xs text-violet-700">{recap.company}</span>}{recap.targetRole && <span className="rounded-full bg-stone-100 px-3 py-1 text-xs text-stone-600">{recap.targetRole}</span>}</div><h2 className="mt-4 text-3xl font-semibold tracking-tight">{recap.title}</h2><p className="mt-4 text-sm leading-7 text-stone-600">{recap.overview}</p><Link href={optimizeHref} className="mt-5 inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white hover:bg-black">{t("Improve my plan", "优化计划")}<ArrowRight className="size-4" /></Link></div><button aria-label={t("Delete interview notes", "删除面经")} onClick={onDelete} className="rounded-full p-2 text-stone-300 hover:bg-rose-50 hover:text-rose-500"><Trash2 className="size-4" /></button></div>{(recap.strengths.length > 0 || recap.weaknesses.length > 0) && <div className="mt-8 grid gap-3 md:grid-cols-2">{recap.strengths.length > 0 && <Summary title={t("What went well", "做得不错")} items={recap.strengths} tone="emerald" />}{recap.weaknesses.length > 0 && <Summary title={t("Focus next", "优先补强")} items={recap.weaknesses} tone="rose" />}</div>}<div className="mt-10 space-y-5">{recap.questions.map((q) => <article key={q.id} className="rounded-[1.75rem] bg-white/65 p-5 md:p-7"><div className="flex items-start gap-3"><span className="grid size-8 shrink-0 place-items-center rounded-full bg-stone-950 text-xs text-white">{q.sequenceNumber}</span><div className="min-w-0 flex-1"><div className="flex items-start justify-between gap-3"><h3 className="text-lg font-semibold leading-7">{q.questionText}</h3><span className={`shrink-0 rounded-full px-2.5 py-1 text-xs ${performanceStyle[q.performance]}`}>{performanceLabel[q.performance]}</span></div>{q.tags.length > 0 && <div className="mt-3 flex flex-wrap gap-1.5">{q.tags.map((tag) => <span key={tag} className="rounded-full bg-stone-100 px-2.5 py-1 text-[11px] text-stone-500">{tag}</span>)}</div>}</div></div><div className="mt-5 space-y-5 text-sm leading-7 md:pl-11">{q.candidateAnswer && <Section title={t("Your answer", "当时回答")} text={q.candidateAnswer} />}{q.followUps.length > 0 && <section><h4 className="text-xs font-semibold uppercase tracking-wider text-stone-400">{t("Follow-ups", "追问")}</h4><ul className="mt-1 space-y-1 text-stone-600">{q.followUps.map((x) => <li key={x}>· {x}</li>)}</ul></section>}<Section title={t("Review", "复盘")} text={q.assessment} />{q.weaknessReason && <Section title={t("Weak spot", "薄弱点")} text={q.weaknessReason} danger />}{q.betterAnswer && <section className="rounded-2xl bg-violet-50/80 p-4"><h4 className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-violet-500"><Sparkles className="size-3.5" />{t("A stronger answer", "更好的回答思路")}</h4><p className="mt-2 whitespace-pre-wrap text-violet-950/75">{q.betterAnswer}</p></section>}</div></article>)}</div></div>;
}
function Summary({ title, items, tone }: { title: string; items: string[]; tone: "emerald" | "rose" }) { return <div className={`rounded-2xl p-5 ${tone === "emerald" ? "bg-emerald-50/70" : "bg-rose-50/70"}`}><h3 className="text-sm font-semibold">{title}</h3><ul className="mt-3 space-y-2 text-sm leading-6 opacity-75">{items.map((x) => <li key={x}>· {x}</li>)}</ul></div>; }
function Section({ title, text, danger = false }: { title: string; text: string; danger?: boolean }) { return <section><h4 className={`text-xs font-semibold uppercase tracking-wider ${danger ? "text-rose-400" : "text-stone-400"}`}>{title}</h4><p className={`mt-1 whitespace-pre-wrap ${danger ? "text-rose-700/80" : "text-stone-600"}`}>{text}</p></section>; }
