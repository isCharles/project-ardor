"use client";

export function realtimeUrl(path: string) {
  const configured = process.env.NEXT_PUBLIC_ARDOR_WS_URL?.replace(/\/$/, "");
  if (configured) return `${configured}${path}`;
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
  const port = window.location.port === "3000" ? ":8080" : window.location.port ? `:${window.location.port}` : "";
  return `${protocol}//${window.location.hostname}${port}${path}`;
}
