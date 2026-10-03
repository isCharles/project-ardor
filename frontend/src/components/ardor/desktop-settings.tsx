"use client";

import { Download, MonitorDown, RotateCw } from "lucide-react";
import { useEffect, useState } from "react";

import { Button } from "@/components/ui/button";

type UpdateStatus = { state: "idle" | "checking" | "downloading" | "current" | "ready" | "error"; message: string; percent?: number };
type DesktopInfo = { version: string; serverUrl: string; updateStatus: UpdateStatus; packaged: boolean };
type DesktopBridge = {
  getInfo(): Promise<DesktopInfo>;
  setServer(url: string): Promise<{ changed: boolean; connected?: boolean }>;
  checkForUpdates(): Promise<UpdateStatus>;
  installUpdate(): Promise<{ installed: boolean }>;
  onUpdateStatus(callback: (status: UpdateStatus) => void): () => void;
};

declare global { interface Window { ardorDesktop?: DesktopBridge } }

export function DesktopSettings() {
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
      const result = await bridge.setServer(server);
      if (result.changed && !result.connected) setNotice("地址已保存，但服务器暂时无法连接。");
    } catch (error) { setNotice(error instanceof Error ? error.message : "无法切换服务器"); }
    finally { setBusy(false); }
  }

  async function checkUpdate() {
    const bridge = window.ardorDesktop;
    if (!bridge) return;
    setNotice("");
    try { setStatus(await bridge.checkForUpdates()); }
    catch (error) { setNotice(error instanceof Error ? error.message : "检查更新失败"); }
  }

  return (
    <section className="ardor-panel rounded-[2rem] p-6 md:p-8 lg:col-span-2">
      <div className="flex items-center gap-3"><MonitorDown className="size-5 text-primary" /><h2 className="text-xl font-semibold">桌面应用</h2><span className="text-xs text-muted-foreground">v{info.version}</span></div>
      <div className="mt-6 grid gap-6 md:grid-cols-2">
        <div>
          <label htmlFor="desktop-server" className="block text-sm font-medium">连接的服务器</label>
          <div className="mt-2 flex flex-wrap gap-2"><input id="desktop-server" className="field min-w-0 flex-1 font-mono text-sm" type="url" value={server} onChange={(event) => setServer(event.target.value)} /><Button type="button" variant="outline" disabled={busy || !server.trim()} onClick={() => void saveServer()}>切换</Button></div>
          <p className="mt-2 text-xs text-muted-foreground">本机可用 HTTP，公网地址须使用 HTTPS。</p>
        </div>
        <div>
          <p className="text-sm font-medium">应用更新</p>
          <p role="status" aria-live="polite" className="mt-2 text-sm text-muted-foreground">{status?.message ?? "可检查更新"}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            {status?.state === "ready" ? <Button type="button" onClick={() => void window.ardorDesktop?.installUpdate()}><Download className="mr-2 size-4" />安装并重启</Button> : <Button type="button" variant="outline" disabled={!info.packaged || status?.state === "checking" || status?.state === "downloading"} onClick={() => void checkUpdate()}><RotateCw className="mr-2 size-4" />检查更新</Button>}
          </div>
          {!info.packaged && <p className="mt-2 text-xs text-muted-foreground">开发模式不提供安装包更新。</p>}
        </div>
      </div>
      {notice && <p role="alert" className="mt-4 text-sm text-red-700">{notice}</p>}
    </section>
  );
}
