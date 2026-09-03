"use client";

import { ArrowLeft, BookOpenText, FileUp, Globe2, Search, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";

type DocumentItem = {
  id: string;
  title: string;
  sourceType: "USER_UPLOAD" | "WEB";
  sourceUrl: string | null;
  originalFilename: string | null;
  chunkCount: number;
  createdAt: string;
};

type SearchResult = {
  documentId: string;
  title: string;
  sourceType: DocumentItem["sourceType"];
  sourceUrl: string | null;
  content: string;
  score: number;
};

type ResearchResponse = { query: string; imported: DocumentItem[] };

export default function KnowledgePage() {
  const router = useRouter();
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function load() {
    try { setDocuments(await api<DocumentItem[]>("/api/knowledge/documents")); }
    catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
      setError(reason instanceof Error ? reason.message : "无法加载知识库");
    }
  }

  useEffect(() => {
    let active = true;
    api<DocumentItem[]>("/api/knowledge/documents")
      .then((items) => { if (active) setDocuments(items); })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : "无法加载知识库");
      });
    return () => { active = false; };
  }, [router]);

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    setBusy("upload"); setError(""); setNotice("");
    try {
      const created = await api<DocumentItem>("/api/knowledge/documents", {
        method: "POST", body: new FormData(form),
      });
      form.reset();
      setNotice(`已加入“${created.title}”`);
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "上传失败"); }
    finally { setBusy(""); }
  }

  async function research(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy("research"); setError(""); setNotice("");
    try {
      const response = await api<ResearchResponse>("/api/knowledge/research", {
        method: "POST", body: JSON.stringify({ query: data.get("query") }),
      });
      form.reset();
      setNotice(`已收录 ${response.imported.length} 个来源`);
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "联网收录失败"); }
    finally { setBusy(""); }
  }

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const query = String(new FormData(event.currentTarget).get("query") ?? "").trim();
    setBusy("search"); setError("");
    try { setResults(await api<SearchResult[]>(`/api/knowledge/search?query=${encodeURIComponent(query)}&limit=5`)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "检索失败"); }
    finally { setBusy(""); }
  }

  async function remove(document: DocumentItem) {
    if (!window.confirm(`永久删除“${document.title}”及其检索片段？`)) return;
    setBusy(document.id); setError(""); setNotice("");
    try {
      await api<void>(`/api/knowledge/documents/${document.id}`, { method: "DELETE" });
      setResults((current) => current.filter((item) => item.documentId !== document.id));
      setNotice("已删除");
      await load();
    } catch (reason) { setError(reason instanceof Error ? reason.message : "删除失败"); }
    finally { setBusy(""); }
  }

  return <main className="ardor-workbench min-h-screen text-[#1d1d1f]">
    <div className="mx-auto max-w-6xl px-5 pb-24 pt-6 md:px-10">
      <Link href="/app" className="inline-flex items-center gap-2 rounded-full px-3 py-2 text-sm text-stone-500 transition hover:bg-white hover:text-stone-900"><ArrowLeft className="size-4" />返回 Ardor</Link>
      <header className="pb-12 pt-14 md:pb-16 md:pt-20"><p className="text-sm font-medium text-violet-600">KNOWLEDGE</p><h1 className="mt-4 text-5xl font-semibold tracking-[-0.055em] md:text-7xl">知识，随时可用。</h1></header>
      {(error || notice) && <div className={`mb-7 rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-700"}`}>{error || notice}</div>}

      <section className="grid gap-5 lg:grid-cols-2">
        <form onSubmit={upload} className="rounded-[2rem] bg-white p-6 shadow-[0_18px_70px_rgba(0,0,0,0.06)] md:p-8">
          <FileUp className="size-6 text-violet-600" /><h2 className="mt-5 text-2xl font-semibold">上传</h2>
          <label className="mt-6 flex min-h-14 cursor-pointer items-center justify-between rounded-2xl border border-dashed border-stone-300 bg-[#fafafa] px-5 text-sm text-stone-500 hover:border-violet-400"><span>PDF · DOCX · TXT · MD</span><input type="file" name="file" required className="max-w-48 text-xs" accept=".pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown" /></label>
          <button disabled={busy !== ""} className="mt-4 rounded-full bg-[#1d1d1f] px-5 py-2.5 text-sm font-medium text-white disabled:opacity-40">{busy === "upload" ? "处理中…" : "加入知识库"}</button>
        </form>
        <form onSubmit={research} className="rounded-[2rem] bg-gradient-to-br from-violet-600 to-blue-600 p-6 text-white shadow-[0_20px_80px_rgba(91,75,220,0.22)] md:p-8">
          <Globe2 className="size-6" /><h2 className="mt-5 text-2xl font-semibold">联网收录</h2>
          <input name="query" required maxLength={400} className="mt-6 min-h-14 w-full rounded-2xl border border-white/20 bg-white/10 px-5 text-sm text-white outline-none placeholder:text-white/55 focus:bg-white/15" placeholder="例如：Java 21 虚拟线程面试题" />
          <button disabled={busy !== ""} className="mt-4 rounded-full bg-white px-5 py-2.5 text-sm font-medium text-violet-700 disabled:opacity-50">{busy === "research" ? "搜索中…" : "搜索并收录"}</button>
        </form>
      </section>

      <section className="mt-10 rounded-[2rem] bg-white p-6 shadow-[0_16px_60px_rgba(0,0,0,0.05)] md:p-8">
        <form onSubmit={search} className="flex gap-3"><div className="relative flex-1"><Search className="absolute left-4 top-1/2 size-4 -translate-y-1/2 text-stone-400" /><input name="query" required maxLength={400} className="min-h-12 w-full rounded-full bg-stone-100 pl-11 pr-5 text-sm outline-none focus:ring-2 focus:ring-violet-200" placeholder="检索知识库" /></div><button disabled={busy !== ""} className="rounded-full bg-violet-600 px-5 text-sm font-medium text-white disabled:opacity-40">检索</button></form>
        {results.length > 0 && <div className="mt-6 grid gap-3">{results.map((result, index) => <article key={`${result.documentId}-${index}`} className="rounded-2xl bg-stone-50 p-5"><p className="text-xs font-medium text-violet-600">{result.title}</p><p className="mt-2 line-clamp-4 text-sm leading-6 text-stone-600">{result.content}</p></article>)}</div>}
      </section>

      <section className="mt-12"><div className="flex items-end justify-between"><div><h2 className="text-2xl font-semibold">资料</h2><p className="mt-1 text-sm text-stone-500">{documents.length} 份</p></div></div>
        {documents.length === 0 ? <div className="mt-6 rounded-[2rem] bg-white py-16 text-center text-stone-400"><BookOpenText className="mx-auto size-8" /><p className="mt-4 text-sm">还没有资料</p></div> : <div className="mt-6 grid gap-4 md:grid-cols-2 lg:grid-cols-3">{documents.map((document) => <article key={document.id} className="rounded-[1.75rem] bg-white p-6 shadow-[0_12px_50px_rgba(0,0,0,0.045)]"><div className="flex items-start justify-between gap-3"><span className={`rounded-full px-3 py-1 text-[11px] font-medium ${document.sourceType === "WEB" ? "bg-blue-50 text-blue-700" : "bg-violet-50 text-violet-700"}`}>{document.sourceType === "WEB" ? "联网" : "上传"}</span><button aria-label={`删除 ${document.title}`} onClick={() => void remove(document)} disabled={busy !== ""} className="rounded-full p-2 text-stone-300 hover:bg-red-50 hover:text-red-600"><Trash2 className="size-4" /></button></div><h3 className="mt-6 line-clamp-2 text-lg font-semibold leading-7">{document.title}</h3><p className="mt-3 text-xs text-stone-400">{document.chunkCount} 个片段</p>{document.sourceUrl && <a href={document.sourceUrl} target="_blank" rel="noreferrer" className="mt-5 block truncate text-xs text-blue-600 hover:underline">查看来源</a>}</article>)}</div>}
      </section>
    </div>
  </main>;
}
