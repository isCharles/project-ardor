"use client";

import {
  BookOpenText, Brain, CalendarDays, FileSearch, FileText, GraduationCap,
  Clock3, Ellipsis, Home, LogOut, MessageSquareText, Settings2, ShieldCheck, SquareStack, Target, TrendingUp,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { api } from "@/lib/api";
import { type TaskSummary } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";
import { AppDialog } from "@/components/ui/app-dialog";
import { Button } from "@/components/ui/button";
import { LocaleSwitch } from "./locale-switch";

const workspaces = [
  { href: "/app", en: "Chat", zh: "对话", icon: Home },
  { href: "/app/resumes", en: "Resumes", zh: "简历", icon: FileSearch },
  { href: "/app/interviews", en: "Mock interviews", zh: "模拟面试", icon: MessageSquareText },
  { href: "/app/recaps", en: "Interview notes", zh: "面经", icon: FileText },
  { href: "/app/cards", en: "Flashcards", zh: "记忆卡", icon: SquareStack },
  { href: "/app/knowledge", en: "Knowledge", zh: "知识库", icon: BookOpenText },
  { href: "/app/learning", en: "Learning", zh: "学习", icon: GraduationCap },
  { href: "/app/calendar", en: "Calendar", zh: "日程", icon: CalendarDays },
  { href: "/app/applications", en: "Applications", zh: "投递节奏", icon: Target },
  { href: "/app/evidence", en: "Growth evidence", zh: "成长证据", icon: TrendingUp },
  { href: "/app/tasks", en: "Background tasks", zh: "后台任务", icon: Clock3 },
] as const;
const defaultHrefs = ["/app", "/app/resumes", "/app/interviews", "/app/cards", "/app/calendar", "/app/applications"];
function preferenceKey(userId: string) { return `ardor:workspace-rail:v1:${userId}`; }
function readHrefs(userId: string): string[] {
  try {
    const saved: unknown = JSON.parse(window.localStorage.getItem(preferenceKey(userId)) ?? "null");
    if (!Array.isArray(saved)) return defaultHrefs;
    return ["/app", ...workspaces.filter((item) => item.href !== "/app" && saved.includes(item.href)).map((item) => item.href)];
  } catch { return defaultHrefs; }
}

function moduleFor(href: string): string | null {
  const moduleName = href.split("/").at(-1);
  return moduleName && ["resumes","recaps","knowledge","learning","interviews","evidence","tasks"].includes(moduleName) ? moduleName : null;
}

export function WorkspaceRail() {
  const pathname = usePathname();
  const router = useRouter();
  const { t } = useLocale();
  const [account, setAccount] = useState<{ id: string; email: string; admin: boolean } | null>(null);
  const [accountOpen, setAccountOpen] = useState(false);
  const [preference, setPreference] = useState<{ userId: string; hrefs: string[] } | null>(null);
  const [customizing, setCustomizing] = useState(false);
  const [customDraft, setCustomDraft] = useState<string[]>(defaultHrefs);
  const [customError, setCustomError] = useState("");
  const pinnedHrefs = preference?.userId === account?.id ? preference?.hrefs ?? defaultHrefs : defaultHrefs;
  const primaryWorkspaces = workspaces.filter((item) => pinnedHrefs.includes(item.href));
  const secondaryWorkspaces = workspaces.filter((item) => !pinnedHrefs.includes(item.href));
  const [moreOpen, setMoreOpen] = useState(false);
  const [morePinned, setMorePinned] = useState(false);
  const [moreTop, setMoreTop] = useState(0);
  const moreCloseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const moreButton = useRef<HTMLButtonElement>(null);
  const morePanel = useRef<HTMLDivElement>(null);
  const [error, setError] = useState("");
  const [tooltip, setTooltip] = useState<{ label: string; top: number } | null>(null);
  const [badges, setBadges] = useState<{ userId: string; ready: Record<string, boolean> } | null>(null);
  const backgroundReady = badges?.userId === account?.id ? badges?.ready ?? {} : {};

  useEffect(() => {
    if (account) return;
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let attempts = 0;
    const loadAccount = () => {
      api<{ id: string; email: string; admin: boolean }>("/api/auth/me")
        .then((value) => { if (!cancelled) { setAccount(value); setPreference({ userId: value.id, hrefs: readHrefs(value.id) }); } })
        .catch(() => {
          if (!cancelled && ++attempts < 3) retryTimer = setTimeout(loadAccount, attempts * 3000);
        });
    };
    loadAccount();
    return () => { cancelled = true; if (retryTimer) clearTimeout(retryTimer); };
  }, [account, pathname]);

  useEffect(() => {
    const open = () => { if (account) { setCustomDraft(pinnedHrefs); setCustomError(""); setCustomizing(true); setMoreOpen(false); setAccountOpen(false); } };
    const sync = (event: StorageEvent) => { if (account && event.key === preferenceKey(account.id)) setPreference({ userId: account.id, hrefs: readHrefs(account.id) }); };
    window.addEventListener("ardor:customize-sidebar", open);
    window.addEventListener("storage", sync);
    return () => { window.removeEventListener("ardor:customize-sidebar", open); window.removeEventListener("storage", sync); };
  }, [account, pinnedHrefs]);

  function saveSidebar() {
    if (!account) return;
    try {
      window.localStorage.setItem(preferenceKey(account.id), JSON.stringify(customDraft));
      setPreference({ userId: account.id, hrefs: customDraft }); setCustomizing(false);
    } catch { setCustomError(t("Could not save on this device. Check your browser storage settings.", "无法保存到此设备，请检查浏览器存储设置。")); }
  }

  useEffect(() => {
    if (!moreOpen) return;
    const closeOnOutside = (event: PointerEvent) => {
      if (!moreButton.current?.contains(event.target as Node) && !morePanel.current?.contains(event.target as Node)) {
        setMoreOpen(false);
        setMorePinned(false);
      }
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") { setMoreOpen(false); setMorePinned(false); moreButton.current?.focus(); }
    };
    document.addEventListener("pointerdown", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    return () => { document.removeEventListener("pointerdown", closeOnOutside); document.removeEventListener("keydown", closeOnEscape); };
  }, [moreOpen]);

  useEffect(() => () => { if (moreCloseTimer.current) clearTimeout(moreCloseTimer.current); }, []);

  function showMore() {
    if (moreCloseTimer.current) clearTimeout(moreCloseTimer.current);
    const top = moreButton.current?.getBoundingClientRect().top ?? 0;
    setMoreTop(Math.max(8, Math.min(top - 8, window.innerHeight - 320)));
    setTooltip(null);
    window.dispatchEvent(new Event("ardor:close-chat-drawer"));
    setMoreOpen(true);
  }

  function hideMoreSoon() {
    if (moreCloseTimer.current) clearTimeout(moreCloseTimer.current);
    moreCloseTimer.current = setTimeout(() => { if (!morePinned) setMoreOpen(false); }, 160);
  }

  function closeMore() { if (moreCloseTimer.current) clearTimeout(moreCloseTimer.current); setMoreOpen(false); setMorePinned(false); }

  useEffect(() => {
    if (!account?.id) return;
    const userId = account.id;
    let cancelled = false;
    let refreshing = false;
    let refreshAgain = false;
    let consecutiveFailures = 0;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const refresh = async () => {
      if (cancelled || document.hidden) return;
      if (refreshing) { refreshAgain = true; return; }
      refreshing = true;
      if (timer) clearTimeout(timer);
      let keepPolling = false;
      try {
        const summary = await api<TaskSummary>("/api/background-tasks/summary");
        consecutiveFailures = 0;
        keepPolling = summary.activeCount > 0;
        if (!cancelled) setBadges({ userId, ready: {
          ...Object.fromEntries(Object.entries(summary.unreadByModule).map(([name,count]) => [name,count>0])),
          tasks: Object.values(summary.unreadByModule).some((count) => count>0),
        } });
      } catch {
        // Badges are informational; they must never interrupt navigation.
        consecutiveFailures += 1;
        keepPolling = true;
      } finally {
        refreshing = false;
        if (cancelled) return;
        if (refreshAgain) { refreshAgain = false; void refresh(); }
        else if (!document.hidden) timer = setTimeout(() => void refresh(), consecutiveFailures ? Math.min(5000 * 2 ** (consecutiveFailures - 1), 30_000) : keepPolling ? 5000 : 30_000);
      }
    };
    const onVisibilityChange = () => {
      if (document.hidden) { if (timer) clearTimeout(timer); }
      else { consecutiveFailures = 0; void refresh(); }
    };
    const wake = () => { consecutiveFailures = 0; void refresh(); };
    void refresh();
    document.addEventListener("visibilitychange", onVisibilityChange);
    window.addEventListener("focus", wake);
    window.addEventListener("ardor:background-refresh", wake);
    return () => { cancelled = true; if (timer) clearTimeout(timer); document.removeEventListener("visibilitychange", onVisibilityChange); window.removeEventListener("focus", wake); window.removeEventListener("ardor:background-refresh", wake); };
  }, [account?.id, pathname]);

  // Navigation is not acknowledgment: only a specific result's read API clears its badge.
  function acknowledgeBackground(itemModule: string | null) { void itemModule; window.dispatchEvent(new Event("ardor:background-refresh")); }

  async function logout() {
    setError("");
    try {
      await api<void>("/api/auth/logout", { method: "POST" });
      setAccountOpen(false);
      router.replace("/login");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not log out. Please try again.", "退出失败，请重试。"));
    }
  }

  return (
    <aside className="relative z-30 flex h-dvh w-16 shrink-0 flex-col items-center border-r border-stone-200/70 bg-gradient-to-b from-white via-[#faf8ff] to-[#fff8f6] py-3 md:w-[4.25rem]" aria-label={t("Main navigation", "主导航")}>
      <nav className="flex min-h-0 w-full flex-1 flex-col items-center gap-1 overflow-y-auto px-2" aria-label={t("Workspaces", "工作区")} onScroll={() => setTooltip(null)}>
        {primaryWorkspaces.map((item, index) => {
          const active = item.href === "/app" ? pathname === "/app" : pathname.startsWith(item.href);
          const label = t(item.en, item.zh);
          const itemModule = moduleFor(item.href);
          return (
            <Link
              key={item.href}
              href={item.href}
              aria-label={label}
              aria-current={active ? "page" : undefined}
              onClick={(event) => { if (item.href === "/app" && pathname === "/app") event.preventDefault(); acknowledgeBackground(itemModule); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }}
              onMouseEnter={(event) => setTooltip({ label, top: event.currentTarget.getBoundingClientRect().top + 8 })}
              onMouseLeave={() => setTooltip(null)}
              onFocus={(event) => setTooltip({ label, top: event.currentTarget.getBoundingClientRect().top + 8 })}
              onBlur={() => setTooltip(null)}
              className={`relative grid size-11 shrink-0 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 ${index === 0 ? "mb-3" : ""} ${active ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900 hover:shadow-sm"}`}
            >
              <item.icon className="size-5" aria-hidden="true" />
              {itemModule && backgroundReady[itemModule] && <span className="absolute right-1 top-1 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-hidden="true" />}
            </Link>
          );
        })}
        <button ref={moreButton} type="button" aria-label={t("More workspaces", "更多工作区")} aria-expanded={moreOpen} aria-controls="ardor-more-workspaces" onMouseEnter={showMore} onMouseLeave={hideMoreSoon} onFocus={showMore} onClick={() => { showMore(); setMorePinned((value) => !value); }} className={`relative grid size-11 shrink-0 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 ${moreOpen || secondaryWorkspaces.some((item) => pathname.startsWith(item.href)) ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900 hover:shadow-sm"}`}><Ellipsis className="size-5" aria-hidden="true" />{secondaryWorkspaces.some((item) => { const moduleName = moduleFor(item.href); return moduleName && backgroundReady[moduleName]; }) && <span className="absolute right-1 top-1 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-hidden="true" />}</button>
      </nav>

      <div className="mt-2 flex w-full shrink-0 flex-col items-center border-t border-stone-200/70 px-2 pt-3">
        <button
          type="button"
          aria-label={t("Account", "个人账户")}
          aria-expanded={accountOpen}
          onClick={() => { setTooltip(null); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); setAccountOpen((open) => !open); }}
          onMouseEnter={(event) => setTooltip({ label: t("Account", "个人账户"), top: event.currentTarget.getBoundingClientRect().top + 8 })}
          onMouseLeave={() => setTooltip(null)}
          onFocus={(event) => setTooltip({ label: t("Account", "个人账户"), top: event.currentTarget.getBoundingClientRect().top + 8 })}
          onBlur={() => setTooltip(null)}
          className="grid size-11 place-items-center rounded-2xl bg-gradient-to-br from-rose-200 via-violet-200 to-blue-200 text-sm font-semibold text-stone-800 transition hover:shadow-md focus-visible:outline-2 focus-visible:outline-violet-500"
        >
          {(account?.email || "A").trim().charAt(0).toUpperCase()}
        </button>
      </div>

      {tooltip && !accountOpen && !moreOpen && <div role="tooltip" className="pointer-events-none fixed left-[4.75rem] z-50 whitespace-nowrap rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs font-medium text-stone-800 shadow-lg" style={{ top: tooltip.top }}>{tooltip.label}</div>}

      {moreOpen && !accountOpen && <div ref={morePanel} id="ardor-more-workspaces" className="fixed left-[4.25rem] z-50 w-56 max-h-[calc(100dvh-1rem)] overflow-y-auto rounded-2xl border border-stone-200 bg-white p-2 shadow-[0_20px_60px_rgba(70,48,100,0.16)]" style={{ top: moreTop }} onMouseEnter={() => { if (moreCloseTimer.current) clearTimeout(moreCloseTimer.current); }} onMouseLeave={hideMoreSoon}>
        {secondaryWorkspaces.map((item) => { const label = t(item.en, item.zh); const moduleName = moduleFor(item.href); return <Link key={item.href} href={item.href} onClick={() => { acknowledgeBackground(moduleName); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }} className={`relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm hover:bg-violet-50 ${pathname.startsWith(item.href) ? "bg-violet-50 text-violet-700" : "text-stone-700"}`}><item.icon className="size-4 shrink-0" aria-hidden="true" /><span>{label}</span>{moduleName && backgroundReady[moduleName] && <span className="ml-auto size-2 rounded-full bg-red-500" aria-hidden="true" />}</Link>; })}
        <button type="button" onClick={() => { if (pathname === "/app") window.dispatchEvent(new Event("ardor:open-memory")); else { window.sessionStorage.setItem("ardor:open-memory", "1"); router.push("/app"); } closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm text-stone-700 hover:bg-violet-50"><Brain className="size-4 shrink-0" aria-hidden="true" />{t("Memory", "总体记忆")}</button>
        <button type="button" disabled={!account} onClick={() => { closeMore(); setCustomDraft(pinnedHrefs); setCustomError(""); setCustomizing(true); }} className="mt-1 flex w-full items-center gap-3 rounded-xl border-t border-stone-100 px-3 py-2.5 text-left text-sm text-violet-700 hover:bg-violet-50"><Settings2 className="size-4" />{t("Customize sidebar", "自定义侧栏")}</button>
      </div>}

      {customizing && <AppDialog title={t("Customize sidebar", "自定义侧栏")} onClose={() => setCustomizing(false)}>
        <p className="mb-4 text-sm text-stone-500">{t("Selected modules stay in the sidebar. Others go in More. Saved on this device for your account.", "选中项显示在侧栏，其余收进更多。按账户保存在此设备。")}</p>
        <div className="space-y-1">{workspaces.map((item) => <label key={item.href} className="flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2 hover:bg-violet-50"><item.icon className="size-4 text-stone-500" /><span className="flex-1 text-sm">{t(item.en, item.zh)}</span><input type="checkbox" checked={customDraft.includes(item.href)} disabled={item.href === "/app"} onChange={(event) => setCustomDraft((current) => event.target.checked ? [...current, item.href] : current.filter((href) => href !== item.href))} className="size-4 accent-violet-600" /></label>)}</div>
        {customError && <p role="alert" className="mt-3 text-sm text-red-600">{customError}</p>}
        <div className="mt-5 flex justify-between gap-2"><Button variant="outline" onClick={() => setCustomDraft(defaultHrefs)}>{t("Reset defaults", "恢复默认")}</Button><Button onClick={saveSidebar}>{t("Save", "保存")}</Button></div>
      </AppDialog>}
      {accountOpen && (
        <>
          <button type="button" className="fixed inset-0 z-40 cursor-default" aria-label={t("Close account menu", "关闭账户菜单")} onClick={() => setAccountOpen(false)} />
          <div className="fixed bottom-3 left-[4.75rem] z-50 w-60 rounded-2xl border border-stone-200 bg-white p-2 shadow-[0_20px_60px_rgba(70,48,100,0.16)]">
            <p className="truncate border-b border-stone-100 px-3 py-2 text-xs text-stone-500" title={account?.email}>{account?.email ?? t("Your account", "个人账户")}</p>
            <Link href="/app/settings" onClick={() => setAccountOpen(false)} className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm hover:bg-violet-50"><Settings2 className="size-4" />{t("Settings", "设置")}</Link>
            {account?.admin && <Link href="/app/admin" onClick={() => setAccountOpen(false)} className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm hover:bg-violet-50"><ShieldCheck className="size-4" />{t("Admin", "管理员")}</Link>}
            <div className="px-3 py-2"><LocaleSwitch /></div>
            <button type="button" onClick={() => void logout()} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm hover:bg-violet-50"><LogOut className="size-4" />{t("Log out", "退出登录")}</button>
            {error && <p role="alert" className="px-3 py-2 text-xs text-red-600">{error}</p>}
          </div>
        </>
      )}
    </aside>
  );
}
