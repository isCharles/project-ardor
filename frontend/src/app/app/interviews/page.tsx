"use client";

import { ArrowLeft, CheckCircle2, MessageSquareText, Play, Send, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { ApiError, api } from "@/lib/api";

type ResumeItem = { id: string; originalFilename: string; analysisId: string | null };
type Session = {
  id: string;
  resumeAnalysisId: string | null;
  targetCompany: string | null;
  targetRole: string;
  status: "IN_PROGRESS" | "COMPLETED" | "CANCELLED" | "CREATED";
  createdAt: string;
};
type Question = { id: string; sequenceNumber: number; questionText: string; questionType: string };
type Progress = {
  sessionId: string;
  status: Session["status"];
  answeredCount: number;
  totalQuestions: number;
  readyToFinish: boolean;
  nextQuestion: Question | null;
};
type Evaluation = {
  overallScore: number;
  evaluation: Record<string, unknown>;
  modelName: string;
};

const evaluationLabels: Record<string, string> = {
  strengths: "表现优势",
  weaknesses: "主要不足",
  knowledgeGaps: "知识缺口",
  communicationIssues: "表达问题",
  suggestedNextSteps: "下一步建议",
};

export default function InterviewsPage() {
  const router = useRouter();
  const [resumes, setResumes] = useState<ResumeItem[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [selectedAnalysisId, setSelectedAnalysisId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function loadLists() {
    try {
      const [resumeItems, interviewItems] = await Promise.all([
        api<ResumeItem[]>("/api/resumes"),
        api<Session[]>("/api/interviews"),
      ]);
      setResumes(resumeItems.filter((item) => item.analysisId));
      setSessions(interviewItems);
      const queryAnalysisId = new URLSearchParams(window.location.search).get("analysisId") ?? "";
      setSelectedAnalysisId(queryAnalysisId || resumeItems.find((item) => item.analysisId)?.analysisId || "");
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
      setError(reason instanceof Error ? reason.message : "无法加载模拟面试");
    }
  }

  useEffect(() => {
    let active = true;
    Promise.all([api<ResumeItem[]>("/api/resumes"), api<Session[]>("/api/interviews")])
      .then(([resumeItems, interviewItems]) => {
        if (!active) return;
        setResumes(resumeItems.filter((item) => item.analysisId));
        setSessions(interviewItems);
        const queryAnalysisId = new URLSearchParams(window.location.search).get("analysisId") ?? "";
        setSelectedAnalysisId(queryAnalysisId || resumeItems.find((item) => item.analysisId)?.analysisId || "");
      })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : "无法加载模拟面试");
      });
    return () => { active = false; };
  }, [router]);

  async function createInterview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(true); setError(""); setEvaluation(null);
    try {
      const session = await api<Session>("/api/interviews", {
        method: "POST",
        body: JSON.stringify({
          resumeAnalysisId: data.get("resumeAnalysisId") || null,
          targetCompany: data.get("targetCompany"),
          targetRole: data.get("targetRole"),
          questionCount: Number(data.get("questionCount")),
        }),
      });
      setProgress(await api<Progress>(`/api/interviews/${session.id}/next-question`));
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "创建面试失败");
    } finally { setBusy(false); }
  }

  async function openSession(session: Session) {
    setBusy(true); setError(""); setEvaluation(null);
    try {
      if (session.status === "COMPLETED") {
        setProgress(null);
        setEvaluation(await api<Evaluation>(`/api/interviews/${session.id}/evaluation`));
      } else if (session.status === "CANCELLED") {
        setProgress(null);
        setError("这场面试已取消，只能从历史记录中删除。");
      } else {
        setProgress(await api<Progress>(`/api/interviews/${session.id}/next-question`));
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法打开面试");
    } finally { setBusy(false); }
  }

  async function submitAnswer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!progress?.nextQuestion) return;
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true); setError("");
    try {
      setProgress(await api<Progress>(`/api/interviews/${progress.sessionId}/answers`, {
        method: "POST",
        body: JSON.stringify({
          questionId: progress.nextQuestion.id,
          answerText: data.get("answerText"),
        }),
      }));
      form.reset();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "提交答案失败");
    } finally { setBusy(false); }
  }

  async function finishInterview() {
    if (!progress) return;
    setBusy(true); setError("");
    try {
      setEvaluation(await api<Evaluation>(`/api/interviews/${progress.sessionId}/finish`, { method: "POST" }));
      setProgress(null);
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "生成评价失败");
    } finally { setBusy(false); }
  }

  async function deleteInterview(session: Session) {
    const label = `${session.targetCompany ? `${session.targetCompany} · ` : ""}${session.targetRole}`;
    if (!window.confirm(`永久删除“${label}”及其题目、回答和评价？`)) return;
    setBusy(true); setError("");
    try {
      await api<void>(`/api/interviews/${session.id}`, { method: "DELETE" });
      if (progress?.sessionId === session.id) setProgress(null);
      setEvaluation(null);
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "删除面试失败");
    } finally { setBusy(false); }
  }

  return (
    <main className="ardor-workbench min-h-screen px-5 py-6 md:px-10 md:py-8">
      <Link href="/app" className="inline-flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-muted-foreground transition hover:bg-muted hover:text-foreground"><ArrowLeft className="size-4" />返回 Ardor</Link>

      <header className="mx-auto max-w-6xl pb-9 pt-10">
        <h1 className="text-4xl font-semibold tracking-tight md:text-5xl">模拟面试</h1>
        <p className="mt-3 text-muted-foreground">选择目标岗位，开始或继续一场面试。</p>
      </header>

      {error && <div className="mx-auto mb-6 max-w-6xl rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}

      <div className="mx-auto grid max-w-6xl gap-6 lg:grid-cols-[0.8fr_1.2fr]">
        <div className="space-y-6">
          <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
            <div className="flex items-center gap-3"><Play className="size-5 text-primary" /><h2 className="text-xl font-semibold">创建面试</h2></div>
            <form className="mt-6 space-y-4" onSubmit={createInterview}>
              <label className="block text-sm font-medium">简历分析<select className="field mt-2" name="resumeAnalysisId" value={selectedAnalysisId} onChange={(event) => setSelectedAnalysisId(event.target.value)}><option value="">不使用简历</option>{resumes.map((resume) => <option key={resume.id} value={resume.analysisId ?? ""}>{resume.originalFilename}</option>)}</select></label>
              <label className="block text-sm font-medium">目标公司<input className="field mt-2" name="targetCompany" maxLength={160} placeholder="例如：字节跳动（可选）" /></label>
              <label className="block text-sm font-medium">目标岗位<input className="field mt-2" name="targetRole" maxLength={160} required placeholder="例如：Java 后端工程师" /></label>
              <label className="block text-sm font-medium">题目数量<select className="field mt-2" name="questionCount" defaultValue="5">{[3,4,5,6,7,8,9,10].map((count) => <option key={count}>{count}</option>)}</select></label>
              <Button disabled={busy}>{busy ? "生成题目中…" : "创建并开始"}</Button>
            </form>
          </section>

          <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
            <h2 className="text-xl font-semibold">历史面试</h2>
            <div className="mt-5 space-y-3">
              {sessions.length === 0 && <p className="text-sm text-muted-foreground">还没有模拟面试。</p>}
              {sessions.map((session) => <div key={session.id} className="flex items-stretch gap-2"><button className="min-w-0 flex-1 rounded-2xl border p-4 text-left transition hover:bg-muted" onClick={() => openSession(session)} disabled={busy}><span className="block truncate font-medium">{session.targetCompany ? `${session.targetCompany} · ` : ""}{session.targetRole}</span><span className="mt-1 block text-xs text-muted-foreground">{session.status === "COMPLETED" ? "已完成" : session.status === "CANCELLED" ? "已取消" : "进行中"}</span></button><button aria-label={`删除 ${session.targetRole}`} title="永久删除" className="rounded-2xl border px-3 text-stone-400 transition hover:border-red-200 hover:bg-red-50 hover:text-red-600" onClick={() => void deleteInterview(session)} disabled={busy}><Trash2 className="size-4" /></button></div>)}
            </div>
          </section>
        </div>

        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center gap-3"><MessageSquareText className="size-5 text-primary" /><h2 className="text-xl font-semibold">面试区</h2></div>
          {!progress && !evaluation && <p className="mt-4 text-sm text-muted-foreground">创建一场新面试，或从历史记录继续。</p>}
          {progress?.nextQuestion && <div className="mt-7">
            <p className="text-sm font-medium text-primary">第 {progress.nextQuestion.sequenceNumber} 题 · {progress.nextQuestion.questionType}</p>
            <h3 className="mt-3 text-2xl font-semibold leading-9">{progress.nextQuestion.questionText}</h3>
            <p className="mt-3 text-sm text-muted-foreground">已回答 {progress.answeredCount} / {progress.totalQuestions}</p>
            <form className="mt-6 space-y-4" onSubmit={submitAnswer}>
              <textarea className="field min-h-44 resize-y" name="answerText" maxLength={20000} required placeholder="像真实面试一样作答，建议说明思路、取舍和结果。" />
              <Button disabled={busy}><Send className="mr-2 size-4" />{busy ? "提交中…" : "提交并进入下一题"}</Button>
            </form>
          </div>}
          {progress?.readyToFinish && <div className="mt-7 rounded-2xl bg-emerald-50 p-6"><div className="flex items-center gap-2 font-semibold text-emerald-800"><CheckCircle2 className="size-5" />全部题目已回答</div><p className="mt-2 text-sm text-emerald-800">结束后将调用你的 LLM 生成结构化评价。</p><Button className="mt-5" disabled={busy} onClick={finishInterview}>{busy ? "生成评价中…" : "结束面试并生成评价"}</Button></div>}
          {evaluation && <div className="mt-7"><div className="rounded-2xl bg-primary p-6 text-primary-foreground"><p className="text-sm opacity-80">综合得分</p><p className="mt-1 text-5xl font-semibold">{evaluation.overallScore}</p></div><div className="mt-6 space-y-5">{Object.entries(evaluation.evaluation).map(([key, value]) => <div key={key}><h3 className="font-semibold">{evaluationLabels[key] ?? key}</h3><pre className="mt-2 whitespace-pre-wrap rounded-2xl bg-muted p-4 text-sm leading-6">{JSON.stringify(value, null, 2)}</pre></div>)}</div></div>}
        </section>
      </div>
    </main>
  );
}
