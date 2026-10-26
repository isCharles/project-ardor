"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { AppDialog } from "@/components/ui/app-dialog";

type Avatar = { userId: string; imageDataUrl: string | null; updatedAt: string | null };
const changed = "ardor:avatar-changed";

function useAvatar(userId: string | null) {
  const [view, setView] = useState<Avatar | null>(null);
  useEffect(() => {
    if (!userId) return;
    let active = true;
    const load = () => { void api<Avatar>("/api/profile/avatar").then(value => {
      if (active && value.userId === userId) setView(value);
    }).catch(() => { /* Keep the default or previous image when offline. */ }); };
    load(); window.addEventListener(changed, load);
    return () => { active = false; window.removeEventListener(changed, load); };
  }, [userId]);
  return view?.userId === userId ? view : null;
}

export function ProfileAvatar({ userId, fallback }: { userId: string | null; fallback: string }) {
  const avatar = useAvatar(userId);
  const image = avatar?.imageDataUrl;
  return image && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(image)
    ? <Image src={image} alt="" width={256} height={256} unoptimized className="size-full rounded-[inherit] object-cover" />
    : <>{fallback.trim().charAt(0).toUpperCase() || "A"}</>;
}

export function ProfileAvatarSettings({ userId, fallback }: { userId: string | null; fallback: string }) {
  const { t } = useLocale();
  const avatar = useAvatar(userId);
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [confirmRemove, setConfirmRemove] = useState(false);
  async function upload(file: File | undefined) {
    if (!file || !userId || busy) return;
    setError("");
    if (file.size > 2 * 1024 * 1024 || !["image/png", "image/jpeg"].includes(file.type)) {
      setError(t("Choose a PNG or JPEG up to 2 MB.", "请选择不超过 2 MB 的 PNG 或 JPEG 图片。")); return;
    }
    setBusy(true);
    try {
      const body = new FormData(); body.set("file", file); body.set("expectedUserId", userId);
      const result = await api<Avatar>("/api/profile/avatar", { method: "PUT", body });
      if (result.userId !== userId) throw Error(t("Account changed. Refresh and try again.", "账户已变化，请刷新重试。"));
      window.dispatchEvent(new Event(changed));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not upload avatar", "头像上传失败")); }
    finally { setBusy(false); if (input.current) input.current.value = ""; }
  }
  async function remove() {
    if (!userId || busy) return;
    setBusy(true); setError("");
    try {
      await api<Avatar>(`/api/profile/avatar?expectedUserId=${encodeURIComponent(userId)}`, { method: "DELETE" });
      window.dispatchEvent(new Event(changed)); setConfirmRemove(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not remove avatar", "移除头像失败")); }
    finally { setBusy(false); }
  }
  return <div className="mt-7 border-b border-stone-200 pb-6">
    <div className="flex flex-wrap items-center gap-4">
      <div aria-label={t("Avatar preview", "头像预览")} className="grid size-16 shrink-0 place-items-center overflow-hidden rounded-2xl bg-gradient-to-br from-rose-200 via-violet-200 to-blue-200 text-xl font-semibold"><ProfileAvatar userId={userId} fallback={fallback} /></div>
      <div><div className="flex flex-wrap gap-2">
        <input ref={input} type="file" accept="image/png,image/jpeg" className="hidden" aria-label={t("Avatar image", "头像图片")} onChange={event => void upload(event.target.files?.[0])} />
        <button type="button" disabled={!userId || busy} onClick={() => input.current?.click()} className="rounded-full bg-stone-950 px-4 py-2 text-sm text-white disabled:opacity-50">{busy ? t("Saving…", "正在保存…") : t("Upload avatar", "上传头像")}</button>
        {avatar?.imageDataUrl && <button type="button" disabled={busy} onClick={() => { setError(""); setConfirmRemove(true); }} className="rounded-full px-4 py-2 text-sm text-stone-500 disabled:opacity-50">{t("Remove", "移除")}</button>}
      </div><p className="mt-2 text-xs text-stone-400">PNG / JPEG · 2 MB</p></div>
    </div>
    {error && !confirmRemove && <p role="alert" className="mt-3 text-sm text-rose-600">{error}</p>}
    {confirmRemove && <AppDialog title={t("Remove avatar?", "移除头像？")} onClose={() => { if (!busy) setConfirmRemove(false); }}>
      <p className="text-sm text-stone-500">{t("Your default avatar will be restored.", "将恢复默认头像。")}</p>
      {error && <p role="alert" className="mt-3 text-sm text-rose-600">{error}</p>}
      <div className="mt-6 flex justify-end gap-2"><button type="button" disabled={busy} onClick={() => setConfirmRemove(false)} className="rounded-full px-4 py-2 text-sm">{t("Cancel", "取消")}</button><button type="button" disabled={busy} onClick={() => void remove()} className="rounded-full bg-rose-600 px-4 py-2 text-sm text-white disabled:opacity-50">{t("Remove avatar", "移除头像")}</button></div>
    </AppDialog>}
  </div>;
}
