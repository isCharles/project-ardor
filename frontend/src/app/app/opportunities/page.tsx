"use client";

import { ChevronDown, FileText, GraduationCap, Layers, Plus, Trash2 } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, Fragment, useEffect, useState } from "react";

import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

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
type MemoryCardRef = { id: string; recapQuestionId: string | null; front: string; status: string };
type LearningPlanRef = { id: string; concept: string; sourceType: string; sourceId: string | null; status: string };

const STATUSES = ["SCHEDULED", "AWAITING_RESULT", "NEXT_ROUND", "REJECTED"];

const STATUS_STYLE: Record<string, string> = {
  SCHEDULED: "bg-violet-100 text-violet-700",
  AWAITING_RESULT: "bg-amber-100 text-amber-800",
  NEXT_ROUND: "bg-sky-100 text-sky-700",
  REJECTED: "bg-rose-100 text-rose-700",
};

function statusLabel(status: string, en: boolean) {
  const labels: Record<string, [string, string]> = {
    SCHEDULED: ["Scheduled", "已约面"],
    AWAITING_RESULT: ["Awaiting result", "等通知"],
    NEXT_ROUND: ["Next round", "下一轮"],
    REJECTED: ["Rejected", "被拒绝"],
  };
  const pair = labels[status] ?? [status, status];
  return en ? pair[0] : pair[1];
}

function latestRound(item: Opportunity): Round | null {
  return item.rounds.length === 0 ? null : item.rounds[item.rounds.length - 1];
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
        const requested = new URLSearchParams(window.location.search).get("selected");
        if (requested && result.some((item) => item.id === requested)) setExpanded(requested);
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
    // A plan's source may be the whole recap or one of its questions; both are valid.
    const questionIds = new Set(linked.flatMap((recap) => recap.questions.map((question) => question.id)));
    return plans.filter((plan) => plan.sourceType === "RECAP" && plan.sourceId !== null
      && (recapIds.has(plan.sourceId) || questionIds.has(plan.sourceId)));
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

  async function setStatus(item: Opportunity, status: string) {
    if (status === item.status) return;
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

  return (
    <main className="ardor-workbench min-h-screen px-5 py-8 md:px-10">
      <div className="mx-auto max-w-6xl">
        <header className="flex flex-wrap items-end justify-between gap-4 pb-8">
          <div>
            <h1 className="text-3xl font-semibold tracking-tight md:text-4xl">{t("Interview pipeline", "面试流程")}</h1>
            <p className="mt-2 text-sm text-muted-foreground">
              {t("Every real interview, one row each — result, date, round, company, platform.", "每条真实面试一行：结果、时间、轮次、公司、平台。")}
            </p>
          </div>
          <button type="button" onClick={() => setFormOpen((open) => !open)}
            className="inline-flex items-center gap-2 rounded-full bg-stone-950 px-5 py-2.5 text-sm font-medium text-white hover:bg-stone-800">
            <Plus className="size-4" />{t("New record", "新建记录")}
          </button>
        </header>

        {error && <div role="alert" className="mb-5 rounded-2xl bg-red-50 px-5 py-4 text-sm text-red-700">{error}</div>}

        {formOpen && (
          <form onSubmit={create} className="ardor-panel mb-6 grid gap-4 rounded-[2rem] p-6 md:grid-cols-3">
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
          <table className="w-full min-w-[860px] border-collapse text-left text-sm">
            <thead className="text-xs uppercase tracking-wide text-stone-400">
              <tr className="border-b border-stone-200">
                <th className="px-4 py-3 font-medium">{t("Role", "岗位")}</th>
                <th className="px-4 py-3 font-medium">{t("Result", "结果")}</th>
                <th className="px-4 py-3 font-medium">{t("Date", "时间")}</th>
                <th className="px-4 py-3 font-medium">{t("Round", "轮次")}</th>
                <th className="px-4 py-3 font-medium">{t("Company", "公司")}</th>
                <th className="px-4 py-3 font-medium">{t("Platform", "平台")}</th>
                <th className="px-4 py-3 font-medium">{t("Duration", "时长")}</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {items.length === 0 && (
                <tr><td colSpan={8} className="px-4 py-14 text-center text-stone-400">
                  {t("No interviews yet. Paste an HR invite to the agent, or add one here.", "还没有面试记录。可以把 HR 邀请粘贴给 Agent，或在这里手动添加。")}
                </td></tr>
              )}
              {items.map((item) => {
                const round = latestRound(item);
                const open = expanded === item.id;
                return (
                  <Fragment key={item.id}>
                    <tr className={`border-b border-stone-100 align-top ${busy === item.id ? "opacity-60" : ""}`}>
                      <td className="px-4 py-3">
                        <button type="button" onClick={() => setExpanded(open ? null : item.id)}
                          className="flex items-start gap-2 text-left font-medium hover:text-violet-700">
                          <ChevronDown className={`mt-0.5 size-4 shrink-0 text-stone-400 transition ${open ? "rotate-180" : ""}`} />
                          <span className="line-clamp-2">{item.targetRole || t("Untitled role", "未填写岗位")}</span>
                        </button>
                        {recapsFor(item).length > 0 && (
                          <Link href={`/app/recaps?selected=${recapsFor(item)[0].id}`}
                            className="ml-6 mt-1 inline-flex items-center gap-1 rounded-full bg-violet-50 px-2 py-0.5 text-[11px] font-medium text-violet-700 hover:bg-violet-100">
                            <FileText className="size-3" />{t("Notes", "面经")}
                          </Link>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <select value={item.status} disabled={busy === item.id} onChange={(event) => void setStatus(item, event.target.value)}
                          className={`rounded-full px-3 py-1 text-xs font-medium ${STATUS_STYLE[item.status] ?? "bg-stone-100 text-stone-600"}`}>
                          {STATUSES.map((status) => <option key={status} value={status}>{statusLabel(status, en)}</option>)}
                        </select>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3 text-stone-600">
                        {formatDate(round?.occurredAt ?? round?.scheduledAt ?? null)}
                      </td>
                      <td className="px-4 py-3 text-stone-600">{item.currentRound || "—"}</td>
                      <td className="px-4 py-3 text-stone-600">{item.company}</td>
                      <td className="px-4 py-3">
                        {item.platform ? <span className="rounded-lg bg-stone-100 px-2 py-0.5 text-xs text-stone-600">{item.platform}</span> : "—"}
                      </td>
                      <td className="px-4 py-3 text-stone-600">{round?.durationMinutes ? `${round.durationMinutes}` : "—"}</td>
                      <td className="px-4 py-3 text-right">
                        <button type="button" aria-label={t("Delete record", "删除记录")} disabled={busy === item.id}
                          onClick={() => void remove(item)} className="rounded-lg p-2 text-stone-300 hover:bg-rose-50 hover:text-rose-600">
                          <Trash2 className="size-4" />
                        </button>
                      </td>
                    </tr>
                    {(item.jdContent || item.inviteContent) && (
                      <tr className="border-b border-stone-100 bg-stone-50/40 align-top">
                        <td colSpan={4} className="px-4 pb-3 text-xs leading-5 text-stone-600">
                          {item.jdContent && (
                            <div>
                              <span className="mr-1 font-semibold text-sky-700">JD</span>
                              <span className="line-clamp-2 whitespace-pre-wrap">{item.jdContent}</span>
                            </div>
                          )}
                        </td>
                        <td colSpan={4} className="px-4 pb-3 text-xs leading-5 text-stone-600">
                          {item.inviteContent && (
                            <div>
                              <span className="mr-1 font-semibold text-rose-600">{t("Invite", "约面邮件")}</span>
                              <span className="line-clamp-2 whitespace-pre-wrap">{item.inviteContent}</span>
                            </div>
                          )}
                        </td>
                      </tr>
                    )}
                    {open && (
                      <tr key={`${item.id}-detail`} className="border-b border-stone-100 bg-stone-50/60">
                        <td colSpan={8} className="px-6 py-5">
                          <div className="space-y-2">
                            {recapsFor(item).map((recap) => (
                              <Link key={recap.id} href={`/app/recaps?selected=${recap.id}`}
                                className="flex items-center gap-3 rounded-2xl bg-violet-50 px-4 py-2.5 text-sm text-violet-900 transition hover:bg-violet-100">
                                <FileText className="size-4 shrink-0 text-violet-600" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-violet-600">{t("Notes", "面经")}</span>
                                <span className="min-w-0 flex-1 truncate">{recap.title}</span>
                              </Link>
                            ))}
                            {cardsFor(item).length > 0 && (
                              <Link href="/app/cards"
                                className="flex items-center gap-3 rounded-2xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-900 transition hover:bg-emerald-100">
                                <Layers className="size-4 shrink-0 text-emerald-600" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-emerald-600">{t("Flashcards", "记忆卡")}</span>
                                <span className="min-w-0 flex-1 truncate">{cardsFor(item).map((card) => card.front).join(" · ")}</span>
                                <span className="shrink-0 rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-medium text-emerald-700">{cardsFor(item).length}</span>
                              </Link>
                            )}
                            {plansFor(item).map((plan) => (
                              <Link key={plan.id} href={`/app/learning?id=${plan.id}`}
                                className="flex items-center gap-3 rounded-2xl bg-amber-50 px-4 py-2.5 text-sm text-amber-900 transition hover:bg-amber-100">
                                <GraduationCap className="size-4 shrink-0 text-amber-600" />
                                <span className="shrink-0 text-[11px] font-semibold uppercase tracking-wide text-amber-600">{t("Learning", "学习")}</span>
                                <span className="min-w-0 flex-1 truncate">{plan.concept}</span>
                                <span className="shrink-0 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700">{plan.status}</span>
                              </Link>
                            ))}
                            {recapsFor(item).length === 0 && cardsFor(item).length === 0 && plansFor(item).length === 0 && (
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
    </main>
  );
}
