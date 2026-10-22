"use client";

import {
  BookOpenText, Brain, Briefcase, CalendarDays, FileSearch, GraduationCap,
  Clock3, Ellipsis, Home, LogOut, MessageSquareText, Settings2, SquareStack, Target, TrendingUp, UsersRound,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Fragment, useEffect, useRef, useState } from "react";
import { destinations, destinationActive, defaultDestinationIds, normalizeDestinationIds, destinationsFromLegacy, legacyHrefsForDestinations } from "@/lib/navigation";

import { api, ApiError } from "@/lib/api";
import { type TaskSummary } from "@/lib/background-tasks";
import { useLocale } from "@/lib/locale";
import { AppDialog } from "@/components/ui/app-dialog";
import { ChatRailLink } from "./chat-rail-link";
import { Button } from "@/components/ui/button";

const icons = { home: Home, briefcase: Briefcase, target: Target, "file-search": FileSearch, book: BookOpenText, graduation: GraduationCap, message: MessageSquareText, users: UsersRound, stack: SquareStack, trend: TrendingUp, calendar: CalendarDays, clock: Clock3 };
const workspaces = destinations.map(item => ({ ...item, icon: icons[item.icon as keyof typeof icons] }));
const defaultIds = defaultDestinationIds;
function preferenceKey(userId: string) { return `ardor:workspace-rail:v1:${userId}`; }
function cacheKey(userId: string) { return `ardor:workspace-rail:v3:${userId}`; }
type SidebarWire = { userId: string; hrefs: string[]; destinationIds?: string[]; revision: number; configured: boolean };
type SidebarView = { userId: string; ids: string[]; revision: number; configured: boolean; supportsIds: boolean };
function validView(value: SidebarWire): SidebarView {
  if (!Array.isArray(value?.hrefs) || typeof value.userId !== "string" || !Number.isSafeInteger(value.revision) || value.revision < 0 || typeof value.configured !== "boolean") throw new Error("Invalid sidebar preferences response");
  const supportsIds = value.destinationIds !== undefined;
  const ids = supportsIds ? normalizeDestinationIds(knownIds(value.destinationIds ?? [])) : value.configured ? destinationsFromLegacy(value.hrefs) : defaultIds;
  return { userId: value.userId, ids, revision: value.revision, configured: value.configured, supportsIds };
}
function knownIds(value: unknown[]): string[] {
  return value.filter((id): id is string => typeof id === "string" && destinations.some(item => item.id === id));
}
function cacheView(owner: string, view: SidebarView) {
  try {
    const previous = JSON.parse(window.localStorage.getItem(cacheKey(owner)) ?? "null");
    if (previous && previous.revision > view.revision) return;
    window.localStorage.setItem(cacheKey(owner), JSON.stringify(view));
  } catch { /* Optional device cache must not change the server's result. */ }
}
function legacyHrefs(userId: string): string[] | null {
  try {
    const value: unknown = JSON.parse(window.localStorage.getItem(preferenceKey(userId)) ?? "null");
    return Array.isArray(value) ? legacyHrefsForDestinations(destinationsFromLegacy(value)) : null;
  } catch { return null; }
}
function readIds(userId: string): string[] {
  try {
    const saved = JSON.parse(window.localStorage.getItem(cacheKey(userId)) ?? "null");
    if (saved?.userId === userId) return normalizeDestinationIds(knownIds(saved.ids ?? []));
    const previous = JSON.parse(window.localStorage.getItem(`ardor:workspace-rail:v2:${userId}`) ?? "null");
    if (previous?.userId === userId) return destinationsFromLegacy(previous.hrefs);
  } catch { /* Preserve local legacy choices without treating them as authorization. */ }
  const legacy = legacyHrefs(userId);
  return legacy ? destinationsFromLegacy(legacy) : defaultIds;
}
function groupLabel(domain: string | null, t: (en: string, zh: string) => string) {
  return domain === "job-search" ? t("Job search", "求职") : domain === "learning" ? t("Learning", "学习") : t("Workspace", "工作台");
}

export function WorkspaceRail() {
  const pathname = usePathname();
  const router = useRouter();
  const { t } = useLocale();
  const [account, setAccount] = useState<{ id: string; email: string; admin: boolean } | null>(null);
  const [accountOpen, setAccountOpen] = useState(false);
  const [preference, setPreference] = useState<{ userId: string; ids: string[]; revision?: number; supportsIds?: boolean } | null>(null);
  const [customRevision, setCustomRevision] = useState(0);
  const [customSaving, setCustomSaving] = useState(false);
  const savingPreference = useRef(false);
  const [customizing, setCustomizing] = useState(false);
  const [customDraft, setCustomDraft] = useState<string[]>(defaultIds);
  const [customError, setCustomError] = useState("");
  const [railContext, setRailContext] = useState<{ id: string; x: number; y: number } | null>(null);
  const [railSaving, setRailSaving] = useState(false);
  const [railError, setRailError] = useState("");
  const railMenuRef = useRef<HTMLDivElement>(null);
  const pinnedIds = preference?.userId === account?.id ? preference?.ids ?? defaultIds : defaultIds;
  const fixedIds = defaultIds;
  const primaryWorkspaces = fixedIds.map(id => workspaces.find(item => item.id === id)).filter((item): item is (typeof workspaces)[number] => !!item);
  const pinnedExtras = pinnedIds.filter(id => !fixedIds.includes(id)).map(id => workspaces.find(item => item.id === id)).filter((item): item is (typeof workspaces)[number] => !!item);
  const secondaryWorkspaces = workspaces.filter((item) => !fixedIds.includes(item.id) && !pinnedIds.includes(item.id));
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
  const hasReady = (href: string) => workspaces.find(item => item.href === href)?.modules.some(module => backgroundReady[module]) ?? false;

  useEffect(() => {
    if (account) return;
    let cancelled = false;
    let retryTimer: ReturnType<typeof setTimeout> | null = null;
    let attempts = 0;
    const loadAccount = () => {
      api<{ id: string; email: string; admin: boolean }>("/api/auth/me")
        .then((value) => { if (!cancelled) { setAccount(value); setPreference({ userId: value.id, ids: readIds(value.id) }); } })
        .catch(() => {
          if (!cancelled && ++attempts < 3) retryTimer = setTimeout(loadAccount, attempts * 3000);
        });
    };
    loadAccount();
    return () => { cancelled = true; if (retryTimer) clearTimeout(retryTimer); };
  }, [account, pathname]);

  useEffect(() => {
    if (!account?.id) return;
    const owner = account.id;
    let cancelled = false;
    let requestVersion = 0;
    async function refresh() {
      if (savingPreference.current || document.hidden) return;
      const version = ++requestVersion;
      try {
        let view = validView(await api<SidebarWire>("/api/profile/sidebar"));
        if (cancelled || version !== requestVersion || view.userId !== owner) return;
        const legacy = legacyHrefs(owner);
        if (!view.configured && legacy) {
          try { view = validView(await api<SidebarWire>("/api/profile/sidebar", { method: "PUT", body: JSON.stringify({ hrefs: legacy, expectedRevision: 0, expectedUserId: owner }) })); }
          catch (reason) {
            if (!(reason instanceof ApiError) || reason.status !== 409) throw reason;
            view = validView(await api<SidebarWire>("/api/profile/sidebar"));
          }
        }
        if (cancelled || version !== requestVersion || savingPreference.current || view.userId !== owner) return;
        setPreference(current => current?.userId === owner && (current.revision ?? -1) > view.revision ? current : { userId: owner, ids: view.ids, revision: view.revision, supportsIds: view.supportsIds });
        cacheView(owner, view);
      } catch { /* Preserve the last known layout. Saving reports failures without pretending to sync. */ }
    }
    const wake = () => void refresh();
    const interval = window.setInterval(wake, 30000);
    window.addEventListener("focus", wake);
    document.addEventListener("visibilitychange", wake);
    window.addEventListener("ardor:sidebar-refresh", wake);
    void refresh();
    return () => { cancelled = true; window.clearInterval(interval); window.removeEventListener("focus", wake); document.removeEventListener("visibilitychange", wake); window.removeEventListener("ardor:sidebar-refresh", wake); };
  }, [account?.id]);

  useEffect(() => {
    const open = () => { if (account) { setCustomDraft(pinnedIds); setCustomRevision(preference?.revision ?? 0); setCustomError(""); setCustomizing(true); setMoreOpen(false); setAccountOpen(false); } };
    const sync = (event: StorageEvent) => { if (account && event.key === cacheKey(account.id)) window.dispatchEvent(new Event("ardor:sidebar-refresh")); };
    window.addEventListener("ardor:customize-sidebar", open);
    window.addEventListener("storage", sync);
    return () => { window.removeEventListener("ardor:customize-sidebar", open); window.removeEventListener("storage", sync); };
  }, [account, pinnedIds, preference?.revision]);

  async function saveSidebar() {
    if (!account || customSaving || preference?.revision === undefined) return;
    setCustomSaving(true); savingPreference.current = true; setCustomError("");
    try {
      const view = validView(await api<SidebarWire>("/api/profile/sidebar", { method: "PUT", body: JSON.stringify({ ...(preference?.supportsIds ? { destinationIds: customDraft } : { hrefs: legacyHrefsForDestinations(customDraft) }), expectedRevision: customRevision, expectedUserId: account.id }) }));
      if (view.userId !== account.id) throw new Error("Account changed");
      setPreference({ userId: account.id, ids: view.ids, revision: view.revision, supportsIds: view.supportsIds });
      cacheView(account.id, view);
      setCustomizing(false);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) {
        try { const view = validView(await api<SidebarWire>("/api/profile/sidebar")); if (view.userId === account.id) { setPreference({ userId: account.id, ids: view.ids, revision: view.revision, supportsIds: view.supportsIds }); setCustomRevision(view.revision); } } catch { /* Keep the draft on refresh failure. */ }
        setCustomError(t("Another device changed your sidebar. Your choices are kept here; review them, then save again to replace the account layout.", "其他设备已更新侧栏。你的选择仍保留在这里，请核对后再次保存，才会替换账户布局。"));
      } else setCustomError(t("Could not sync your sidebar. Your choices are kept; retry when the server is available.", "侧栏同步失败，你的选择已保留。请在服务恢复后重试。"));
    } finally { setCustomSaving(false); savingPreference.current = false; }
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

  useEffect(() => {
    if (!railContext) return;
    const close = (event: Event) => {
      if (event instanceof PointerEvent && railMenuRef.current?.contains(event.target as Node)) return;
      if (event instanceof KeyboardEvent && event.key !== "Escape") return;
      setRailContext(null);
    };
    document.addEventListener("pointerdown", close);
    document.addEventListener("keydown", close);
    window.addEventListener("scroll", close, true);
    return () => { document.removeEventListener("pointerdown", close); document.removeEventListener("keydown", close); window.removeEventListener("scroll", close, true); };
  }, [railContext]);

  useEffect(() => {
    if (!railError) return;
    const timer = window.setTimeout(() => setRailError(""), 4000);
    return () => window.clearTimeout(timer);
  }, [railError]);

  function openRailContext(event: React.MouseEvent, id: string) {
    event.preventDefault();
    event.stopPropagation();
    setTooltip(null);
    setRailContext({ id, x: Math.min(event.clientX, window.innerWidth - 220), y: Math.min(event.clientY, window.innerHeight - 140) });
  }

  async function setPinned(id: string, pinned: boolean) {
    if (!account || !preference || preference.revision === undefined || railSaving) return;
    const next = pinned
      ? [pinnedIds[0] ?? "chat", id, ...pinnedIds.filter((value) => value !== id && value !== pinnedIds[0])]
      : pinnedIds.filter((value) => value !== id);
    const normalized = normalizeDestinationIds(next);
    setRailContext(null);
    setRailSaving(true);
    setRailError("");
    try {
      const view = validView(await api<SidebarWire>("/api/profile/sidebar", { method: "PUT", body: JSON.stringify({
        ...(preference.supportsIds ? { destinationIds: normalized } : { hrefs: legacyHrefsForDestinations(normalized) }),
        expectedRevision: preference.revision,
        expectedUserId: account.id,
      }) }));
      if (view.userId !== account.id) throw new Error("Account changed");
      setPreference({ userId: account.id, ids: view.ids, revision: view.revision, supportsIds: view.supportsIds });
      cacheView(account.id, view);
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) {
        try {
          const view = validView(await api<SidebarWire>("/api/profile/sidebar"));
          if (view.userId === account.id) setPreference({ userId: account.id, ids: view.ids, revision: view.revision, supportsIds: view.supportsIds });
        } catch { /* Keep the current layout; the retry message is enough. */ }
        setRailError(t("The sidebar changed on another device. Try again.", "侧栏已在其他设备更新，请重试。"));
      } else {
        setRailError(t("Could not update the sidebar.", "侧栏更新失败，请重试。"));
      }
    } finally {
      setRailSaving(false);
    }
  }

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

  // Navigating into a module acknowledges its finished background results for that rail item.
  function acknowledgeBackground(modules: string[]) {
    const names = [...new Set(modules)].filter(Boolean);
    if (!names.length) return;
    setBadges(current => current ? { userId: current.userId, ready: { ...current.ready, ...Object.fromEntries(names.map(name => [name, false])) } } : current);
    void Promise.all(names.map(name => api<void>(`/api/background-tasks/modules/${encodeURIComponent(name)}/read`, { method: "POST" })))
      .catch(() => { /* Badges are informational; navigation must not fail with them. */ })
      .finally(() => window.dispatchEvent(new Event("ardor:background-refresh")));
  }

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

  const secondaryGroups: { domain: string | null; items: typeof secondaryWorkspaces }[] = [];
  for (const item of secondaryWorkspaces) {
    const group = secondaryGroups.find(candidate => candidate.domain === item.domain);
    if (group) group.items.push(item);
    else secondaryGroups.push({ domain: item.domain, items: [item] });
  }
  const domainRank = (domain: string | null) => domain === "job-search" ? 0 : domain === "learning" ? 1 : 2;
  secondaryGroups.sort((left, right) => domainRank(left.domain) - domainRank(right.domain));

  return (
    <aside className="relative z-30 flex h-full w-16 shrink-0 flex-col items-center border-r border-stone-200/70 bg-gradient-to-b from-white via-[#faf8ff] to-[#fff8f6] py-3 md:w-[4.25rem]" aria-label={t("Main navigation", "主导航")}>
      <nav className="flex min-h-0 w-full flex-1 flex-col items-center gap-1 overflow-y-auto px-2" aria-label={t("Workspaces", "工作区")} onScroll={() => setTooltip(null)}>
        {primaryWorkspaces.map((item, index) => {
          const active = destinationActive(item.id, pathname);
          const label = t(item.en, item.zh);
          if (item.href === "/app") return <ChatRailLink key={`${pathname}:${account?.id ?? "loading"}`} userId={account?.id ?? null} active={active}
            onOpen={() => { setTooltip(null); closeMore(); setAccountOpen(false); }}
            className={`relative mb-3 grid size-11 shrink-0 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 ${active ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900 hover:shadow-sm"}`} />;
          return (
            <Link
              key={item.href}
              href={item.href}
              aria-label={label}
              aria-current={active ? "page" : undefined}
              onClick={() => { acknowledgeBackground(item.modules); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }}
              onMouseEnter={(event) => setTooltip({ label, top: event.currentTarget.getBoundingClientRect().top + 8 })}
              onMouseLeave={() => setTooltip(null)}
              onFocus={(event) => setTooltip({ label, top: event.currentTarget.getBoundingClientRect().top + 8 })}
              onBlur={() => setTooltip(null)}
              className={`relative grid size-11 shrink-0 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 ${index === 0 ? "mb-3" : ""} ${active ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900 hover:shadow-sm"}`}
            >
              <item.icon className="size-5" aria-hidden="true" />
              {hasReady(item.href) && <span className="absolute right-1 top-1 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-hidden="true" />}
            </Link>
          );
        })}
        <button ref={moreButton} type="button" aria-label={t("More workspaces", "更多工作区")} aria-expanded={moreOpen} aria-controls="ardor-more-workspaces" onMouseEnter={showMore} onMouseLeave={hideMoreSoon} onFocus={showMore} onClick={() => { showMore(); setMorePinned((value) => !value); }} className={`relative grid size-11 shrink-0 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 ${moreOpen || secondaryWorkspaces.some((item) => destinationActive(item.id, pathname)) ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900 hover:shadow-sm"}`}><Ellipsis className="size-5" aria-hidden="true" />{secondaryWorkspaces.some(item => hasReady(item.href)) && <span className="absolute right-1 top-1 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-hidden="true" />}</button>
        {pinnedExtras.length > 0 && <div className="mt-2 flex w-full flex-col items-center gap-1 border-t border-stone-200/60 pt-2">
          {pinnedExtras.map((item) => {
            const active = destinationActive(item.id, pathname);
            const label = t(item.en, item.zh);
            return <Link key={item.id} href={item.href} aria-label={label} aria-current={active ? "page" : undefined}
              onContextMenu={(event) => openRailContext(event, item.id)}
              onClick={() => { acknowledgeBackground(item.modules); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }}
              onMouseEnter={(event) => setTooltip({ label, top: event.currentTarget.getBoundingClientRect().top + 8 })}
              onMouseLeave={() => setTooltip(null)}
              onFocus={(event) => setTooltip({ label, top: event.currentTarget.getBoundingClientRect().top + 8 })}
              onBlur={() => setTooltip(null)}
              className={`relative grid size-11 shrink-0 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 ${active ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900 hover:shadow-sm"}`}>
              <item.icon className="size-5" aria-hidden="true" />
              {hasReady(item.href) && <span className="absolute right-1 top-1 size-2 rounded-full bg-red-500 ring-2 ring-white" aria-hidden="true" />}
            </Link>;
          })}
        </div>}
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

      {railContext && (() => {
        const item = workspaces.find(candidate => candidate.id === railContext.id);
        if (!item) return null;
        const pinned = pinnedIds.includes(item.id);
        return <div ref={railMenuRef} role="menu" aria-label={t(item.en, item.zh)} onContextMenu={(event) => event.preventDefault()} onPointerDown={(event) => event.stopPropagation()} className="fixed z-[60] w-40 rounded-xl border border-stone-200 bg-white p-1.5 shadow-[0_20px_60px_rgba(70,48,100,0.18)]" style={{ left: railContext.x, top: railContext.y }}>
          <button type="button" role="menuitem" disabled={railSaving || preference?.revision === undefined} onClick={() => void setPinned(item.id, !pinned)} className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm text-stone-700 hover:bg-violet-50 disabled:opacity-40">{pinned ? t("Unpin", "取消置顶") : t("Pin", "置顶")}</button>
        </div>;
      })()}

      {railError && <div role="status" className="fixed bottom-5 left-[5rem] z-[70] rounded-xl bg-stone-900 px-4 py-2.5 text-xs text-white shadow-lg">{railError}</div>}

      {moreOpen && !accountOpen && <div ref={morePanel} id="ardor-more-workspaces" className="fixed left-[4.25rem] z-50 w-56 overflow-y-auto overscroll-contain rounded-2xl border border-stone-200 bg-white p-2 shadow-[0_20px_60px_rgba(70,48,100,0.16)]" style={{ top: moreTop, maxHeight: `calc(100dvh - ${moreTop}px - 12px)` }} onMouseEnter={() => { if (moreCloseTimer.current) clearTimeout(moreCloseTimer.current); }} onMouseLeave={hideMoreSoon}>
        {secondaryGroups.map((group) => <Fragment key={group.domain ?? "workspace"}>
          <p className="px-3 pb-1 pt-3 text-[10px] font-semibold uppercase tracking-widest text-stone-400">{groupLabel(group.domain, t)}</p>
          {group.items.map((item) => { const label = t(item.en, item.zh); return <Link key={item.id} href={item.href} onContextMenu={(event) => openRailContext(event, item.id)} onClick={() => { acknowledgeBackground(item.modules); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }} className={`relative flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm hover:bg-violet-50 ${destinationActive(item.id, pathname) ? "bg-violet-50 text-violet-700" : "text-stone-700"}`}><item.icon className="size-4 shrink-0" aria-hidden="true" /><span>{label}</span>{hasReady(item.href) && <span className="ml-auto size-2 rounded-full bg-red-500" aria-hidden="true" />}</Link>; })}
        </Fragment>)}
        <button type="button" onClick={() => { if (pathname === "/app") window.dispatchEvent(new Event("ardor:open-memory")); else { window.sessionStorage.setItem("ardor:open-memory", "1"); router.push("/app"); } closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm text-stone-700 hover:bg-violet-50"><Brain className="size-4 shrink-0" aria-hidden="true" />{t("Memory", "总体记忆")}</button>
        <button type="button" disabled={!account} onClick={() => { closeMore(); setCustomDraft(pinnedIds); setCustomRevision(preference?.revision ?? 0); setCustomError(""); setCustomizing(true); }} className="mt-1 flex w-full items-center gap-3 rounded-xl border-t border-stone-100 px-3 py-2.5 text-left text-sm text-violet-700 hover:bg-violet-50"><Settings2 className="size-4" />{t("Customize sidebar", "自定义侧栏")}</button>
      </div>}

      {customizing && <AppDialog title={t("Customize sidebar", "自定义侧栏")} onClose={() => setCustomizing(false)}>
        <p className="mb-4 text-sm text-stone-500">{t("Selected modules stay in the sidebar. Others go in More. Synced to your account across browser and desktop.", "选中项显示在侧栏，其余收进更多。按账户在浏览器与桌面端同步。")}</p>
        {preference?.revision === undefined && <p role="status" className="mb-3 text-xs text-stone-500">{t("Waiting to read account settings. Your choices will not be overwritten.", "正在读取账户设置，不会覆盖你的选择。")} <button type="button" className="text-violet-700 underline" onClick={() => window.dispatchEvent(new Event("ardor:sidebar-refresh"))}>{t("Retry sync", "重试同步")}</button></p>}
        <div className="space-y-1">{workspaces.map((item, index) => <Fragment key={item.id}>{(index === 0 || workspaces[index - 1].domain !== item.domain) && <p className="px-3 pb-1 pt-3 text-xs font-semibold text-stone-500">{groupLabel(item.domain, t)}</p>}<label className="flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2 hover:bg-violet-50"><item.icon className="size-4 text-stone-500" /><span className="flex-1 text-sm">{t(item.en, item.zh)}</span><input type="checkbox" checked={customDraft.includes(item.id)} disabled={fixedIds.includes(item.id) || customSaving} onChange={(event) => setCustomDraft((current) => event.target.checked ? [...current, item.id] : current.filter((id) => id !== item.id))} className="size-4 accent-violet-600" /></label></Fragment>)}</div>
        {customError && <p role="alert" className="mt-3 text-sm text-red-600">{customError}</p>}
        {account && legacyHrefs(account.id) && <button type="button" disabled={customSaving} onClick={() => setCustomDraft(destinationsFromLegacy(legacyHrefs(account.id)))} className="mt-3 text-xs text-violet-700 underline">{t("Load this device's previous layout", "载入此设备的旧布局")}</button>}
        <div className="mt-5 flex justify-between gap-2"><Button variant="outline" disabled={customSaving} onClick={() => setCustomDraft(defaultIds)}>{t("Reset defaults", "恢复默认")}</Button><Button disabled={customSaving || preference?.revision === undefined} onClick={() => void saveSidebar()}>{customSaving ? t("Syncing…", "正在同步…") : t("Save", "保存")}</Button></div>
      </AppDialog>}
      {accountOpen && (
        <>
          <button type="button" className="fixed inset-0 z-40 cursor-default" aria-label={t("Close account menu", "关闭账户菜单")} onClick={() => setAccountOpen(false)} />
          <div className="fixed bottom-3 left-[4.75rem] z-50 w-60 rounded-2xl border border-stone-200 bg-white p-2 shadow-[0_20px_60px_rgba(70,48,100,0.16)]">
            <p className="truncate border-b border-stone-100 px-3 py-2 text-xs text-stone-500" title={account?.email}>{account?.email ?? t("Your account", "个人账户")}</p>
            <Link href="/app/settings" onClick={() => setAccountOpen(false)} className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm hover:bg-violet-50"><Settings2 className="size-4" />{t("Settings", "设置")}</Link>
            <button type="button" onClick={() => void logout()} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm hover:bg-violet-50"><LogOut className="size-4" />{t("Log out", "退出登录")}</button>
            {error && <p role="alert" className="px-3 py-2 text-xs text-red-600">{error}</p>}
          </div>
        </>
      )}
    </aside>
  );
}
