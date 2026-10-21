"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useLayoutEffect, useRef } from "react";

import { destinations, destinationForPath } from "@/lib/navigation";
import { useLocale } from "@/lib/locale";

/**
 * Section tabs belong to the page, not to the app chrome. After mount this
 * host moves into the page <main>, right after the page header (or the title
 * block on pages without a <header>), so it scrolls with the content and sits
 * on the workbench surface instead of occupying a separate top strip.
 * A MutationObserver re-attaches it when a page swaps a loading skeleton for
 * loaded content.
 */
function findAnchor(main: HTMLElement): Element | null {
  const title = main.querySelector("h1");
  if (title) {
    const header = title.closest("header");
    if (header && main.contains(header)) return header;
    const subtitle = title.nextElementSibling;
    return subtitle && subtitle.tagName === "P" ? subtitle : title;
  }
  const header = main.querySelector("header");
  if (header && main.contains(header)) return header;
  return main.firstElementChild ?? main;
}

export function DomainNavigation() {
  const pathname = usePathname();
  const { t } = useLocale();
  const current = destinationForPath(pathname);
  const slot = useRef<HTMLDivElement>(null);
  const host = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const hostNode = host.current;
    const slotNode = slot.current;
    if (!hostNode || !slotNode) return;

    const place = () => {
      const main = document.querySelector<HTMLElement>("[data-app-scroll] main");
      const anchor = main ? findAnchor(main) : null;
      if (!anchor?.parentElement) return;
      if (hostNode.parentElement === anchor.parentElement && hostNode.previousElementSibling === anchor) return;
      anchor.insertAdjacentElement("afterend", hostNode);
    };

    place();
    const scrollRoot = document.querySelector("[data-app-scroll]");
    if (!scrollRoot) return () => void slotNode.appendChild(hostNode);
    let frame = 0;
    const observer = new MutationObserver(() => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        place();
      });
    });
    observer.observe(scrollRoot, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      if (frame) cancelAnimationFrame(frame);
      slotNode.appendChild(hostNode);
    };
  }, [pathname]);

  if (!current?.domain) return null;

  const root = destinations.find(item => item.id === current.domain)!;
  const tabs = destinations.filter(item => item.domain === current.domain && item.tab);
  const currentTab = current.section ?? current.id;
  const labelOf = (item: { id: string; en: string; zh: string }) =>
    item.id === "job-search" ? t("Overview", "总览") : item.id === "learning" ? t("Topics", "主题") : t(item.en, item.zh);

  return (
    <div ref={slot} className="hidden" aria-hidden="true">
      <div ref={host} className="mb-7 mt-4 flex w-full flex-wrap items-center">
        <nav
          id="ardor-domain-navigation"
          aria-label={t("Section navigation", "领域导航")}
          onPointerMove={(event) => {
            const bounds = event.currentTarget.getBoundingClientRect();
            event.currentTarget.style.setProperty("--ardor-glass-x", `${event.clientX - bounds.left}px`);
            event.currentTarget.style.setProperty("--ardor-glass-y", `${event.clientY - bounds.top}px`);
          }}
          className="ardor-liquid inline-flex max-w-full items-center gap-0.5 overflow-x-auto whitespace-nowrap rounded-full p-1"
        >
          <span className="ml-1 mr-2 flex shrink-0 items-center gap-1.5 pl-2 text-[10px] font-semibold uppercase tracking-[0.16em] text-stone-500">
            <span aria-hidden="true" className="size-1.5 rounded-full bg-violet-500 shadow-[0_0_0_3px_rgba(139,92,246,0.16)]" />
            {t(root.en, root.zh)}
          </span>
          {tabs.map(item => {
            const active = currentTab === item.id;
            return (
              <Link
                key={item.id}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={`ardor-liquid-tab inline-flex h-8 items-center rounded-full px-3.5 text-sm font-medium ${active ? "bg-white/70 text-violet-800 shadow-[inset_0_1px_0_rgba(255,255,255,0.95),inset_0_-2px_6px_rgba(255,255,255,0.35),0_4px_12px_rgba(38,28,60,0.16)]" : "text-stone-600 hover:bg-white/45 hover:text-stone-900"}`}
              >
                {labelOf(item)}
              </Link>
            );
          })}
        </nav>
      </div>
    </div>
  );
}
