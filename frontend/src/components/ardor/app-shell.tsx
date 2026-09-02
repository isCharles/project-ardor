"use client";

import {
  CalendarRange, FileText, Layers, MessageSquare, PanelLeft, Settings, SquareStack, X,
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import * as React from "react";

import { cn } from "@/lib/utils";
import styles from "./app-shell.module.css";

/* The record: every durable thing Ardor keeps, addressed by series.
   The letters are the same ones that index items inside each section,
   so the navigation teaches the notation. */
const RECORD = [
  { href: "/app/resumes", label: "简历", series: "R", icon: FileText },
  { href: "/app/interviews", label: "模拟面试", series: "I", icon: MessageSquare },
  { href: "/app/recaps", label: "面经", series: "V", icon: Layers },
  { href: "/app/cards", label: "记忆卡", series: "C", icon: SquareStack },
  { href: "/app/calendar", label: "日程", series: "T", icon: CalendarRange },
] as const;

export function AppShell({
  aside,
  children,
  headerLeft,
  headerRight,
  /** Chat owns its own scrolling; record pages scroll the main column. */
  scroll = true,
}: {
  aside?: React.ReactNode;
  children: React.ReactNode;
  headerLeft?: React.ReactNode;
  headerRight?: React.ReactNode;
  scroll?: boolean;
}) {
  const pathname = usePathname();
  const [open, setOpen] = React.useState(false);
  const close = React.useCallback(() => setOpen(false), []);

  return (
    <div className={cn(styles.shell, "flex h-dvh overflow-hidden bg-[var(--ardor-surface)] text-[var(--ardor-ink)]")}>
      {open && (
        <button
          aria-label="关闭导航"
          onClick={() => setOpen(false)}
          className="fixed inset-0 z-40 bg-[rgba(26,25,23,0.32)] md:hidden"
        />
      )}

      <aside
        data-open={open}
        className={cn(
          "ardor-drawer fixed inset-y-0 left-0 z-50 flex w-[15.5rem] shrink-0 flex-col",
          "bg-[var(--ardor-rail)] text-[var(--ardor-rail-ink)]",
          styles.sidebar,
          "md:static md:z-auto",
        )}
      >
        <div className={cn(styles.sidebarHeader, "flex h-16 shrink-0 items-center justify-between pl-4 pr-2")}>
          <Link href="/app" onClick={close} className={styles.workspaceBrand}>
            <span className={styles.protocolMark} aria-hidden><span /><span /></span>
            <span>
              <strong>ARDOR</strong>
              <small>CAREER OS / 04</small>
            </span>
          </Link>
          <button
            aria-label="关闭导航"
            onClick={() => setOpen(false)}
            className="rounded-[var(--ardor-radius-sm)] p-1.5 text-[var(--ardor-rail-ink-3)] hover:bg-[var(--ardor-rail-hover)] md:hidden"
          >
            <X className="size-4" />
          </button>
        </div>

        {aside && <div className="flex min-h-0 flex-1 flex-col">{aside}</div>}
        {!aside && <div className="flex-1" />}

        <nav className={cn(styles.protocolNav, "shrink-0 border-t border-[var(--ardor-rail-rule)] p-2")} aria-label="记录">
          <p className="t-eyebrow px-2 pb-1.5 pt-1 text-[var(--ardor-rail-ink-3)]">Protocols</p>
          {RECORD.map((item) => {
            const active = pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                onClick={close}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "group relative flex items-center gap-2.5 rounded-[var(--ardor-radius-sm)] py-1.5 pl-3.5 pr-2",
                  "text-[0.84375rem] transition-colors duration-[var(--ardor-fast)]",
                  active
                    ? "bg-[var(--ardor-rail-active)] text-[var(--ardor-rail-ink)]"
                    : "text-[var(--ardor-rail-ink-2)] hover:bg-[var(--ardor-rail-hover)] hover:text-[var(--ardor-rail-ink)]",
                )}
              >
                {/* A short length of the thread marks where you are. */}
                <span
                  aria-hidden
                  className={cn(
                    "absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-full transition-colors duration-[var(--ardor-fast)]",
                    active ? "bg-[var(--ardor-rail-accent)]" : "bg-transparent",
                  )}
                />
                <item.icon
                  className={cn(
                    "size-4 shrink-0",
                    active ? "text-[var(--ardor-rail-ink-2)]" : "text-[var(--ardor-rail-ink-3)]",
                  )}
                  aria-hidden
                />
                <span className="min-w-0 flex-1 truncate">{item.label}</span>
                <span
                  className={cn(
                    "ardor-index",
                    active ? "text-[var(--ardor-rail-accent)]" : "text-[var(--ardor-rail-ink-3)]",
                  )}
                  aria-hidden
                >
                  {item.series}
                </span>
              </Link>
            );
          })}

          <Link
            href="/app/settings"
            onClick={close}
            aria-current={pathname.startsWith("/app/settings") ? "page" : undefined}
            className={cn(
              "relative mt-1 flex items-center gap-2.5 rounded-[var(--ardor-radius-sm)] py-1.5 pl-3.5 pr-2",
              "text-[0.84375rem] transition-colors duration-[var(--ardor-fast)]",
              pathname.startsWith("/app/settings")
                ? "bg-[var(--ardor-rail-active)] text-[var(--ardor-rail-ink)]"
                : "text-[var(--ardor-rail-ink-2)] hover:bg-[var(--ardor-rail-hover)] hover:text-[var(--ardor-rail-ink)]",
            )}
          >
            <span
              aria-hidden
              className={cn(
                "absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-full",
                pathname.startsWith("/app/settings") ? "bg-[var(--ardor-rail-accent)]" : "bg-transparent",
              )}
            />
            <Settings className="size-4 shrink-0 text-[var(--ardor-rail-ink-3)]" aria-hidden />
            设置
          </Link>
        </nav>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className={cn(styles.topbar, "flex h-14 shrink-0 items-center gap-2 border-b border-[var(--ardor-rule)] px-3 md:px-5")}>
          <button
            aria-label="打开导航"
            onClick={() => setOpen(true)}
            className="rounded-[var(--ardor-radius-sm)] p-1.5 text-[var(--ardor-ink-3)] hover:bg-[var(--ardor-sunken)] md:hidden"
          >
            <PanelLeft className="size-4" />
          </button>
          <div className="flex min-w-0 flex-1 items-center gap-2">
            <span className={styles.topbarSignal} aria-hidden />
            {headerLeft}
          </div>
          {headerRight}
        </header>
        <main className={cn(styles.workspace, "min-h-0 flex-1", scroll && "overflow-y-auto")}>{children}</main>
      </div>
    </div>
  );
}

/** Standard measure and padding for the record pages. */
export function PageBody({ children }: { children: React.ReactNode }) {
  return (
    <div className={cn(styles.pageBody, "mx-auto w-full max-w-[var(--ardor-shell)] px-5 pb-24 pt-8 md:px-8")}>
      {children}
    </div>
  );
}
