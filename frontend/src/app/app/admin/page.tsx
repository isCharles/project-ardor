"use client";

import { Activity, KeyRound, LoaderCircle, Save, Server, ShieldCheck, Trash2, Users } from "lucide-react";
import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";

import { AdminShell } from "@/components/ardor/admin-shell";
import { Button } from "@/components/ui/button";
import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { usageFeatureLabels } from "@/lib/usage-features";

type ServiceType = "PRIMARY_LLM" | "EMBEDDING" | "ASR" | "TTS" | "FALLBACK_LLM" | "WEB_SEARCH";
type ApiConfig = { serviceType: ServiceType; configured: boolean; provider: string; baseUrl: string | null; model: string | null; keyHint: string | null; updatedAt: string | null };
type User = { id: string; email: string; status: "ACTIVE" | "DISABLED"; role: "USER" | "ADMIN"; membership: "FREE" | "PLUS" | "PRO"; createdAt: string };
type UsagePolicy = { feature: string; freeMonthlyLimit: number; plusMonthlyLimit: number; proMonthlyLimit: number; userMinuteLimit: number; globalMinuteLimit: number; updatedAt: string };
type Plan = { code: "FREE" | "PLUS" | "PRO"; nameZh: string; nameEn: string; priceCents: number; currency: string; billingInterval: string; tokenMonthlyLimit: number; paymentEnabled: boolean };
type RedemptionCode = { id: string; codeHint: string; planTier: "PLUS" | "PRO"; durationDays: number; maxUses: number; usedCount: number; expiresAt: string | null; note: string | null; createdAt: string };
type Overview = { counts: Record<string, number>; databaseAvailable: boolean; apiConfigs: ApiConfig[] };
type Feedback = { kind: "pending" | "success" | "error"; message: string };

export default function AdminPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const services: Array<{ type: ServiceType; label: string; note: string }> = [
    { type: "PRIMARY_LLM", label: t("Primary model", "主模型"), note: t("Agent, resume analysis and interview recaps", "Agent、简历分析与面经") },
    { type: "EMBEDDING", label: t("Embedding model", "向量模型"), note: t("Semantic knowledge search", "知识库语义检索") },
    { type: "WEB_SEARCH", label: t("Web search", "联网搜索"), note: "Tavily" },
    { type: "FALLBACK_LLM", label: t("Fallback model", "备用模型"), note: t("When the primary model is unavailable", "主线路不可用时") },
    { type: "ASR", label: t("Speech recognition", "语音识别"), note: t("Audio transcription", "音频转录") },
    { type: "TTS", label: t("Text to speech", "语音合成"), note: t("Voice interviews", "语音面试") },
  ];
  const countLabels: Record<string, string> = {
    users: t("Users", "用户"), conversations: t("Conversations", "对话"), messages: t("Messages", "消息"), resumes: t("Resumes", "简历"),
    recaps: t("Interview recaps", "面经"), knowledgeDocuments: t("Knowledge", "知识"), calendarTasks: t("Calendar tasks", "日程"), memoryCards: t("Flashcards", "卡片"),
  };
  const englishUsageLabels: Record<string, string> = {
    AGENT_CHAT: "Agent chat", RESUME_ANALYSIS: "Resume analysis", INTERVIEW_CREATE: "Mock interviews",
    INTERVIEW_EVALUATION: "Interview evaluation", INTERVIEW_RECAP: "Interview recaps", INTERVIEW_REPLAY: "Interview replay",
    LEARNING_PLAN: "Learning plans", LEARNING_ATTEMPT: "Learning exercises", KNOWLEDGE_UPLOAD: "Knowledge uploads",
    KNOWLEDGE_SEARCH: "Knowledge search", KNOWLEDGE_RESEARCH: "Web research", MODEL_DESCRIPTION: "Model descriptions",
    CONNECTION_TEST: "Connection tests", VOICE_ASR: "Voice transcription", VOICE_TTS: "Voice synthesis",
  };
  const featureLabel = (feature: string) => locale === "en" ? englishUsageLabels[feature] ?? feature : usageFeatureLabels[feature] ?? feature;
  const [overview, setOverview] = useState<Overview | null>(null);
  const [users, setUsers] = useState<User[]>([]);
  const [policies, setPolicies] = useState<UsagePolicy[]>([]);
  const [plans, setPlans] = useState<Plan[]>([]);
  const [codes, setCodes] = useState<RedemptionCode[]>([]);
  const [generatedCode, setGeneratedCode] = useState("");
  const [codeBusy, setCodeBusy] = useState(false);
  const [feedback, setFeedback] = useState<Partial<Record<ServiceType, Feedback>>>({});
  const [busy, setBusy] = useState("");
  const [error, setError] = useState("");

  async function load() {
    try {
      const [current, nextOverview, nextUsers, nextPolicies, nextPlans, nextCodes] = await Promise.all([
        api<{ admin: boolean }>("/api/auth/me"),
        api<Overview>("/api/admin/overview"),
        api<User[]>("/api/admin/users"),
        api<UsagePolicy[]>("/api/admin/usage-policies"),
        api<Plan[]>("/api/admin/plans"),
        api<RedemptionCode[]>("/api/admin/redemption-codes"),
      ]);
      if (!current.admin) return router.replace("/app");
      setOverview(nextOverview); setUsers(nextUsers); setPolicies(nextPolicies); setPlans(nextPlans); setCodes(nextCodes);
    } catch (reason) {
      if (reason instanceof ApiError && (reason.status === 401 || reason.status === 403)) return router.replace("/app");
      setError(reason instanceof Error ? reason.message : t("Could not load the admin dashboard", "无法加载管理面板"));
    }
  }

  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  async function submitConfig(form: HTMLFormElement, type: ServiceType, action: "save" | "test") {
    const data = new FormData(form);
    const body = JSON.stringify({
      provider: type === "WEB_SEARCH" ? "TAVILY" : data.get("provider"),
      baseUrl: type === "WEB_SEARCH" ? "https://api.tavily.com" : data.get("baseUrl"),
      model: type === "WEB_SEARCH" ? "-" : data.get("model"),
      apiKey: data.get("apiKey"),
    });
    setBusy(`${type}:${action}`);
    setFeedback((current) => ({ ...current, [type]: { kind: "pending", message: action === "save" ? t("Saving securely…", "正在加密保存…") : t("Testing connection…", "正在测试连接…") } }));
    try {
      if (action === "save") {
        const updated = await api<ApiConfig>(`/api/admin/api-configs/${type}`, { method: "PUT", body });
        (form.elements.namedItem("apiKey") as HTMLInputElement).value = "";
        setOverview((current) => current ? { ...current, apiConfigs: current.apiConfigs.map((item) => item.serviceType === type ? updated : item) } : current);
        setFeedback((current) => ({ ...current, [type]: { kind: "success", message: `${t("Enabled as the system default", "已启用为系统默认")}${updated.keyHint ? ` · ${updated.keyHint}` : ""}` } }));
      } else {
        const result = await api<{ message?: string; latencyMs?: number }>(`/api/admin/api-configs/${type}/test`, { method: "POST", body });
        setFeedback((current) => ({ ...current, [type]: { kind: "success", message: `${result.message ?? t("Connected", "连接成功")}${result.latencyMs == null ? "" : ` · ${result.latencyMs} ms`}` } }));
      }
    } catch (reason) {
      setFeedback((current) => ({ ...current, [type]: { kind: "error", message: reason instanceof Error ? reason.message : t("Operation failed", "操作失败") } }));
    } finally { setBusy(""); }
  }

  async function updateUser(user: User, patch: Partial<Pick<User, "status" | "role">>) {
    setBusy(`user:${user.id}`); setError("");
    try {
      const updated = await api<User>(`/api/admin/users/${user.id}`, { method: "PATCH", body: JSON.stringify(patch) });
      setUsers((current) => current.map((item) => item.id === updated.id ? updated : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not update user", "用户更新失败")); }
    finally { setBusy(""); }
  }

  async function updateMembership(user: User, tier: User["membership"]) {
    setBusy(`membership:${user.id}`); setError("");
    try {
      await api(`/api/admin/users/${user.id}/membership`, { method: "PUT", body: JSON.stringify({ tier }) });
      setUsers((current) => current.map((item) => item.id === user.id ? { ...item, membership: tier } : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not update membership", "会员更新失败")); }
    finally { setBusy(""); }
  }

  async function updatePolicy(event: FormEvent<HTMLFormElement>, feature: string) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const body = JSON.stringify({
      freeMonthlyLimit: Number(data.get("freeMonthlyLimit")),
      plusMonthlyLimit: Number(data.get("plusMonthlyLimit")),
      proMonthlyLimit: Number(data.get("proMonthlyLimit")),
      userMinuteLimit: Number(data.get("userMinuteLimit")),
      globalMinuteLimit: Number(data.get("globalMinuteLimit")),
    });
    setBusy(`policy:${feature}`); setError("");
    try {
      const saved = await api<UsagePolicy>(`/api/admin/usage-policies/${feature}`, { method: "PUT", body });
      setPolicies((current) => current.map((item) => item.feature === feature ? saved : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not update quota", "额度更新失败")); }
    finally { setBusy(""); }
  }

  async function updatePlan(event: FormEvent<HTMLFormElement>, code: Plan["code"]) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setBusy(`plan:${code}`); setError("");
    try {
      const saved = await api<Plan>(`/api/admin/plans/${code}`, { method: "PUT", body: JSON.stringify({
        priceCents: Number(data.get("priceCents")), tokenMonthlyLimit: Number(data.get("tokenMonthlyLimit")),
        paymentEnabled: data.get("paymentEnabled") === "on",
      }) });
      setPlans((current) => current.map((item) => item.code === code ? saved : item));
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not update plan", "套餐更新失败")); }
    finally { setBusy(""); }
  }

  async function generateCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setCodeBusy(true); setError(""); setGeneratedCode("");
    try {
      const expiry = String(data.get("expiresAt") ?? "").trim();
      const created = await api<{ code: string }>("/api/admin/redemption-codes", { method: "POST", body: JSON.stringify({
        planTier: data.get("planTier"), durationDays: Number(data.get("durationDays")),
        maxUses: Number(data.get("maxUses")), expiresAt: expiry ? new Date(`${expiry}T23:59:59Z`).toISOString() : null,
        note: data.get("note"),
      }) });
      setGeneratedCode(created.code);
      setCodes(await api<RedemptionCode[]>("/api/admin/redemption-codes"));
      form.reset();
    } catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not generate code", "兑换码生成失败")); }
    finally { setCodeBusy(false); }
  }

  async function removeConfig(type: ServiceType, label: string) {    if (!window.confirm(t(`Remove the system ${label} service? Users without a personal configuration will lose access.`, `移除系统${label}？未设置个人配置的用户将无法使用这项能力。`))) return;
    setBusy(`${type}:delete`);
    try {
      await api<void>(`/api/admin/api-configs/${type}`, { method: "DELETE" });
      await load();
      setFeedback((current) => ({ ...current, [type]: { kind: "success", message: t("System default removed", "系统默认已移除") } }));
    } catch (reason) {
      setFeedback((current) => ({ ...current, [type]: { kind: "error", message: reason instanceof Error ? reason.message : t("Could not remove service", "移除失败") } }));
    } finally { setBusy(""); }
  }

  if (!overview) return <AdminShell><div className="grid min-h-[60vh] place-items-center"><LoaderCircle className="size-5 animate-spin text-[var(--ardor-accent)]" /></div></AdminShell>;

  return <AdminShell>
    <div>
      <div className="mb-10 flex items-end justify-between gap-6">
        <div><p className="t-eyebrow text-[var(--ardor-accent)]">Control plane</p><h1 className="mt-2 text-4xl font-semibold tracking-[-0.045em]">{t("Admin dashboard", "管理工作台")}</h1></div>
        <div className="flex items-center gap-2 text-sm text-emerald-700"><Activity className="size-4" />{t("System online", "系统在线")}</div>
      </div>

      {error && <p className="mb-6 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</p>}

      <section id="overview" className="scroll-mt-8 grid grid-cols-2 gap-px overflow-hidden rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-rule)] md:grid-cols-4">
        {Object.entries(overview.counts).map(([name, value]) => <div key={name} className="bg-[var(--ardor-panel)] p-5"><p className="text-xs text-[var(--ardor-ink-3)]">{countLabels[name] ?? name}</p><p className="mt-2 text-3xl font-semibold tabular-nums">{value.toLocaleString()}</p></div>)}
      </section>

      <section id="api-configs" className="mt-12 scroll-mt-8">
        <div className="mb-5 flex items-center gap-3"><Server className="size-5 text-[var(--ardor-accent)]" /><h2 className="text-xl font-semibold">{t("System APIs", "系统 API")}</h2><span className="text-sm text-[var(--ardor-ink-3)]">{t("Users inherit these defaults unless they set a personal override", "用户默认继承，也可个人覆盖")}</span></div>
        <div className="grid gap-4 lg:grid-cols-2">
          {services.map(({ type, label, note }) => {
            const config = overview.apiConfigs.find((item) => item.serviceType === type)!;
            const state = feedback[type];
            return <form key={`${type}:${config.updatedAt ?? "new"}`} onSubmit={(event) => { event.preventDefault(); void submitConfig(event.currentTarget, type, "save"); }} className="rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-panel)] p-5">
              <div className="flex items-start justify-between"><div><h3 className="font-semibold">{label}</h3><p className="mt-1 text-xs text-[var(--ardor-ink-3)]">{note}</p></div><div className="flex items-center gap-2"><span className={`rounded-full px-2.5 py-1 text-[11px] ${config.configured ? "bg-emerald-50 text-emerald-700" : "bg-[var(--ardor-sunken)] text-[var(--ardor-ink-3)]"}`}>{config.configured ? t("Configured", "已配置") : t("Not configured", "未配置")}</span>{config.configured && <button type="button" aria-label={t(`Remove ${label}`, `移除${label}`)} disabled={!!busy} onClick={() => void removeConfig(type, label)} className="grid size-7 place-items-center rounded-full text-[var(--ardor-ink-3)] hover:bg-rose-50 hover:text-rose-600"><Trash2 className="size-3.5" /></button>}</div></div>
              {type !== "WEB_SEARCH" && <div className="mt-5 grid gap-3 sm:grid-cols-2">
                {type === "PRIMARY_LLM" ? <select name="provider" defaultValue={config.provider || "OPENAI_COMPATIBLE"} className="h-10 rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm"><option value="OPENAI_COMPATIBLE">{t("OpenAI compatible", "OpenAI 兼容")}</option><option value="ANTHROPIC_COMPATIBLE">{t("Anthropic compatible", "Anthropic 兼容")}</option></select> : <input name="provider" required defaultValue={config.provider} placeholder="Provider" className="h-10 rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm" />}
                <input name="model" required defaultValue={config.model ?? ""} placeholder={t("Model name", "模型名称")} className="h-10 rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm" />
                <input name="baseUrl" required defaultValue={config.baseUrl ?? ""} placeholder="Base URL" className="h-10 rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm sm:col-span-2" />
              </div>}
              <div className="mt-3 flex gap-2"><div className="relative min-w-0 flex-1"><KeyRound className="absolute left-3 top-3 size-4 text-[var(--ardor-ink-3)]" /><input name="apiKey" type="password" required={!config.configured} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={config.keyHint ? t(`Saved key ${config.keyHint}; enter a new key to replace it`, `当前已保存 ${config.keyHint}；输入新 Key 才会替换`) : "API Key"} className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent pl-9 pr-3 text-sm" /></div><button type="button" disabled={!!busy} onClick={(event) => { const form = event.currentTarget.form; if (form?.reportValidity()) void submitConfig(form, type, "test"); }} className="rounded-xl border border-[var(--ardor-rule)] px-3 text-sm">{t("Test", "测试")}</button><button disabled={!!busy} aria-label={t(`Save ${label} configuration`, `保存${label}配置`)} className="grid size-10 place-items-center rounded-xl bg-[var(--ardor-ink)] text-white"><Save className="size-4" /></button></div>
              {state && <p className={`mt-3 text-xs ${state.kind === "error" ? "text-rose-600" : state.kind === "success" ? "text-emerald-700" : "text-[var(--ardor-ink-3)]"}`}>{state.message}</p>}
            </form>;
          })}
        </div>
      </section>

      <section id="quotas" className="mt-12 scroll-mt-8">
        <div className="mb-5"><h2 className="text-xl font-semibold">{t("Quotas", "额度")}</h2><p className="mt-1 text-sm text-[var(--ardor-ink-3)]">{t("Monthly limits apply per user and feature; short-term limits protect system costs.", "按用户、功能和自然月计算；短时上限保护系统成本。")}</p></div>
        <div className="grid gap-3 lg:grid-cols-2">{policies.map((policy) => <form key={policy.feature} onSubmit={(event) => void updatePolicy(event, policy.feature)} className="rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-panel)] p-5">
          <div className="flex items-center justify-between"><h3 className="font-medium">{featureLabel(policy.feature)}</h3><button disabled={!!busy} className="grid size-9 place-items-center rounded-xl bg-[var(--ardor-ink)] text-white" aria-label={t(`Save ${featureLabel(policy.feature)} quota`, `保存${featureLabel(policy.feature)}额度`)}><Save className="size-4" /></button></div>
          <div className="mt-4 grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">{([
            ["freeMonthlyLimit", t("Free / month", "免费 / 月")], ["plusMonthlyLimit", t("Plus / month", "Plus / 月")],
            ["proMonthlyLimit", t("Pro / month", "Pro / 月")], ["userMinuteLimit", t("Per user / min", "每人 / 分")],
            ["globalMinuteLimit", t("Global / min", "全站 / 分")],
          ] as const).map(([field, label]) => <label key={field} className="space-y-2 text-[var(--ardor-ink-3)]"><span>{label}</span><input name={field} type="number" min={field.includes("Monthly") ? 0 : 1} required defaultValue={policy[field]} className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" /></label>)}</div>
        </form>)}</div>
      </section>

      <section id="plans" className="mt-12 scroll-mt-8">
        <div className="mb-5"><h2 className="text-xl font-semibold">{t("Plans", "套餐")}</h2><p className="mt-1 text-sm text-[var(--ardor-ink-3)]">{t("Token budgets are monthly per user. Module limits above still apply together.", "每个用户每月的 Token 预算；上方各模块次数限制同时生效。")}</p></div>
        <div className="grid gap-3 md:grid-cols-3">{plans.map((plan) => <form key={plan.code} onSubmit={(event) => void updatePlan(event, plan.code)} className="rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-panel)] p-5">
          <div className="flex items-center justify-between"><h3 className="font-medium">{locale === "en" ? plan.nameEn : plan.nameZh}</h3><button disabled={!!busy} className="grid size-9 place-items-center rounded-xl bg-[var(--ardor-ink)] text-white" aria-label={t(`Save ${plan.code} plan`, `保存 ${plan.code} 套餐`)}><Save className="size-4" /></button></div>
          <div className="mt-4 space-y-3 text-xs text-[var(--ardor-ink-3)]"><label className="block space-y-2"><span>{t("Price (cents)", "价格（分）")}</span><input name="priceCents" type="number" min={0} required defaultValue={plan.priceCents} className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" /></label><label className="block space-y-2"><span>{t("Monthly tokens", "每月 Token")}</span><input name="tokenMonthlyLimit" type="number" min={0} required defaultValue={plan.tokenMonthlyLimit} className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" /></label><label className="flex items-center gap-2"><input name="paymentEnabled" type="checkbox" defaultChecked={plan.paymentEnabled} />{t("Payment enabled", "开放支付")}</label></div>
        </form>)}</div>
      </section>

      <section id="redemption" className="mt-12 scroll-mt-8">
        <div className="mb-5"><h2 className="text-xl font-semibold">{t("Redemption codes", "兑换码")}</h2><p className="mt-1 text-sm text-[var(--ardor-ink-3)]">{t("Issue plan codes for Plus or Pro. The full code is shown only once after generation.", "为 Plus / Pro 生成兑换码；完整兑换码只在生成时显示一次。")}</p></div>
        <div className="grid gap-4 lg:grid-cols-[1fr_1.25fr]">
          <form onSubmit={(event) => void generateCode(event)} className="rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-panel)] p-5">
            <div className="grid grid-cols-2 gap-3 text-xs text-[var(--ardor-ink-3)]"><label className="space-y-2"><span>{t("Plan", "套餐")}</span><select name="planTier" className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]"><option value="PLUS">Plus</option><option value="PRO">Pro</option></select></label><label className="space-y-2"><span>{t("Duration (days)", "有效天数")}</span><input name="durationDays" type="number" min={1} defaultValue={30} required className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" /></label><label className="space-y-2"><span>{t("Max uses", "最多兑换人数")}</span><input name="maxUses" type="number" min={1} defaultValue={1} required className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" /></label><label className="space-y-2"><span>{t("Code expires", "兑换码截止日")}</span><input name="expiresAt" type="date" className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" /></label><label className="col-span-2 space-y-2"><span>{t("Note", "备注")}</span><input name="note" maxLength={200} className="h-10 w-full rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-sm text-[var(--ardor-ink)]" placeholder={t("Optional", "选填")} /></label></div>
            <Button className="mt-4" disabled={codeBusy}>{codeBusy ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <KeyRound className="mr-2 size-4" />}{t("Generate code", "生成兑换码")}</Button>
            {generatedCode && <div className="mt-4 rounded-2xl bg-emerald-50 p-3"><p className="text-xs text-emerald-800">{t("Copy this code now; it will not be shown again.", "请立即复制，之后不再显示完整兑换码。")}</p><p className="mt-2 break-all font-mono text-lg font-semibold text-emerald-900">{generatedCode}</p></div>}
          </form>
          <div className="rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-panel)] p-2">
            {codes.length === 0 ? <p className="p-4 text-sm text-[var(--ardor-ink-3)]">{t("No codes generated yet.", "还没有生成兑换码。")}</p> : codes.map((code) => <div key={code.id} className="flex flex-wrap items-center justify-between gap-2 border-b border-[var(--ardor-rule)] px-3 py-3 text-xs last:border-0"><div><p className="font-mono font-medium">{code.codeHint}</p><p className="mt-1 text-[var(--ardor-ink-3)]">{code.planTier} · {code.durationDays} {t("days", "天")} · {code.usedCount}/{code.maxUses} {t("used", "已用")}{code.expiresAt ? ` · ${new Date(code.expiresAt).toLocaleDateString(locale === "en" ? "en-US" : "zh-CN")}` : ""}</p></div><span className={`rounded-full px-2 py-1 ${code.usedCount >= code.maxUses ? "bg-stone-100 text-stone-500" : "bg-emerald-50 text-emerald-700"}`}>{code.usedCount >= code.maxUses ? t("Used up", "已用完") : t("Active", "可用")}</span></div>)}
          </div>
        </div>
      </section>

      <section id="users" className="mt-12 scroll-mt-8">
        <div className="mb-5 flex items-center gap-3"><Users className="size-5 text-[var(--ardor-accent)]" /><h2 className="text-xl font-semibold">{t("Users", "用户")}</h2><span className="text-sm text-[var(--ardor-ink-3)]">{t(`${users.length} accounts`, `${users.length} 个账户`)}</span></div>
        <div className="overflow-hidden rounded-3xl border border-[var(--ardor-rule)] bg-[var(--ardor-panel)]">
          {users.map((user, index) => <div key={user.id} className={`flex flex-col gap-3 p-4 md:flex-row md:items-center ${index ? "border-t border-[var(--ardor-rule)]" : ""}`}>
            <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium">{user.email}</p><p className="mt-1 text-xs text-[var(--ardor-ink-3)]">{new Date(user.createdAt).toLocaleDateString(locale === "en" ? "en-US" : "zh-CN")}</p></div>
            <div className="flex items-center gap-2"><select aria-label={t(`${user.email} membership`, `${user.email} 的会员`)} value={user.membership} disabled={!!busy} onChange={(event) => void updateMembership(user, event.target.value as User["membership"])} className="h-9 rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-xs"><option value="FREE">{t("Free", "免费")}</option><option value="PLUS">Plus</option><option value="PRO">Pro</option></select><select aria-label={t(`${user.email} role`, `${user.email} 的角色`)} value={user.role} disabled={!!busy} onChange={(event) => void updateUser(user, { role: event.target.value as User["role"] })} className="h-9 rounded-xl border border-[var(--ardor-rule)] bg-transparent px-3 text-xs"><option value="USER">{t("User", "用户")}</option><option value="ADMIN">{t("Admin", "管理员")}</option></select><button disabled={!!busy} onClick={() => void updateUser(user, { status: user.status === "ACTIVE" ? "DISABLED" : "ACTIVE" })} className={`h-9 rounded-xl px-3 text-xs ${user.status === "ACTIVE" ? "bg-emerald-50 text-emerald-700" : "bg-rose-50 text-rose-700"}`}>{user.status === "ACTIVE" ? t("Active", "正常") : t("Disabled", "已禁用")}</button></div>
          </div>)}
        </div>
      </section>
      <div className="mt-8 flex items-center gap-2 text-xs text-[var(--ardor-ink-3)]"><ShieldCheck className="size-4" />{t("Only the last four key characters are shown; admins cannot read user content.", "密钥仅显示末四位；管理员不读取用户内容。")}</div>
    </div>
  </AdminShell>;
}
