"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "@/lib/api";

export type AiJobType = "LEARNING_CREATE" | "LEARNING_EVALUATE" | "REPLAY_EVALUATE" | "INTERVIEW_EVALUATE";
export type AiJob = {
  id: string; type: AiJobType; resourceId: string | null;
  status: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED";
  attempts: number; resultPath: string | null; errorCode: string | null; errorMessage: string | null;
  createdAt: string; startedAt: string | null; finishedAt: string | null;
  estimateMinSeconds: number; estimateMaxSeconds: number;
};
export function jobPending(job: AiJob | null) { return job?.status === "QUEUED" || job?.status === "RUNNING"; }

export function useAiJob(type: AiJobType, resourceId: string | null, enabled = true) {
  const [job, setJob] = useState<AiJob | null>(null);
  const [pollFailed, setPollFailed] = useState(false);
  const pending = jobPending(job) || pollFailed;
  useEffect(() => {
    if (!enabled) return;
    let active = true;
    const query = new URLSearchParams({ type });
    if (resourceId) query.set("resourceId", resourceId);
    const refresh = () => {
      if (document.visibilityState === "hidden") return;
      void api<AiJob[]>(`/api/ai-jobs?${query}`).then((items) => {
        if (active) { setJob(items[0] ?? null); setPollFailed(false); }
      }).catch(() => { if (active) setPollFailed(true); });
    };
    refresh();
    const timer = pending ? window.setInterval(refresh, 3000) : null;
    window.addEventListener("focus", refresh);
    return () => { active = false; if (timer) window.clearInterval(timer); window.removeEventListener("focus", refresh); };
  }, [type, resourceId, enabled, pending]);
  const retry = useCallback(async () => {
    if (!job) return;
    setJob(await api<AiJob>(`/api/ai-jobs/${job.id}/retry`, { method: "POST" }));
    setPollFailed(false);
  }, [job]);
  // A route change can briefly leave the old result in state until its first poll.
  const visibleJob = job?.type === type && (!resourceId || job.resourceId === resourceId) && enabled ? job : null;
  return { job: visibleJob, setJob, retry, pollFailed };
}
