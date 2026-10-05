import { api } from "@/lib/api";

export type BackgroundModule = "resumes" | "recaps" | "knowledge";

export function backgroundNotificationKey(userId: string, state: "pending" | "ready", moduleName: BackgroundModule) {
  return `ardor:background-${state}:${userId}:${moduleName}`;
}

export async function markBackgroundPending(moduleName: BackgroundModule) {
  try {
    const user = await api<{ id: string }>("/api/auth/me");
    window.localStorage.setItem(backgroundNotificationKey(user.id, "pending", moduleName), "1");
  } catch {
    // Badges are informational. The shared rail also observes running jobs.
  }
}
