"use client";

import { ArrowLeft, BookOpenText, FileUp, Globe2, Search, Sparkles, Trash2, TriangleAlert } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import styles from "./knowledge.module.css";

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

type IndexStatus = {
  embeddingConfigured: boolean;
  embeddingModel: string | null;
  embeddedChunks: number;
  pendingChunks: number;
};

export default function KnowledgePage() {
  const router = useRouter();
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [indexStatus, setIndexStatus] = useState<IndexStatus | null>(null);
  const [results, setResults] = useState<SearchResult[]>([]);
  const [searched, setSearched] = useState(false);
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadStatus = useCallback(async () => {
    try { setIndexStatus(await api<IndexStatus>("/api/knowledge/index-status")); }
    catch { /* Coverage is informational; a failure here must not break the page. */ }
  }, []);

  const load = useCallback(async () => {
    try { setDocuments(await api<DocumentItem[]>("/api/knowledge/documents")); }
    catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
      setError(reason instanceof Error ? reason.message : "无法加载知识库");
    }
    await loadStatus();
  }, [router, loadStatus]);

  useEffect(() => {
    let active = true;
    Promise.all([
      api<DocumentItem[]>("/api/knowledge/documents"),
      api<IndexStatus>("/api/knowledge/index-status").catch(() => null),
    ])
      .then(([items, status]) => {
        if (!active) return;
        setDocuments(items);
        if (status) setIndexStatus(status);
      })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : "无法加载知识库");
      });
    return () => { active = false; };
  }, [router]);

  // Embedding happens after the ingest transaction commits, so freshly uploaded
  // chunks show up as pending for a moment. Poll only while that is true.
  useEffect(() => {
    if (!indexStatus || indexStatus.pendingChunks === 0) return;
    const timer = window.setInterval(() => { void loadStatus(); }, 4000);
    return () => window.clearInterval(timer);
  }, [indexStatus, loadStatus]);

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    setBusy("upload"); setError(""); setNotice("");
    try {
      const created = await api<DocumentItem>("/api/knowledge/documents", {
        method: "POST", body: new FormData(form),
      });
      window.localStorage.setItem("ardor:background-pending:knowledge", "1");
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
      if (response.imported.length > 0) window.localStorage.setItem("ardor:background-pending:knowledge", "1");
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
    try {
      setResults(await api<SearchResult[]>(`/api/knowledge/search?query=${encodeURIComponent(query)}&limit=5`));
      setSearched(true);
    }
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

  const totalChunks = indexStatus ? indexStatus.embeddedChunks + indexStatus.pendingChunks : 0;
  const coverage = totalChunks > 0 ? Math.round((indexStatus!.embeddedChunks / totalChunks) * 100) : 0;

  return (
    <main className={styles.shell}>
      <div className={styles.mesh} aria-hidden="true">
        <div className={styles.meshField} />
        <div className={styles.meshBeam} />
        <div className={styles.meshFade} />
        <div className={styles.meshGrain} />
      </div>

      <div className={`${styles.content} mx-auto max-w-6xl px-5 pb-24 pt-6 md:px-10`}>
        <Link
          href="/app"
          className="inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm text-[#162032]/70 transition hover:bg-white/60 hover:text-[#162032]"
        >
          <ArrowLeft className="size-4" />返回 Ardor
        </Link>

        <header className="pb-10 pt-14 md:pb-14 md:pt-20">
          <p className={`${styles.label} text-[#162032]/65`}>Knowledge Base</p>
          <h1 className={`${styles.display} mt-5 max-w-3xl`}>
            知识，检索得到
            <span className="text-[#162032]/55"> 才算拥有。</span>
          </h1>
        </header>

        {(error || notice) && (
          <div
            role="status"
            aria-live="polite"
            className={`${styles.card} ${styles.riseIn} mb-7 px-5 py-4 text-sm ${error ? "text-[#a33a32]" : "text-[#1f5c3a]"}`}
          >
            {error || notice}
          </div>
        )}

        {/* --- Retrieval coverage. Without this the user cannot tell whether the
               library is semantically searchable or silently keyword-only. --- */}
        {indexStatus && (
          <section aria-labelledby="index-heading" className={`${styles.cardInk} ${styles.riseIn} mb-8 p-6 md:p-8`}>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div>
                <p className={`${styles.label} text-[#9FE6FF]`}>Retrieval Index</p>
                <h2 id="index-heading" className="mt-3 text-xl font-medium">
                  {indexStatus.embeddingConfigured ? "语义检索已启用" : "当前仅关键词检索"}
                </h2>
              </div>
              {indexStatus.embeddingConfigured && indexStatus.embeddingModel && (
                <span className={`${styles.pill} bg-white/10 px-3 py-1.5 text-[#9FE6FF]`}>
                  {indexStatus.embeddingModel}
                </span>
              )}
            </div>

            {indexStatus.embeddingConfigured ? (
              <>
                <div className="mt-6 flex items-baseline gap-6">
                  <span className="font-mono text-3xl font-medium tabular-nums">{indexStatus.embeddedChunks}</span>
                  <span className="text-sm text-white/60">
                    个片段已向量化
                    {indexStatus.pendingChunks > 0 && ` · ${indexStatus.pendingChunks} 个排队中`}
                  </span>
                </div>
                <div className={`${styles.meter} mt-4 bg-white/15`}>
                  {indexStatus.pendingChunks > 0
                    ? <div className={styles.meterPending} />
                    : <div className={styles.meterFill} style={{ width: `${coverage}%` }} />}
                </div>
                <p className="mt-4 text-sm leading-6 text-white/55">
                  {indexStatus.pendingChunks > 0
                    ? "新加入的资料正在后台补齐向量，完成前这部分只能通过关键词命中。"
                    : "检索会同时使用语义相似度和关键词，两路结果按排名融合。"}
                </p>
              </>
            ) : (
              <>
                <div className="mt-5 flex items-start gap-3 text-sm leading-6 text-white/70">
                  <TriangleAlert className="mt-0.5 size-4 shrink-0 text-[#FFD84D]" />
                  <p>
                    还没有配置 Embedding 服务，知识库只能按字面匹配。
                    换个说法提问就可能搜不到——例如资料里写“垃圾回收”，搜“自动内存管理”不会命中。
                  </p>
                </div>
                <Link
                  href="/app/settings"
                  className={`${styles.buttonGhost} mt-6 inline-flex items-center gap-2 border-white/20 bg-white/10 px-5 py-2.5 text-sm text-white hover:bg-white/20`}
                >
                  <Sparkles className="size-4" />去设置向量模型
                </Link>
              </>
            )}
          </section>
        )}

        <section className="grid gap-5 lg:grid-cols-2">
          <form onSubmit={upload} className={`${styles.cardSolid} p-6 md:p-8`}>
            <FileUp className="size-6 text-[#0094FF]" />
            <h2 className="mt-5 text-xl font-medium">上传资料</h2>
            <label
              htmlFor="knowledge-file"
              className="mt-6 flex min-h-14 cursor-pointer items-center justify-between gap-3 rounded-lg border border-dashed border-[#162032]/20 bg-[#FFFCF0] px-5 text-sm text-[#162032]/60 transition hover:border-[#0094FF]"
            >
              <span className={styles.label}>PDF · DOCX · TXT · MD</span>
              <input
                id="knowledge-file"
                type="file"
                name="file"
                required
                className="max-w-48 text-xs"
                accept=".pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown"
              />
            </label>
            <button disabled={busy !== ""} className={`${styles.buttonPrimary} mt-5 px-5 py-2.5 text-sm`}>
              {busy === "upload" ? "处理中…" : "加入知识库"}
            </button>
          </form>

          <form onSubmit={research} className={`${styles.cardSolid} p-6 md:p-8`}>
            <Globe2 className="size-6 text-[#FF7618]" />
            <h2 className="mt-5 text-xl font-medium">联网收录</h2>
            <label htmlFor="knowledge-research" className="sr-only">要收录的主题</label>
            <input
              id="knowledge-research"
              name="query"
              required
              maxLength={400}
              className={`${styles.field} mt-6 min-h-14 w-full px-5 text-sm`}
              placeholder="例如：Java 21 虚拟线程面试题"
            />
            <button disabled={busy !== ""} className={`${styles.buttonPrimary} mt-5 px-5 py-2.5 text-sm`}>
              {busy === "research" ? "搜索中…" : "搜索并收录"}
            </button>
          </form>
        </section>

        <section className={`${styles.cardSolid} mt-6 p-6 md:p-8`}>
          <form onSubmit={search} className="flex flex-wrap gap-3">
            <div className="relative min-w-56 flex-1">
              <label htmlFor="knowledge-search" className="sr-only">检索知识库</label>
              <Search className="pointer-events-none absolute left-4 top-1/2 size-4 -translate-y-1/2 text-[#162032]/35" />
              <input
                id="knowledge-search"
                name="query"
                required
                maxLength={400}
                className={`${styles.field} min-h-12 w-full pl-11 pr-5 text-sm`}
                placeholder="检索知识库"
              />
            </div>
            <button disabled={busy !== ""} className={`${styles.buttonPrimary} px-6 py-2.5 text-sm`}>
              {busy === "search" ? "检索中…" : "检索"}
            </button>
          </form>

          {results.length > 0 && (
            <div className="mt-6 grid gap-3">
              {results.map((result, index) => (
                <article key={`${result.documentId}-${index}`} className={`${styles.riseIn} rounded-lg bg-[#FFFCF0] p-5`}>
                  <div className="flex items-center justify-between gap-3">
                    <p className={`${styles.label} text-[#0094FF]`}>{result.title}</p>
                    <span className={`${styles.pill} shrink-0 bg-[#162032]/6 px-2.5 py-1 text-[#162032]/55`}>
                      {result.score.toFixed(4)}
                    </span>
                  </div>
                  <p className="mt-3 line-clamp-4 text-sm leading-6 text-[#162032]/70">{result.content}</p>
                </article>
              ))}
            </div>
          )}

          {searched && results.length === 0 && busy === "" && (
            <p className="mt-6 text-sm leading-6 text-[#162032]/55">
              没有命中。
              {indexStatus && !indexStatus.embeddingConfigured
                ? "当前只有关键词检索，换成资料里出现过的说法再试，或配置向量模型以支持语义检索。"
                : "换个说法或更具体的关键词再试。"}
            </p>
          )}
        </section>

        <section className="mt-12">
          <div className="flex items-end justify-between gap-4">
            <div>
              <p className={`${styles.label} text-[#162032]/45`}>Library</p>
              <h2 className="mt-3 text-xl font-medium">资料 · {documents.length} 份</h2>
            </div>
          </div>

          {documents.length === 0 ? (
            <div className={`${styles.card} mt-6 py-16 text-center text-[#162032]/45`}>
              <BookOpenText className="mx-auto size-8" />
              <p className="mt-4 text-sm">还没有资料</p>
            </div>
          ) : (
            <div className="mt-6 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
              {documents.map((document) => (
                <article key={document.id} className={`${styles.cardSolid} p-6`}>
                  <div className="flex items-start justify-between gap-3">
                    <span
                      className={`${styles.pill} px-3 py-1 ${
                        document.sourceType === "WEB"
                          ? "bg-[#0094FF]/10 text-[#0067b3]"
                          : "bg-[#FF7618]/10 text-[#b34e00]"
                      }`}
                    >
                      {document.sourceType === "WEB" ? "Web" : "Upload"}
                    </span>
                    <button
                      aria-label={`删除 ${document.title}`}
                      onClick={() => void remove(document)}
                      disabled={busy !== ""}
                      className="rounded-lg p-2 text-[#162032]/25 transition hover:bg-[#a33a32]/8 hover:text-[#a33a32] disabled:opacity-40"
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </div>
                  <h3 className="mt-6 line-clamp-2 text-lg font-medium leading-7">{document.title}</h3>
                  <p className={`${styles.label} mt-3 text-[#162032]/40`}>{document.chunkCount} Chunks</p>
                  {document.sourceUrl && (
                    <a
                      href={document.sourceUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="mt-5 block truncate text-xs text-[#0094FF] hover:underline"
                    >
                      查看来源
                    </a>
                  )}
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
