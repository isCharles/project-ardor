"use client";

import { useEffect, useRef, useState } from "react";
import { AppDialog } from "@/components/ui/app-dialog";
import { useLocale } from "@/lib/locale";

const clamp = (value: number) => Math.max(-100, Math.min(100, value));

export function AvatarCropEditor({ image, error, onSave, onClose }: {
  image: ImageBitmap; error: string; onSave: (file: File) => Promise<boolean>; onClose: () => void;
}) {
  const { t } = useLocale();
  const canvas = useRef<HTMLCanvasElement>(null);
  const drag = useRef<{ x: number; y: number; horizontal: number; vertical: number } | null>(null);
  const [zoom, setZoom] = useState(1);
  const [horizontal, setHorizontal] = useState(0);
  const [vertical, setVertical] = useState(0);
  const [saving, setSaving] = useState(false);
  const [localError, setLocalError] = useState("");
  const side = Math.min(image.width, image.height) / zoom;
  useEffect(() => {
    const context = canvas.current?.getContext("2d");
    if (!context) return;
    context.clearRect(0, 0, 256, 256);
    context.imageSmoothingQuality = "high";
    context.drawImage(image, (image.width - side) * (horizontal + 100) / 200,
      (image.height - side) * (vertical + 100) / 200, side, side, 0, 0, 256, 256);
  }, [image, side, horizontal, vertical]);

  async function save() {
    if (saving) return;
    setSaving(true); setLocalError("");
    try {
      const preview = canvas.current;
      if (!preview) throw Error(t("Could not prepare image", "无法处理图片"));
      const blob = await new Promise<Blob>((resolve, reject) => preview.toBlob(value =>
        value ? resolve(value) : reject(Error(t("Could not prepare image", "无法处理图片"))), "image/png"));
      if (await onSave(new File([blob], "avatar.png", { type: "image/png" }))) onClose();
    } catch (reason) { setLocalError(reason instanceof Error ? reason.message : t("Could not prepare image", "无法处理图片")); }
    finally { setSaving(false); }
  }
  return <AppDialog title={t("Adjust avatar", "调整头像")} onClose={() => { if (!saving) onClose(); }}>
    <div className="flex flex-col items-center gap-5">
      <canvas ref={canvas} width={256} height={256} aria-label={t("Avatar crop preview", "头像裁剪预览")}
        className="aspect-square w-full max-w-64 touch-none cursor-grab rounded-2xl bg-stone-100 active:cursor-grabbing"
        onPointerDown={event => {
          if (saving) return;
          event.currentTarget.setPointerCapture(event.pointerId);
          drag.current = { x: event.clientX, y: event.clientY, horizontal, vertical };
        }} onPointerMove={event => {
          const start = drag.current;
          if (!start || saving) return;
          const size = event.currentTarget.getBoundingClientRect().width;
          if (image.width > side) setHorizontal(clamp(start.horizontal - (event.clientX - start.x) / size * side / (image.width - side) * 200));
          if (image.height > side) setVertical(clamp(start.vertical - (event.clientY - start.y) / size * side / (image.height - side) * 200));
        }} onPointerUp={() => { drag.current = null; }} onPointerCancel={() => { drag.current = null; }} />
      <div className="w-full space-y-4">
        <label className="flex items-center gap-3 text-xs text-stone-500"><span className="w-20 shrink-0">{t("Zoom", "缩放")}</span>
          <input aria-label={t("Avatar zoom", "头像缩放")} type="range" min="1" max="4" step="0.01" value={zoom} disabled={saving} onChange={event => setZoom(Number(event.target.value))} className="min-w-0 flex-1 accent-violet-600" /></label>
        <label className="flex items-center gap-3 text-xs text-stone-500"><span className="w-20 shrink-0">{t("Horizontal", "水平位置")}</span>
          <input aria-label={t("Avatar horizontal position", "头像水平位置")} type="range" min="-100" max="100" value={horizontal} disabled={saving || image.width <= side} onChange={event => setHorizontal(Number(event.target.value))} className="min-w-0 flex-1 accent-violet-600" /></label>
        <label className="flex items-center gap-3 text-xs text-stone-500"><span className="w-20 shrink-0">{t("Vertical", "垂直位置")}</span>
          <input aria-label={t("Avatar vertical position", "头像垂直位置")} type="range" min="-100" max="100" value={vertical} disabled={saving || image.height <= side} onChange={event => setVertical(Number(event.target.value))} className="min-w-0 flex-1 accent-violet-600" /></label>
      </div>
    </div>
    {(localError || error) && <p role="alert" className="mt-4 text-sm text-rose-600">{localError || error}</p>}
    <div className="mt-6 flex justify-end gap-2">
      <button type="button" disabled={saving} onClick={onClose} className="rounded-full px-4 py-2 text-sm disabled:opacity-50">{t("Cancel", "取消")}</button>
      <button type="button" disabled={saving} onClick={() => void save()} className="rounded-full bg-stone-950 px-4 py-2 text-sm text-white disabled:opacity-50">{saving ? t("Saving…", "正在保存…") : t("Save avatar", "保存头像")}</button>
    </div>
  </AppDialog>;
}
