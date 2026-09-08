"use client";

import { Activity, ArrowLeft, Bot, CheckCircle2, CircleAlert, Database, Globe2, KeyRound, LoaderCircle, LogOut, Mic, RotateCcw, Save, UserRound, Volume2, Wifi } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, MouseEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ardor/overlay";
import { ApiError, api } from "@/lib/api";

type CurrentUser = { id: string; email: string };
type Profile = { displayName: string | null; headline: string | null; targetRoles: string[]; timezone: string };
type LlmConfig = {
  configured: boolean;
  provider: "OPENAI_COMPATIBLE" | "ANTHROPIC_COMPATIBLE";
  baseUrl: string;
  model: string;
  keyHint: string | null;
  configurationSource: "PERSONAL" | "ADMIN" | "NONE";
  personalOverride: boolean;
};
type WebSearchConfig = { configured: boolean; provider: "TAVILY"; keyHint: string | null; configurationSource: "PERSONAL" | "ADMIN" | "NONE"; personalOverride: boolean };
type AuxiliaryServiceType = "EMBEDDING" | "ASR" | "TTS" | "FALLBACK_LLM";
type AuxiliaryApiConfig = {
  serviceType: AuxiliaryServiceType;
  configured: boolean;
  provider: string;
  baseUrl: string;
  model: string;
  keyHint: string | null;
  configurationSource: "PERSONAL" | "ADMIN" | "NONE";
  personalOverride: boolean;
};
type InlineFeedback = { kind: "pending" | "success" | "error"; message: string };
type HealthServiceType = "PRIMARY_LLM" | "WEB_SEARCH" | AuxiliaryServiceType;
type HealthResult = { kind: "pending" | "success" | "error"; message: string };

const integrationDetails = {
  EMBEDDING: { title: "向量模型", description: "用于知识库语义检索与长期记忆。", icon: Database },
  ASR: { title: "语音识别 ASR", description: "用于语音面试的回答转写。", icon: Mic },
  TTS: { title: "语音合成 TTS", description: "用于朗读语音面试题目。", icon: Volume2 },
  FALLBACK_LLM: { title: "备用 LLM", description: "主模型不可用时的备用线路。", icon: Bot },
} satisfies Record<AuxiliaryServiceType, { title: string; description: string; icon: typeof Database }>;

const healthServiceLabels: Record<HealthServiceType, string> = {
  PRIMARY_LLM: "主 LLM",
  WEB_SEARCH: "联网搜索",
  EMBEDDING: "向量模型",
  ASR: "语音识别 ASR",
  TTS: "语音合成 TTS",
  FALLBACK_LLM: "备用 LLM",
};

export default function SettingsPage() {
  const router = useRouter();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [llm, setLlm] = useState<LlmConfig | null>(null);
  const [webSearch, setWebSearch] = useState<WebSearchConfig | null>(null);
  const [integrations, setIntegrations] = useState<AuxiliaryApiConfig[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [testingLlm, setTestingLlm] = useState(false);
  const [savingLlm, setSavingLlm] = useState(false);
  const [testingWebSearch, setTestingWebSearch] = useState(false);
  const [savingWebSearch, setSavingWebSearch] = useState(false);
  const [llmFeedback, setLlmFeedback] = useState<InlineFeedback | null>(null);
  const [webSearchFeedback, setWebSearchFeedback] = useState<InlineFeedback | null>(null);
  const [testingIntegrations, setTestingIntegrations] = useState<Partial<Record<AuxiliaryServiceType, boolean>>>({});
  const [savingIntegrations, setSavingIntegrations] = useState<Partial<Record<AuxiliaryServiceType, boolean>>>({});
  const [integrationFeedback, setIntegrationFeedback] = useState<Partial<Record<AuxiliaryServiceType, InlineFeedback>>>({});
  const [showPersonalApis, setShowPersonalApis] = useState(false);
  const [healthDialogOpen, setHealthDialogOpen] = useState(false);
  const [testingAll, setTestingAll] = useState(false);
  const [healthResults, setHealthResults] = useState<Record<HealthServiceType, HealthResult> | null>(null);

  useEffect(() => {
    Promise.all([
      api<CurrentUser>("/api/auth/me"),
      api<Profile>("/api/profile"),
      api<LlmConfig>("/api/settings/llm"),
      api<WebSearchConfig>("/api/settings/web-search"),
      api<AuxiliaryApiConfig[]>("/api/settings/integrations"),
    ])
      .then(([currentUser, currentProfile, currentLlm, currentWebSearch, currentIntegrations]) => {
        setUser(currentUser); setProfile(currentProfile); setLlm(currentLlm);
        setWebSearch(currentWebSearch); setIntegrations(currentIntegrations);
      })
      .catch((reason) => {
        if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
        setError(reason instanceof Error ? reason.message : "无法加载设置");
      })
      .finally(() => setLoading(false));
  }, [router]);

  async function runSave(action: () => Promise<string>) {
    setError(""); setNotice("");
    try { setNotice(await action()); }
    catch (reason) { setError(reason instanceof Error ? reason.message : "保存失败"); }
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    await runSave(async () => {
      const updated = await api<Profile>("/api/profile", {
        method: "PUT",
        body: JSON.stringify({
          displayName: data.get("displayName"),
          headline: data.get("headline"),
          targetRoles: String(data.get("targetRoles") ?? "").split(/[，,]/).map((role) => role.trim()).filter(Boolean),
          timezone: String(data.get("timezone") ?? "Asia/Shanghai"),
        }),
      });
      setProfile(updated);
      return "个人资料已保存";
    });
  }

  async function saveLlm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setSavingLlm(true); setLlmFeedback({ kind: "pending", message: "正在加密保存…" });
    try {
      const updated = await api<LlmConfig>("/api/settings/llm", {
        method: "PUT",
        body: JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") }),
      });
      setLlm(updated);
      (form.elements.namedItem("apiKey") as HTMLInputElement).value = "";
      setLlmFeedback({ kind: "success", message: `已加密保存${updated.keyHint ? ` · ${updated.keyHint}` : ""}` });
    } catch (reason) {
      setLlmFeedback({ kind: "error", message: reason instanceof Error ? reason.message : "保存失败" });
    } finally { setSavingLlm(false); }
  }

  async function testLlm(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form;
    if (!form || !form.reportValidity()) return;
    const data = new FormData(form);
    setTestingLlm(true); setLlmFeedback({ kind: "pending", message: "正在测试模型连接…" });
    try {
      const body = JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") });
      let result: { message: string; model: string; latencyMs: number } | null = null;
      for (let attempt = 0; attempt <= 5; attempt += 1) {
        if (attempt > 0) {
          setLlmFeedback({ kind: "pending", message: `网络波动，正在进行第 ${attempt}/5 次重试…` });
          await new Promise((resolve) => window.setTimeout(resolve, Math.min(1500 * (2 ** (attempt - 1)), 8000)));
        }
        try {
          result = await api<{ message: string; model: string; latencyMs: number }>("/api/settings/llm/test", { method: "POST", body });
          break;
        } catch (reason) {
          if (!(reason instanceof ApiError) || reason.body.retryable !== true || attempt === 5) throw reason;
        }
      }
      if (!result) throw new Error("连接测试未返回结果");
      setLlmFeedback({ kind: "success", message: `${result.message} · ${result.model} · ${result.latencyMs} ms` });
    } catch (reason) { setLlmFeedback({ kind: "error", message: reason instanceof Error ? reason.message : "连接测试失败" }); }
    finally { setTestingLlm(false); }
  }

  async function testAllConnections() {
    const serviceTypes: HealthServiceType[] = ["PRIMARY_LLM", "WEB_SEARCH", "EMBEDDING", "ASR", "TTS", "FALLBACK_LLM"];
    const integrationConfigs = integrations ?? [];
    const pending = Object.fromEntries(serviceTypes.map((type) => [type, { kind: "pending", message: "等待检测…" }])) as Record<HealthServiceType, HealthResult>;
    setHealthResults(pending);
    setHealthDialogOpen(true);
    setTestingAll(true);

    const update = (type: HealthServiceType, result: HealthResult) => {
      setHealthResults((current) => current ? { ...current, [type]: result } : current);
    };
    const formData = (id: string) => {
      const form = document.getElementById(id);
      if (!(form instanceof HTMLFormElement)) throw new Error("配置表单尚未载入");
      return new FormData(form);
    };
    const run = async (type: HealthServiceType, action: () => Promise<string>) => {
      update(type, { kind: "pending", message: "正在连接…" });
      try {
        update(type, { kind: "success", message: await action() });
      } catch (reason) {
        update(type, { kind: "error", message: reason instanceof Error ? reason.message : "连接失败" });
      }
    };

    await Promise.all([
      run("PRIMARY_LLM", async () => {
        const data = formData("personal-api-primary-llm");
        const result = await api<{ message: string; model: string; latencyMs: number }>("/api/settings/llm/test", {
          method: "POST",
          body: JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") }),
        });
        return `${result.model} · ${result.latencyMs} ms`;
      }),
      run("WEB_SEARCH", async () => {
        const data = formData("personal-api-web-search");
        const result = await api<{ message: string; latencyMs: number; usage: number | null; limit: number | null }>("/api/settings/web-search/test", {
          method: "POST", body: JSON.stringify({ apiKey: data.get("apiKey") }),
        });
        const usage = result.usage == null || result.limit == null ? "" : ` · 用量 ${result.usage}/${result.limit}`;
        return `${result.latencyMs} ms${usage}`;
      }),
      ...integrationConfigs.map((integration) => run(integration.serviceType, async () => {
        const data = formData(`personal-api-${integration.serviceType.toLowerCase()}`);
        const result = await api<{ success: boolean; message: string; latencyMs: number }>(`/api/settings/integrations/${integration.serviceType}/test`, {
          method: "POST",
          body: JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") }),
        });
        return `${result.message} · ${result.latencyMs} ms`;
      })),
    ]);
    setTestingAll(false);
  }

  async function saveWebSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setSavingWebSearch(true); setWebSearchFeedback({ kind: "pending", message: "正在加密保存…" });
    try {
      const updated = await api<WebSearchConfig>("/api/settings/web-search", {
        method: "PUT",
        body: JSON.stringify({ apiKey: data.get("apiKey") }),
      });
      setWebSearch(updated);
      (form.elements.namedItem("apiKey") as HTMLInputElement).value = "";
      setWebSearchFeedback({ kind: "success", message: `已加密保存 · ${updated.keyHint}` });
    } catch (reason) {
      setWebSearchFeedback({ kind: "error", message: reason instanceof Error ? reason.message : "保存失败" });
    } finally { setSavingWebSearch(false); }
  }

  async function testWebSearch(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form;
    if (!form || !form.reportValidity()) return;
    const data = new FormData(form);
    setTestingWebSearch(true); setWebSearchFeedback({ kind: "pending", message: "正在验证 Tavily API Key 与用量…" });
    try {
      const result = await api<{ message: string; latencyMs: number; usage: number | null; limit: number | null }>("/api/settings/web-search/test", {
        method: "POST",
        body: JSON.stringify({ apiKey: data.get("apiKey") }),
      });
      const usage = result.usage == null || result.limit == null ? "" : ` · 当前用量 ${result.usage}/${result.limit}`;
      setWebSearchFeedback({ kind: "success", message: `${result.message} · ${result.latencyMs} ms${usage}` });
    } catch (reason) { setWebSearchFeedback({ kind: "error", message: reason instanceof Error ? reason.message : "联网搜索测试失败" }); }
    finally { setTestingWebSearch(false); }
  }

  async function saveIntegration(event: FormEvent<HTMLFormElement>, serviceType: AuxiliaryServiceType) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setSavingIntegrations((current) => ({ ...current, [serviceType]: true }));
    setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "pending", message: "正在加密保存…" } }));
    try {
      const updated = await api<AuxiliaryApiConfig>(`/api/settings/integrations/${serviceType}`, {
        method: "PUT",
        body: JSON.stringify({
          provider: data.get("provider"), baseUrl: data.get("baseUrl"),
          model: data.get("model"), apiKey: data.get("apiKey"),
        }),
      });
      setIntegrations((current) => current?.map((item) => item.serviceType === serviceType ? updated : item) ?? [updated]);
      (form.elements.namedItem("apiKey") as HTMLInputElement).value = "";
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "success", message: `已加密保存 · ${updated.keyHint}` } }));
    } catch (reason) {
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "error", message: reason instanceof Error ? reason.message : "保存失败" } }));
    } finally {
      setSavingIntegrations((current) => ({ ...current, [serviceType]: false }));
    }
  }

  async function testIntegration(event: MouseEvent<HTMLButtonElement>, serviceType: AuxiliaryServiceType) {
    const form = event.currentTarget.form;
    if (!form || !form.reportValidity()) return;
    const data = new FormData(form);
    setTestingIntegrations((current) => ({ ...current, [serviceType]: true }));
    setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "pending", message: "正在验证服务地址和 API Key…" } }));
    try {
      const result = await api<{ success: boolean; message: string; latencyMs: number }>(`/api/settings/integrations/${serviceType}/test`, {
        method: "POST",
        body: JSON.stringify({
          provider: data.get("provider"), baseUrl: data.get("baseUrl"),
          model: data.get("model"), apiKey: data.get("apiKey"),
        }),
      });
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "success", message: `${result.message} · ${result.latencyMs} ms` } }));
    } catch (reason) {
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "error", message: reason instanceof Error ? reason.message : "连接测试失败" } }));
    } finally {
      setTestingIntegrations((current) => ({ ...current, [serviceType]: false }));
    }
  }

  async function restoreAdminDefault(kind: "llm" | "web-search" | AuxiliaryServiceType) {
    const endpoint = kind === "llm"
      ? "/api/settings/llm"
      : kind === "web-search"
        ? "/api/settings/web-search"
        : `/api/settings/integrations/${kind}`;
    try {
      await api<void>(endpoint, { method: "DELETE" });
      if (kind === "llm") setLlm(await api<LlmConfig>(endpoint));
      else if (kind === "web-search") setWebSearch(await api<WebSearchConfig>(endpoint));
      else {
        const updated = await api<AuxiliaryApiConfig[]>("/api/settings/integrations");
        setIntegrations(updated);
      }
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "无法恢复管理员默认配置");
    }
  }

  async function logout() {
    await api<void>("/api/auth/logout", { method: "POST" });
    router.replace("/login");
  }

  if (loading) return <main className="ardor-workbench grid min-h-screen place-items-center text-muted-foreground">正在载入设置…</main>;
  if (!user || !profile || !llm || !webSearch || !integrations) return <main className="ardor-workbench grid min-h-screen place-items-center px-6 text-red-700">{error || "设置不可用"}</main>;
  const allApiConfigs = [llm, webSearch, ...integrations];
  const personalApiCount = allApiConfigs.filter((item) => item.personalOverride).length;
  const inheritedApiCount = allApiConfigs.filter((item) => item.configurationSource === "ADMIN").length;

  return (
    <main className="ardor-workbench min-h-screen px-5 py-6 md:px-10 md:py-8">
      <Link href="/app" className="inline-flex items-center gap-2 rounded-xl px-3 py-2 text-sm text-muted-foreground transition hover:bg-muted hover:text-foreground"><ArrowLeft className="size-4" />返回 Ardor</Link>
      <header className="mx-auto flex max-w-6xl flex-wrap items-end justify-between gap-6 pb-9 pt-10">
        <div><h1 className="text-4xl font-semibold tracking-tight md:text-5xl">设置</h1><p className="mt-3 text-muted-foreground">{user.email} · 登录状态保留 30 天</p></div>
        <Button variant="outline" onClick={logout}><LogOut className="mr-2 size-4" />退出登录</Button>
      </header>

      {(notice || error) && <div className={`mx-auto mb-6 max-w-6xl rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-800"}`}>{error || notice}</div>}

      <div className="mx-auto grid max-w-6xl gap-6 lg:grid-cols-2">
        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center gap-3"><UserRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">个人资料</h2></div>
          <p className="mt-2 text-sm text-muted-foreground">Agent 会把这些信息作为求职上下文。</p>
          <form key={JSON.stringify(profile)} className="mt-7 space-y-5" onSubmit={saveProfile}>
            <label className="block text-sm font-medium">称呼<input className="field mt-2" name="displayName" defaultValue={profile.displayName ?? ""} maxLength={120} /></label>
            <label className="block text-sm font-medium">当前定位<input className="field mt-2" name="headline" defaultValue={profile.headline ?? ""} maxLength={240} placeholder="例如：3 年经验 Java 后端工程师" /></label>
            <label className="block text-sm font-medium">目标岗位<input className="field mt-2" name="targetRoles" defaultValue={profile.targetRoles.join("，")} placeholder="Java 后端，AI 应用工程师" /><span className="mt-2 block text-xs font-normal text-muted-foreground">多个岗位用逗号分隔</span></label>
            <label className="block text-sm font-medium">时区<select className="field mt-2" name="timezone" defaultValue={profile.timezone}><option value="Asia/Shanghai">中国标准时间</option><option value="Asia/Tokyo">日本标准时间</option><option value="Europe/London">英国时间</option><option value="America/New_York">美国东部时间</option><option value="America/Los_Angeles">美国西部时间</option><option value="UTC">UTC</option></select></label>
            <Button><Save className="mr-2 size-4" />保存资料</Button>
          </form>
        </section>

        {!showPersonalApis ? <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">API 服务</h2></div>
          <div className="mt-8 rounded-[1.5rem] bg-gradient-to-br from-orange-50 to-violet-50 p-5">
            <p className="text-sm font-medium">由管理员统一提供</p>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">已接入 {inheritedApiCount} 项能力，你无需填写 API Key。</p>
          </div>
          {personalApiCount > 0 && <p className="mt-4 text-xs text-muted-foreground">当前另有 {personalApiCount} 项个人覆盖配置。</p>}
          <Button type="button" variant="outline" className="mt-6" onClick={() => setShowPersonalApis(true)}>使用自己的 API</Button>
        </section> : <>
        <section className="flex flex-wrap items-center justify-between gap-4 lg:col-span-2">
          <div><h2 className="text-xl font-semibold">个人 API</h2><p className="mt-1 text-sm text-muted-foreground">只覆盖你主动配置的能力，其余继续使用管理员默认。</p></div>
          <div className="flex flex-wrap gap-3">
            <Button type="button" disabled={testingAll} onClick={() => void testAllConnections()}>{testingAll ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Activity className="mr-2 size-4" />}{testingAll ? "正在检测…" : "测试全部连接"}</Button>
            <Button type="button" variant="outline" onClick={() => setShowPersonalApis(false)}>收起</Button>
          </div>
        </section>

        <section className="ardor-panel flex min-h-[34rem] flex-col rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center justify-between gap-4"><div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">LLM 服务</h2></div>{llm.configured && <span className="flex items-center gap-1 rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="size-3.5" />{llm.personalOverride ? "个人配置" : "管理员默认"} {llm.keyHint}</span>}</div>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">Agent 使用 LangChain4j Tool Calling。模型必须支持工具调用；建议先测试连接，再保存配置。</p>
          <form id="personal-api-primary-llm" key={JSON.stringify(llm)} className="mt-7 flex flex-1 flex-col gap-5" onSubmit={saveLlm}>
            <label className="block text-sm font-medium">API 协议<select className="field mt-2" name="provider" defaultValue={llm.provider}><option value="OPENAI_COMPATIBLE">OpenAI Compatible</option><option value="ANTHROPIC_COMPATIBLE">Anthropic Compatible</option></select></label>
            <label className="block text-sm font-medium">Base URL<input className="field mt-2 font-mono text-sm" type="url" name="baseUrl" defaultValue={llm.baseUrl} maxLength={512} required /><span className="mt-2 block text-xs font-normal leading-5 text-muted-foreground">DeepSeek 示例：OpenAI 填 https://api.deepseek.com；Anthropic 填 https://api.deepseek.com/anthropic。</span></label>
            <label className="block text-sm font-medium">模型名称<input className="field mt-2 font-mono text-sm" name="model" defaultValue={llm.model} maxLength={160} required /></label>
            <label className="block text-sm font-medium">API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={llm.personalOverride ? `当前个人 Key ${llm.keyHint}；输入新 Key 才会替换` : llm.configurationSource === "ADMIN" ? "留空测试管理员默认；填写后创建个人覆盖" : "首次配置必须填写"} /></label>
            <div className="mt-auto flex flex-wrap gap-3 pt-2"><Button type="button" variant="outline" disabled={testingLlm || savingLlm} onClick={testLlm}>{testingLlm ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingLlm ? "正在测试…" : "测试连接"}</Button><Button disabled={testingLlm || savingLlm}>{savingLlm ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingLlm ? "正在保存…" : "保存为个人配置"}</Button>{llm.personalOverride && <Button type="button" variant="outline" onClick={() => void restoreAdminDefault("llm")}><RotateCcw className="mr-2 size-4" />恢复管理员默认</Button>}</div>
            <FeedbackBanner feedback={llmFeedback} />
          </form>
        </section>

        <section className="ardor-panel flex min-h-[34rem] flex-col rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3"><Globe2 className="size-5 text-primary" /><h2 className="text-xl font-semibold">联网搜索</h2></div>
            {webSearch.configured && <span className="flex items-center gap-1 rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="size-3.5" />{webSearch.personalOverride ? "个人配置" : "管理员默认"} {webSearch.keyHint}</span>}
          </div>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">让 Agent 在遇到最新职位、公司、政策和新闻时主动查证。当前使用 Tavily，Key 只会加密保存。</p>
          <form id="personal-api-web-search" className="mt-7 flex flex-1 flex-col gap-5" onSubmit={saveWebSearch}>
            <label className="block text-sm font-medium">Tavily API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={webSearch.personalOverride ? `当前个人 Key ${webSearch.keyHint}；输入新 Key 才会替换` : webSearch.configurationSource === "ADMIN" ? "留空测试管理员默认；填写后创建个人覆盖" : "首次配置必须填写"} /></label>
            <p className="rounded-2xl bg-stone-950/[0.04] px-4 py-3 text-xs leading-5 text-muted-foreground">测试只读取 Tavily Key 与账户用量，不执行搜索，不消耗 Search credit。</p>
            <div className="mt-auto flex flex-wrap gap-3 pt-2">
              <Button type="button" variant="outline" disabled={testingWebSearch || savingWebSearch} onClick={testWebSearch}>{testingWebSearch ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingWebSearch ? "正在测试…" : "测试连接"}</Button>
              <Button disabled={testingWebSearch || savingWebSearch}>{savingWebSearch ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingWebSearch ? "正在保存…" : "保存配置"}</Button>
              {webSearch.personalOverride && <Button type="button" variant="outline" onClick={() => void restoreAdminDefault("web-search")}><RotateCcw className="mr-2 size-4" />恢复管理员默认</Button>}
            </div>
            <FeedbackBanner feedback={webSearchFeedback} />
          </form>
        </section>

            {integrations.map((integration) => {
              const detail = integrationDetails[integration.serviceType];
              const Icon = detail.icon;
              return (
                <form id={`personal-api-${integration.serviceType.toLowerCase()}`} key={JSON.stringify(integration)} className="ardor-panel flex min-h-[34rem] flex-col rounded-[2rem] p-6 md:p-8" onSubmit={(event) => saveIntegration(event, integration.serviceType)}>
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex gap-3"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-stone-950 text-white"><Icon className="size-4" /></span><div><h3 className="font-semibold">{detail.title}</h3><p className="mt-1 text-xs leading-5 text-muted-foreground">{detail.description}</p></div></div>
                    {integration.configured && <span className="shrink-0 text-xs font-medium text-emerald-700">{integration.personalOverride ? "个人配置" : "管理员默认"} {integration.keyHint}</span>}
                  </div>
                  <div className="mt-5 grid gap-4 sm:grid-cols-2">
                    <label className="block text-xs font-medium">服务商<input className="field mt-2 text-sm" name="provider" defaultValue={integration.provider} maxLength={120} required placeholder="例如 OpenAI" /></label>
                    <label className="block text-xs font-medium">模型<input className="field mt-2 font-mono text-sm" name="model" defaultValue={integration.model} maxLength={160} required placeholder="模型名称" /></label>
                    <label className="block text-xs font-medium sm:col-span-2">Base URL<input className="field mt-2 font-mono text-sm" type="url" name="baseUrl" defaultValue={integration.baseUrl} maxLength={512} required placeholder="https://api.example.com" /></label>
                    <label className="block text-xs font-medium sm:col-span-2">API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={integration.personalOverride ? `当前个人 Key ${integration.keyHint}；输入新 Key 才会替换` : integration.configurationSource === "ADMIN" ? "留空测试管理员默认；填写后创建个人覆盖" : "首次配置必须填写"} /></label>
                  </div>
                  <div className="mt-auto flex flex-wrap gap-2 pt-6">
                    <Button type="button" variant="outline" disabled={testingIntegrations[integration.serviceType] || savingIntegrations[integration.serviceType]} onClick={(event) => testIntegration(event, integration.serviceType)}>{testingIntegrations[integration.serviceType] ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingIntegrations[integration.serviceType] ? "正在测试…" : "测试连接"}</Button>
                    <Button disabled={testingIntegrations[integration.serviceType] || savingIntegrations[integration.serviceType]}>{savingIntegrations[integration.serviceType] ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingIntegrations[integration.serviceType] ? "正在保存…" : "保存配置"}</Button>
                    {integration.personalOverride && <Button type="button" variant="outline" onClick={() => void restoreAdminDefault(integration.serviceType)}><RotateCcw className="mr-2 size-4" />恢复管理员默认</Button>}
                  </div>
                  <FeedbackBanner feedback={integrationFeedback[integration.serviceType] ?? null} />
                </form>
              );
            })}
        </>}
      </div>

      <Dialog
        open={healthDialogOpen}
        onClose={() => setHealthDialogOpen(false)}
        title="连接健康检查"
        description={testingAll ? "正在逐项检查，可关闭后继续使用设置页。" : "检查完成。结果会保留到你手动关闭。"}
        footer={<Button type="button" onClick={() => setHealthDialogOpen(false)}>关闭</Button>}
      >
        <div className="space-y-2">
          {(Object.keys(healthServiceLabels) as HealthServiceType[]).map((type) => {
            const result = healthResults?.[type] ?? { kind: "pending", message: "等待检测…" };
            return (
              <div key={type} className="flex items-start gap-3 rounded-2xl bg-stone-950/[0.035] px-4 py-3">
                {result.kind === "success" ? <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-emerald-600" /> : result.kind === "error" ? <CircleAlert className="mt-0.5 size-5 shrink-0 text-red-600" /> : <LoaderCircle className="mt-0.5 size-5 shrink-0 animate-spin text-violet-600" />}
                <div className="min-w-0"><p className="text-sm font-semibold">{healthServiceLabels[type]}</p><p className={`mt-0.5 break-words text-xs leading-5 ${result.kind === "error" ? "text-red-700" : "text-muted-foreground"}`}>{result.message}</p></div>
              </div>
            );
          })}
        </div>
      </Dialog>
    </main>
  );
}

function FeedbackBanner({ feedback }: { feedback: InlineFeedback | null }) {
  if (!feedback) return null;
  const palette = feedback.kind === "success"
    ? "border-emerald-200 bg-emerald-50 text-emerald-800"
    : feedback.kind === "error"
      ? "border-red-200 bg-red-50 text-red-700"
      : "border-stone-200 bg-stone-100 text-stone-600";
  return <p role="status" aria-live="polite" className={`mt-4 rounded-xl border px-4 py-3 text-sm leading-5 ${palette}`}>{feedback.message}</p>;
}
