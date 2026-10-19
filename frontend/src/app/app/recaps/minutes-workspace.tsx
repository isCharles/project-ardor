"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { useReadVisibleResult } from "@/lib/background-tasks";
import { MarkdownContent } from "@/components/ardor/markdown-content";

type Summary = { id: string; company: string; status: string; revision: number };
type Review = { id: string; revision: number; status: string; recapId: string | null; errorMessage: string | null; minutes: string };
type Draft = { id: string; rawContent: string; minutes: string | null; revision: number; status: string; errorMessage: string | null; reviews: Review[] };

export function MinutesWorkspace() {
  const { t } = useLocale();
  const params = useSearchParams();
  const [items, setItems] = useState<Summary[]>([]);
  const [error, setError] = useState("");
  const selected = params.get("draft");
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try { const result = await api<Summary[]>("/api/interview-recaps/drafts"); if (alive) { setItems(result); setError(""); } }
      catch (e) { if (alive && !(e instanceof ApiError && e.status === 404)) setError(t("Could not load saved transcripts. Retry by refreshing.", "无法读取已保存转录，请刷新重试。")); }
    };
    void load(); const timer = window.setInterval(() => void load(), 5000);
    return () => { alive=false; window.clearInterval(timer); };
  }, [t]);
  return <section className="mx-auto mb-8 max-w-4xl space-y-4" aria-label={t("Transcript workflow", "转录与纪要工作流")}>
    {error && <p role="alert" className="text-sm text-rose-700">{error}</p>}
    {items.length > 0 && <div className="rounded-3xl border border-stone-200 bg-white/80 p-5">
      <h2 className="font-semibold">{t("Transcripts & editable minutes", "原始转录与待确认纪要")}</h2>
      <p className="mt-2 text-sm leading-6 text-stone-500">{t("Correct the draft while your memory is fresh. A detailed review starts only after you confirm the minutes.", "请趁记忆新鲜纠正和补充初稿。只有你确认纪要后，才开始详细复盘。")}</p>
      <ul className="mt-3 divide-y divide-stone-100">{items.map(item => <li key={item.id}><Link className="flex items-center justify-between gap-4 py-3 text-sm" href={`/app/recaps?draft=${item.id}`}>
        <span className="truncate">{item.company}</span><span className="shrink-0 text-stone-500">{item.revision > 0 ? t("Open minutes", "编辑／确认纪要") : item.status === "FAILED" ? t("Draft failed · raw saved", "初稿失败 · 原文已保存") : t("Preparing draft · raw saved", "初稿整理中 · 原文已保存")}</span>
      </Link></li>)}</ul>
    </div>}
    {selected && <MinutesEditor key={selected} id={selected} />}
  </section>;
}

function MinutesEditor({ id }: { id: string }) {
  const { t } = useLocale();
  const [draft, setDraft] = useState<Draft | null>(null);
  const [text, setText] = useState("");
  const [owner, setOwner] = useState("");
  const [error, setError] = useState("");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const [hasConflict, setHasConflict] = useState(false);
  const [saveTick, setSaveTick] = useState(0);
  const initialized = useRef(false), stopped = useRef(false), conflicted = useRef(false);
  const current = useRef(""), saved = useRef(""), revision = useRef(0);
  const saving = useRef<Promise<boolean> | null>(null);
  const cacheKey = owner ? `ardor:interview-minutes:${owner}:${id}` : "";
  useReadVisibleResult("RECAP_ORGANIZE",id,!!draft);

  useEffect(() => {
    stopped.current=false;
    const load = async () => {
      try {
        const account = await api<{ id: string }>("/api/auth/me");
        const result = await api<Draft>(`/api/interview-recaps/drafts/${id}`);
        if (stopped.current) return;
        setOwner(account.id); setDraft(result);
        if (!initialized.current && result.minutes != null) {
          initialized.current=true; revision.current=result.revision; saved.current=result.minutes;
          let local: { text?: string; revision?: number } | null = null;
          try { local=JSON.parse(sessionStorage.getItem(`ardor:interview-minutes:${account.id}:${id}`) ?? "null"); } catch { /* Storage is optional. */ }
          const restored=typeof local?.text === "string" && local.text.length <= 50000 ? local.text : result.minutes;
          current.current=restored; setText(restored);
          if (local && local.revision !== result.revision && restored !== result.minutes) {
            conflicted.current=true; setHasConflict(true);
            setError(t("The server has a newer version. Your local draft is preserved below; copy it before reloading. It will not overwrite the server automatically.", "服务端纪要已更新。下方保留你的本地草稿，请先复制内容再重新加载；不会自动覆盖服务端。"));
          }
        }
      } catch (e) { if (!stopped.current) setError(e instanceof Error ? e.message : t("Could not load minutes", "无法加载纪要")); }
    };
    void load(); const timer=window.setInterval(() => void load(),4000);
    return () => { stopped.current=true; window.clearInterval(timer); };
  },[id,t]);

  const persist = useCallback((): Promise<boolean> => {
    if (saving.current) return saving.current;
    if (conflicted.current || !initialized.current) return Promise.resolve(false);
    if (current.current === saved.current) return Promise.resolve(true);
    const sent=current.current, expected=revision.current;
    const promise=(async () => {
      setStatus(t("Saving…", "正在保存…"));
      try {
        const result=await api<Draft>(`/api/interview-recaps/drafts/${id}`,{method:"PUT",body:JSON.stringify({minutes:sent,revision:expected})});
        if (stopped.current) return false;
        revision.current=result.revision; saved.current=sent; setDraft(result); setError("");
        try { if (cacheKey) sessionStorage.setItem(cacheKey,JSON.stringify({text:current.current,revision:result.revision})); } catch { /* Server save remains authoritative. */ }
        setStatus(current.current===sent ? t("Saved", "已保存") : t("More changes pending…", "后续修改待保存…"));
        setSaveTick(tick=>tick+1); return true;
      } catch(e) {
        if (e instanceof ApiError && e.status === 409) { conflicted.current=true; setHasConflict(true); }
        setError(e instanceof ApiError && e.status === 409 ? t("Another edit changed this version. Your text is kept; copy it before reloading.", "另一处编辑更新了纪要。你的输入已保留，请先复制再刷新对照。") : t("Save failed. Your text is kept here; retry before leaving.", "保存失败，输入已保留在此页面，请重试后再离开。"));
        setStatus(t("Not saved", "尚未保存")); return false;
      } finally { saving.current=null; }
    })();
    saving.current=promise; return promise;
  },[cacheKey,id,t]);

  useEffect(() => {
    if (!initialized.current || !cacheKey) return;
    try { sessionStorage.setItem(cacheKey,JSON.stringify({text,revision:revision.current})); } catch { /* Do not block edits. */ }
    const timer=window.setTimeout(() => void persist(),900);
    return () => window.clearTimeout(timer);
  },[text,cacheKey,persist,saveTick]);
  useEffect(() => {
    const warn=(event: BeforeUnloadEvent) => { if (current.current!==saved.current) event.preventDefault(); };
    window.addEventListener("beforeunload",warn); return () => window.removeEventListener("beforeunload",warn);
  },[]);

  async function confirm() {
    setBusy(true);
    try {
      for(let attempt=0;attempt<3;attempt++) {
        if (!await persist()) return;
        if(current.current===saved.current) break;
      }
      if(current.current!==saved.current) return;
      const key=`${cacheKey}:confirm:${revision.current}`;
      let requestId=crypto.randomUUID();
      try { requestId=sessionStorage.getItem(key) ?? requestId; sessionStorage.setItem(key,requestId); } catch { /* Server also deduplicates the confirmed version. */ }
      const result=await api<Draft>(`/api/interview-recaps/drafts/${id}/confirm`,{method:"POST",body:JSON.stringify({revision:revision.current,requestId})});
      setDraft(result); setStatus(t("Confirmed. Detailed review queued; you can leave this page.", "纪要已确认，详细复盘已提交后台，可以离开页面。")); setError("");
    } catch(e) { setError(e instanceof Error ? e.message : t("Could not confirm minutes", "确认失败")); }
    finally { setBusy(false); }
  }
  async function retry(reviewId?: string) {
    setBusy(true);
    try {
      await api(reviewId ? `/api/interview-recaps/drafts/${id}/reviews/${reviewId}/retry` : `/api/interview-recaps/jobs/${id}/retry`,{method:"POST"});
      setError(""); setStatus(t("Retry queued", "已提交重试"));
    } catch(e) { setError(e instanceof Error ? e.message : t("Retry failed", "重试失败")); }
    finally { setBusy(false); }
  }
  if (!draft) return <p role="status">{error || t("Loading saved transcript…", "正在读取已保存的转录…")}</p>;
  return <div className="space-y-5 rounded-3xl border border-stone-200 bg-white/90 p-5 md:p-8">
    <p className="text-xs text-stone-500">{t("Transcript / Minutes / Review", "原始转录 / 用户纪要 / 详细复盘")}</p>
    <h2 className="text-xl font-semibold">{t("Check your minutes", "趁记忆新鲜，校正纪要")}</h2>
    <p className="text-sm leading-6 text-stone-600">{t("Your raw transcript is saved unchanged. Check speakers, technical terms, missing answers and rehearsal noise while your memory is fresh. Uncertainty is allowed; do not invent missing details.", "原始转录已保存。请趁记忆新鲜核对说话人、技术名词、缺失回答和面试前练习。记不清的地方可以保留“待确认”，不必补写。")}</p>
    <details className="rounded-2xl bg-stone-50 p-4"><summary className="cursor-pointer text-sm font-medium">{t("Original transcript · read only", "原始转录 · 只读")}</summary><pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap break-words text-sm leading-7">{draft.rawContent}</pre></details>
    {draft.minutes == null ? <div role="status" className="text-sm">{draft.status === "FAILED" ? <><p>{t("Draft generation failed. The raw transcript is safe.", "初稿整理失败，原始转录仍然保留。")}</p><button disabled={busy} onClick={() => void retry()} className="mt-3 underline">{t("Retry draft", "重试初稿")}</button></> : t("Preparing a factual draft, not an evaluation. You may leave and come back.", "正在整理事实纪要，不会提前评价。可以离开后再回来。")}</div> : <>
      <label className="block text-sm font-medium" htmlFor="interview-minutes-editor">{t("Editable minutes · auto-saved", "纪要编辑区 · 自动保存")}</label>
      <textarea id="interview-minutes-editor" value={text} onChange={e => {current.current=e.target.value;setText(e.target.value);}} disabled={busy} maxLength={50000} rows={22} className="field w-full resize-y font-mono text-sm leading-7" />
      <details className="rounded-2xl bg-stone-50 p-4"><summary className="cursor-pointer text-sm text-stone-600">{t("Preview formatted minutes", "预览分块纪要")}</summary><MarkdownContent className="mt-4">{text}</MarkdownContent></details>
      <div className="flex flex-wrap items-center justify-between gap-3"><span role="status" className="text-sm text-stone-500">{status || t(`Version ${draft.revision}`,`版本 ${draft.revision}`)}</span><div className="flex gap-3"><button disabled={busy || hasConflict} onClick={() => void persist()} className="rounded-full border border-stone-300 px-4 py-2 text-sm">{t("Save", "保存")}</button><button disabled={busy || text.trim().length<30 || hasConflict || draft.reviews.some(r => r.revision===draft.revision && text===draft.minutes)} onClick={() => void confirm()} className="rounded-full bg-stone-950 px-5 py-2 text-sm text-white disabled:opacity-40">{t("Confirm minutes & generate review", "确认纪要并生成复盘")}</button></div></div>
    </>}
    {error && <p role="alert" className="rounded-xl bg-rose-50 p-3 text-sm text-rose-700">{error}</p>}
    {draft.reviews.length>0 && <section className="space-y-3 border-t border-stone-200 pt-4"><h3 className="font-medium">{t("Reviews by confirmed version", "已确认版本的复盘记录")}</h3>{draft.reviews.map(r => <div key={r.id} className="rounded-xl bg-stone-50 p-4 text-sm">
      <p>{t(`Confirmed minutes v${r.revision}`,`已确认纪要 v${r.revision}`)}{r.revision!==draft.revision && <span className="ml-2 text-amber-700">{t("Minutes changed since this review", "此后纪要已修改，复盘依据为旧版本")}</span>}</p>
      <details className="mt-2"><summary className="cursor-pointer text-stone-500">{t("Confirmed source snapshot", "查看本次确认的纪要快照")}</summary><p className="mt-2 whitespace-pre-wrap break-words leading-7">{r.minutes}</p></details>
      {r.status === "COMPLETED" ? r.recapId ? <Link className="mt-2 inline-block underline" href={`/app/recaps?selected=${r.recapId}`}>{t("Open detailed review", "打开详细复盘")}</Link> : <p>{t("The report was deleted. Its confirmation record remains.", "复盘报告已删除，确认记录仍保留。")}</p> : r.status === "FAILED" ? <><p className="mt-2">{t("Review failed. Your confirmed minutes are safe.", "复盘失败，已确认纪要仍保留。")}</p><button disabled={busy} className="mt-2 underline" onClick={() => void retry(r.id)}>{t("Retry this version", "重试此版本")}</button></> : <p role="status" className="mt-2 animate-pulse">{t("Generating review in the background…", "正在后台生成详细复盘…")}</p>}
    </div>)}</section>}
  </div>;
}
