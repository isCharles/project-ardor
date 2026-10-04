"use client";

import { ArrowDownToLine, X } from "lucide-react";
import { useEffect, useState } from "react";

type UpdateStatus = { state: string; message: string; version?: string };
type DesktopBridge = {
  getInfo(): Promise<{ updateStatus: UpdateStatus }>;
  installUpdate(): Promise<{ installed: boolean }>;
  onUpdateStatus(callback: (status: UpdateStatus) => void): () => void;
};

export function DesktopUpdatePrompt() {
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [dismissedVersion, setDismissedVersion] = useState<string | null>(null);

  useEffect(() => {
    const bridge = (window as Window & { ardorDesktop?: DesktopBridge }).ardorDesktop;
    if (!bridge) return;
    let mounted = true;
    void bridge.getInfo().then((info) => { if (mounted) setStatus(info.updateStatus); }).catch(() => undefined);
    const unsubscribe = bridge.onUpdateStatus((next) => { if (mounted) setStatus(next); });
    return () => { mounted = false; unsubscribe(); };
  }, []);

  if (status?.state !== "ready" || status.version === dismissedVersion) return null;

  return (
    <aside role="status" aria-live="polite" className="fixed right-5 top-20 z-[60] flex max-w-[min(24rem,calc(100vw-2.5rem))] items-start gap-3 rounded-2xl border border-white/80 bg-white/95 p-4 shadow-[0_20px_70px_rgba(74,57,130,0.2)] backdrop-blur-xl">
      <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-gradient-to-br from-fuchsia-100 to-sky-100 text-violet-700"><ArrowDownToLine className="size-5" /></span>
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-stone-900">新版本 {status.version} 已就绪</p>
        <p className="mt-1 text-xs text-stone-500">已下载，安装后 Ardor 会重新启动。</p>
        <button type="button" onClick={() => void (window as Window & { ardorDesktop?: DesktopBridge }).ardorDesktop?.installUpdate()} className="mt-3 rounded-full bg-stone-900 px-4 py-2 text-xs font-medium text-white hover:bg-stone-700">安装并重启</button>
      </div>
      <button type="button" aria-label="稍后更新" onClick={() => setDismissedVersion(status.version ?? "unknown")} className="rounded-full p-1 text-stone-400 hover:bg-stone-100 hover:text-stone-700"><X className="size-4" /></button>
    </aside>
  );
}
