"use client";

import { Download, MonitorDown, RotateCw } from "lucide-react";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { describeDesktopUpdateError } from "@/lib/desktop-update-error";
import { useLocale, type Locale } from "@/lib/locale";

type UpdateStatus = { state: "idle" | "checking" | "downloading" | "current" | "ready" | "error" | "unavailable"; message: string; percent?: number; version?: string };
type DesktopInfo = { version: string; serverUrl: string; locale: Locale | null; updateStatus: UpdateStatus; packaged: boolean };
type SetServerResult = { changed: boolean; connected?: boolean; errorCode?: "invalidServer" | "httpsRequired" | "rootOnly"; message?: string };
type DesktopBridge = {
  getInfo(): Promise<DesktopInfo>;
  setLocale(locale: Locale): Promise<{ locale: Locale }>;
  setServer(url: string): Promise<SetServerResult>;
  // Missing on desktop builds older than the structured server errors.
  setServerResult?(url: string): Promise<SetServerResult>;
  checkForUpdates(): Promise<UpdateStatus>;
  installUpdate(): Promise<{ installed: boolean }>;
  onUpdateStatus(callback: (status: UpdateStatus) => void): () => void;
};

declare global { interface Window { ardorDesktop?: DesktopBridge } }

// Electron wraps errors thrown by ipcMain handlers as
// "Error invoking remote method '<channel>': Error: <message>"; show only the
// already-localized message from the main process.
function ipcErrorMessage(error: Error) {
  return error.message.replace(/^Error invoking remote method '[^']*': (?:\w*Error: )?/, "");
}

export function DesktopSettings() {
  const { t } = useLocale();
  const [info, setInfo] = useState<DesktopInfo | null>(null);
  const [server, setServer] = useState("");
  const [status, setStatus] = useState<UpdateStatus | null>(null);
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const bridge = window.ardorDesktop;
    if (!bridge) return;
    let mounted = true;
    bridge.getInfo().then((current) => {
      if (!mounted) return;
      setInfo(current); setServer(current.serverUrl); setStatus(current.updateStatus);
    }).catch(() => undefined);
    const unsubscribe = bridge.onUpdateStatus((next) => { if (mounted) setStatus(next); });
    return () => { mounted = false; unsubscribe(); };
  }, []);

  if (!info) return null;

  async function saveServer() {
    const bridge = window.ardorDesktop;
    if (!bridge) return;
    setBusy(true); setNotice("");
    try {
      const result = bridge.setServerResult ? await bridge.setServerResult(server) : await bridge.setServer(server);
      if (result.errorCode) setNotice(result.message ?? t("Enter a valid server address.", "请输入有效的服务器地址"));
      else if (result.changed && !result.connected) setNotice(t("Address saved, but the server is temporarily unreachable.", "地址已保存，但服务器暂时无法连接。"));
    } catch (error) { setNotice(error instanceof Error ? ipcErrorMessage(error) : t("Could not switch servers", "无法切换服务器")); }
    finally { setBusy(false); }
  }

  async function checkUpdate() {
    const bridge = window.ardorDesktop;
    if (!bridge) return;
    setNotice("");
    try { setStatus(await bridge.checkForUpdates()); }
    catch (error) { setStatus({ state: "error", message: error instanceof Error ? error.message : t("Update check failed", "检查更新失败") }); }
  }

  const updateError = status?.state === "error" ? describeDesktopUpdateError(status.message) : null;
  const updateSummary = updateError?.kind === "legacy-feed"
    ? t("This installer uses an old private update feed. Install the latest public release once.", "此安装包仍使用旧的私有更新源。请手动安装一次最新公开版本。")
    : updateError?.kind === "not-found"
      ? t("Update files are not available yet (404).", "更新文件暂不可用（404）。")
      : updateError?.kind === "access"
        ? t("The update source denied access.", "更新源拒绝访问。")
        : updateError?.kind === "network"
          ? t("Could not reach the update source. Try again later.", "暂时无法连接更新源，请稍后重试。")
          : t("Could not check for updates. Try again later.", "检查更新失败，请稍后重试。");
  const statusText = updateError ? updateSummary : status?.state === "checking"
    ? t("Checking for updates…", "正在检查更新…")
    : status?.state === "downloading"
      ? status.percent == null
        ? t(`Downloading version ${status.version ?? ""}…`, `正在下载版本 ${status.version ?? ""}…`)
        : t(`Downloading update ${status.percent}%…`, `正在下载更新 ${status.percent}%…`)
      : status?.state === "current"
        ? t("You're up to date", "已经是最新版本")
        : status?.state === "ready"
          ? t(`Version ${status.version ?? ""} is ready to install`, `版本 ${status.version ?? ""} 已下载，可安装并重启`)
          : status?.state === "unavailable"
            ? t("Updates are unavailable in development mode", "开发模式不能检查安装包更新")
            : status?.state === "error"
              ? updateSummary
              : t("Ready to check for updates", "可检查更新");

  return (
    <section className="ardor-panel rounded-[2rem] p-6 md:p-8 lg:col-span-2">
      <div className="flex items-center gap-3"><MonitorDown className="size-5 text-primary" /><h2 className="text-xl font-semibold">{t("Desktop app", "桌面应用")}</h2><span className="text-xs text-muted-foreground">v{info.version}</span></div>
      <div className="mt-6 grid gap-6 md:grid-cols-2">
        <div>
          <label htmlFor="desktop-server" className="block text-sm font-medium">{t("Connected server", "连接的服务器")}</label>
          <div className="mt-2 flex flex-wrap gap-2"><input id="desktop-server" className="field min-w-0 flex-1 font-mono text-sm" type="url" value={server} onChange={(event) => setServer(event.target.value)} /><Button type="button" variant="outline" disabled={busy || !server.trim()} onClick={() => void saveServer()}>{t("Switch", "切换")}</Button></div>
          <p className="mt-2 text-xs text-muted-foreground">{t("Local servers may use HTTP; public servers require HTTPS.", "本机可用 HTTP，公网地址须使用 HTTPS。")}</p>
        </div>
        <div>
          <p className="text-sm font-medium">{t("App updates", "应用更新")}</p>
          <p role="status" aria-live="polite" className="mt-2 min-w-0 break-words text-sm text-muted-foreground">{statusText}</p>
          {updateError?.detail && <details className="mt-2 min-w-0 text-xs text-muted-foreground"><summary className="w-fit cursor-pointer text-violet-700">{t("View details", "查看详情")}</summary><pre className="mt-2 max-h-36 max-w-full overflow-auto whitespace-pre-wrap break-all rounded-xl bg-stone-100/80 p-3 font-mono">{updateError.detail}</pre></details>}
          <p className="mt-1 text-xs text-muted-foreground">{t("The desktop app checks and downloads updates automatically. You choose when to install and restart.", "桌面版启动后会自动检查并下载更新；由你决定何时安装并重启。")}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            {status?.state === "ready" ? <Button type="button" onClick={() => void window.ardorDesktop?.installUpdate()}><Download className="mr-2 size-4" />{t("Install and restart", "安装并重启")}</Button> : <Button type="button" variant="outline" disabled={!info.packaged || status?.state === "checking" || status?.state === "downloading"} onClick={() => void checkUpdate()}><RotateCw className="mr-2 size-4" />{t("Check for updates", "检查更新")}</Button>}
          </div>
          {!info.packaged && <p className="mt-2 text-xs text-muted-foreground">{t("Installer updates are unavailable in development mode.", "开发模式不提供安装包更新。")}</p>}
        </div>
      </div>
      {notice && <p role="alert" className="mt-4 min-w-0 break-all text-sm text-red-700">{notice.length > 300 ? `${notice.slice(0, 300)}…` : notice}</p>}
    </section>
  );
}
