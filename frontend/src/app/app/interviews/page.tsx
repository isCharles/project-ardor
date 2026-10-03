"use client";

import { ArrowLeft, CheckCircle2, Code2, Keyboard, MessageSquareText, Mic, Play, Send, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { VoiceAnswerRecorder } from "@/components/interview/voice-answer-recorder";
import { ApiError, api } from "@/lib/api";

type ResumeItem = { id: string; originalFilename: string; analysisId: string | null };
type Session = {
  id: string;
  resumeAnalysisId: string | null;
  modality: "TEXT" | "VOICE";
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
type CodeRunResult = {
  compileStdout: string; compileStderr: string;
  stdout: string; stderr: string; exitCode: number; status: string;
};

const javaStarter = `import java.util.*;

public class Main {
    public static void main(String[] args) {
        Scanner in = new Scanner(System.in);
        // 在这里编写代码
    }
}`;

const evaluationLabels: Record<string, string> = {
  strengths: "表现优势",
  weaknesses: "主要不足",
  knowledgeGaps: "知识缺口",
  communicationIssues: "表达问题",
  suggestedNextSteps: "下一步建议",
};
const questionTypeLabels: Record<string, string> = {
  TECHNICAL: "技术理解", PROJECT: "项目深挖", BEHAVIORAL: "行为经历", CODING: "编程题",
};

export default function InterviewsPage() {
  const router = useRouter();
  const [resumes, setResumes] = useState<ResumeItem[]>([]);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [evaluation, setEvaluation] = useState<Evaluation | null>(null);
  const [activeSession, setActiveSession] = useState<Session | null>(null);
  const [answerText, setAnswerText] = useState("");
  const [code, setCode] = useState(javaStarter);
  const [stdin, setStdin] = useState("");
  const [runResult, setRunResult] = useState<CodeRunResult | null>(null);
  const [runnerAvailable, setRunnerAvailable] = useState(false);
  const [running, setRunning] = useState(false);
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
    api<{ available: boolean }>("/api/interviews/code-runner")
      .then((status) => setRunnerAvailable(status.available))
      .catch(() => setRunnerAvailable(false));
  }, []);

  function showProgress(next: Progress) {
    setProgress(next);
    const question = next.nextQuestion;
    if (!question) return;
    const prefix = `ardor:interview-draft:${question.id}:`;
    setAnswerText(sessionStorage.getItem(`${prefix}answer`) ?? "");
    if (question.questionType === "CODING") {
      setCode(sessionStorage.getItem(`${prefix}code`) ?? javaStarter);
      setStdin(sessionStorage.getItem(`${prefix}stdin`) ?? "");
      setRunResult(null);
    }
  }

  function saveDraft(field: "answer" | "code" | "stdin", value: string) {
    const question = progress?.nextQuestion;
    if (question) sessionStorage.setItem(`ardor:interview-draft:${question.id}:${field}`, value);
    if (field === "answer") setAnswerText(value);
    if (field === "code") setCode(value);
    if (field === "stdin") setStdin(value);
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
          modality: data.get("modality"),
          targetCompany: data.get("targetCompany"),
          targetRole: data.get("targetRole"),
          questionCount: Number(data.get("questionCount")),
        }),
      });
      setActiveSession(session);
      setAnswerText("");
      showProgress(await api<Progress>(`/api/interviews/${session.id}/next-question`));
      await loadLists();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "创建面试失败");
    } finally { setBusy(false); }
  }

  async function openSession(session: Session) {
    setBusy(true); setError(""); setEvaluation(null);
    setActiveSession(session);
    setAnswerText("");
    try {
      if (session.status === "COMPLETED") {
        setProgress(null);
        setEvaluation(await api<Evaluation>(`/api/interviews/${session.id}/evaluation`));
      } else if (session.status === "CANCELLED") {
        setProgress(null);
        setError("这场面试已取消，只能从历史记录中删除。");
      } else {
        showProgress(await api<Progress>(`/api/interviews/${session.id}/next-question`));
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法打开面试");
    } finally { setBusy(false); }
  }

  async function submitAnswer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!progress?.nextQuestion) return;
    const question = progress.nextQuestion;
    const submittedAnswer = question.questionType === "CODING"
      ? `Java 21 代码：\n\`\`\`java\n${code}\n\`\`\`\n\n思路与复杂度：${answerText.trim() || "未填写"}`
      : answerText;
    if (submittedAnswer.length > 20000) { setError("答案过长，请缩短代码或说明。"); return; }
    setBusy(true); setError("");
    try {
      const next = await api<Progress>(`/api/interviews/${progress.sessionId}/answers`, {
        method: "POST",
        body: JSON.stringify({
          questionId: progress.nextQuestion.id,
          answerText: submittedAnswer,
        }),
      });
      const prefix = `ardor:interview-draft:${question.id}:`;
      for (const field of ["answer", "code", "stdin"]) sessionStorage.removeItem(`${prefix}${field}`);
      showProgress(next);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "提交答案失败");
    } finally { setBusy(false); }
  }

  async function runCode() {
    if (!progress?.nextQuestion) return;
    setRunning(true); setError(""); setRunResult(null);
    try {
      setRunResult(await api<CodeRunResult>(`/api/interviews/${progress.sessionId}/run-code`, {
        method: "POST",
        body: JSON.stringify({ questionId: progress.nextQuestion.id, code, stdin }),
      }));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "代码运行失败");
    } finally { setRunning(false); }
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
      if (activeSession?.id === session.id) setActiveSession(null);
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
              <fieldset>
                <legend className="text-sm font-medium">面试方式</legend>
                <div className="mt-2 grid grid-cols-2 gap-3">
                  <label className="flex cursor-pointer items-center gap-3 rounded-2xl border bg-white/70 p-4 transition has-[:checked]:border-violet-400 has-[:checked]:bg-violet-50"><input type="radio" name="modality" value="TEXT" defaultChecked className="sr-only" /><Keyboard className="size-4" /><span className="text-sm font-medium">文字</span></label>
                  <label className="flex cursor-pointer items-center gap-3 rounded-2xl border bg-white/70 p-4 transition has-[:checked]:border-violet-400 has-[:checked]:bg-violet-50"><input type="radio" name="modality" value="VOICE" className="sr-only" /><Mic className="size-4" /><span className="text-sm font-medium">语音</span></label>
                </div>
              </fieldset>
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
              {sessions.map((session) => <div key={session.id} className="flex items-stretch gap-2"><button className="min-w-0 flex-1 rounded-2xl border p-4 text-left transition hover:bg-muted" onClick={() => openSession(session)} disabled={busy}><span className="block truncate font-medium">{session.targetCompany ? `${session.targetCompany} · ` : ""}{session.targetRole}</span><span className="mt-1 block text-xs text-muted-foreground">{session.modality === "VOICE" ? "语音" : "文字"} · {session.status === "COMPLETED" ? "已完成" : session.status === "CANCELLED" ? "已取消" : "进行中"}</span></button><button aria-label={`删除 ${session.targetRole}`} title="永久删除" className="rounded-2xl border px-3 text-stone-400 transition hover:border-red-200 hover:bg-red-50 hover:text-red-600" onClick={() => void deleteInterview(session)} disabled={busy}><Trash2 className="size-4" /></button></div>)}
            </div>
          </section>
        </div>

        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center gap-3"><MessageSquareText className="size-5 text-primary" /><h2 className="text-xl font-semibold">面试区</h2></div>
          {!progress && !evaluation && <p className="mt-4 text-sm text-muted-foreground">创建一场新面试，或从历史记录继续。</p>}
          {progress?.nextQuestion && <div className="mt-7">
            <p className="text-sm font-medium text-primary">第 {progress.nextQuestion.sequenceNumber} 题 · {questionTypeLabels[progress.nextQuestion.questionType] ?? progress.nextQuestion.questionType}</p>
            <h3 className="mt-3 text-2xl font-semibold leading-9">{progress.nextQuestion.questionText}</h3>
            <p className="mt-3 text-sm text-muted-foreground">已回答 {progress.answeredCount} / {progress.totalQuestions}</p>
            <form className="mt-6 space-y-4" onSubmit={submitAnswer}>
              {activeSession?.modality === "VOICE" && <VoiceAnswerRecorder key={progress.nextQuestion.id} sessionId={progress.sessionId} questionId={progress.nextQuestion.id} disabled={busy} onTranscript={setAnswerText} onError={setError} />}
              {progress.nextQuestion.questionType === "CODING" && <div className="space-y-3 rounded-3xl border border-violet-200 bg-white/75 p-4">
                <div className="flex items-center gap-2 text-sm font-medium"><Code2 className="size-4 text-violet-600" />Java 21 · Main.java</div>
                  <textarea aria-label="Java 代码" className="field min-h-80 resize-y font-mono text-[13px] leading-6" spellCheck={false} maxLength={18000} value={code} onChange={(event) => saveDraft("code", event.target.value)} onKeyDown={(event) => {
                  if (event.key !== "Tab") return;
                  event.preventDefault();
                  const input = event.currentTarget;
                  const next = `${code.slice(0, input.selectionStart)}    ${code.slice(input.selectionEnd)}`;
                  const position = input.selectionStart + 4;
                  saveDraft("code", next);
                  requestAnimationFrame(() => input.setSelectionRange(position, position));
                }} />
                <label className="block text-sm font-medium">标准输入<textarea className="field mt-2 min-h-24 resize-y font-mono text-sm" maxLength={8000} value={stdin} onChange={(event) => saveDraft("stdin", event.target.value)} placeholder="在这里粘贴样例输入，也可以自己构造边界用例" /></label>
                <div className="flex items-center gap-3"><Button type="button" variant="outline" disabled={running || busy || !runnerAvailable || !code.trim()} onClick={() => void runCode()}><Play className="mr-2 size-4" />{running ? "运行中…" : "运行代码"}</Button>{!runnerAvailable && <span className="text-xs text-amber-700">运行器尚未配置</span>}</div>
                {runResult && <div className="space-y-3 rounded-2xl bg-slate-950 p-4 text-sm text-slate-100" aria-live="polite">
                  <div className="text-xs text-slate-400">{runResult.exitCode === 0 ? "运行完成" : `退出码 ${runResult.exitCode}`}{runResult.status ? ` · ${runResult.status}` : ""}</div>
                  {(runResult.compileStderr || runResult.compileStdout) && <pre className="whitespace-pre-wrap break-all text-amber-200">{runResult.compileStderr || runResult.compileStdout}</pre>}
                  <pre className="min-h-6 whitespace-pre-wrap break-all">{runResult.stdout || "（没有标准输出）"}</pre>
                  {runResult.stderr && <pre className="whitespace-pre-wrap break-all text-rose-300">{runResult.stderr}</pre>}
                </div>}
              </div>}
              <textarea className="field min-h-44 resize-y" name="answerText" maxLength={progress.nextQuestion.questionType === "CODING" ? 1500 : 20000} required={progress.nextQuestion.questionType !== "CODING"} value={answerText} onChange={(event) => saveDraft("answer", event.target.value)} placeholder={activeSession?.modality === "VOICE" ? "转写结果会出现在这里，可修改后提交。" : progress.nextQuestion.questionType === "CODING" ? "可选：说明思路和复杂度" : "像真实面试一样作答，建议说明思路、取舍和结果。"} />
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
