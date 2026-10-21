"use client";

import { Briefcase, ChevronDown, GraduationCap, Layers } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { destinations, destinationForPath } from "@/lib/navigation";
import { useLocale } from "@/lib/locale";

const domainIcons = { "job-search": Briefcase, learning: GraduationCap } as const;
const closeDelay = 160;
const scrollThreshold = 8;

export function DomainNavigation() {
  const pathname = usePathname();
  const { t } = useLocale();
  const current = destinationForPath(pathname);
  const host = useRef<HTMLDivElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastScroll = useRef(0);
  const [menu, setMenu] = useState<{ path: string; pinned: boolean } | null>(null);
  const [hidden, setHidden] = useState(false);
  const [compact, setCompact] = useState(false);

  const clearCloseTimer = useCallback(() => {
    if (closeTimer.current) {
      clearTimeout(closeTimer.current);
      closeTimer.current = null;
    }
  }, []);

  const expanded = menu?.path === pathname;
  const pinned = expanded && menu.pinned;

  const expand = useCallback(() => {
    clearCloseTimer();
    setMenu(current => (current?.path === pathname ? current : { path: pathname, pinned: false }));
  }, [clearCloseTimer, pathname]);

  const close = useCallback(() => {
    setMenu(null);
  }, []);

  const closeSoon = useCallback(() => {
    if (pinned) return;
    clearCloseTimer();
    closeTimer.current = setTimeout(() => setMenu(null), closeDelay);
  }, [clearCloseTimer, pinned]);

  useEffect(() => () => clearCloseTimer(), [clearCloseTimer]);

  useEffect(() => {
    const scrollRoot = host.current?.closest("[data-app-scroll]");
    if (!scrollRoot) return;
    const onScroll = () => {
      const y = (scrollRoot as HTMLElement).scrollTop;
      setCompact(y > scrollThreshold);
      if (y <= scrollThreshold) {
        setHidden(false);
      } else if (y > lastScroll.current + scrollThreshold) {
        setHidden(true);
        setMenu(null);
      } else if (y < lastScroll.current - scrollThreshold) {
        setHidden(false);
      }
      lastScroll.current = y;
    };
    scrollRoot.addEventListener("scroll", onScroll, { passive: true });
    return () => scrollRoot.removeEventListener("scroll", onScroll);
  }, []);

  useEffect(() => {
    if (!expanded) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!host.current?.contains(event.target as Node)) close();
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      close();
      host.current?.querySelector<HTMLButtonElement>("button")?.focus();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [close, expanded]);

  if (!current?.domain) return null;

  const root = destinations.find(item => item.id === current.domain)!;
  const tabs = destinations.filter(item => item.domain === current.domain && item.tab);
  const currentTab = current.section ?? current.id;
  const currentLabel = tabs.find(item => item.id === currentTab);
  const labelOf = (item: { id: string; en: string; zh: string }) =>
    item.id === "job-search" ? t("Overview", "总览") : item.id === "learning" ? t("Topics", "主题") : t(item.en, item.zh);
  const DomainIcon = domainIcons[current.domain as keyof typeof domainIcons] ?? Layers;

  return (
    <div
      ref={host}
      className={`sticky top-0 z-30 flex px-5 pb-2 pt-3 transition-[transform,opacity] duration-200 motion-reduce:transition-none md:px-10 ${hidden ? "pointer-events-none -translate-y-[140%] opacity-0" : ""}`}
      aria-hidden={hidden}
      onMouseEnter={expand}
      onMouseLeave={closeSoon}
    >
      <div className="relative">
        <button
          id="ardor-domain-nav-trigger"
          type="button"
          aria-expanded={expanded}
          aria-controls="ardor-domain-navigation"
          aria-label={`${t("Section navigation", "领域导航")}: ${t(root.en, root.zh)} / ${currentLabel ? labelOf(currentLabel) : ""}`}
          tabIndex={hidden ? -1 : 0}
          onClick={(event) => {
            event.stopPropagation();
            if (expanded && pinned) close();
            else {
              clearCloseTimer();
              setMenu({ path: pathname, pinned: true });
            }
          }}
          className={`inline-flex h-[38px] items-center gap-2 rounded-full border border-white/85 bg-white/55 text-[13px] font-medium text-stone-600 shadow-[0_10px_30px_rgba(49,39,70,0.08)] backdrop-blur-xl transition-[background-color,box-shadow,width,padding] hover:bg-white/80 hover:shadow-[0_14px_38px_rgba(49,39,70,0.13)] focus-visible:outline-2 focus-visible:outline-violet-500 ${compact ? "w-[38px] justify-center p-0" : "px-3.5"} ${expanded ? "bg-white/80 shadow-[0_14px_38px_rgba(49,39,70,0.13)]" : ""}`}
        >
          {compact ? (
            <DomainIcon className="size-[18px] text-violet-600" aria-hidden="true" />
          ) : (
            <>
              <span className="grid size-5 shrink-0 place-items-center rounded-full bg-violet-100 text-violet-700">
                <DomainIcon className="size-3.5" aria-hidden="true" />
              </span>
              <span className="text-stone-400">{t(root.en, root.zh)}</span>
              <span aria-hidden="true" className="text-stone-300">/</span>
              <span className="font-semibold text-stone-800">{currentLabel ? labelOf(currentLabel) : ""}</span>
              <ChevronDown
                aria-hidden="true"
                className={`size-4 text-stone-400 transition-transform duration-200 ${expanded ? "rotate-180" : ""}`}
              />
            </>
          )}
        </button>

        {expanded && (
          <nav
            id="ardor-domain-navigation"
            aria-label={t("Section navigation", "领域导航")}
            className="absolute left-0 top-[calc(100%+8px)] z-40 flex items-center gap-0.5 whitespace-nowrap rounded-[18px] border border-white/90 bg-white/70 p-1.5 shadow-[0_26px_70px_rgba(49,39,70,0.17)] backdrop-blur-2xl"
          >
            {tabs.map(item => {
              const active = currentTab === item.id;
              return (
                <Link
                  key={item.id}
                  href={item.href}
                  aria-current={active ? "page" : undefined}
                  onClick={() => close()}
                  className={`inline-flex h-9 items-center rounded-xl px-3.5 text-sm font-medium transition-colors ${active ? "bg-white text-violet-800 shadow-[0_2px_10px_rgba(49,39,70,0.10)]" : "text-stone-500 hover:bg-white/60 hover:text-stone-900"}`}
                >
                  {labelOf(item)}
                </Link>
              );
            })}
          </nav>
        )}
      </div>
    </div>
  );
}
