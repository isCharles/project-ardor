"use client";

import { useState } from "react";
import { useLocale } from "@/lib/locale";

export type InterviewReference = {
  id: string; company: string; targetRole: string | null;
  rounds?: { id: string; roundNumber: number; stage: string }[];
};

/** Round choice is explicit. Switching the parent never carries a stale child ID. */
export function InterviewContextFields({ opportunities }: { opportunities: InterviewReference[] }) {
  const { t } = useLocale();
  const [processId, setProcessId] = useState(opportunities[0]?.id ?? "");
  const selected = opportunities.find(item => item.id === processId);
  return <>
    <label className="block text-sm font-medium">{t("Interview", "所属面试")}
      <select name="jobOpportunityId" required value={processId} onChange={event => setProcessId(event.target.value)} className="field mt-2">
        {opportunities.map(item => <option key={item.id} value={item.id}>{item.company}{item.targetRole ? ` · ${item.targetRole}` : ""}</option>)}
      </select>
    </label>
    <label className="block text-sm font-medium">{t("Interview round", "所属轮次")}
      <select key={processId} name="interviewRoundId" defaultValue="" className="field mt-2">
        <option value="">{t("Not specified · keep at interview level", "暂不指定 · 保留在面试层级")}</option>
        {(selected?.rounds ?? []).map(round => <option key={round.id} value={round.id}>{t(`Round ${round.roundNumber}`, `第 ${round.roundNumber} 轮`)} · {round.stage}</option>)}
      </select>
    </label>
  </>;
}

export function InterviewRecordContext({ processId, roundId, opportunities }: {
  processId: string | null; roundId: string | null | undefined; opportunities: InterviewReference[];
}) {
  const { t } = useLocale();
  const interview = opportunities.find(item => item.id === processId);
  const round = interview?.rounds?.find(item => item.id === roundId);
  return <p className="mb-5 text-xs text-stone-500" data-interview-record-context>
    {interview?.company ?? t("Interview record", "面试记录")}{" · "}
    {round ? t(`Round ${round.roundNumber}`, `第 ${round.roundNumber} 轮`) : t("Round not specified", "轮次未指定")}
  </p>;
}
