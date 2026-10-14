export type AgentFeature = { key: string; id: string; label: string; englishLabel: string; description: string };

export function featureHref(key?: string) {
  return ({ LEARNING: "/app/learning", CARDS: "/app/cards", RESUMES: "/app/resumes", INTERVIEWS: "/app/interviews", RECAPS: "/app/recaps", KNOWLEDGE: "/app/knowledge", CALENDAR: "/app/calendar", APPLICATIONS: "/app/applications", MEMORY: "/app" } as Record<string, string>)[key ?? ""] ?? "/app";
}

export function agentCreationHref(feature: "LEARNING" | "CARDS", locale: string) {
  const prefill = feature === "LEARNING"
    ? locale === "en" ? "I'd like to learn…" : "我想要学习…"
    : locale === "en" ? "Help me prepare flashcards about…" : "帮我整理关于…的记忆卡";
  return `/app?new=1&feature=${feature}&prefill=${encodeURIComponent(prefill)}`;
}
