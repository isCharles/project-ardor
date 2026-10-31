/**
 * Orders a run's recorded steps the way the model worked: a short spoken line,
 * then the tools it announced, then the next line. The model-wait accounting row
 * is not a tool and is rendered as part of the run header instead.
 */

export type TimelineStep = {
  key?: string;
  label: string;
  status?: string | null;
  elapsedMs?: number;
  done?: boolean;
};

export type RunSegment<T extends TimelineStep = TimelineStep> =
  | { kind: "say"; key: string; text: string }
  | { kind: "tools"; key: string; steps: T[]; running: boolean };

const MODEL_WAIT_LABEL = /^模型输出与上游等待/;

function isModelWait(step: TimelineStep, index: number) {
  if (step.status === "MODEL_WAIT" || step.key === "status") return true;
  // Traces saved before MODEL_WAIT existed carry the same fixed label as COMPLETED.
  return index === 0 && MODEL_WAIT_LABEL.test(step.label);
}

export function runSegments<T extends TimelineStep>(steps: T[] | null | undefined): RunSegment<T>[] {
  const segments: RunSegment<T>[] = [];
  let tools: T[] = [];
  let toolsStart = 0;
  const flush = () => {
    if (!tools.length) return;
    segments.push({
      kind: "tools",
      key: `tools-${toolsStart}`,
      steps: tools,
      running: tools.some(step => step.done === false),
    });
    tools = [];
  };
  (steps ?? []).forEach((step, index) => {
    if (isModelWait(step, index)) return;
    if (step.status === "COMMENTARY") {
      flush();
      const text = String(step.label ?? "").trim();
      if (text) segments.push({ kind: "say", key: `say-${index}`, text });
      return;
    }
    if (!tools.length) toolsStart = index;
    tools.push(step);
  });
  flush();
  return segments;
}
