"use client";

import { ChevronDown, Plus, Trash2 } from "lucide-react";
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

const STATUSES = ["INVITED", "SCHEDULED", "INTERVIEWED", "AWAITING_RESULT", "OFFER", "REJECTED", "WITHDRAWN", "NO_RESPONSE"];

const STATUS_STYLE: Record<string, string> = {
  INVITED: "bg-sky-100 text-sky-700",
  SCHEDULED: "bg-violet-100 text-violet-700",
  INTERVIEWED: "bg-blue-100 text-blue-700",
  AWAITING_RESULT: "bg-amber-100 text-amber-800",
  OFFER: "bg-emerald-100 text-emerald-700",
  REJECTED: "bg-rose-100 text-rose-700",
  WITHDRAWN: "bg-stone-200 text-stone-600",
  NO_RESPONSE: "bg-stone-200 text-stone-600",
};

function statusLabel(status: string, en: boolean) {
  const labels: Record<string, [string, string]> = {
    INVITED: ["Invited", "已邀请"],
    SCHEDULED: ["Scheduled", "已约面"],
    INTERVIEWED: ["Interviewed", "已面完"],
    AWAITING_RESULT: ["Awaiting result", "等通知"],
    OFFER: ["Offer", "已发 offer"],
    REJECTED: ["Rejected", "未通过"],
    WITHDRAWN: ["Withdrawn", "已放弃"],
    NO_RESPONSE: ["No response", "无回复"],
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
    api<Opportunity[]>("/api/opportunities")
      .then((result) => { if (active) setItems(result); })
      .catch((reason) => {
        if (!active) return;
        if (reason instanceof ApiError && reason.status === 401) router.replace("/login");
        else setError(reason instanceof Error ? reason.message : t("Could not load interview pipeline", "无法加载面试流程"));
      });
    return () => { active = false; };
    // Loaded once; every later write refreshes from its own response.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [router]);

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
          inviteContent: "",
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
                    {open && (
                      <tr key={`${item.id}-detail`} className="border-b border-stone-100 bg-stone-50/60">
                        <td colSpan={8} className="px-6 py-5">
                          <div className="grid gap-5 md:grid-cols-2">
                            <div>
                              <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">{t("Rounds", "轮次")}</h3>
                              {item.rounds.length === 0 && <p className="mt-2 text-sm text-stone-500">{t("No rounds recorded yet.", "还没有轮次记录。")}</p>}
                              <ul className="mt-2 space-y-1 text-sm text-stone-600">
                                {item.rounds.map((entry) => (
                                  <li key={entry.id}>
                                    {t(`Round ${entry.roundNumber}`, `第 ${entry.roundNumber} 轮`)} · {entry.stage}
                                    {entry.scheduledAt ? ` · ${formatDate(entry.scheduledAt)}` : ""}
                                    {entry.durationMinutes ? ` · ${entry.durationMinutes}min` : ""}
                                  </li>
                                ))}
                              </ul>
                            </div>
                            <div className="space-y-3">
                              {item.jdContent && (
                                <div>
                                  <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">JD</h3>
                                  <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded-xl bg-white p-3 text-xs leading-5 text-stone-600">{item.jdContent}</pre>
                                </div>
                              )}
                              {item.inviteContent && (
                                <div>
                                  <h3 className="text-xs font-medium uppercase tracking-wide text-stone-400">{t("Original invite", "原始邀约")}</h3>
                                  <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded-xl bg-white p-3 text-xs leading-5 text-stone-600">{item.inviteContent}</pre>
                                </div>
                              )}
                            </div>
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
