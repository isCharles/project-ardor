"use client";

import { ArrowLeft, ArrowRight, Check, Clock3, Pencil, Plus, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { ApiError, api } from "@/lib/api";

type Card = {
  id: string;
  sourceType: "INTERVIEW" | "KNOWLEDGE" | "AGENT" | "WEB";
  sourceLabel: string | null;
  sourceUrl: string | null;
  front: string;
  back: string;
  tags: string[];
  status: "NEW" | "LEARNING" | "REVIEW" | "SUSPENDED";
  nextReviewAt: string;
  intervalDays: number;
  repetitions: number;
  lapses: number;
};

type Rating = "AGAIN" | "HARD" | "GOOD" | "EASY";

const sourceLabel: Record<Card["sourceType"], string> = {
  INTERVIEW: "面试原题", KNOWLEDGE: "我的题库", AGENT: "Ardor 训练题", WEB: "网络题",
};

const ratings: Array<{ value: Rating; label: string; tone: string }> = [
  { value: "AGAIN", label: "忘记了", tone: "hover:bg-rose-50 hover:text-rose-700" },
  { value: "HARD", label: "有点难", tone: "hover:bg-amber-50 hover:text-amber-700" },
  { value: "GOOD", label: "记得住", tone: "hover:bg-emerald-50 hover:text-emerald-700" },
  { value: "EASY", label: "很熟悉", tone: "hover:bg-sky-50 hover:text-sky-700" },
];

function reviewDate(value: string) {
  return new Date(value).toLocaleDateString("zh-CN", { month: "long", day: "numeric" });
}

function RichText({ text }: { text: string }) {
  return <ReactMarkdown remarkPlugins={[remarkGfm]} components={{
    p: ({ children }) => <p className="mb-4 last:mb-0">{children}</p>,
    h1: ({ children }) => <p className="mb-4 font-semibold">{children}</p>,
    h2: ({ children }) => <p className="mb-4 font-semibold">{children}</p>,
    h3: ({ children }) => <p className="mb-4 font-semibold">{children}</p>,
    ul: ({ children }) => <ul className="mb-4 list-disc space-y-1 pl-6">{children}</ul>,
    ol: ({ children }) => <ol className="mb-4 list-decimal space-y-1 pl-6">{children}</ol>,
    pre: ({ children }) => <pre className="mb-4 overflow-x-auto rounded-xl bg-stone-100 p-4 text-sm leading-6">{children}</pre>,
    blockquote: ({ children }) => <blockquote className="mb-4 border-l-2 border-violet-300 pl-4 text-stone-600">{children}</blockquote>,
    table: ({ children }) => <table className="mb-4 block max-w-full overflow-x-auto text-left text-sm">{children}</table>,
    th: ({ children }) => <th className="border-b border-stone-200 px-3 py-2 font-semibold">{children}</th>,
    td: ({ children }) => <td className="border-b border-stone-100 px-3 py-2">{children}</td>,
    strong: ({ children }) => <strong className="font-semibold text-stone-900">{children}</strong>,
    code: ({ children }) => <code className="rounded bg-stone-100 px-1 py-0.5 font-mono text-[0.9em]">{children}</code>,
    a: ({ children, href }) => <a href={href} target="_blank" rel="noopener noreferrer" className="text-violet-700 underline underline-offset-4">{children}</a>,
    img: ({ alt }) => <span>{alt}</span>,
  }}>{text}</ReactMarkdown>;
}

export default function CardsPage() {
  const router = useRouter();
  const [cards, setCards] = useState<Card[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [reviewed, setReviewed] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<Card | null>(null);
  const [formSource, setFormSource] = useState<Card["sourceType"]>("KNOWLEDGE");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    void api<Card[]>("/api/memory-cards")
      .then((items) => { if (active) setCards(items); })
      .catch((reason) => {
        if (!active) return;
        setLoadFailed(true);
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : "无法加载记忆卡");
      })
      .finally(() => { if (active) setLoading(false); });
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => { active = false; window.clearInterval(timer); };
  }, [router]);

  const dueCards = useMemo(() => cards
    .filter((item) => item.status !== "SUSPENDED" && new Date(item.nextReviewAt).getTime() <= now)
    .sort((left, right) => new Date(left.nextReviewAt).getTime() - new Date(right.nextReviewAt).getTime()), [cards, now]);
  const card = dueCards[0] ?? null;
  const sessionTotal = reviewed + dueCards.length;
  const nextScheduled = cards.filter((item) => item.status !== "SUSPENDED")
    .sort((left, right) => new Date(left.nextReviewAt).getTime() - new Date(right.nextReviewAt).getTime())[0] ?? null;
  const library = [...cards].sort((left, right) => {
    if ((left.status === "SUSPENDED") !== (right.status === "SUSPENDED")) return left.status === "SUSPENDED" ? 1 : -1;
    return new Date(left.nextReviewAt).getTime() - new Date(right.nextReviewAt).getTime();
  });

  function openCreate() {
    setEditing(null); setFormSource("KNOWLEDGE"); setError(""); setShowForm(true);
  }

  function openEdit(item: Card) {
    setEditing(item); setError(""); setShowForm(true);
  }

  async function retryLoad() {
    setLoading(true); setError(""); setLoadFailed(false);
    try { setCards(await api<Card[]>("/api/memory-cards")); }
    catch (reason) {
      setLoadFailed(true);
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : "无法加载记忆卡");
    } finally { setLoading(false); }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const content = {
      front: data.get("front"), back: data.get("back"),
      tags: String(data.get("tags") ?? "").split(/[，,]/).map((item) => item.trim()).filter(Boolean),
    };
    setBusy(true); setError("");
    try {
      const saved = await api<Card>(editing ? `/api/memory-cards/${editing.id}` : "/api/memory-cards", {
        method: editing ? "PUT" : "POST",
        body: JSON.stringify(editing ? content : {
          ...content, sourceType: data.get("sourceType"), sourceLabel: data.get("sourceLabel"), sourceUrl: data.get("sourceUrl"),
        }),
      });
      setCards((items) => editing ? items.map((item) => item.id === saved.id ? saved : item) : [...items, saved]);
      setShowForm(false); setEditing(null); setRevealed(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : editing ? "保存修改失败" : "建卡失败"); }
    finally { setBusy(false); }
  }

  async function review(rating: Rating) {
    if (!card || !revealed || busy) return;
    setBusy(true); setError("");
    try {
      const updated = await api<Card>(`/api/memory-cards/${card.id}/reviews`, {
        method: "POST", body: JSON.stringify({ rating }),
      });
      setCards((items) => items.map((item) => item.id === updated.id ? updated : item));
      setReviewed((count) => count + 1);
      setRevealed(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : "保存复习结果失败"); }
    finally { setBusy(false); }
  }

  return (
    <main className="ardor-workbench min-h-screen px-5 pb-16 pt-6 text-[#1d1d1f] md:px-10">
      <div className="mx-auto max-w-7xl">
        <div className="flex items-center justify-between">
          <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-600 hover:bg-white/70 hover:text-stone-900"><ArrowLeft className="size-4" />返回 Ardor</Link>
          {!loading && cards.length > 0 && <button type="button" onClick={openCreate} className="inline-flex items-center gap-2 rounded-full border border-white/80 bg-white/65 px-4 py-2.5 text-sm font-medium text-stone-700 shadow-sm hover:bg-white"><Plus className="size-4" />新建卡片</button>}
        </div>

        <header className="pb-8 pt-12 md:pb-10 md:pt-16">
          <h1 className="text-4xl font-semibold tracking-[-0.055em] md:text-6xl">练到会。</h1>
        </header>
        {error && !showForm && !revealed && <p role="alert" className="mb-5 rounded-2xl bg-red-50/90 px-5 py-4 text-sm text-red-700">{error}</p>}

        {loading ? <div className="ardor-panel h-96 animate-pulse rounded-[2.5rem]" aria-label="正在加载记忆卡" /> : loadFailed ? (
          <div className="ardor-panel grid min-h-80 place-items-center rounded-[2.5rem] p-8 text-center"><div><h2 className="text-2xl font-semibold">卡片还没加载出来</h2><button type="button" onClick={() => void retryLoad()} className="mt-6 rounded-full bg-[#d75f3e] px-6 py-3 text-sm font-medium text-white">重试</button></div></div>
        ) : (
          <div className="grid items-start gap-7 lg:grid-cols-[minmax(0,1fr)_19rem]">
            <section className="ardor-panel min-w-0 rounded-[2.5rem] p-6 md:p-10" aria-label="当前复习">
              <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
                <span className="font-medium text-violet-700">本轮复习</span>
                <span className="tabular-nums text-stone-500">{reviewed > 0 ? `本轮已复习 ${reviewed} · ` : ""}还剩 {dueCards.length}</span>
              </div>
              {sessionTotal > 0 && <div className="mt-5 h-1.5 overflow-hidden rounded-full bg-stone-200/70" role="progressbar" aria-label="本轮复习进度" aria-valuenow={reviewed} aria-valuemin={0} aria-valuemax={sessionTotal}><div className="h-full rounded-full bg-gradient-to-r from-orange-400 via-pink-500 to-violet-500 transition-[width] duration-500 motion-reduce:transition-none" style={{ width: `${reviewed / sessionTotal * 100}%` }} /></div>}

              {card ? (
                <article key={card.id} className="pt-10 md:pt-14">
                  <div className="flex flex-wrap items-center gap-2 text-xs text-stone-500">
                    <span className="rounded-full bg-violet-50 px-3 py-1.5 font-medium text-violet-700">{sourceLabel[card.sourceType]}</span>
                    {card.sourceLabel && <span className="max-w-48 truncate">{card.sourceLabel}</span>}
                    {card.tags.slice(0, 2).map((tag) => <span key={tag} className="rounded-full bg-stone-100/80 px-3 py-1.5">{tag}</span>)}
                  </div>
                  <div className="mb-14 mt-7 min-h-40 break-words text-[clamp(1.65rem,3vw,2.8rem)] font-semibold leading-[1.28] tracking-[-0.035em] md:mb-20"><RichText text={card.front} /></div>
                  {!revealed ? (
                    <button type="button" onClick={() => setRevealed(true)} className="inline-flex items-center gap-3 rounded-full bg-[#d75f3e] px-6 py-3.5 text-sm font-medium text-white shadow-[0_10px_25px_rgba(215,95,62,0.18)] transition hover:bg-[#bd4f32]">
                      显示答案<ArrowRight className="size-4" />
                    </button>
                  ) : (
                    <div className="border-t border-stone-200/80 pt-7">
                      <p className="mb-4 text-xs font-medium text-violet-700">参考答案</p>
                      <div className="break-words text-[15px] leading-8 text-stone-700"><RichText text={card.back} /></div>
                      <fieldset disabled={busy} className="mt-9 border-t border-stone-200/80 pt-6">
                        <legend className="sr-only">这道题掌握得怎么样</legend>
                        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                          {ratings.map((item) => <button key={item.value} type="button" onClick={() => void review(item.value)} className={`rounded-2xl border border-stone-200/80 bg-white/80 px-3 py-3.5 text-sm font-medium text-stone-700 transition disabled:opacity-50 ${item.tone}`}>{item.label}</button>)}
                        </div>
                      </fieldset>
                      {error && <p role="alert" className="mt-4 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">{error}</p>}
                    </div>
                  )}
                </article>
              ) : (
                <div className="grid min-h-80 place-items-center py-12 text-center">
                  <div><div className="mx-auto grid size-16 place-items-center rounded-full bg-emerald-50 text-emerald-600">{cards.length === 0 ? <Plus className="size-7" /> : reviewed > 0 ? <Check className="size-7" /> : <Clock3 className="size-7" />}</div><h2 className="mt-6 text-2xl font-semibold">{cards.length === 0 ? "从一张卡片开始。" : reviewed > 0 ? "本轮练完了。" : "还没到复习时间。"}</h2><p className="mt-2 text-sm text-stone-500">{nextScheduled ? `下次复习 · ${reviewDate(nextScheduled.nextReviewAt)}` : cards.length ? "当前没有待复习的卡片。" : "记下想练的题，下次从这里继续。"}</p>{cards.length === 0 && <button type="button" onClick={openCreate} className="mt-7 inline-flex items-center gap-2 rounded-full bg-[#d75f3e] px-6 py-3 text-sm font-medium text-white">新建卡片<ArrowRight className="size-4" /></button>}</div>
                </div>
              )}
            </section>

            <aside className="ardor-soft-panel rounded-[2rem] p-4 md:p-5" aria-label="卡片库">
              <div className="flex items-center justify-between px-2 py-2"><h2 className="font-semibold">卡片库</h2><span className="text-xs tabular-nums text-stone-500">{cards.length}</span></div>
              {library.length === 0 ? <p className="px-2 pb-3 pt-5 text-sm text-stone-500">卡片会出现在这里。</p> : <div className="mt-2 max-h-[40rem] space-y-1 overflow-y-auto pr-1">{library.map((item) => <div key={item.id} className={`group rounded-2xl p-3.5 transition ${item.id === card?.id ? "bg-white shadow-sm" : "hover:bg-white/60"}`}><div className="flex items-start gap-2"><div className="min-w-0 flex-1"><p className="line-clamp-2 break-words text-sm font-medium leading-5">{item.front}</p><p className="mt-2 flex items-center gap-1.5 text-xs text-stone-500"><Clock3 className="size-3.5 shrink-0" />{item.status === "SUSPENDED" ? "已暂停" : new Date(item.nextReviewAt).getTime() <= now ? "待复习" : reviewDate(item.nextReviewAt)}</p></div><button type="button" aria-label={`编辑：${item.front.slice(0, 80)}`} onClick={() => openEdit(item)} className="shrink-0 rounded-full p-2 text-stone-500 hover:bg-white hover:text-stone-900"><Pencil className="size-3.5" /></button></div></div>)}</div>}
            </aside>
          </div>
        )}
      </div>

      {showForm && <div className="fixed inset-0 z-50 grid place-items-center bg-black/20 p-4 backdrop-blur-sm" onMouseDown={(event) => { if (!busy && event.target === event.currentTarget) setShowForm(false); }}><section role="dialog" aria-modal="true" aria-labelledby="card-form-title" className="ardor-panel max-h-[92vh] w-full max-w-2xl overflow-y-auto rounded-[2rem] p-6 md:p-8"><div className="mb-6 flex items-center justify-between"><h2 id="card-form-title" className="text-2xl font-semibold">{editing ? "编辑记忆卡" : "新建记忆卡"}</h2><button type="button" aria-label="关闭" disabled={busy} onClick={() => setShowForm(false)} className="rounded-full p-2 text-stone-500 hover:bg-white disabled:opacity-40"><X className="size-5" /></button></div><form key={editing?.id ?? "new"} onSubmit={save} className="space-y-4">{!editing && <><select name="sourceType" value={formSource} onChange={(event) => setFormSource(event.target.value as Card["sourceType"])} className="field"><option value="KNOWLEDGE">我的题库</option><option value="AGENT">训练题</option><option value="WEB">网络题</option></select><input name="sourceLabel" maxLength={240} className="field" placeholder="来源名称（可选）" /><input name="sourceUrl" type="url" required={formSource === "WEB"} maxLength={1000} className="field" placeholder={formSource === "WEB" ? "来源链接" : "来源链接（可选）"} /></>}<textarea name="front" required maxLength={12000} rows={3} defaultValue={editing?.front ?? ""} className="field resize-y" placeholder="问题" /><textarea name="back" required maxLength={20000} rows={6} defaultValue={editing?.back ?? ""} className="field resize-y" placeholder="参考答案或回答框架" /><input name="tags" defaultValue={editing?.tags.join(", ") ?? ""} className="field" placeholder="标签，用逗号分隔" />{error && <p role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700">{error}</p>}<button disabled={busy} className="w-full rounded-full bg-[#d75f3e] px-5 py-3 text-sm font-medium text-white disabled:opacity-40">{busy ? "保存中…" : editing ? "保存修改" : "保存并安排复习"}</button></form></section></div>}
    </main>
  );
}
