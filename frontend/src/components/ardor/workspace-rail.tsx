"use client";

import {
  BookOpenText, Brain, CalendarDays, FileSearch, FileText, GraduationCap,
  Ellipsis, Home, LogOut, MessageSquareText, Settings2, ShieldCheck, SquareStack, Target, TrendingUp,
} from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { api } from "@/lib/api";
import { backgroundNotificationKey, type BackgroundModule } from "@/lib/background-notifications";
import { useLocale } from "@/lib/locale";
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
] as const;
const primaryHrefs = new Set(["/app", "/app/resumes", "/app/interviews", "/app/cards", "/app/calendar", "/app/applications"]);
const primaryWorkspaces = workspaces.filter((item) => primaryHrefs.has(item.href));
const secondaryWorkspaces = workspaces.filter((item) => !primaryHrefs.has(item.href));
const backgroundPaths: Record<BackgroundModule, string> = { resumes: "/app/resumes", recaps: "/app/recaps", knowledge: "/app/knowledge" };
type ResumeBackgroundStatus = { analysisStatus: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | null };
type RecapBackgroundStatus = { status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" };
type KnowledgeBackgroundStatus = { pendingChunks: number };

function moduleFor(href: string): BackgroundModule | null {
  if (href === "/app/resumes") return "resumes";
  if (href === "/app/recaps") return "recaps";
  if (href === "/app/knowledge") return "knowledge";
  return null;
}

export function WorkspaceRail() {
  const pathname = usePathname();
  const router = useRouter();
  const { t } = useLocale();
  const [account, setAccount] = useState<{ id: string; email: string; admin: boolean } | null>(null);
  const [accountOpen, setAccountOpen] = useState(false);
  const [moreOpen, setMoreOpen] = useState(false);
  const [morePinned, setMorePinned] = useState(false);
  const [moreTop, setMoreTop] = useState(0);
  const moreCloseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const moreButton = useRef<HTMLButtonElement>(null);
  const morePanel = useRef<HTMLDivElement>(null);
  const [error, setError] = useState("");
  const [tooltip, setTooltip] = useState<{ label: string; top: number } | null>(null);
  const [backgroundReady, setBackgroundReady] = useState<Record<BackgroundModule, boolean>>({ resumes: false, recaps: false, knowledge: false });

  useEffect(() => {
    api<{ id: string; email: string; admin: boolean }>("/api/auth/me").then(setAccount).catch(() => undefined);
  }, []);

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
    const modules: BackgroundModule[] = ["resumes", "recaps", "knowledge"];
    const refresh = async () => {
      try {
        const [resumes, recaps, knowledge] = await Promise.all([
          api<ResumeBackgroundStatus[]>("/api/resumes"),
          api<RecapBackgroundStatus[]>("/api/interview-recaps/jobs"),
          api<KnowledgeBackgroundStatus>("/api/knowledge/index-status"),
        ]);
        const active = {
          resumes: resumes.some((item) => item.analysisStatus === "QUEUED" || item.analysisStatus === "RUNNING"),
          recaps: recaps.some((item) => item.status === "QUEUED" || item.status === "RUNNING"),
          knowledge: knowledge.pendingChunks > 0,
        } satisfies Record<BackgroundModule, boolean>;
        for (const itemModule of modules) {
          const pendingKey = backgroundNotificationKey(userId, "pending", itemModule);
          const readyKey = backgroundNotificationKey(userId, "ready", itemModule);
          if (pathname.startsWith(backgroundPaths[itemModule])) {
            window.localStorage.removeItem(readyKey);
            if (!active[itemModule]) window.localStorage.removeItem(pendingKey);
            continue;
          }
          if (active[itemModule]) window.localStorage.setItem(pendingKey, "1");
          else if (window.localStorage.getItem(pendingKey) === "1") {
            window.localStorage.removeItem(pendingKey);
            window.localStorage.setItem(readyKey, "1");
          }
        }
        if (!cancelled) setBackgroundReady(Object.fromEntries(modules.map((itemModule) => [itemModule, window.localStorage.getItem(backgroundNotificationKey(userId, "ready", itemModule)) === "1"])) as Record<BackgroundModule, boolean>);
      } catch {
        // Badges are informational; they must never interrupt navigation.
      }
    };
    void refresh();
    const timer = window.setInterval(() => void refresh(), 10_000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [account?.id, pathname]);

  function acknowledgeBackground(itemModule: BackgroundModule | null) {
    if (!itemModule || !account?.id) return;
    window.localStorage.removeItem(backgroundNotificationKey(account.id, "ready", itemModule));
    setBackgroundReady((current) => ({ ...current, [itemModule]: false }));
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
              onClick={() => { acknowledgeBackground(itemModule); closeMore(); window.dispatchEvent(new Event("ardor:close-chat-drawer")); }}
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
      </div>}

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
