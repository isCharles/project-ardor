"use client";

import { useEffect } from "react";
import { api } from "@/lib/api";

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
export function useReadVisibleResult(kind: string,resourceId: string | null | undefined,ready: boolean,version: string | number | undefined = "") {
  useEffect(() => {
    if (!resourceId || !ready) return;
    let active=true;
    const query = new URLSearchParams({kind,resourceId,limit:"1"});
    const check = () => {
      if (!active || document.hidden) return;
      void api<BackgroundTask[]>(`/api/background-tasks?${query}`).then(async (tasks) => {
        const task=tasks[0];
        if (active && !document.hidden && task?.status==="COMPLETED" && task.unread) await readBackgroundTask(task);
      }).catch(() => undefined);
    };
    check(); document.addEventListener("visibilitychange",check);
    return () => { active=false; document.removeEventListener("visibilitychange",check); };
  },[kind,resourceId,ready,version]);
}
