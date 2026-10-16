"use client";
import { ArrowLeft, ArrowRight, PanelLeft } from "lucide-react";
import { useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { useLocale } from "@/lib/locale";

const subscribe = () => () => {};
export function DesktopFrame({ children }: { children: ReactNode }) {
  const enabled = useSyncExternalStore(subscribe, () => window.ardorDesktop?.windowChrome === true, () => false);
  const [navigation, setNavigation] = useState({ back: false, forward: false });
  const [error, setError] = useState("");
  const pathname = usePathname();
  const router = useRouter();
  const { t } = useLocale();
  useEffect(() => {
    if (!enabled) return;
    const bridge = window.ardorDesktop;
    let active = true;
    void bridge?.getNavigation?.().then((value) => { if (active) setNavigation(value); }).catch(() => undefined);
    const offNavigation = bridge?.onNavigation?.(setNavigation);
    const offAction = bridge?.onWindowAction?.((action) => {
      if (action === "settings") router.push("/app/settings");
      if (action === "new-chat") { if (pathname === "/app") window.dispatchEvent(new Event("ardor:new-chat")); else router.push("/app?new=1"); }
      if (action === "toggle-sidebar" && pathname === "/app") window.dispatchEvent(new Event("ardor:toggle-chat-sidebar"));
    });
    return () => { active = false; offNavigation?.(); offAction?.(); };
  }, [enabled, pathname, router]);
  async function go(direction: "back" | "forward") {
    try { const value = await window.ardorDesktop?.navigate?.(direction); if (value) setNavigation(value); setError(""); }
    catch { setError(t("Navigation unavailable", "暂时无法导航")); }
  }
  return <div data-desktop-chrome={enabled || undefined} className="flex h-dvh flex-col overflow-hidden">
    {enabled && <header aria-label={t("Window toolbar", "窗口工具栏")} className="ardor-window-titlebar flex h-[46px] shrink-0 items-center gap-1 bg-[#faf9f6] px-2 pr-40 text-stone-500"
      style={{ backgroundImage: "linear-gradient(to right, #fff7ed, #f8faf8, #faf9f6)", backgroundSize: "calc(100% - 160px) 100%", backgroundRepeat: "no-repeat" }}>
      <button type="button" disabled={!navigation.back} aria-label={t("Back", "后退")} onClick={() => void go("back")} className="ardor-window-control rounded-lg p-2 hover:bg-white/70 disabled:opacity-30"><ArrowLeft className="size-4" /></button>
      <button type="button" disabled={!navigation.forward} aria-label={t("Forward", "前进")} onClick={() => void go("forward")} className="ardor-window-control rounded-lg p-2 hover:bg-white/70 disabled:opacity-30"><ArrowRight className="size-4" /></button>
      <button type="button" disabled={pathname !== "/app"} aria-label={t("Toggle conversations", "折叠对话栏")} onClick={() => window.dispatchEvent(new Event("ardor:toggle-chat-sidebar"))} className="ardor-window-control mr-3 rounded-lg p-2 hover:bg-white/70 disabled:opacity-30"><PanelLeft className="size-4" /></button>
      {(["file", "edit", "view", "help"] as const).map((name) => <button key={name} type="button" onClick={() => { void window.ardorDesktop?.showMenu?.(name).catch(() => setError(t("Menu unavailable", "暂时无法打开菜单"))); }} className="ardor-window-control rounded-lg px-3 py-1.5 text-sm capitalize hover:bg-white/70">{name}</button>)}
      {error && <span role="status" className="truncate text-xs text-rose-600">{error}</span>}
    </header>}
    <div className="relative min-h-0 flex-1 overflow-auto">{children}</div>
  </div>;
}
