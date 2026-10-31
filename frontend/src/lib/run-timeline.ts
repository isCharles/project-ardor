/**
 * The run as one ordered process: the short lines the model said and the tools
 * they announced, in recorded order. The model-wait accounting row is not a
 * tool and is rendered as part of the summary instead.
 */

export type TimelineStep = {
  key?: string;
  label: string;
  status?: string | null;
  elapsedMs?: number;
  done?: boolean;
};

export type RunRow<T extends TimelineStep = TimelineStep> =
  | { kind: "say"; key: string; text: string }
  | { kind: "tool"; key: string; step: T };

export type RunTimeline<T extends TimelineStep = TimelineStep> = {
  rows: RunRow<T>[];
  tools: T[];
  running: boolean;
};

const MODEL_WAIT_LABEL = /^模型输出与上游等待/;

function isModelWait(step: TimelineStep, index: number) {
  if (step.status === "MODEL_WAIT" || step.key === "status") return true;
  // Traces saved before MODEL_WAIT existed carry the same fixed label as COMPLETED.
  return index === 0 && MODEL_WAIT_LABEL.test(step.label);
}

export function runTimeline<T extends TimelineStep>(steps: T[] | null | undefined): RunTimeline<T> {
  const rows: RunRow<T>[] = [];
  const tools: T[] = [];
  (steps ?? []).forEach((step, index) => {
    if (isModelWait(step, index)) return;
    if (step.status === "COMMENTARY") {
      const text = String(step.label ?? "").trim();
      if (text) rows.push({ kind: "say", key: `say-${index}`, text });
      return;
    }
    tools.push(step);
    rows.push({ kind: "tool", key: `tool-${index}`, step });
  });
  return { rows, tools, running: tools.some(step => step.done === false) };
}
