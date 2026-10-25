export type LearningAnswer = { exerciseId?: string; question: string; answer: string; type?: string; choiceCorrect?: boolean };
export type QuestionFeedback = { exerciseId?: string; question?: string; feedback: string; referenceAnswer?: string };
export type LearningReviewSummary = { feedback?: string; strengths?: string[]; gaps?: string[]; nextFocus?: string };

/** A model's paraphrase or array order is not a question identity. Never hide unassigned feedback. */
export function associateLearningFeedback(items: LearningAnswer[], feedback: unknown = []) {
  const perQuestion: QuestionFeedback[][] = items.map(() => []);
  const unmatched: QuestionFeedback[] = [];
  const rows = Array.isArray(feedback) ? feedback : feedback == null ? [] : [feedback];
  for (const value of rows) {
    const row = typeof value === "string" ? { feedback: value } : value;
    if (!row || typeof row !== "object" || typeof row.feedback !== "string") continue;
    const entry: QuestionFeedback = { feedback: row.feedback,
      ...(typeof row.referenceAnswer === "string" ? { referenceAnswer: row.referenceAnswer } : {}),
      ...(typeof row.exerciseId === "string" ? { exerciseId: row.exerciseId } : {}),
      ...(typeof row.question === "string" ? { question: row.question } : {}) };
    const matches = items.flatMap((item, index) => {
      const same = entry.exerciseId ? item.exerciseId === entry.exerciseId
        : typeof entry.question === "string" && item.question.trim() === entry.question.trim();
      return same ? [index] : [];
    });
    if (matches.length === 1) perQuestion[matches[0]].push(entry);
    else unmatched.push(entry);
  }
  return { perQuestion, unmatched };
}
