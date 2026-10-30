"use client";

import { useId } from "react";

import { cn } from "@/lib/utils";

/**
 * The four supplied petal paths, used unchanged by every mark and app icon.
 * The favicon cut only tightens the viewBox and trades the radial glow for
 * stronger stops so 16–48 px rasters keep their contrast.
 */
const PETALS = {
  top: "M286 286 C225 274 174 247 173 192 C172 154 196 124 237 124 C280 122 308 157 305 204 C303 233 291 274 286 286Z",
  pink: "M286 286 C306 228 346 188 384 193 C418 195 436 214 423 244 C406 284 353 314 286 286Z",
  lilac: "M286 286 C339 297 381 321 394 354 C412 391 385 412 359 404 C311 395 289 352 286 286Z",
  left: "M286 286 C264 337 230 391 187 402 C150 412 123 389 133 352 C151 298 222 273 286 286Z",
} as const;

function Defs({ uid }: { uid: string }) {
  return (
    <defs>
      <linearGradient id={`ardor-top-${uid}`} x1=".16" y1=".8" x2=".94" y2=".24">
        <stop stopColor="#4aa3ff" />
        <stop offset=".55" stopColor="#0b78ff" />
        <stop offset="1" stopColor="#0062f0" />
      </linearGradient>
      <linearGradient id={`ardor-pink-${uid}`} x1="0" y1=".9" x2=".9" y2=".05">
        <stop stopColor="#4aa3ff" />
        <stop offset=".55" stopColor="#9fc0ff" />
        <stop offset=".77" stopColor="#f3c7ff" />
        <stop offset="1" stopColor="#ffc9ec" />
      </linearGradient>
      <linearGradient id={`ardor-lilac-${uid}`} x1="0" y1=".15" x2=".95" y2=".9">
        <stop stopColor="#7fb2ff" />
        <stop offset=".5" stopColor="#c9d8ff" />
        <stop offset=".78" stopColor="#dfd0ff" />
        <stop offset="1" stopColor="#e8d4ff" />
      </linearGradient>
      <linearGradient id={`ardor-left-${uid}`} x1=".1" y1=".2" x2=".76" y2=".88">
        <stop stopColor="#005df0" />
        <stop offset=".24" stopColor="#1f86ff" />
        <stop offset=".62" stopColor="#4ea8ff" />
        <stop offset="1" stopColor="#73b8ff" />
      </linearGradient>
    </defs>
  );
}

function Petals({ uid }: { uid: string }) {
  return (
    <g>
      <path d={PETALS.top} fill={`url(#ardor-top-${uid})`} />
      <path d={PETALS.pink} fill={`url(#ardor-pink-${uid})`} />
      <path d={PETALS.lilac} fill={`url(#ardor-lilac-${uid})`} />
      <path d={PETALS.left} fill={`url(#ardor-left-${uid})`} />
    </g>
  );
}

export function ArdorMark({ className }: { className?: string }) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  return (
    <svg aria-hidden data-brand-mark="ardor" viewBox="109.5 97 340 340" className={cn("shrink-0", className)}>
      <Defs uid={uid} />
      <Petals uid={uid} />
    </svg>
  );
}

export function ArdorAppIcon({ className }: { className?: string }) {
  const uid = useId().replace(/[^a-zA-Z0-9_-]/g, "");
  return (
    <svg aria-hidden data-brand-mark="ardor" data-brand-icon="app" viewBox="79 66 396 396" className={cn("shrink-0", className)}>
      <Defs uid={uid} />
      <defs>
        <linearGradient id={`ardor-tile-${uid}`} x2=".8" y2="1">
          <stop stopColor="#ffffff" />
          <stop offset="1" stopColor="#eef4ff" />
        </linearGradient>
      </defs>
      <rect x="79" y="66" width="396" height="396" rx="110" fill={`url(#ardor-tile-${uid})`} />
      <g transform="translate(279.5 267) scale(1.06) translate(-279.5 -267)">
        <Petals uid={uid} />
      </g>
    </svg>
  );
}
