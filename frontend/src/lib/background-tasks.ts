"use client";

import { useEffect } from "react";
import { api,ApiError } from "@/lib/api";

export type BackgroundTask = {
  id: string; kind: string; module: string; title: string; status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  attempts: number; createdAt: string; startedAt: string | null; finishedAt: string | null;
  errorCode: string | null; resultPath: string | null; resourceId: string | null;
  completionToken: string; unread: boolean; estimateMinSeconds: number; estimateMaxSeconds: number;
};
export type TaskSummary = { unreadByModule: Record<string, number>; activeCount: number };
export async function readBackgroundTask(task: BackgroundTask) {
  await api<void>(`/api/background-tasks/${task.kind}/${task.id}/read`, {
    method: "POST", body: JSON.stringify({ completionToken: task.completionToken }),
  });
  window.dispatchEvent(new Event("ardor:background-refresh"));
}

// A specific loaded report is acknowledgment; merely visiting a module is not.
export function useReadVisibleResult(kind: string,resourceId: string | null | undefined,ready: boolean,version: string | number | undefined = "",taskId?: string | null) {
  useEffect(() => {
    if (!resourceId || !ready) return;
    let active=true;
    let checking=false;
    let timer: ReturnType<typeof setTimeout> | null=null;
    const query = new URLSearchParams({kind,resourceId,limit:"1"});
    const check = async () => {
      if (!active || document.hidden || checking) return;
      checking=true;
      let retry=false;
      try {
        const tasks=taskId ? [await api<BackgroundTask>(`/api/background-tasks/${kind}/${taskId}`)]
          : await api<BackgroundTask[]>(`/api/background-tasks?${query}`);
        const task=tasks[0];
        if (!active || document.hidden) return;
        if (task && (task.status==="COMPLETED" || task.status==="FAILED") && task.resourceId===resourceId && task.unread) await readBackgroundTask(task);
        else retry=task?.status==="QUEUED" || task?.status==="RUNNING";
      } catch(reason) { retry=!(reason instanceof ApiError && reason.status===404); }
      finally {
        checking=false;
        if (active && retry && !document.hidden) timer=setTimeout(()=>void check(),3000);
      }
    };
    const wake=()=>{ if(timer) clearTimeout(timer); void check(); };
    wake(); document.addEventListener("visibilitychange",wake);
    return () => { active=false; if(timer) clearTimeout(timer); document.removeEventListener("visibilitychange",wake); };
  },[kind,resourceId,ready,version,taskId]);
}

/**
 * Acknowledges every finished task of this resource across the given kinds.
 * One page can produce several tasks (creation plus each evaluation attempt), and
 * the module badge counts all of them, so reading a single result leaves the badge lit.
 */
export function useAcknowledgeResourceTasks(kinds: string[], resourceId: string | null | undefined, ready: boolean, version: string | number = "") {
  const kindsKey = kinds.join(",");
  useEffect(() => {
    if (!resourceId || !ready) return;
    let active = true;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const check = async () => {
      if (!active || document.hidden) return;
      let pending = false;
      for (const kind of kindsKey.split(",")) {
        try {
          const tasks: BackgroundTask[] = await api<BackgroundTask[]>(`/api/background-tasks?kind=${encodeURIComponent(kind)}&resourceId=${encodeURIComponent(resourceId)}&limit=20`);
          if (!active || document.hidden) return;
          for (const task of tasks) {
            if (task.resourceId !== resourceId) continue;
            if ((task.status === "COMPLETED" || task.status === "FAILED") && task.unread) await readBackgroundTask(task);
            else if (task.status === "QUEUED" || task.status === "RUNNING") pending = true;
          }
        } catch { /* Badges are informational; never block the page. */ }
      }
      if (active && pending && !document.hidden) timer = setTimeout(() => void check(), 3000);
    };
    const wake = () => { if (timer) clearTimeout(timer); void check(); };
    void check();
    document.addEventListener("visibilitychange", wake);
    return () => { active = false; if (timer) clearTimeout(timer); document.removeEventListener("visibilitychange", wake); };
  }, [kindsKey, resourceId, ready, version]);
}
