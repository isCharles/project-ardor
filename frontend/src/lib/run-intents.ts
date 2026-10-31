/**
 * One quiet line per intermediate model call: what Ardor said it was about to do.
 * The full commentary stays in the run trace; this keeps the answer readable.
 */

export type CommentaryStep = { label: string; status?: string | null };

const SENTENCE_END = /[。．.!?！？]/;
const MIN_SENTENCE = 8;

export function stripMarkdown(text: string): string {
  return String(text ?? "")
    .replace(/```[\s\S]*?```/g, " ")
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/[*_`~#]+/g, "")
    .replace(/^\s*(?:[-•]|\d+\.)\s+/gm, "")
    .replace(/\s+/g, " ")
    .trim();
}

export function oneLine(text: string, limit = 80): string {
  const clean = stripMarkdown(text);
  if (!clean) return "";
  let end = -1;
  for (let index = 0; index < clean.length; index += 1) {
    if (SENTENCE_END.test(clean[index])) { end = index + 1; break; }
  }
  const first = end > 0 ? clean.slice(0, end).trim() : clean;
  // "好的。" alone says nothing about the next step; fall back to the full text.
  const sentence = first.length >= MIN_SENTENCE ? first : clean;
  const characters = Array.from(sentence);
  return characters.length > limit ? `${characters.slice(0, Math.max(1, limit - 1)).join("")}…` : sentence;
}

export function runIntents(steps: CommentaryStep[] | null | undefined): string[] {
  return (steps ?? [])
    .filter(step => step?.status === "COMMENTARY")
    .map(step => oneLine(step.label))
    .filter((line, index, lines): line is string => Boolean(line) && lines.indexOf(line) === index);
}

/** Trace steps without the commentary: those lines render above the answer instead. */
export function toolSteps<T extends CommentaryStep>(steps: T[] | null | undefined): T[] {
  return (steps ?? []).filter(step => step?.status !== "COMMENTARY");
}
