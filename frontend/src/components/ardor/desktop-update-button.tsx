"use client";

import { ArrowDownToLine, LoaderCircle, RotateCw } from "lucide-react";
import { useEffect, useState } from "react";
import { useLocale } from "@/lib/locale";

type UpdateStatus = { state: string; message?: string; percent?: number; version?: string };

/** Uses the existing trusted desktop bridge; browser-only clients have no installer. */
export function DesktopUpdateButton() {
  const { t } = useLocale();
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [packaged, setPackaged] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");

  useEffect(() => {
    const bridge = window.ardorDesktop;
    if (!bridge) return;
    let active = true, receivedEvent = false;
    const unsubscribe = bridge.onUpdateStatus((next) => {
      receivedEvent = true;
      if (active) { setStatus(next); setNotice(""); }
    });
    void bridge.getInfo().then((info) => {
      if (!active) return;
      setPackaged(info.packaged);
      if (!receivedEvent) setStatus(info.updateStatus);
    }).catch(() => undefined);
    return () => { active = false; unsubscribe(); };
  }, []);

  if (!packaged || !status) return null;
  const downloading = status.state === "downloading";
  const pending = busy || status.state === "checking" || downloading;
  const ready = status.state === "ready";
  const percent = typeof status.percent === "number" && Number.isFinite(status.percent)
    ? Math.min(100, Math.max(0, Math.round(status.percent))) : null;
  const label = ready ? t("Install and restart", "安装并重启")
    : downloading ? percent === null ? t("Downloading update…", "正在下载更新…") : t(`Downloading update: ${percent}%`, `正在下载更新：${percent}%`)
    : status.state === "checking" ? t("Checking for updates…", "正在检查更新…")
    : status.state === "current" ? t("Up to date. Check again", "已是最新版本，点击重新检查")
    : status.state === "error" ? t("Update failed. Retry", "更新失败，点击重试")
    : t("Check for updates", "检查更新");

  async function activate() {
    const bridge = window.ardorDesktop;
    if (!bridge || pending) return;
    setBusy(true); setNotice("");
    try {
      if (ready) {
        const result = await bridge.installUpdate();
        if (!result.installed) {
          setStatus((await bridge.getInfo()).updateStatus);
          setNotice(t("Update is not ready yet. Check again.", "更新尚未就绪，请重新检查。"));
        }
      } else setStatus(await bridge.checkForUpdates());
    } catch {
      setNotice(ready ? t("Could not install. Try again.", "安装失败，请重试。") : t("Could not check for updates. Try again.", "检查更新失败，请重试。"));
    } finally { setBusy(false); }
  }

  return <div className="group relative mb-3">
    <button type="button" data-desktop-update={status.state} aria-label={label} disabled={pending} onClick={() => void activate()}
      className={`relative grid size-11 place-items-center rounded-2xl transition-colors focus-visible:outline-2 focus-visible:outline-violet-500 disabled:cursor-wait ${ready ? "bg-violet-100 text-violet-700" : "text-stone-500 hover:bg-white hover:text-stone-900"}`}>
      {downloading && percent !== null ? <>
        <svg viewBox="0 0 44 44" className="absolute inset-0 size-11 -rotate-90" role="progressbar" aria-label={t("Update download", "更新下载")} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
          <circle cx="22" cy="22" r="18" fill="none" stroke="currentColor" strokeWidth="2" className="opacity-15" />
          <circle cx="22" cy="22" r="18" fill="none" stroke="currentColor" strokeWidth="2" pathLength="100" strokeDasharray="100" strokeDashoffset={100 - percent} strokeLinecap="round" className="transition-[stroke-dashoffset] duration-300 motion-reduce:transition-none" />
        </svg><span className="text-[10px] font-semibold tabular-nums">{percent}%</span>
      </> : pending ? <LoaderCircle className="size-5 animate-spin motion-reduce:animate-none" aria-hidden />
        : ready ? <ArrowDownToLine className="size-5" aria-hidden /> : <RotateCw className="size-5" aria-hidden />}
      {ready && <span className="absolute right-1 top-1 size-2 rounded-full bg-violet-600 ring-2 ring-white" aria-hidden />}
    </button>
    <span role="status" className={`${notice ? "block" : "hidden group-hover:block group-focus-within:block"} absolute bottom-1 left-14 z-50 w-max max-w-64 rounded-xl border border-stone-200 bg-white px-3 py-2 text-xs text-stone-700 shadow-lg`}>
      {notice || label}{ready && status.version ? ` · ${status.version}` : ""}
    </span>
  </div>;
}
