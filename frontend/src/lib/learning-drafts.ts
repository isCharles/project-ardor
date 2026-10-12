type DraftPlan = { id: string; attemptCount: number; exercises: Array<{ question: string; type?: string; options?: string[] }> };

function key(userId: string, plan: DraftPlan) {
  return `ardor:learning-answers:${userId}:${plan.id}:${plan.attemptCount}`;
}

function fingerprint(plan: DraftPlan) {
  return JSON.stringify(plan.exercises.map(({ question, type, options }) => ({ question, type, options })));
}

export function readLearningDraft(userId: string | null, plan: DraftPlan): string[] | null {
  if (!userId) return null;
  try {
    const saved = JSON.parse(window.sessionStorage.getItem(key(userId, plan)) ?? "null");
    return saved?.fingerprint === fingerprint(plan) && Array.isArray(saved.answers)
      && saved.answers.length === plan.exercises.length && saved.answers.every((answer: unknown) => typeof answer === "string")
      ? saved.answers : null;
  } catch { return null; }
}

export function saveLearningDraft(userId: string | null, plan: DraftPlan, answers: string[]) {
  if (!userId) return;
  try { window.sessionStorage.setItem(key(userId, plan), JSON.stringify({ fingerprint: fingerprint(plan), answers })); }
  catch { /* Keep editing available when browser storage is unavailable. */ }
}

export function clearLearningDraft(userId: string | null, plan: DraftPlan) {
  if (!userId) return;
  try { window.sessionStorage.removeItem(key(userId, plan)); }
  catch { /* The round key prevents an old draft from affecting a new round. */ }
}
