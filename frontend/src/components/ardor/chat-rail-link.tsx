"use client";

import { ChevronDown, Home, LoaderCircle, PanelLeft, Plus } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Conversation = { id: string; title: string };

/** Temporary navigation preview; it never overlays an already pinned chat list. */
export function ChatRailLink({ userId, active, className, onOpen }: {
  userId: string | null; active: boolean; className: string; onOpen: () => void;
}) {
  const { t } = useLocale();
  const router = useRouter();
  const [pinned, setPinned] = useState(true);
  const [open, setOpen] = useState(false);
  const [expanded, setExpanded] = useState(true);
  const [items, setItems] = useState<Conversation[] | null>(null);
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const trigger = useRef<HTMLAnchorElement>(null);
  const panel = useRef<HTMLElement>(null);
  const closeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const suppressFocus = useRef(false);
  const keyboard = useRef(false);
  function cancelClose() { if (closeTimer.current) clearTimeout(closeTimer.current); }
  function close() { cancelClose(); setOpen(false); }
  function show(fromKeyboard: boolean) {
    cancelClose();
    if (open || !userId || (active && pinned)) return;
    keyboard.current = fromKeyboard;
    setItems(null); setError(""); setOpen(true); onOpen();
  }
  function closeSoon() {
    cancelClose();
    if (keyboard.current && panel.current?.contains(document.activeElement)) return;
    closeTimer.current = setTimeout(() => setOpen(false), 180);
  }
  function openPinned() {
    close();
    if (active) { if (!pinned) window.dispatchEvent(new Event("ardor:toggle-chat-sidebar")); }
    else router.push("/app");
  }
  useEffect(() => {
    const state = (event: Event) => {
      const visible = (event as CustomEvent<boolean>).detail === true;
      setPinned(visible);
      if (visible) setOpen(false);
    };
    window.addEventListener("ardor:chat-sidebar-state", state);
    window.dispatchEvent(new Event("ardor:request-chat-sidebar-state"));
    return () => { window.removeEventListener("ardor:chat-sidebar-state", state); if (closeTimer.current) clearTimeout(closeTimer.current); };
  }, []);
  useEffect(() => {
    if (!open || !userId) return;
    const controller = new AbortController();
    void api<Conversation[]>("/api/agent/conversations", { signal: controller.signal }).then(value => {
      if (!controller.signal.aborted) setItems(Array.isArray(value) ? value.filter(item => typeof item.id === "string" && typeof item.title === "string").slice(0, 20) : []);
    }).catch(() => { if (!controller.signal.aborted) setError(t("Could not load recent chats.", "暂时无法读取最近对话。")); });
    return () => controller.abort();
  }, [open, userId, attempt, t]);
  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => { if (!trigger.current?.contains(event.target as Node) && !panel.current?.contains(event.target as Node)) setOpen(false); };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false); suppressFocus.current = true; trigger.current?.focus();
    };
    document.addEventListener("pointerdown", outside);
    document.addEventListener("keydown", escape);
    return () => { document.removeEventListener("pointerdown", outside); document.removeEventListener("keydown", escape); };
  }, [open]);
  return <>
    <Link ref={trigger} href="/app" aria-label={t("Chat", "对话")} aria-current={active ? "page" : undefined}
      aria-haspopup="dialog" aria-expanded={open} aria-controls={open ? "ardor-chat-preview" : undefined}
      title={active && pinned ? t("Chat", "对话") : undefined} className={className}
      onPointerEnter={event => { if (event.pointerType === "mouse") show(false); }} onPointerLeave={closeSoon}
      onFocus={event => { if (suppressFocus.current) { suppressFocus.current = false; return; } if (event.currentTarget.matches(":focus-visible")) show(true); }}
      onBlur={event => { if (!panel.current?.contains(event.relatedTarget as Node)) closeSoon(); }}
      onKeyDown={event => { if (event.key === "ArrowRight" || event.key === "ArrowDown") { event.preventDefault(); show(true); requestAnimationFrame(() => panel.current?.focus()); } }}
      onClick={event => { onOpen(); if (active) { event.preventDefault(); openPinned(); } else close(); }}>
      <Home className="size-5" aria-hidden="true" />
    </Link>
    {open && <section ref={panel} id="ardor-chat-preview" role="dialog" aria-modal="false" aria-label={t("Recent chats preview", "最近对话预览")} tabIndex={-1}
      onPointerEnter={cancelClose} onPointerLeave={closeSoon} onFocus={cancelClose}
      onBlur={event => { if (!panel.current?.contains(event.relatedTarget as Node) && !trigger.current?.contains(event.relatedTarget as Node)) closeSoon(); }}
      className="fixed left-[4.75rem] top-16 z-50 flex max-h-[calc(100dvh-5rem)] w-[min(19rem,calc(100vw-5.5rem))] flex-col rounded-2xl border border-stone-200/80 bg-white/95 p-3 shadow-[0_18px_60px_rgba(80,60,110,0.14)] backdrop-blur-xl outline-none">
      <div className="mb-2 flex items-center gap-2"><button type="button" onClick={() => setExpanded(value => !value)} aria-expanded={expanded}
        className="flex flex-1 items-center gap-2 rounded-lg px-2 py-2 text-left text-xs font-medium text-stone-500 hover:bg-violet-50"><ChevronDown aria-hidden="true" className={`size-3.5 transition-transform motion-reduce:transition-none ${expanded ? "" : "-rotate-90"}`} />{t("Recent chats", "最近对话")}</button>
        <button type="button" onClick={openPinned} aria-label={t("Pin conversations sidebar", "展开固定对话栏")} className="rounded-lg p-2 text-stone-500 hover:bg-violet-50"><PanelLeft className="size-4" /></button></div>
      {expanded && <div className="min-h-0 overflow-y-auto">
        {error ? <div role="status" className="px-2 py-4 text-xs text-stone-500">{error} <button type="button" className="text-violet-700 underline" onClick={() => { setError(""); setItems(null); setAttempt(value => value + 1); }}>{t("Retry", "重试")}</button></div>
          : items === null ? <p role="status" className="flex items-center gap-2 px-2 py-4 text-xs text-stone-400"><LoaderCircle className="size-3.5 animate-spin motion-reduce:animate-none" />{t("Loading chats…", "正在读取对话…")}</p>
          : items.length ? items.map(item => <Link key={item.id} href={`/app?conversation=${encodeURIComponent(item.id)}`} onClick={event => {
              close();
              if (active && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
                event.preventDefault();
                window.dispatchEvent(new CustomEvent("ardor:select-chat", { detail: item.id }));
              }
            }} title={item.title}
              className="block truncate rounded-xl px-3 py-2.5 text-sm text-stone-700 transition-colors hover:bg-violet-50 focus-visible:outline-2 focus-visible:outline-violet-500">{item.title}</Link>)
            : <p className="px-2 py-4 text-xs text-stone-400">{t("No recent chats yet.", "暂无最近对话。")}</p>}
      </div>}
      <Link href="/app?new=1" onClick={event => {
        close();
        if (active && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) {
          event.preventDefault(); window.dispatchEvent(new Event("ardor:new-chat"));
        }
      }} className="mt-2 flex items-center gap-2 rounded-xl border-t border-stone-100 px-3 py-2.5 text-sm text-violet-700 hover:bg-violet-50"><Plus className="size-4" />{t("New chat", "新对话")}</Link>
    </section>}
  </>;
}
