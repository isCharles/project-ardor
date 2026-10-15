export type AgentFeature = { key: string; id: string; label: string; englishLabel: string; description: string };

export function featureHref(key?: string) {
  return ({ LEARNING: "/app/learning", CARDS: "/app/cards", RESUMES: "/app/resumes", INTERVIEWS: "/app/interviews", RECAPS: "/app/recaps", KNOWLEDGE: "/app/knowledge", CALENDAR: "/app/calendar", APPLICATIONS: "/app/applications", MEMORY: "/app" } as Record<string, string>)[key ?? ""] ?? "/app";
}

const creationPrompts = {
  LEARNING: ["I'd like to learn…", "我想要学习…"],
  CARDS: ["Help me prepare flashcards about…", "帮我整理关于…的记忆卡"],
  RESUMES: ["Help me analyze my resume for…", "帮我针对…分析简历"],
  INTERVIEWS: ["Prepare a mock interview for…", "帮我准备一场…岗位的模拟面试"],
  RECAPS: ["Organize these interview notes and identify what I should improve:\n", "帮我整理下面的面试内容，找出薄弱点：\n"],
  KNOWLEDGE: ["Find and add reliable learning material about…", "帮我查找并收录关于…的可靠学习资料"],
  CALENDAR: ["Add this to my calendar:\n", "帮我把下面这件事安排到日历：\n"],
  APPLICATIONS: ["Help me record today's applications…", "帮我记录今天的投递情况…"],
  MEMORY: ["I'd like you to remember…", "我希望你记住…"],
} as const;

export function agentCreationHref(feature: keyof typeof creationPrompts, locale: string, customPrefill?: string) {
  const prefill = customPrefill ?? creationPrompts[feature][locale === "en" ? 0 : 1];
  return `/app?new=1&feature=${feature}&prefill=${encodeURIComponent(prefill)}`;
}
