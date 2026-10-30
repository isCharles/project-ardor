"use client";

import { ArrowUpDown, ChevronDown, EyeOff, FileSearch, FileText, GraduationCap, Layers, Pencil, Plus, Sparkles, StickyNote, Trash2, X } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, Fragment, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { AppDialog } from "@/components/ui/app-dialog";
import { useDismiss } from "@/components/ardor/overlay";
import type { PlanContext } from "@/components/ardor/learning-context";

type Round = {
  id: string;
  roundNumber: number;
  stage: string;
  scheduledAt: string | null;
  occurredAt: string | null;
  durationMinutes: number | null;
  note: string | null;
};

type Opportunity = {
  id: string;
  company: string;
  targetRole: string | null;
  platform: string | null;
  status: string;
  jdContent: string | null;
  inviteContent: string | null;
  currentRound: number;
  rounds: Round[];
  createdAt: string;
  updatedAt: string;
};

type RecapRef = { id: string; title: string; jobOpportunityId: string | null; questions: { id: string }[] };
type MemoryCardRef = { id: string; recapQuestionId: string | null; status: string };
type LearningPlanRef = PlanContext & { id: string; concept: string; status: string };
type RelatedPlan = LearningPlanRef & { relation: "TARGET" | "SOURCE" };

const STATUSES = ["SCHEDULED", "AWAITING_RESULT", "NEXT_ROUND", "REJECTED"];

const COURSE_STATUS: Record<string, { en: string; zh: string; row: string; badge: string }> = {
  COMPLETED: { en: "Completed", zh: "已完成", row: "bg-emerald-50 text-emerald-900 hover:bg-emerald-100", badge: "bg-emerald-100 text-emerald-700" },
  IN_PROGRESS: { en: "In progress", zh: "学习中", row: "bg-violet-50 text-violet-900 hover:bg-violet-100", badge: "bg-violet-100 text-violet-700" },
  NEEDS_REVIEW: { en: "Needs review", zh: "待巩固", row: "bg-amber-50 text-amber-900 hover:bg-amber-100", badge: "bg-amber-100 text-amber-700" },
  SCHEDULED: { en: "Scheduled", zh: "待学习", row: "bg-stone-50 text-stone-800 hover:bg-stone-100", badge: "bg-stone-100 text-stone-600" },
};

const STATUS_STYLE: Record<string, string> = {
  SCHEDULED: "bg-violet-100 text-violet-700",
  AWAITING_RESULT: "bg-amber-100 text-amber-800",
  NEXT_ROUND: "bg-sky-100 text-sky-700",
  REJECTED: "bg-stone-100 text-stone-600",
};

function statusLabel(status: string, en: boolean) {
  const labels: Record<string, [string, string]> = {
    SCHEDULED: ["Scheduled", "已约面"],
    AWAITING_RESULT: ["Awaiting result", "等通知"],
    NEXT_ROUND: ["Next round", "下一轮"],
    REJECTED: ["Closed", "终止"],
  };
  const pair = labels[status] ?? [status, status];
  return en ? pair[0] : pair[1];
}

function latestRound(item: Opportunity): Round | null {
  return item.rounds.length === 0 ? null : item.rounds[item.rounds.length - 1];
}

const STAGE_LABEL: Record<string, [string, string]> = {
  HR: ["HR", "HR"], TECHNICAL: ["Technical", "技术"], MANAGER: ["Manager", "主管"],
  CROSS: ["Cross-functional", "交叉"], FINAL: ["Final", "终面"], OTHER: ["Other", "其他"],
};

function stageLabel(stage: string, en: boolean) {
  const pair = STAGE_LABEL[stage] ?? [stage, stage];
  return en ? pair[0] : pair[1];
}

export default function OpportunitiesPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const en = locale === "en";
  const [items, setItems] = useState<Opportunity[]>([]);
  const [recaps, setRecaps] = useState<RecapRef[]>([]);
  const [cards, setCards] = useState<MemoryCardRef[]>([]);
  const [plans, setPlans] = useState<LearningPlanRef[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [newMenuOpen, setNewMenuOpen] = useState(false);
  const [details, setDetails] = useState<{ item: Opportunity } | null>(null);
  const [planPicker, setPlanPicker] = useState<RelatedPlan[] | null>(null);
  const [editing, setEditing] = useState<Opportunity | null>(null);
  const newMenuRef = useDismiss<HTMLDivElement>(newMenuOpen, () => setNewMenuOpen(false));
  const [sortAscending, setSortAscending] = useState(false);
  const [presentationMode, setPresentationMode] = useState(false);

  function fail(reason: unknown, fallbackEn: string, fallbackZh: string) {
    if (reason instanceof ApiError && reason.status === 401) { router.replace("/login"); return; }
    setError(reason instanceof Error ? reason.message : t(fallbackEn, fallbackZh));
  }

  async function load() {
    try { setItems(await api<Opportunity[]>("/api/opportunities")); }
    catch (reason) { fail(reason, "Could not load interview pipeline", "无法加载面试流程"); }
  }

  useEffect(() => {
    let active = true;
    Promise.all([
      api<Opportunity[]>("/api/opportunities"),
      // Recaps, cards and plans join client-side, so the pipeline owns no recap schema.
      api<RecapRef[]>("/api/interview-recaps"),
      api<MemoryCardRef[]>("/api/memory-cards"),
      api<LearningPlanRef[]>("/api/learning-plans"),
    ])
      .then(([result, recapResult, cardResult, planResult]) => {
        if (!active) return;
        setItems(result);
        setRecaps(recapResult);
        setCards(cardResult);
        setPlans(planResult);
      })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : t("Could not load interview pipeline", "无法加载面试流程"));
      });
    return () => { active = false; };
    // Loaded once; every later write refreshes from its own response.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

  function recapsFor(item: Opportunity) {
    return recaps.filter((recap) => recap.jobOpportunityId === item.id);
  }

  function cardsFor(item: Opportunity) {
    const questionIds = new Set(recapsFor(item).flatMap((recap) => recap.questions.map((question) => question.id)));
    return cards.filter((card) => card.recapQuestionId !== null && questionIds.has(card.recapQuestionId));
  }

  function plansFor(item: Opportunity) {
    const linked = recapsFor(item);
    const recapIds = new Set(linked.map((recap) => recap.id));
    const questionIds = new Set(linked.flatMap((recap) => recap.questions.map((question) => question.id)));
    return plans.flatMap<LearningPlanRef & { relation: "TARGET" | "SOURCE" }>((plan) => {
      if (plan.targetInterviewId === item.id) return [{ ...plan, relation: "TARGET" as const }];
      const id = plan.originResourceId;
      const fromHere = plan.originKind === "INTERVIEW_RECORD" ? !!id && recapIds.has(id)
        : plan.originKind === "INTERVIEW_QUESTION" ? !!id && questionIds.has(id)
        : !plan.originKind && plan.sourceType === "RECAP" && !!plan.sourceId
          && (recapIds.has(plan.sourceId) || questionIds.has(plan.sourceId));
      return fromHere ? [{ ...plan, relation: "SOURCE" as const }] : [];
    });
  }

  function otherContent(item: Opportunity) {
    return [
      item.jdContent ? "JD" : null,
      item.inviteContent ? t("Invite email", "约面邮件") : null,
      item.rounds.some((entry) => entry.note?.trim()) ? t("Round notes", "轮次备注") : null,
    ].filter(Boolean).join(" · ");
  }

  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const scheduledAt = String(data.get("scheduledAt") ?? "");
    const duration = Number(data.get("durationMinutes") ?? 0);
    setBusy("create"); setError("");
    try {
      await api<Opportunity>("/api/opportunities", {
        method: "POST",
        body: JSON.stringify({
          company: String(data.get("company") ?? ""),
          targetRole: String(data.get("targetRole") ?? ""),
          platform: String(data.get("platform") ?? ""),
          jdContent: String(data.get("jdContent") ?? ""),
          inviteContent: String(data.get("inviteContent") ?? ""),
          stage: String(data.get("stage") ?? ""),
          scheduledAt: scheduledAt ? new Date(scheduledAt).toISOString() : null,
          durationMinutes: Number.isFinite(duration) && duration > 0 ? duration : null,
          note: "",
        }),
      });
      (event.target as HTMLFormElement).reset();
      setFormOpen(false);
      await load();
    } catch (reason) { fail(reason, "Could not create the record", "创建记录失败"); }
    finally { setBusy(""); }
  }

  async function saveEdit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editing) return;
    const data = new FormData(event.currentTarget);
    setBusy(editing.id); setError("");
    try {
      await api<Opportunity>(`/api/opportunities/${editing.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          company: String(data.get("company") ?? ""),
          targetRole: String(data.get("targetRole") ?? ""),
          platform: String(data.get("platform") ?? ""),
          jdContent: String(data.get("jdContent") ?? ""),
          inviteContent: String(data.get("inviteContent") ?? ""),
          status: String(data.get("status") ?? ""),
        }),
      });
      setEditing(null);
      await load();
    } catch (reason) { fail(reason, "Could not save the record", "保存失败"); }
    finally { setBusy(""); }
  }

  async function setStatus(item: Opportunity, status: string) {    if (status === item.status) return;
    setBusy(item.id); setError("");
    try {
      await api<Opportunity>(`/api/opportunities/${item.id}`, {
        method: "PATCH", body: JSON.stringify({ status }),
      });
      await load();
    } catch (reason) { fail(reason, "This status change is not allowed", "状态变更不被允许"); }
    finally { setBusy(""); }
  }

  async function remove(item: Opportunity) {
    const name = `${item.company}${item.targetRole ? ` · ${item.targetRole}` : ""}`;
    if (!window.confirm(t(`Delete “${name}” and its rounds?`, `删除“${name}”及其全部轮次？`))) return;
    setBusy(item.id); setError("");
    try {
      await api<void>(`/api/opportunities/${item.id}`, { method: "DELETE" });
      await load();
    } catch (reason) { fail(reason, "Could not delete the record", "删除失败"); }
    finally { setBusy(""); }
  }

  function formatDate(value: string | null) {
    if (!value) return "—";
    return new Date(value).toLocaleString(en ? "en-US" : "zh-CN",
      { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
  }

  function interviewTime(item: Opportunity) {
    const round = latestRound(item);
    const value = round?.occurredAt ?? round?.scheduledAt ?? null;
    return value ? new Date(value).getTime() : Number.NaN;
  }

  // Sorted by the interview date shown in the row; undated records stay last.
  const sortedItems = [...items].sort((left, right) => {
    const a = interviewTime(left);
    const b = interviewTime(right);
    const aMissing = Number.isNaN(a);
    const bMissing = Number.isNaN(b);
    if (aMissing && bMissing) return 0;
    if (aMissing) return 1;
    if (bMissing) return -1;
    return sortAscending ? a - b : b - a;
  });

  return (
    <main className="ardor-workbench min-h-screen px-5 py-8 md:px-10">
      <div className="mx-auto max-w-6xl">
        <header className="flex flex-wrap items-end justify-between gap-4 pb-8">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight md:text-4xl">{t("Interviews", "面试")}</h1>
          </div>
          <div className="flex items-center gap-2">
            <button type="button" aria-pressed={presentationMode} onClick={() => setPresentationMode(value => !value)} title={t("Hide results, dates and round numbers", "隐藏结果、时间与轮次")} className={`inline-flex items-center gap-2 rounded-full border px-4 py-2.5 text-sm transition ${presentationMode ? "border-violet-300 bg-violet-50 text-violet-700" : "border-stone-300 text-stone-700 hover:bg-stone-100"}`}><EyeOff className="size-4" />{t("Presentation mode", "演示模式")}</button>
            {!presentationMode && <button type="button" onClick={() => setSortAscending((value) => !value)}
              className="inline-flex items-center gap-2 rounded-full border border-stone-300 px-4 py-2.5 text-sm text-stone-700 hover:bg-stone-100">
              <ArrowUpDown className="size-4" />
              {sortAscending ? t("Date: oldest first", "日期：正序") : t("Date: newest first", "日期：倒序")}
            </button>}
            <div ref={newMenuRef} className="relative" onMouseEnter={() => setNewMenuOpen(true)} onMouseLeave={() => setNewMenuOpen(false)}>
              <button type="button" aria-haspopup="menu" aria-expanded={newMenuOpen} onClick={() => setNewMenuOpen(true)}
                className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white hover:bg-stone-800">
                <Plus className="size-4" />{t("New record", "新建记录")}<ChevronDown className={`size-3.5 transition ${newMenuOpen ? "rotate-180" : ""}`} />
              </button>
              {newMenuOpen && <div role="menu" className="absolute right-0 top-full z-30 w-56 rounded-2xl border border-stone-200 bg-white p-1.5 pt-2 shadow-[0_18px_50px_rgba(49,39,70,0.16)]">
                <Link href={`/app?new=1&prefill=${encodeURIComponent(locale === "en" ? "I may send you a JD, interview invite or other role information. Help me record this interview." : "我现在可能发你 JD、约面邮件等岗位信息，你帮我记录一下这个面试。")}`} onClick={() => setNewMenuOpen(false)} className="flex items-start gap-2.5 rounded-xl px-3 py-2.5 hover:bg-violet-50">
                  <Sparkles className="mt-0.5 size-4 shrink-0 text-violet-600" />
                  <span><span className="block text-sm font-medium text-stone-800">{t("Let Ardor fill it", "让 Ardor 帮我记")}</span><span className="mt-0.5 block text-[11px] leading-4 text-stone-400">{t("Describe the interview in chat", "在对话里描述这次面试")}</span></span>
                </Link>
                <button type="button" onClick={() => { setNewMenuOpen(false); setFormOpen(true); }} className="flex items-start gap-2.5 rounded-xl px-3 py-2.5 text-left hover:bg-stone-50">
                  <Pencil className="mt-0.5 size-4 shrink-0 text-stone-500" />
                  <span><span className="block text-sm font-medium text-stone-800">{t("Fill it myself", "自己填写")}</span><span className="mt-0.5 block text-[11px] leading-4 text-stone-400">{t("Company, role, time, JD", "公司、岗位、时间、JD")}</span></span>
                </button>
              </div>}
            </div>
          </div>
        </header>

        {error && <div role="alert" className="mb-5 rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}

        {formOpen && (
          <form onSubmit={create} className="ardor-panel relative mb-6 grid gap-4 rounded-[2rem] p-6 md:grid-cols-3">
            <button type="button" aria-label={t("Close", "关闭")} onClick={() => setFormOpen(false)} className="absolute right-5 top-5 rounded-full p-1.5 text-stone-400 hover:bg-stone-100"><X className="size-4" /></button>
            <label className="text-sm font-medium">{t("Company", "公司")}
              <input name="company" required maxLength={160} className="field mt-2" placeholder={t("e.g. ByteDance", "例如：字节跳动")} />
            </label>
            <label className="text-sm font-medium md:col-span-2">{t("Role / posting title", "岗位 / 招聘标题")}
              <input name="targetRole" maxLength={160} className="field mt-2" placeholder={t("e.g. Backend intern — international commercialization", "例如：后端开发实习生—国际商业化产品与技术")} />
            </label>
            <label className="text-sm font-medium">{t("Platform", "面试平台")}
              <input name="platform" maxLength={80} className="field mt-2" placeholder={t("e.g. Feishu", "例如：飞书")} />
            </label>
            <label className="text-sm font-medium">{t("Interview time", "面试时间")}
              <input name="scheduledAt" type="datetime-local" className="field mt-2" />
            </label>
            <label className="text-sm font-medium">{t("Duration (minutes)", "时长（分钟）")}
              <input name="durationMinutes" type="number" min={1} max={480} className="field mt-2" />
            </label>
            <label className="text-sm font-medium">{t("Round type", "本轮环节")}
              <select name="stage" defaultValue="OTHER" className="field mt-2">
                {["HR", "TECHNICAL", "MANAGER", "CROSS", "FINAL", "OTHER"].map((stage) => <option key={stage} value={stage}>{stage}</option>)}
              </select>
            </label>
            <label className="text-sm font-medium md:col-span-2">{t("JD (optional)", "JD（可选）")}
              <textarea name="jdContent" rows={3} maxLength={50000} className="field mt-2 resize-y" />
            </label>
            <label className="text-sm font-medium md:col-span-2">{t("Invite email (optional)", "约面邮件（可选）")}
              <textarea name="inviteContent" rows={3} maxLength={50000} className="field mt-2 resize-y" placeholder={t("Paste the HR email or notification", "粘贴 HR 邮件或通知原文")} />
            </label>
            <div className="md:col-span-3">
              <button disabled={busy === "create"} className="rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white disabled:opacity-50">
                {busy === "create" ? t("Saving…", "保存中…") : t("Save record", "保存记录")}
              </button>
            </div>
          </form>
        )}

        <div className="overflow-x-auto rounded-3xl border border-stone-200 bg-white">
          <table className={`w-full table-fixed border-collapse text-left text-sm ${presentationMode ? "min-w-[620px]" : "min-w-[860px]"}`}>
            <colgroup>
              {(presentationMode ? [45, 18, 14, 8, 15] : [28, 12, 16, 6, 12, 10, 6, 10]).map((width, index) => <col key={index} style={{ width: `${width}%` }} />)}
            </colgroup>
            <thead className="text-xs uppercase tracking-wide text-stone-400">
              <tr className="border-b border-stone-200">
                <th className="px-4 py-3 font-medium">{t("Role", "岗位")}</th>
                {!presentationMode && <><th className="px-4 py-3 font-medium">{t("Result", "结果")}</th>
                <th className="px-4 py-3 font-medium">{t("Date", "时间")}</th>
                <th className="px-4 py-3 font-medium">{t("Round", "轮次")}</th></>}
                <th className="px-4 py-3 font-medium">{t("Company", "公司")}</th>
                <th className="px-4 py-3 font-medium">{t("Platform", "平台")}</th>
                <th className="px-4 py-3 font-medium">{t("Duration", "时长")}</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {items.length === 0 && (
                <tr><td colSpan={presentationMode ? 5 : 8} className="px-4 py-14 text-center text-stone-400">
                  {t("No interviews yet. Paste an HR invite to the agent, or add one here.", "还没有面试记录。可以把 HR 邀请粘贴给 Agent，或在这里手动添加。")}
                </td></tr>
              )}
              {sortedItems.map((item) => {
                const round = latestRound(item);
                const open = expanded === item.id;
                return (
                  <Fragment key={item.id}>
                    <tr className={`border-b border-stone-100 align-top ${busy === item.id ? "opacity-60" : ""}`}>
                      <td className="px-4 py-3">
                        <button type="button" onClick={() => setExpanded(open ? null : item.id)}
                          className="flex w-full min-w-0 items-start gap-2 text-left font-medium hover:text-violet-700">
                          <ChevronDown className={`mt-0.5 size-4 shrink-0 text-stone-400 transition ${open ? "rotate-180" : ""}`} />
                          <span className="line-clamp-2">{item.targetRole || t("Untitled role", "未填写岗位")}</span>
                        </button>
                      </td>
                      {!presentationMode && <><td className="px-4 py-3">
                        <select value={item.status} title={statusLabel(item.status, en)} aria-label={t("Result", "结果")} disabled={busy === item.id} onChange={(event) => void setStatus(item, event.target.value)}
                          className={`w-full min-w-0 max-w-full truncate rounded-full px-3 py-1 text-xs font-medium ${STATUS_STYLE[item.status] ?? "bg-stone-100 text-stone-600"}`}>
                          {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status, en)}</option>)}
                        </select>
                      </td>
                      <td className="truncate px-4 py-3 text-stone-600" title={formatDate(round?.occurredAt ?? round?.scheduledAt ?? null)}>
                        {formatDate(round?.occurredAt ?? round?.scheduledAt ?? null)}
                      </td>
                      <td className="px-4 py-3 text-stone-600">{item.currentRound || "—"}</td></>}
                      <td className="truncate px-4 py-3 text-stone-600" title={item.company}>{item.company}</td>
                      <td className="px-4 py-3">
                        {item.platform ? <span className="rounded-lg bg-stone-100 px-2 py-0.5 text-xs text-stone-600">{item.platform}</span> : "—"}
                      </td>
                      <td className="px-4 py-3 text-stone-600">{round?.durationMinutes ? `${round.durationMinutes}` : "—"}</td>
                      <td className="px-4 py-3 text-right">
                        <button type="button" aria-label={t("Edit record", "编辑记录")} disabled={busy === item.id}
                          onClick={() => setEditing(item)} className="rounded-lg p-2 text-stone-300 hover:bg-stone-100 hover:text-stone-700">
                          <Pencil className="size-4" />
                        </button>
                        <button type="button" aria-label={t("Delete record", "删除记录")} disabled={busy === item.id}
                          onClick={() => void remove(item)} className="rounded-lg p-2 text-stone-300 hover:bg-rose-50 hover:text-rose-600">
                          <Trash2 className="size-4" />
                        </button>
                      </td>
                    </tr>
                    {open && (
                      <tr key={`${item.id}-detail`} className="border-b border-stone-100 bg-stone-50/60">
                        <td colSpan={presentationMode ? 5 : 8} className="px-6 py-5">
                          <div className="min-w-0 space-y-2">
                            {(recapsFor(item).length > 0 || plansFor(item).length > 0) && (
                              <Link href={"/app/learning/progress?sourceInterviewId=" + item.id}
                                className="flex w-full min-w-0 items-center gap-3 rounded-2xl bg-violet-50 px-4 py-2.5 text-sm text-violet-900 transition hover:bg-violet-100">
                                <GraduationCap className="size-4 shrink-0 text-violet-600" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-violet-600">{t("Improve", "补强")}</span>
                                <span className="min-w-0 flex-1 truncate">{t("View learning progress from this interview", "查看这场面试的补强进度")}</span>
                              </Link>
                            )}
                            {recapsFor(item).map((recap) => (
                              <Link key={recap.id} href={`/app/recaps?selected=${recap.id}`}
                                className="flex w-full min-w-0 items-center gap-3 rounded-2xl bg-violet-50 px-4 py-2.5 text-sm text-violet-900 transition hover:bg-violet-100">
                                <FileText className="size-4 shrink-0 text-violet-600" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-violet-600">{t("Notes", "面经")}</span>
                                <span className="min-w-0 flex-1 truncate" title={recap.title}>{recap.title}</span>
                              </Link>
                            ))}
                            {cardsFor(item).length > 0 && (
                              <Link href="/app/cards"
                                className="flex w-full min-w-0 items-center gap-3 rounded-2xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-900 transition hover:bg-emerald-100">
                                <Layers className="size-4 shrink-0 text-emerald-600" />
                                <span className="min-w-0 flex-1 text-sm font-medium text-emerald-700">{t("Flashcards", "记忆卡")}</span>
                                <span className="shrink-0 rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700">{cardsFor(item).length}</span>
                              </Link>
                            )}
                            {plansFor(item).length > 0 && (
                              <button type="button" onClick={() => setPlanPicker(plansFor(item))}
                                className="flex w-full min-w-0 items-center gap-3 rounded-2xl bg-amber-50 px-4 py-2.5 text-left text-sm text-amber-900 transition hover:bg-amber-100">
                                <GraduationCap className="size-4 shrink-0 text-amber-600" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-amber-600">{t("Courses", "课程")}</span>
                                <span className="min-w-0 flex-1 truncate">{t(`${plansFor(item).length} related course${plansFor(item).length > 1 ? "s" : ""}`, `${plansFor(item).length} 门相关课程`)}</span>
                              </button>
                            )}
                            {otherContent(item) && (
                              <button type="button"
                                onClick={() => setDetails({ item })}
                                className="flex w-full items-center gap-3 rounded-2xl bg-stone-100 px-4 py-2.5 text-left text-sm text-stone-800 transition hover:bg-stone-200">
                                <FileSearch className="size-4 shrink-0 text-stone-500" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-stone-500">{t("Other", "其他")}</span>
                                <span className="min-w-0 flex-1 truncate">{otherContent(item)}</span>
                              </button>
                            )}
                            {recapsFor(item).length === 0 && cardsFor(item).length === 0 && plansFor(item).length === 0
                              && !otherContent(item) && (
                                <p className="text-sm text-stone-400">{t("Nothing attached to this interview yet.", "这次面试还没有关联任何内容。")}</p>
                              )}
                          </div>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
      {planPicker && (
        <AppDialog title={t("Courses", "课程")} onClose={() => setPlanPicker(null)}>
          <div className="space-y-2">{planPicker.map((plan) => {
            const status = COURSE_STATUS[plan.status] ?? { en: "Status unavailable", zh: "状态未知", row: "bg-stone-50 text-stone-800 hover:bg-stone-100", badge: "bg-stone-100 text-stone-600" };
            return (
            <Link key={plan.id} href={`/app/learning?id=${plan.id}`} onClick={() => setPlanPicker(null)}
              className={`flex w-full min-w-0 items-center gap-3 rounded-2xl px-4 py-3 text-sm transition ${status.row}`}>
              <GraduationCap className="size-4 shrink-0" />
              {plan.relation === "TARGET" && <span className="shrink-0 text-[11px] font-semibold">{t("Preparation", "面试准备")}</span>}
              <span className="min-w-0 flex-1 truncate" title={plan.concept}>{plan.concept}</span>
              <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${status.badge}`}>{t(status.en, status.zh)}</span>
            </Link>
          ); })}</div>
        </AppDialog>
      )}
      {details && (
        <AppDialog title={t("Other", "其他")} wide onClose={() => setDetails(null)}>
          <div className="max-h-[60dvh] space-y-3 overflow-y-auto">
            {details.item.jdContent && (
              <section className="rounded-2xl border border-stone-200 bg-white p-4">
                <h3 className="text-[11px] font-semibold uppercase tracking-wide text-stone-500">JD</h3>
                <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-stone-700">{details.item.jdContent}</p>
              </section>
            )}
            {details.item.inviteContent && (
              <section className="rounded-2xl border border-stone-200 bg-white p-4">
                <h3 className="text-[11px] font-semibold uppercase tracking-wide text-stone-500">{t("Invite email", "约面邮件")}</h3>
                <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-stone-700">{details.item.inviteContent}</p>
              </section>
            )}
            {details.item.rounds.filter((entry) => entry.note?.trim()).map((entry) => (
              <section key={entry.id} className="rounded-2xl border border-stone-200 bg-white p-4">
                <h3 className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-wide text-stone-500">
                  <StickyNote className="size-3.5" />{t(`Round ${entry.roundNumber}`, `第 ${entry.roundNumber} 轮`)} · {stageLabel(entry.stage, en)}
                </h3>
                <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-stone-700">{entry.note}</p>
              </section>
            ))}
          </div>
        </AppDialog>
      )}
      {editing && (
        <AppDialog title={t("Edit interview", "编辑面试")} wide onClose={() => setEditing(null)}>
          <form onSubmit={saveEdit} className="space-y-3 text-sm">
            <label className="block font-medium">{t("Company", "公司")}
              <input name="company" defaultValue={editing.company} required maxLength={160} className="field mt-1" />
            </label>
            <label className="block font-medium">{t("Role / posting title", "岗位 / 招聘标题")}
              <input name="targetRole" defaultValue={editing.targetRole ?? ""} maxLength={160} className="field mt-1" />
            </label>
            <label className="block font-medium">{t("Platform", "面试平台")}
              <input name="platform" defaultValue={editing.platform ?? ""} maxLength={80} className="field mt-1" />
            </label>
            <label className="block font-medium">JD
              <textarea name="jdContent" defaultValue={editing.jdContent ?? ""} rows={5} maxLength={50000} className="field mt-1 resize-y" />
            </label>
            <label className="block font-medium">{t("Invite email", "约面邮件")}
              <textarea name="inviteContent" defaultValue={editing.inviteContent ?? ""} rows={5} maxLength={50000} className="field mt-1 resize-y" />
            </label>
            <label className="block font-medium">{t("Status", "状态")}
              <select name="status" defaultValue={editing.status} className="field mt-1">
                {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status, en)}</option>)}
              </select>
            </label>
            <div className="flex justify-end gap-2 pt-2">
              <button type="button" onClick={() => setEditing(null)} className="rounded-full border border-stone-300 px-4 py-2">
                {t("Cancel", "取消")}
              </button>
              <button disabled={busy === editing.id} className="rounded-full bg-stone-950 px-4 py-2 text-white disabled:opacity-50">
                {busy === editing.id ? t("Saving…", "保存中…") : t("Save", "保存")}
              </button>
            </div>
          </form>
        </AppDialog>
      )}
    </main>
  );
}
