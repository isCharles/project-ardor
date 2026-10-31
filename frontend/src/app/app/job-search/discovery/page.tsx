"use client";

import {
  Bookmark, BookmarkCheck, Building2, Check, Clock3, ExternalLink,
  EyeOff, Inbox, LoaderCircle, MapPin, RotateCcw, Search, ShieldAlert, X,
} from "lucide-react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { FormEvent, Suspense, useCallback, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { useLocale, type Locale } from "@/lib/locale";

type Preferences = { roles: string[]; locations: string[]; keywords: string[]; suggestedRoles: string[] };
type DiscoveryError = { query: string | null; url: string | null; code: string; message: string };
type Run = {
  id: string; jobId: string; status: "QUEUED" | "RUNNING" | "COMPLETED" | "PARTIAL" | "FAILED" | "CANCELED";
  roles: string[]; locations: string[]; keywords: string[]; queries: string[]; queriesDone: number;
  candidateCount: number; newCount: number; updatedCount: number; skippedCount: number; errorCount: number;
  errors: DiscoveryError[]; cancelRequested: boolean; createdAt: string; startedAt: string | null;
  finishedAt: string | null; resultPath: string;
};
type Source = { source: string; url: string; discoveredAt: string };
type Posting = {
  id: string; state: "NEW" | "SAVED" | "IGNORED"; company: string; title: string; location: string | null;
  applyUrl: string; source: string; salaryText: string | null; experienceText: string | null;
  deadlineText: string | null; linkStatus: "OPEN" | "CLOSED" | "RESTRICTED" | "UNKNOWN";
  firstSeenAt: string; lastVerifiedAt: string | null; discoveredAt: string; lastSeenAt: string;
  appliedAt: string | null; matchReason: string | null; matchEvidence: string | null;
  discoveryQuery: string | null; sources: Source[];
};
type Summary = { inbox: number; saved: number; ignored: number; activeRunId: string | null };
type StateFilter = "NEW" | "SAVED" | "IGNORED";

const statusLabel: Record<string, [string, string]> = {
  QUEUED: ["Queued", "排队中"], RUNNING: ["Searching", "正在搜索"], COMPLETED: ["Completed", "已完成"],
  PARTIAL: ["Partially completed", "部分完成"], FAILED: ["Failed", "失败"], CANCELED: ["Canceled", "已取消"],
};
const linkLabel: Record<Posting["linkStatus"], [string, string]> = {
  OPEN: ["Link reachable", "链接可访问"], CLOSED: ["Link may be closed", "链接可能已关闭"],
  RESTRICTED: ["Login or verification required", "需要登录或验证"], UNKNOWN: ["Not verified", "尚未确认"],
};
const linkStyle: Record<Posting["linkStatus"], string> = {
  OPEN: "bg-emerald-50 text-emerald-700", CLOSED: "bg-stone-100 text-stone-600",
  RESTRICTED: "bg-amber-50 text-amber-800", UNKNOWN: "bg-stone-100 text-stone-500",
};
const stateLabel: Record<StateFilter, [string, string]> = {
  NEW: ["Inbox", "收件箱"], SAVED: ["Saved", "已收藏"], IGNORED: ["Ignored", "已忽略"],
};

function splitList(value: string) {
  return value.split(/[,，\n]/).map(item => item.trim()).filter(Boolean);
}

function formatDate(value: string | null, locale: Locale) {
  if (!value) return "";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return date.toLocaleString(locale === "en" ? "en" : "zh-CN", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

function safeHref(value: string) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:" ? url.href : undefined;
  } catch {
    return undefined;
  }
}

export default function JobDiscoveryPage() {
  return <Suspense fallback={<main className="ardor-workbench min-h-screen p-10 text-sm text-stone-500">…</main>}><JobDiscoveryContent /></Suspense>;
}

function JobDiscoveryContent() {
  const router = useRouter();
  const search = useSearchParams();
  const { locale, t } = useLocale();
  const runParam = search.get("run");
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [rolesDraft, setRolesDraft] = useState("");
  const [locationsDraft, setLocationsDraft] = useState("");
  const [keywordsDraft, setKeywordsDraft] = useState("");
  const [run, setRun] = useState<Run | null>(null);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [stateFilter, setStateFilter] = useState<StateFilter>("NEW");
  const [query, setQuery] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [items, setItems] = useState<Posting[]>([]);
  const [offset, setOffset] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [busyPosting, setBusyPosting] = useState("");
  const [confirmApplied, setConfirmApplied] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadSummary = useCallback(async () => {
    try { setSummary(await api<Summary>("/api/job-discovery/summary")); } catch { /* The inbox still renders without counts. */ }
  }, []);

  const loadItems = useCallback(async (state: StateFilter, text: string, nextOffset: number, append: boolean) => {
    try {
      const page = await api<Posting[]>(`/api/job-discovery/postings?state=${state}&q=${encodeURIComponent(text)}&limit=20&offset=${nextOffset}`);
      setItems(current => append ? [...current, ...page] : page);
      setHasMore(page.length === 20);
      setOffset(nextOffset);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
      else setError(reason instanceof Error ? reason.message : t("Could not load the job inbox", "无法加载岗位收件箱"));
    }
  }, [router, t]);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [prefs, currentSummary] = await Promise.all([
          api<Preferences>("/api/job-discovery/preferences"),
          api<Summary>("/api/job-discovery/summary"),
        ]);
        if (!active) return;
        setPreferences(prefs);
        setSummary(currentSummary);
        setRolesDraft(prefs.roles.join(", "));
        setLocationsDraft(prefs.locations.join(", "));
        setKeywordsDraft(prefs.keywords.join(", "));
        const runId = runParam ?? currentSummary.activeRunId;
        if (runId) {
          const current = await api<Run>(`/api/job-discovery/runs/${runId}`);
          if (active) setRun(current);
        }
        if (active) await loadItems("NEW", "", 0, false);
      } catch (reason) {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : t("Could not load job discovery", "无法加载岗位发现"));
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; };
  }, [loadItems, router, runParam, t]);

  useEffect(() => {
    if (!run || !["QUEUED", "RUNNING"].includes(run.status)) return;
    const timer = window.setInterval(async () => {
      try {
        const next = await api<Run>(`/api/job-discovery/runs/${run.id}`);
        setRun(next);
        if (!["QUEUED", "RUNNING"].includes(next.status)) {
          void loadItems(stateFilter, appliedQuery, 0, false);
          void loadSummary();
        }
      } catch { /* Keep the last known progress. */ }
    }, 1500);
    return () => window.clearInterval(timer);
  }, [appliedQuery, loadItems, loadSummary, run, stateFilter]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 5000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  function addSuggestion(role: string) {
    if (splitList(rolesDraft).includes(role)) return;
    setRolesDraft(current => splitList(current).concat(role).join(", "));
  }

  async function startSearch(event?: FormEvent) {
    event?.preventDefault();
    if (starting) return;
    setStarting(true); setError(""); setNotice("");
    try {
      const next = await api<Run>("/api/job-discovery/runs", {
        method: "POST",
        body: JSON.stringify({
          requestId: crypto.randomUUID(),
          roles: splitList(rolesDraft),
          locations: splitList(locationsDraft),
          keywords: splitList(keywordsDraft),
        }),
      });
      setRun(next);
      setStateFilter("NEW");
      setQuery(""); setAppliedQuery("");
      await loadItems("NEW", "", 0, false);
      void loadSummary();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not start the search", "无法开始岗位搜索"));
    } finally {
      setStarting(false);
    }
  }

  async function cancelRun() {
    if (!run) return;
    setError("");
    try { setRun(await api<Run>(`/api/job-discovery/runs/${run.id}/cancel`, { method: "POST" })); void loadSummary(); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not cancel the search", "无法取消搜索")); }
  }

  async function retryRun() {
    if (!run) return;
    setError("");
    try {
      const next = await api<Run>(`/api/job-discovery/runs/${run.id}/retry`, { method: "POST" });
      setRun(next);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not retry the search", "无法重试搜索"));
    }
  }

  async function setPostingState(posting: Posting, state: StateFilter) {
    setBusyPosting(posting.id); setError("");
    try {
      await api<Posting>(`/api/job-discovery/postings/${posting.id}`, {
        method: "PATCH", body: JSON.stringify({ state }),
      });
      // The moved row leaves this filtered result set, so the next page must start
      // from the top; keeping the old offset would skip an unseen posting.
      await loadItems(stateFilter, appliedQuery, 0, false);
      void loadSummary();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not update this job", "无法更新岗位"));
    } finally {
      setBusyPosting("");
    }
  }

  async function markApplied(posting: Posting) {
    setBusyPosting(posting.id); setError("");
    try {
      const updated = await api<Posting>(`/api/job-discovery/postings/${posting.id}/applied`, {
        method: "POST", body: JSON.stringify({ confirm: true }),
      });
      setItems(current => current.map(item => item.id === updated.id ? updated : item));
      setConfirmApplied("");
      setNotice(t("Recorded as sent. This counts once toward today's resume sends.", "已记为投递，并计入今天的简历投递数（只计一次）。"));
      void loadSummary();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not record the application", "无法记录投递"));
    } finally {
      setBusyPosting("");
    }
  }

  function changeFilter(next: StateFilter) {
    setStateFilter(next);
    setQuery(""); setAppliedQuery("");
    void loadItems(next, "", 0, false);
  }

  const working = !!run && ["QUEUED", "RUNNING"].includes(run.status);
  const lastRunTerminal = !!run && ["COMPLETED", "PARTIAL", "FAILED", "CANCELED"].includes(run.status);
  const progress = run && run.queries.length > 0 ? Math.round((run.queriesDone / run.queries.length) * 100) : 0;

  return <main className="ardor-workbench min-h-screen px-5 pb-20 pt-6 text-[#1d1d1f] md:px-10">
    <div className="mx-auto max-w-5xl">
      <header className="flex flex-wrap items-start justify-between gap-5 pb-8 pt-12 md:pt-16">
        <div>
          <div className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-white/80 text-violet-600 shadow-sm"><Inbox className="size-6" /></div>
          <h1 className="text-4xl font-semibold tracking-[-0.055em] md:text-6xl">{t("Job inbox", "岗位收件箱")}</h1>
          <p className="mt-3 max-w-2xl text-sm text-stone-500">{t("Search public postings from your preferences. Saving or opening a link is not an application.", "按你的求职偏好搜索公开岗位。收藏或打开链接都不代表已经投递。")}</p>
        </div>
        {summary && <div className="flex gap-2 pt-2 text-xs text-stone-500">
          <span className="rounded-full bg-white/80 px-3 py-1.5">{t("Inbox", "收件箱")} {summary.inbox}</span>
          <span className="rounded-full bg-white/80 px-3 py-1.5">{t("Saved", "已收藏")} {summary.saved}</span>
          <span className="rounded-full bg-white/80 px-3 py-1.5">{t("Ignored", "已忽略")} {summary.ignored}</span>
        </div>}
      </header>

      {error && <div role="alert" className="mb-5 rounded-2xl bg-rose-50 px-5 py-4 text-sm text-rose-700">{error}</div>}
      {notice && <div role="status" className="mb-5 rounded-2xl bg-emerald-50 px-5 py-4 text-sm text-emerald-800">{notice}</div>}

      <section className="rounded-[2rem] border border-white/80 bg-white/80 p-6 shadow-[0_18px_70px_rgba(58,44,94,0.08)] backdrop-blur-xl md:p-8">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-sm font-semibold text-stone-800">{t("Search criteria", "搜索条件")}</h2>
          <span className="text-xs text-stone-400">{t("Saved for next time", "会保存为下次的默认条件")}</span>
        </div>
        <form onSubmit={startSearch} className="mt-4 grid gap-3 md:grid-cols-3">
          <label className="text-xs text-stone-500">{t("Target roles", "目标岗位")}
            <input value={rolesDraft} onChange={event => setRolesDraft(event.target.value)} placeholder={t("e.g. Backend Engineer, Data Analyst", "例如：后端开发、数据分析")} className="mt-1 w-full rounded-2xl border border-stone-200 bg-white px-4 py-2.5 text-sm outline-none focus:border-violet-400" />
          </label>
          <label className="text-xs text-stone-500">{t("Locations (optional)", "地点（可选）")}
            <input value={locationsDraft} onChange={event => setLocationsDraft(event.target.value)} placeholder={t("e.g. Beijing, Remote", "例如：北京、远程")} className="mt-1 w-full rounded-2xl border border-stone-200 bg-white px-4 py-2.5 text-sm outline-none focus:border-violet-400" />
          </label>
          <label className="text-xs text-stone-500">{t("Keywords (optional)", "关键词（可选）")}
            <input value={keywordsDraft} onChange={event => setKeywordsDraft(event.target.value)} placeholder={t("e.g. Java, platform", "例如：Java、平台")} className="mt-1 w-full rounded-2xl border border-stone-200 bg-white px-4 py-2.5 text-sm outline-none focus:border-violet-400" />
          </label>
        </form>
        <div className="mt-4 flex flex-wrap items-center gap-2">
          {preferences?.suggestedRoles.map(role => <button key={role} type="button" onClick={() => addSuggestion(role)} className="rounded-full border border-violet-100 bg-violet-50 px-3 py-1.5 text-xs text-violet-700 hover:bg-violet-100">+ {role}</button>)}
          {preferences && preferences.suggestedRoles.length === 0 && <span className="text-xs text-stone-400">{t("Add target roles in your profile for suggestions.", "在个人资料里补上目标岗位，Ardor 就能给出建议。")}</span>}
          <button type="button" onClick={() => void startSearch()} disabled={starting || working} className="ml-auto inline-flex items-center gap-2 rounded-full bg-violet-600 px-4 py-2.5 text-sm font-medium text-white transition hover:bg-violet-700 disabled:opacity-40">
            {starting || working ? <LoaderCircle className="size-4 animate-spin" /> : <Search className="size-4" />}{t("Find jobs", "搜索岗位")}
          </button>
        </div>
      </section>

      {run && <section data-discovery-run data-status={run.status} className="mt-5 rounded-[2rem] border border-white/80 bg-white/75 p-6 shadow-sm">
        <div className="flex flex-wrap items-center gap-3">
          <span className="text-sm font-medium">{t(...(statusLabel[run.status] ?? [run.status, run.status]))}</span>
          <span className="text-xs text-stone-400">{t(`${run.queriesDone}/${run.queries.length} searches`, `${run.queriesDone}/${run.queries.length} 个查询`)} · {t("New", "新增")} {run.newCount} · {t("Updated", "更新")} {run.updatedCount} · {t("Skipped", "跳过")} {run.skippedCount}</span>
          <div className="ml-auto flex gap-2">
            {working && <button type="button" onClick={() => void cancelRun()} className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:border-rose-200 hover:text-rose-600">{t("Cancel", "取消搜索")}</button>}
            {lastRunTerminal && run.status !== "COMPLETED" && <button type="button" onClick={() => void retryRun()} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-600"><RotateCcw className="size-3.5" />{t("Retry", "重试")}</button>}
          </div>
        </div>
        {working && <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-stone-100"><div className="h-full rounded-full bg-violet-500 transition-all" style={{ width: `${Math.max(4, progress)}%` }} /></div>}
        {run.errors.length > 0 && <details className="mt-3 text-xs text-amber-800" open={run.status !== "COMPLETED" && run.errors.length <= 3}>
          <summary className="cursor-pointer">{run.status === "COMPLETED"
            ? t(`Search notes (${run.errorCount})`, `搜索提示（${run.errorCount} 项）`)
            : t(`Part of this search failed (${run.errorCount})`, `部分搜索未完成（${run.errorCount} 项）`)}</summary>
          <ul className="mt-2 space-y-1">{run.errors.slice(0, 6).map((item, index) => <li key={`${item.code}-${index}`} className="break-words">{item.query ? `${item.query} · ` : ""}{item.message}{item.url && <span className="text-stone-400"> · {item.url}</span>}</li>)}</ul>
        </details>}
        {run.status === "FAILED" && <p className="mt-3 text-xs text-rose-700">{t("The search could not run. Check the web search configuration in Settings, then retry.", "搜索未能执行。请检查设置中的联网搜索配置后重试。")} <Link href="/app/settings" className="underline">{t("Open settings", "打开设置")}</Link></p>}
        {run.status === "COMPLETED" && run.newCount + run.updatedCount === 0 && <p className="mt-3 text-xs text-stone-500">{t("No matching postings this time. Try fewer locations or a broader role name.", "这次没有找到匹配岗位。可以放宽地点，或换一个更宽泛的岗位名称。")}</p>}
        {run.status === "PARTIAL" && <p className="mt-3 text-xs text-amber-800">{t("Some sources failed; the results below are kept. Retrying will not duplicate them.", "部分来源失败，已成功的结果仍保留。重试不会重复保存。")}</p>}
      </section>}

      <section className="mt-7">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div role="tablist" aria-label={t("Job states", "岗位状态")} className="inline-flex rounded-full bg-stone-100 p-1">
            {(Object.keys(stateLabel) as StateFilter[]).map(value => <button key={value} type="button" role="tab" aria-selected={stateFilter === value} onClick={() => changeFilter(value)} className={`rounded-full px-3.5 py-1.5 text-xs font-medium transition ${stateFilter === value ? "bg-white text-stone-900 shadow-sm" : "text-stone-500 hover:text-stone-800"}`}>{t(...stateLabel[value])}{summary ? ` ${value === "NEW" ? summary.inbox : value === "SAVED" ? summary.saved : summary.ignored}` : ""}</button>)}
          </div>
          <form onSubmit={event => { event.preventDefault(); setAppliedQuery(query); void loadItems(stateFilter, query, 0, false); }} className="flex items-center gap-2">
            <input aria-label={t("Search inbox", "搜索收件箱")} value={query} onChange={event => setQuery(event.target.value)} placeholder={t("Company, role, location", "公司、岗位、地点")} className="w-48 rounded-full border border-stone-200 bg-white px-4 py-2 text-xs outline-none focus:border-violet-300" />
            <button type="submit" className="rounded-full border border-stone-200 bg-white px-3 py-2 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-600">{t("Filter", "筛选")}</button>
          </form>
        </div>

        <div className="mt-4 space-y-3">
          {loading ? <div role="status" className="rounded-3xl bg-white/60 p-8 text-center text-sm text-stone-500">{t("Loading jobs…", "正在加载岗位…")}</div>
            : items.length === 0 ? <div data-empty className="rounded-3xl border border-dashed border-stone-200 bg-white/50 p-10 text-center">
              <p className="text-sm font-medium text-stone-700">{stateFilter === "NEW" ? t("No jobs in the inbox yet", "收件箱里还没有岗位") : stateFilter === "SAVED" ? t("No saved jobs yet", "还没有收藏岗位") : t("No ignored jobs", "没有已忽略的岗位")}</p>
              <p className="mt-2 text-xs text-stone-500">{appliedQuery ? t("Nothing matches this filter.", "没有符合当前筛选的岗位。") : t("Set your target roles and start a search; only matched, readable links are saved.", "设置目标岗位后开始搜索；只会保存匹配且链接可核验的岗位。")}</p>
            </div>
            : items.map(posting => <PostingCard key={posting.id} posting={posting} locale={locale} busy={busyPosting === posting.id}
                confirming={confirmApplied === posting.id}
                onConfirmApplied={() => setConfirmApplied(posting.id)}
                onCancelApplied={() => setConfirmApplied("")}
                onApplied={() => void markApplied(posting)}
                onState={state => void setPostingState(posting, state)} />)}
        </div>
        {hasMore && <div className="mt-5 text-center"><button type="button" onClick={() => void loadItems(stateFilter, appliedQuery, offset + 20, true)} className="rounded-full border border-stone-200 bg-white px-5 py-2 text-xs text-stone-600 hover:border-violet-200">{t("Load more", "加载更多")}</button></div>}
      </section>
    </div>
  </main>;
}

function PostingCard({ posting, locale, busy, confirming, onConfirmApplied, onCancelApplied, onApplied, onState }: {
  posting: Posting; locale: Locale; busy: boolean; confirming: boolean;
  onConfirmApplied: () => void; onCancelApplied: () => void; onApplied: () => void;
  onState: (state: StateFilter) => void;
}) {
  const { t } = useLocale();
  const href = safeHref(posting.applyUrl);
  const missingFacts = !posting.salaryText && !posting.experienceText && !posting.deadlineText;
  return <article data-job-posting data-state={posting.state} data-link-status={posting.linkStatus} className="rounded-[1.75rem] border border-white/80 bg-white/85 p-5 shadow-[0_10px_40px_rgba(58,44,94,0.05)] backdrop-blur md:p-6">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        <h3 className="text-base font-semibold text-stone-900">{posting.title}</h3>
        <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-stone-500">
          <span className="inline-flex items-center gap-1"><Building2 className="size-3.5" />{posting.company}</span>
          <span className="inline-flex items-center gap-1"><MapPin className="size-3.5" />{posting.location ?? t("Location not provided", "地点未提供")}</span>
          <span className={`rounded-full px-2 py-0.5 ${linkStyle[posting.linkStatus]}`}>{t(...linkLabel[posting.linkStatus])}</span>
          {posting.appliedAt && <span className="rounded-full bg-violet-50 px-2 py-0.5 text-violet-700">{t("Marked as sent", "已投递")}</span>}
        </div>
      </div>
      {href ? <a href={href} target="_blank" rel="noopener noreferrer" aria-label={t(`Open application for ${posting.title}`, `打开 ${posting.title} 的申请页`)} className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-stone-900 px-4 py-2 text-xs font-medium text-white transition hover:bg-stone-700">{t("Apply page", "申请页")}<ExternalLink className="size-3.5" /></a> : <span className="text-xs text-rose-600">{t("Invalid link", "链接无效")}</span>}
    </div>

    {(posting.matchReason || posting.matchEvidence) && <div className="mt-4 rounded-2xl bg-stone-50/80 px-4 py-3">
      {posting.matchReason && <p className="text-xs font-medium text-stone-700">{t("Match", "匹配判断")} · {posting.matchReason}</p>}
      {posting.matchEvidence && <details className="mt-2 text-xs text-stone-500"><summary className="cursor-pointer">{t("Match evidence", "匹配依据")}</summary><p className="mt-1.5 leading-5">{posting.matchEvidence}</p></details>}
    </div>}

    <div className="mt-3 flex flex-wrap items-center gap-2 text-[11px] text-stone-500">
      <span>{t("Source", "来源")} {posting.source}{posting.sources.length > 1 ? ` +${posting.sources.length - 1}` : ""}</span>
      <span className="text-stone-300">·</span>
      <span className="inline-flex items-center gap-1"><Clock3 className="size-3" />{t("Found", "发现")} {formatDate(posting.discoveredAt, locale)}</span>
      <span className="text-stone-300">·</span>
      <span>{posting.lastVerifiedAt ? `${t("Verified", "核验")} ${formatDate(posting.lastVerifiedAt, locale)}` : t("Not verified yet", "尚未核验")}</span>
    </div>

    <div className="mt-3 flex flex-wrap gap-1.5 text-[11px]">
      {posting.salaryText && <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-emerald-800">{t("Salary", "薪资")} {posting.salaryText}</span>}
      {posting.experienceText && <span className="rounded-full bg-sky-50 px-2.5 py-1 text-sky-800">{t("Experience", "经验")} {posting.experienceText}</span>}
      {posting.deadlineText && <span className="rounded-full bg-amber-50 px-2.5 py-1 text-amber-800">{t("Deadline", "截止")} {posting.deadlineText}</span>}
      {missingFacts && <span className="text-stone-400">{t("Salary, experience and deadline are not stated on the page.", "页面未提供薪资、经验或截止信息。")}</span>}
    </div>

    <div className="mt-4 flex flex-wrap items-center gap-2">
      {posting.state !== "SAVED" && <button type="button" disabled={busy} onClick={() => onState("SAVED")} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-700 disabled:opacity-40"><Bookmark className="size-3.5" />{t("Save", "收藏")}</button>}
      {posting.state === "SAVED" && <button type="button" disabled={busy} onClick={() => onState("NEW")} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:border-violet-200 hover:text-violet-700 disabled:opacity-40"><BookmarkCheck className="size-3.5" />{t("Back to inbox", "移回收件箱")}</button>}
      {posting.state !== "IGNORED" && <button type="button" disabled={busy} onClick={() => onState("IGNORED")} className="inline-flex items-center gap-1.5 rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:border-stone-300 disabled:opacity-40"><EyeOff className="size-3.5" />{t("Ignore", "忽略")}</button>}
      {posting.state === "IGNORED" && <button type="button" disabled={busy} onClick={() => onState("NEW")} className="rounded-full border border-stone-200 bg-white px-3 py-1.5 text-xs text-stone-600 hover:border-violet-200 disabled:opacity-40">{t("Move back to inbox", "移回收件箱")}</button>}
      {!posting.appliedAt && <div className="ml-auto">
        {confirming ? <div role="group" aria-label={t("Confirm resume sent", "确认简历已发出")} className="flex items-center gap-2 rounded-2xl bg-violet-50 px-3 py-2 text-xs text-violet-800">
          <ShieldAlert className="size-3.5 shrink-0" />
          <span>{t("Has your resume actually been sent?", "简历已经实际发出了吗？")}</span>
          <button type="button" disabled={busy} onClick={onApplied} className="rounded-full bg-violet-600 px-3 py-1 text-white disabled:opacity-40">{t("Yes, record it", "是，记录下来")}</button>
          <button type="button" onClick={onCancelApplied} aria-label={t("Cancel", "取消")}><X className="size-3.5" /></button>
        </div> : <button type="button" disabled={busy} onClick={onConfirmApplied} className="inline-flex items-center gap-1.5 rounded-full border border-violet-200 bg-violet-50 px-3 py-1.5 text-xs text-violet-700 disabled:opacity-40"><Check className="size-3.5" />{t("I sent my resume", "我已投递")}</button>}
      </div>}
    </div>
    {posting.appliedAt && <p className="mt-3 text-[11px] text-violet-700">{t("Recorded as sent, counted once in resume sends.", "已记为投递，并计入简历投递数（只计一次）。")}</p>}
  </article>;
}
