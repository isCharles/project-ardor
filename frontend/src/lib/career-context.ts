import { api } from "@/lib/api";

/* What Ardor currently knows about the user's job search.

   The agent's whole premise is that it holds this state across sessions,
   so the interface has to be able to show it. Today that means one
   parallel fan-out over the existing collection endpoints; the right
   long-term shape is a single GET /api/agent/context that aggregates
   server-side. Kept behind this one function so that swap is local. */

export type ContextResume = {
  id: string;
  originalFilename: string;
  parseStatus: "PENDING" | "PARSED" | "FAILED";
  analysisId: string | null;
  analysisStatus: "QUEUED" | "RUNNING" | "COMPLETED" | "FAILED" | null;
};
export type ContextInterview = {
  id: string;
  targetRole: string;
  targetCompany: string | null;
  status: "CREATED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
};
export type ContextRecap = {
  id: string;
  title: string;
  questions: { performance: "STRONG" | "MIXED" | "WEAK" | "UNKNOWN" }[];
};
export type ContextCard = { id: string; nextReviewAt: string; status: string };
export type ContextTask = {
  id: string;
  title: string;
  dueAt: string | null;
  status: string;
  taskKind: string;
};

export type CareerContext = {
  resumes: ContextResume[];
  interviews: ContextInterview[];
  recaps: ContextRecap[];
  cards: ContextCard[];
  tasks: ContextTask[];
};

export const emptyCareerContext: CareerContext = {
  resumes: [], interviews: [], recaps: [], cards: [], tasks: [],
};

/** Never lets one slow or failing collection blank the whole surface. */
export async function loadCareerContext(): Promise<CareerContext> {
  const [resumes, interviews, recaps, cards, tasks] = await Promise.all([
    api<ContextResume[]>("/api/resumes").catch(() => []),
    api<ContextInterview[]>("/api/interviews").catch(() => []),
    api<ContextRecap[]>("/api/interview-recaps").catch(() => []),
    api<ContextCard[]>("/api/memory-cards").catch(() => []),
    api<ContextTask[]>("/api/calendar/tasks").catch(() => []),
  ]);
  return { resumes, interviews, recaps, cards, tasks };
}

/* --- Derived facts. One place, so the empty state, the header and the
       suggestions can never disagree about what Ardor knows. --- */

export function latestResume(context: CareerContext) {
  return context.resumes[0] ?? null;
}

export function dueCards(context: CareerContext) {
  const now = Date.now();
  return context.cards.filter(
    (card) => card.status !== "SUSPENDED" && new Date(card.nextReviewAt).getTime() <= now,
  );
}

export function openTasks(context: CareerContext) {
  return context.tasks.filter((task) => task.status === "TODO" || task.status === "IN_PROGRESS");
}

export function nextTask(context: CareerContext) {
  const now = Date.now();
  return (
    openTasks(context)
      .filter((task) => task.dueAt && new Date(task.dueAt).getTime() >= now)
      .sort((a, b) => new Date(a.dueAt!).getTime() - new Date(b.dueAt!).getTime())[0] ?? null
  );
}

export function activeInterview(context: CareerContext) {
  return context.interviews.find((item) => item.status === "IN_PROGRESS" || item.status === "CREATED") ?? null;
}

export function weakPoints(context: CareerContext) {
  return context.recaps.reduce(
    (total, recap) =>
      total + recap.questions.filter((q) => q.performance === "WEAK" || q.performance === "MIXED").length,
    0,
  );
}

/** "今天 20:00" reads as a deadline; "9/2 20:00" reads as a database row. */
export function formatWhen(iso: string) {
  const date = new Date(iso);
  const startOf = (value: Date) => new Date(value.getFullYear(), value.getMonth(), value.getDate()).getTime();
  const days = Math.round((startOf(date) - startOf(new Date())) / 86_400_000);
  const time = date.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" });
  if (days === 0) return `今天 ${time}`;
  if (days === 1) return `明天 ${time}`;
  if (days === 2) return `后天 ${time}`;
  if (days > 2 && days < 7) return `${days} 天后 ${time}`;
  return `${date.toLocaleDateString("zh-CN", { month: "numeric", day: "numeric" })} ${time}`;
}

export function memoryLines(content: string) {
  return content
    .split("\n")
    .map((line) => line.replace(/^[-*·•]\s*/, "").trim())
    .filter(Boolean);
}

/** Openers built from real state, so they are never generic filler. */
export function suggestions(context: CareerContext, memory: string): string[] {
  const out: string[] = [];
  const resume = latestResume(context);
  const due = dueCards(context).length;
  const upcoming = nextTask(context);
  const running = activeInterview(context);
  const weak = weakPoints(context);

  if (running) {
    out.push(`继续 ${running.targetCompany ? `${running.targetCompany} ` : ""}${running.targetRole} 那场模拟面试`);
  }
  if (upcoming) {
    out.push(`${formatWhen(upcoming.dueAt!)} 的「${upcoming.title}」，我该重点准备什么`);
  }
  if (resume && !resume.analysisId) {
    out.push(`分析 ${resume.originalFilename}，指出最该改的三处`);
  } else if (resume) {
    out.push(`对照我的简历，帮我准备一场后端面试`);
  }
  if (weak > 0) {
    out.push(`按面经里的 ${weak} 个薄弱点，安排这周的复习计划`);
  }
  if (due > 0) {
    out.push(`用今天到期的 ${due} 张记忆卡考我`);
  }

  if (out.length === 0) {
    out.push(
      "我想准备字节的 Java 后端面试",
      "先看看我的简历，再告诉我缺什么",
      memory ? "根据你记得的信息，帮我定这个月的求职计划" : "帮我把求职目标拆成这个月的计划",
    );
  }
  return out.slice(0, 4);
}
