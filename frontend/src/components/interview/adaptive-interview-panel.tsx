"use client";

import { ExternalLink, Loader2, ShieldCheck, Sparkles, Trash2 } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type LeetCode = { id: number; slug: string; titleEn: string; titleZh: string };
type Question = {
  id: string;
  sequenceNumber: number;
  questionText: string;
  questionType: string;
  competency: string | null;
  knowledgePoint: string | null;
  difficulty: number | null;
  origin: string;
  leetcode: LeetCode | null;
};
type Evidence = { knowledgePoint: string; difficulty: number; score?: number; detail?: string };
type Competency = {
  skill: string;
  jdPriority: number;
  estimatedLevel: number | null;
  confidence: number | null;
  evidenceCount: number;
  state: "VERIFIED" | "UNVERIFIED" | "GAP_EXPOSED";
  strengths: Evidence[];
  knowledgeGaps: Evidence[];
};
type Session = {
  sessionId: string;
  status: "CREATED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
  targetRole: string;
  targetCompany: string | null;
  seniority: string | null;
  durationMinutes: number | null;
  deepDive: boolean;
  askedCount: number;
  answeredCount: number;
  readyToFinish: boolean;
  awaitingEvaluation: boolean;
  currentQuestion: Question | null;
  evaluationError: string | null;
  competencies: Competency[];
  lastDecision: { action: string; reason: string } | null;
};
type ListItem = { id: string; targetRole: string; targetCompany: string | null; status: Session["status"] };
type ResumeItem = { id: string; originalFilename: string; analysisId: string | null };
type Recommendation = {
  competency: string;
  knowledgePoint: string;
  severity: number;
  advice: string;
  evidence: string;
};
type Report = {
  overallScore: number;
  evaluation: {
    reportKind: string;
    jobFit: string;
    levelAssessment: string;
    performanceSummary: string;
    knowledgeBoundary: { competency: string; knowledgePoint: string; difficulty: number; detail: string }[];
    learningRecommendations: Recommendation[];
  };
  modelName: string;
};

export function AdaptiveInterviewPanel() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const [resumes, setResumes] = useState<ResumeItem[]>([]);
  const [sessions, setSessions] = useState<ListItem[]>([]);
  const [state, setState] = useState<Session | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [answerText, setAnswerText] = useState("");
  const [feedback, setFeedback] = useState<"" | "TOO_HARD" | "FITTING" | "TOO_EASY">("");
  const [selectedAnalysisId, setSelectedAnalysisId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  /** The lists are read on mount and again after every mutation, so the history never goes stale. */
  async function fetchLists() {
    const [resumeItems, sessionItems] = await Promise.all([
      api<ResumeItem[]>("/api/resumes"),
      api<ListItem[]>("/api/interviews/adaptive"),
    ]);
    return { resumeItems, sessionItems };
  }

  function applyLists({ resumeItems, sessionItems }: { resumeItems: ResumeItem[]; sessionItems: ListItem[] }) {
    setResumes(resumeItems.filter((item) => item.analysisId));
    setSessions(sessionItems);
    setSelectedAnalysisId((current) => current || resumeItems.find((item) => item.analysisId)?.analysisId || "");
    setError("");
  }

  async function loadLists() {
    try {
      applyLists(await fetchLists());
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not load interviews", "无法加载自适应面试"));
    }
  }

  useEffect(() => {
    let active = true;
    void fetchLists()
      .then((lists) => { if (active) applyLists(lists); })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : t("Could not load interviews", "无法加载自适应面试"));
      });
    return () => { active = false; };
  }, [router, t]);

  async function createInterview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const seniority = String(data.get("seniority") ?? "");
    setError("");
    setReport(null);
    setBusy(true);
    try {
      const created = await api<{ id: string }>("/api/interviews/adaptive", {
        method: "POST",
        body: JSON.stringify({
          resumeAnalysisId: (data.get("resumeAnalysisId") as string) || null,
          targetCompany: String(data.get("targetCompany") ?? ""),
          targetRole: String(data.get("targetRole") ?? ""),
          seniority,
          durationMinutes: data.get("durationMinutes") ? Number(data.get("durationMinutes")) : null,
          deepDive: seniority === "SENIOR" || seniority === "EXPERT",
          jobDescription: String(data.get("jobDescription") ?? ""),
        }),
      });
      setState(await api<Session>(`/api/interviews/adaptive/${created.id}`));
      setAnswerText("");
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not create interview", "创建自适应面试失败"));
    } finally {
      setBusy(false);
    }
  }

  async function openSession(session: ListItem) {
    setBusy(true);
    setError("");
    setReport(null);
    setAnswerText("");
    try {
      if (session.status === "COMPLETED") {
        setState(await api<Session>(`/api/interviews/adaptive/${session.id}`));
        setReport(await api<Report>(`/api/interviews/${session.id}/evaluation`));
      } else {
        setState(await api<Session>(`/api/interviews/adaptive/${session.id}`));
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not open interview", "无法打开面试"));
    } finally {
      setBusy(false);
    }
  }

  /** Reading the state is also how a half-finished turn recovers: the server scores what is pending. */
  async function refresh() {
    if (!state) return;
    setBusy(true);
    setError("");
    try {
      setState(await api<Session>(`/api/interviews/adaptive/${state.sessionId}`));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not continue", "无法继续"));
    } finally {
      setBusy(false);
    }
  }

  async function submitAnswer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!state?.currentQuestion) return;
    setBusy(true);
    setError("");
    try {
      setState(await api<Session>(`/api/interviews/adaptive/${state.sessionId}/answers`, {
        method: "POST",
        body: JSON.stringify({
          questionId: state.currentQuestion.id,
          answerText,
          durationSeconds: null,
          difficultyFeedback: feedback || null,
        }),
      }));
      setAnswerText("");
      setFeedback("");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not submit answer", "提交答案失败"));
    } finally {
      setBusy(false);
    }
  }

  async function finishInterview() {
    if (!state) return;
    setBusy(true);
    setError("");
    try {
      setReport(await api<Report>(`/api/interviews/adaptive/${state.sessionId}/finish`, { method: "POST" }));
      setState(await api<Session>(`/api/interviews/adaptive/${state.sessionId}`));
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not generate report", "生成报告失败"));
    } finally {
      setBusy(false);
    }
  }

  async function deleteInterview(session: ListItem) {
    if (!window.confirm(t(`Permanently delete “${session.targetRole}”?`, `永久删除“${session.targetRole}”？`))) return;
    setBusy(true);
    setError("");
    try {
      await api<void>(`/api/interviews/${session.id}`, { method: "DELETE" });
      if (state?.sessionId === session.id) {
        setState(null);
        setReport(null);
      }
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not delete interview", "删除失败"));
    } finally {
      setBusy(false);
    }
  }

  const difficultyLabels = ["Concepts", "Mechanisms", "Application", "Principles", "Scenarios"];
  const originLabels: Record<string, [string, string]> = {
    PLAN: ["Opening", "起始"],
    FOLLOW_UP: ["Follow-up", "追问"],
    CLARIFICATION: ["Clarify", "澄清"],
    ALTERNATE: ["New angle", "换题"],
  };
  const stateLabels: Record<Competency["state"], [string, string]> = {
    VERIFIED: ["Verified", "已验证"],
    GAP_EXPOSED: ["Gap found", "已暴露缺口"],
    UNVERIFIED: ["Not tested", "未考察"],
  };
  const question = state?.currentQuestion ?? null;

  return (
    <div className="mx-auto grid max-w-6xl gap-6 lg:grid-cols-[0.85fr_1.15fr]">
      <div className="space-y-6">
        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <h2 className="text-xl font-semibold">{t("Probe the boundary", "探边界")}</h2>
          <p className="mt-2 text-sm text-muted-foreground">
            {t("Paste a job description. Difficulty follows your answers.", "贴上岗位描述，难度跟着你的回答走。")}
          </p>
          <form className="mt-6 space-y-4" onSubmit={createInterview}>
            <label className="block text-sm font-medium">
              {t("Target role", "目标岗位")}
              <input className="field mt-2" name="targetRole" maxLength={160} required
                placeholder={t("e.g. Java backend engineer", "例如：Java 后端工程师")} />
            </label>
            <label className="block text-sm font-medium">
              {t("Company", "目标公司")}
              <input className="field mt-2" name="targetCompany" maxLength={160}
                placeholder={t("Optional", "可选")} />
            </label>
            <div className="grid gap-4 sm:grid-cols-2">
              <label className="block text-sm font-medium">
                {t("Level", "层级")}
                <select className="field mt-2" name="seniority" defaultValue="MID">
                  <option value="JUNIOR">{t("Junior", "初级")}</option>
                  <option value="MID">{t("Mid", "中级")}</option>
                  <option value="SENIOR">{t("Senior", "高级")}</option>
                  <option value="EXPERT">{t("Expert", "资深")}</option>
                </select>
              </label>
              <label className="block text-sm font-medium">
                {t("Minutes", "时长（分钟）")}
                <input className="field mt-2" name="durationMinutes" type="number" min={1} max={120} />
              </label>
            </div>
            <label className="block text-sm font-medium">
              {t("Resume analysis", "简历分析")}
              <select className="field mt-2" name="resumeAnalysisId" value={selectedAnalysisId}
                onChange={(event) => setSelectedAnalysisId(event.target.value)}>
                <option value="">{t("No resume", "不使用简历")}</option>
                {resumes.map((resume) => (
                  <option key={resume.id} value={resume.analysisId ?? ""}>{resume.originalFilename}</option>
                ))}
              </select>
            </label>
            <label className="block text-sm font-medium">
              {t("Job description", "岗位描述")}
              <textarea className="field mt-2 min-h-32" name="jobDescription" maxLength={20000}
                placeholder={t("Paste the requirements you want probed.", "贴入你想被考察的要求。")} />
            </label>
            <Button disabled={busy}>{busy ? t("Planning…", "规划能力维度中…") : t("Start", "开始")}</Button>
          </form>
        </section>

        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <h2 className="text-xl font-semibold">{t("History", "历史")}</h2>
          <div className="mt-5 space-y-3">
            {sessions.length === 0 && (
              <p className="text-sm text-muted-foreground">{t("None yet.", "还没有记录。")}</p>
            )}
            {sessions.map((session) => (
              <div key={session.id} className="flex items-stretch gap-2">
                <button className="min-w-0 flex-1 rounded-2xl border p-4 text-left transition hover:bg-muted"
                  onClick={() => void openSession(session)} disabled={busy}>
                  <span className="block truncate font-medium">
                    {session.targetCompany ? `${session.targetCompany} · ` : ""}{session.targetRole}
                  </span>
                  <span className="mt-1 block text-xs text-muted-foreground">
                    {session.status === "COMPLETED" ? t("Report ready", "已有报告")
                      : session.status === "CANCELLED" ? t("Cancelled", "已取消")
                        : t("In progress", "进行中")}
                  </span>
                </button>
                <button aria-label={t(`Delete ${session.targetRole}`, `删除 ${session.targetRole}`)}
                  className="rounded-2xl border px-3 text-stone-400 transition hover:border-red-200 hover:bg-red-50 hover:text-red-600"
                  onClick={() => void deleteInterview(session)} disabled={busy}>
                  <Trash2 className="size-4" />
                </button>
              </div>
            ))}
          </div>
        </section>
      </div>

      <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
        {error && <div className="mb-6 rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}
        {!state && !error && (
          <p className="text-sm text-muted-foreground">
            {t("Start one, or open a run from history.", "开始一场，或从历史打开。")}
          </p>
        )}

        {state && (
          <div className="space-y-6">
            <header className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="text-xl font-semibold">{state.targetRole}</h2>
              <p className="text-sm text-muted-foreground">
                {t(`${state.answeredCount} answered`, `已答 ${state.answeredCount} 题`)}
                {" · "}
                {t(`${state.competencies.length} areas`, `${state.competencies.length} 个维度`)}
              </p>
            </header>

            {state.lastDecision && (
              <p className="-mt-2 text-sm text-muted-foreground">{state.lastDecision.reason}</p>
            )}

            {state.awaitingEvaluation && (
              <div className="flex flex-wrap items-center gap-3 rounded-2xl bg-amber-50 px-5 py-4 text-sm text-amber-800">
                <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                <span>{t("An answer is waiting to be scored.", "有一份回答还没评分。")}</span>
                <Button variant="outline" className="px-4 py-2" disabled={busy} onClick={() => void refresh()}>
                  {t("Retry", "重试")}
                </Button>
              </div>
            )}
            {!state.awaitingEvaluation && state.evaluationError && (
              <div className="rounded-2xl bg-amber-50 px-5 py-4 text-sm text-amber-800">
                {t("Scoring failed earlier.", "此前评分失败：")} {state.evaluationError}
                <Button variant="outline" className="mt-3 px-4 py-2" disabled={busy} onClick={() => void refresh()}>
                  {t("Retry", "重试")}
                </Button>
              </div>
            )}

            {question && (
              <div>
                <p className="text-sm font-medium text-primary">
                  {question.competency}
                  {question.knowledgePoint ? ` · ${question.knowledgePoint}` : ""}
                  {question.difficulty
                    ? ` · ${difficultyLabels[question.difficulty - 1] ?? question.difficulty}`
                    : ""}
                  {" · "}
                  {(originLabels[question.origin] ?? [question.origin, question.origin])[locale === "en" ? 0 : 1]}
                </p>
                {question.leetcode ? (
                  <>
                    <h3 className="mt-3 text-2xl font-semibold leading-9">
                      {question.leetcode.id}. {locale === "en" ? question.leetcode.titleEn : question.leetcode.titleZh}
                    </h3>
                    <a href={`https://${locale === "en" ? "leetcode.com" : "leetcode.cn"}/problems/${question.leetcode.slug}/`}
                      target="_blank" rel="noopener noreferrer"
                      className="mt-4 inline-flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium transition hover:bg-muted">
                      {t("Open on LeetCode", "在力扣打开")}
                      <ExternalLink className="size-4" aria-hidden="true" />
                    </a>
                  </>
                ) : (
                  <h3 className="mt-3 text-2xl font-semibold leading-9">{question.questionText}</h3>
                )}

                <form className="mt-6 space-y-4" onSubmit={submitAnswer}>
                  <textarea className="field min-h-40 resize-y" maxLength={20000} required value={answerText}
                    onChange={(event) => setAnswerText(event.target.value)}
                    placeholder={t("Answer as you would out loud.", "像当面回答那样写出来。")} />
                  <fieldset>
                    <legend className="text-sm font-medium">{t("Felt like", "这题难度")}</legend>
                    <div className="mt-2 flex gap-2">
                      {([["TOO_HARD", "Too hard", "偏难"], ["FITTING", "Fitting", "合适"], ["TOO_EASY", "Too easy", "偏易"]] as const)
                        .map(([value, english, chinese]) => (
                          <label key={value}
                            className="flex cursor-pointer items-center rounded-full border px-4 py-2 text-sm transition has-[:checked]:border-violet-400 has-[:checked]:bg-violet-50">
                            <input type="radio" name="feedback" value={value} className="sr-only"
                              checked={feedback === value} onChange={() => setFeedback(value)} />
                            {locale === "en" ? english : chinese}
                          </label>
                        ))}
                    </div>
                  </fieldset>
                  <Button disabled={busy}>{busy ? t("Scoring…", "评分中…") : t("Submit", "提交")}</Button>
                </form>
              </div>
            )}

            {state.readyToFinish && !report && (
              <Button disabled={busy} onClick={() => void finishInterview()}>
                {busy ? t("Writing…", "生成报告中…") : t("Finish and report", "结束并生成报告")}
              </Button>
            )}

            {report && <AdaptiveReport report={report} label={t} />}

            <div className="border-t pt-6">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <ShieldCheck className="size-4 text-primary" aria-hidden="true" />
                {t("Where you stand", "当前画像")}
              </h3>
              <ul className="mt-4 space-y-3">
                {state.competencies.map((competency) => (
                  <li key={competency.skill} className="rounded-2xl border p-4">
                    <div className="flex flex-wrap items-baseline justify-between gap-2">
                      <span className="font-medium">{competency.skill}</span>
                      <span className="text-xs text-muted-foreground">
                        {stateLabels[competency.state][locale === "en" ? 0 : 1]}
                        {competency.estimatedLevel
                          ? ` · ${t("level", "水平")} ${competency.estimatedLevel}`
                          : ""}
                        {competency.evidenceCount > 0
                          ? ` · ${competency.evidenceCount} ${t("evidence", "证据")}`
                          : ""}
                      </span>
                    </div>
                    {competency.knowledgeGaps.length > 0 && (
                      <details className="mt-2">
                        <summary className="cursor-pointer text-xs text-muted-foreground">
                          {t("Gaps", "缺口")}
                        </summary>
                        <ul className="mt-2 space-y-1 text-xs text-muted-foreground">
                          {competency.knowledgeGaps.map((gap, index) => (
                            <li key={`${gap.knowledgePoint}-${index}`}>
                              {gap.knowledgePoint} · {difficultyLabels[(gap.difficulty ?? 1) - 1] ?? gap.difficulty}
                              {gap.detail ? ` · ${gap.detail}` : ""}
                            </li>
                          ))}
                        </ul>
                      </details>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          </div>
        )}
      </section>
    </div>
  );
}

function AdaptiveReport({ report, label }: { report: Report; label: (english: string, chinese: string) => string }) {
  const content = report.evaluation;
  return (
    <div className="space-y-6 rounded-2xl border p-6">
      <header className="flex flex-wrap items-baseline gap-3">
        <h3 className="flex items-center gap-2 text-xl font-semibold">
          <Sparkles className="size-5 text-primary" aria-hidden="true" />
          {label("Report", "报告")}
        </h3>
        <p className="text-2xl font-semibold">{report.overallScore}</p>
      </header>
      <dl className="space-y-3 text-sm">
        <div>
          <dt className="font-medium">{label("Fit", "岗位匹配")}</dt>
          <dd className="mt-1 text-muted-foreground">{content.jobFit}</dd>
        </div>
        <div>
          <dt className="font-medium">{label("Level", "水平")}</dt>
          <dd className="mt-1 text-muted-foreground">{content.levelAssessment}</dd>
        </div>
        <div>
          <dt className="font-medium">{label("Performance", "本场表现")}</dt>
          <dd className="mt-1 text-muted-foreground">{content.performanceSummary}</dd>
        </div>
      </dl>
      {content.learningRecommendations.length > 0 && (
        <div>
          <h4 className="text-sm font-semibold">{label("Next steps", "下一步")}</h4>
          <ol className="mt-3 space-y-3">
            {content.learningRecommendations.map((item, index) => (
              <li key={`${item.competency}-${item.knowledgePoint}-${index}`} className="rounded-xl border p-4 text-sm">
                <p className="font-medium">{item.competency} · {item.knowledgePoint}</p>
                <p className="mt-1">{item.advice}</p>
                <p className="mt-1 text-xs text-muted-foreground">{item.evidence}</p>
              </li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}
