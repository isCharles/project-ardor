import { api } from "@/lib/api";

export type BackgroundModule = "resumes" | "recaps" | "knowledge";

export function backgroundNotificationKey(userId: string, state: "pending" | "ready", moduleName: BackgroundModule) {
  return `ardor:background-${state}:${userId}:${moduleName}`;
}

export function markBackgroundPending(moduleName: BackgroundModule) {
  void api<{ id: string }>("/api/auth/me")
    .then((user) => {
      window.localStorage.setItem(backgroundNotificationKey(user.id, "pending", moduleName), "1");
      window.dispatchEvent(new Event("ardor:background-refresh"));
    })
    .catch(() => undefined);
  // Badge bookkeeping is best-effort and must never delay a successful job action.
  window.dispatchEvent(new Event("ardor:background-refresh"));
}
