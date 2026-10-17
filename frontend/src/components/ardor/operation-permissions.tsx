"use client";

import { useEffect, useMemo, useState } from "react";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

type Approval = { id: string; tool: string; arguments: string; status: string; expiresAt: string; module?: string | null; capability?: string | null; suggestedLevel?: number };
const moduleNames: Record<string, [string, string]> = {
  profile: ["profile", "个人资料"], web: ["web search", "网络检索"], knowledge: ["knowledge", "知识库"],
  learning: ["learning plans", "学习计划"], memory: ["memories", "长期记忆"], applications: ["applications", "投递记录"],
  calendar: ["calendar", "日历"], resumes: ["resumes", "简历"], interviews: ["interviews", "面试"],
  recaps: ["interview notes", "面经"], cards: ["flashcards", "记忆卡"], opportunities: ["pipeline records", "面试流程"],
  system: ["background jobs", "后台任务"],
};
const names: Record<string, [string, string]> = {
  update_learning_plan: ["Change a learning plan", "修改学习计划"], begin_learning_plan: ["Start learning", "开始学习"],
  update_calendar_task: ["Change a calendar task", "修改日程"], update_recurring_task: ["Change a recurring schedule", "修改重复安排"],
  create_calendar_task: ["Add a calendar task", "新增日程"], create_recurring_task: ["Add a recurring schedule", "新增重复安排"],
  create_memory_card: ["Add a flashcard", "新增记忆卡"], update_memory_card: ["Edit a flashcard", "修改记忆卡"],
  review_memory_card: ["Record a flashcard review", "登记记忆卡复习"], suspend_memory_card: ["Change flashcard review status", "修改记忆卡复习状态"],
  add_memory: ["Remember this information", "新增长期记忆"], update_memory: ["Correct a memory", "修改长期记忆"],
  record_application_count: ["Record applications", "记录投递数量"], set_application_rhythm: ["Change application goals", "修改投递目标"],
  update_interview_recap_metadata: ["Change interview notes", "修改面经信息"], create_interview: ["Create a mock interview", "创建模拟面试"],
  submit_interview_answer: ["Submit an interview answer", "提交面试回答"], finish_interview: ["Evaluate this interview", "评估模拟面试"],
  cancel_interview: ["Stop this interview and keep its records", "停止面试并保留记录"], analyze_resume: ["Analyze this resume", "分析简历"],
  organize_interview_recap: ["Organize interview notes", "整理面经"], research_knowledge_from_web: ["Add researched material to knowledge", "将调研资料加入知识库"],
};
const fields: Record<string, [string, string]> = {
  title: ["Title", "标题"], scheduledAt: ["Scheduled time", "安排时间"], dueAt: ["Due time", "计划时间"],
  description: ["Description", "说明"], priority: ["Priority", "优先级"], status: ["Status", "状态"],
  content: ["Content", "内容"], memory: ["Memory", "记忆内容"], question: ["Question", "题目"], answer: ["Answer", "答案"],
  tags: ["Tags", "标签"], company: ["Company", "公司"], role: ["Role", "岗位"], interviewAt: ["Interview time", "面试时间"],
  targetRole: ["Target role", "目标岗位"], count: ["Count", "数量"], date: ["Date", "日期"], weeklyTarget: ["Weekly target", "每周目标"],
  enabled: ["Enabled", "启用"], suspended: ["Pause reviews", "暂停复习"], concept: ["Concept", "概念"], reason: ["Reason", "原因"],
  weeklyGoal: ["Weekly application goal", "每周投递目标"], reminderEnabled: ["In-app reminder", "工作台内提醒"], reminderTime: ["Reminder time", "提醒时间"], weekdaysOnly: ["Weekdays only", "仅工作日"],
  timeOfDay: ["Local time", "本地时间"], frequency: ["Frequency", "重复频率"], interval: ["Repeat interval", "重复间隔"], weekdays: ["Days of week", "星期"], monthDay: ["Day of month", "每月日期"], startDate: ["Start date", "开始日期"], endDate: ["End date", "结束日期"],
  occurredAt: ["Interview date", "面试日期"], targetCompany: ["Target company", "目标公司"], rating: ["Review result", "复习结果"], sourceType: ["Source type", "来源类型"], sourceLabel: ["Source", "来源"], sourceUrl: ["Source link", "来源链接"], query: ["Research topic", "调研主题"],
};
const resources: Record<string, [string, string, string]> = {
  update_calendar_task: ["/api/calendar/tasks", "taskId", "title"], update_recurring_task: ["/api/calendar/series", "seriesId", "title"],
  update_memory_card: ["/api/memory-cards", "cardId", "front"], review_memory_card: ["/api/memory-cards", "cardId", "front"], suspend_memory_card: ["/api/memory-cards", "cardId", "front"],
  update_memory: ["/api/agent/memory/items", "memoryId", "content"], update_interview_recap_metadata: ["/api/interview-recaps", "recapId", "title"],
  cancel_interview: ["/api/interviews", "interviewId", "targetRole"], submit_interview_answer: ["/api/interviews", "interviewId", "targetRole"], finish_interview: ["/api/interviews", "interviewId", "targetRole"], analyze_resume: ["/api/resumes", "resumeId", "originalFilename"],
};
const effects: Record<string, [string, string]> = {
  update_recurring_task: ["Only future, unfinished occurrences not individually edited will change. Completed or individually edited occurrences are preserved.", "仅修改未来未完成且未单独编辑的实例；已完成或单独改过的实例保持不变。"],
  update_memory_card: ["This edits the card content and preserves its review history.", "修改卡片内容，保留复习进度。"],
  review_memory_card: ["This records the selected rating and reschedules the next review.", "登记该掌握结果，并重新安排下次复习。"],
  suspend_memory_card: ["This changes whether the card participates in scheduled reviews; it does not delete the card.", "调整该卡是否参与复习，不删除卡片。"],
  add_memory: ["This information will be saved as a long-term memory used across future conversations.", "将保存为长期记忆，用于之后的对话。"],
  update_memory: ["This replaces this memory's content; other memories stay unchanged.", "替换这条记忆的内容，其他记忆不变。"],
  record_application_count: ["This replaces the total for the selected day; it does not add to the old count.", "覆盖所选日期的投递总数，不是在旧数量上累加。"],
  set_application_rhythm: ["This changes your weekly goal and in-app reminders, not operating-system notifications.", "修改每周目标和工作台内提醒，不设置系统通知。"],
  submit_interview_answer: ["This saves the answer to this interview's current question and advances its progress.", "向这场面试当前题目保存回答并推进进度。"],
  finish_interview: ["This submits the interview for background assessment and changes its status.", "提交后台面试评估并改变面试状态。"],
  analyze_resume: ["This submits a background assessment; existing reports remain available.", "提交后台简历分析，已有报告仍可查看。"],
};

export function OperationPermissions({ runId, onConfirming, onConfirmed, onFailed }: { runId: string; onConfirming: (tool: string) => void; onConfirmed: (tool: string) => void; onFailed: (tool: string) => void }) {
  const { t } = useLocale();
  const [items, setItems] = useState<Approval[]>([]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState<string | null>(null);
  useEffect(() => {
    let active = true, timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const value = await api<Approval[]>(`/api/agent/runs/${encodeURIComponent(runId)}/approvals`);
        if (active) setItems(value.filter(item => item.status === "PENDING"));
      } catch { /* The run may not yet be persisted; no approval is inferred from a failed read. */ }
      if (active) timer = setTimeout(() => void poll(), 1000);
    };
    void poll();
    return () => { active = false; clearTimeout(timer); };
  }, [runId]);
  async function decide(item: Approval, allow: boolean, remember = false) {
    setSaving(item.id); setError("");
    if (allow) onConfirming(item.tool);
    try {
      // A persistent module grant is optional; the one-off decision still stands if it fails.
      if (allow && remember && item.module && typeof item.suggestedLevel === "number" && item.suggestedLevel >= 0) {
        await api<void>(`/api/agent/permissions/${encodeURIComponent(item.module)}`, { method: "PUT", body: JSON.stringify({ level: item.suggestedLevel }) })
          .catch(() => undefined);
      }
      await api<void>(`/api/agent/runs/${encodeURIComponent(runId)}/approvals/${encodeURIComponent(item.id)}`, { method: "POST", body: JSON.stringify({ allow }) });
      setItems(current => current.filter(value => value.id !== item.id));
      if (allow) onConfirmed(item.tool);
    } catch (reason) { if (allow) onFailed(item.tool); setError(reason instanceof Error ? reason.message : t("Could not submit your decision.", "无法提交你的决定。")); }
    finally { setSaving(null); }
  }
  if (!items.length) return null;
  return <section aria-label={t("Suggested operations", "操作建议")} className="ardor-composer-enter mx-auto mb-3 max-h-[40dvh] max-w-3xl space-y-4 overflow-y-auto rounded-2xl border border-violet-200 bg-white/95 p-4 shadow-lg backdrop-blur-xl">
    {items.map(item => <Operation key={item.id} item={item} disabled={saving !== null} onDecision={(allow, remember) => void decide(item, allow, remember)} />)}
    {error && <p role="alert" className="break-words text-xs text-rose-600">{error}</p>}
  </section>;
}

function Operation({ item, disabled, onDecision }: { item: Approval; disabled: boolean; onDecision: (allow: boolean, remember?: boolean) => void }) {
  const { t, locale } = useLocale();
  const [remember, setRemember] = useState(false);
  const moduleName = item.module ? moduleNames[item.module] : undefined;
  const canRemember = !!moduleName && item.capability !== "DELETE"
    && typeof item.suggestedLevel === "number" && item.suggestedLevel >= 0;
  const args = useMemo<Record<string, unknown>>(() => { try { const value = JSON.parse(item.arguments); return value && typeof value === "object" && !Array.isArray(value) ? value : {}; } catch { return {}; } }, [item.arguments]);
  const [target, setTarget] = useState("");
  const [previousTime, setPreviousTime] = useState("");
  const [lookupError, setLookupError] = useState(false);
  const [lookupRevision, setLookupRevision] = useState(0);
  const isLearning = item.tool === "update_learning_plan" || item.tool === "begin_learning_plan";
  const resource = resources[item.tool];
  const needsTarget = isLearning || !!resource;
  const hasTargetId = typeof args[isLearning ? "planId" : resource?.[1] ?? ""] === "string";
  useEffect(() => {
    let active = true;
    if (item.tool === "update_learning_plan" || item.tool === "begin_learning_plan") {
      const id = args.planId;
      if (typeof id === "string") void api<{ concept: string; scheduledAt?: string }>(`/api/learning-plans/${encodeURIComponent(id)}`).then(value => { if (!value.concept) throw new Error("Missing target"); if (active) { setTarget(value.concept); setPreviousTime(value.scheduledAt ?? ""); } }).catch(() => { if (active) setLookupError(true); });
    }
    if (resource && typeof args[resource[1]] === "string") void api<Record<string, unknown>[]>(resource[0]).then(values => {
      const value = values.find(entry => entry.id === args[resource[1]]);
      if (!value || typeof value[resource[2]] !== "string" || !value[resource[2]]) throw new Error("Missing target");
      if (active) { setTarget(value[resource[2]] as string); setPreviousTime(typeof value.scheduledAt === "string" ? value.scheduledAt : ""); }
    }).catch(() => { if (active) setLookupError(true); });
    return () => { active = false; };
  }, [args, item.tool, resource, lookupRevision]);
  const name = names[item.tool] ?? ["Apply this change", "执行这项修改"];
  const entries = Object.entries(args).filter(([key, value]) => value != null && !key.toLowerCase().endsWith("id"));
  const effect = effects[item.tool];
  const text = (value: unknown) => {
    if (typeof value === "boolean") return value ? t("Yes", "是") : t("No", "否");
    if (typeof value === "string" && /^\d{4}-\d\d-\d\dT/.test(value) && Number.isFinite(Date.parse(value))) return new Date(value).toLocaleString(locale === "en" ? "en-US" : "zh-CN");
    return typeof value === "string" ? value : JSON.stringify(value);
  };
  return <article className="min-w-0 text-sm">
    <h3 className="break-words font-medium text-stone-800">{t(name[0], name[1])}{target && ` · ${target}`}</h3>
    <p className="mt-1 text-xs text-stone-500">{t("Nothing has changed yet. Confirm to apply the values below, or reject to leave it unchanged.", "目前尚未改动。确认后执行下列内容，拒绝则保持原样。")}</p>
    {effect && <p className="mt-2 text-xs text-stone-600">{t(effect[0], effect[1])}</p>}
    {needsTarget && !target && <p role="status" className="mt-2 text-xs text-amber-700">{lookupError || !hasTargetId ? t("Cannot read the target. Confirmation is disabled until the target is identified.", "无法读取操作对象，识别到具体目标前不能确认。") : t("Reading the target before confirmation…", "正在读取具体操作对象…")}{lookupError && <button type="button" onClick={() => { setLookupError(false); setLookupRevision(value => value + 1); }} className="ml-2 underline">{t("Retry target lookup", "重新读取对象")}</button>}</p>}
    {previousTime && args.scheduledAt != null && <p className="mt-2 text-xs text-stone-500">{t("Current scheduled time", "当前安排时间")}：{text(previousTime)}</p>}
    <dl className="mt-3 space-y-2 text-xs">{entries.map(([key, value]) => { const label = fields[key] ?? [key, key]; return <div key={key}><dt className="text-stone-500">{t(label[0], label[1])}</dt><dd className="mt-1 whitespace-pre-wrap break-words text-stone-800">{text(value)}</dd></div>; })}</dl>
    {item.tool === "update_learning_plan" && <p className="mt-2 text-xs text-stone-500">{t("Lesson content and practice records are preserved.", "讲解内容和练习记录保持不变。")}</p>}
    <details className="mt-2 text-xs text-stone-400"><summary>{t("Technical details", "技术详情")}</summary><pre className="mt-2 whitespace-pre-wrap break-all">{item.arguments}</pre></details>
    {canRemember && <label className="mt-3 flex items-start gap-2 text-xs text-stone-600"><input type="checkbox" checked={remember} disabled={disabled} onChange={(event) => setRemember(event.target.checked)} className="mt-0.5 size-3.5" /><span>{t(`Always allow changes to ${moduleName[0]}`, `以后都允许 Ardor 修改「${moduleName[1]}」`)}<span className="block text-stone-400">{t("You can change this later in settings. Deleting still asks.", "可在设置中随时改回；删除仍然需要确认。")}</span></span></label>}
    <div className="mt-3 flex justify-end gap-2"><button type="button" disabled={disabled} onClick={() => onDecision(false)} className="rounded-full px-4 py-2 text-xs text-stone-600 hover:bg-stone-100 disabled:opacity-50">{t("Reject", "拒绝")}</button><button type="button" disabled={disabled || needsTarget && !target} onClick={() => onDecision(true, remember)} className="rounded-full bg-violet-600 px-4 py-2 text-xs text-white hover:bg-violet-700 disabled:opacity-50">{disabled ? t("Submitting…", "提交中…") : t("Confirm", "确认")}</button></div>
  </article>;
}
