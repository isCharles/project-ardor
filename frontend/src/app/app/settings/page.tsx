"use client";

import { ArrowLeft, Bot, CheckCircle2, Database, Globe2, KeyRound, LoaderCircle, LogOut, Mic, Save, UserRound, Volume2, Wifi } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, MouseEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { ApiError, api } from "@/lib/api";

type CurrentUser = { id: string; email: string };
type Profile = { displayName: string | null; headline: string | null; targetRoles: string[] };
type LlmConfig = {
  configured: boolean;
  provider: "OPENAI_COMPATIBLE" | "ANTHROPIC_COMPATIBLE";
  baseUrl: string;
  model: string;
  keyHint: string | null;
};
type WebSearchConfig = { configured: boolean; provider: "TAVILY"; keyHint: string | null };
type AuxiliaryServiceType = "EMBEDDING" | "ASR" | "TTS" | "FALLBACK_LLM";
type AuxiliaryApiConfig = {
  serviceType: AuxiliaryServiceType;
  configured: boolean;
  provider: string;
  baseUrl: string;
  model: string;
  keyHint: string | null;
};
type InlineFeedback = { kind: "pending" | "success" | "error"; message: string };

const integrationDetails = {
  EMBEDDING: { title: "向量模型", description: "为语义检索和长期记忆预留。", icon: Database },
  ASR: { title: "语音识别 ASR", description: "为音频转文字与面试转写预留。", icon: Mic },
  TTS: { title: "语音合成 TTS", description: "为语音回复与模拟面试预留。", icon: Volume2 },
  FALLBACK_LLM: { title: "备用 LLM", description: "为主模型不可用时的降级线路预留。", icon: Bot },
} satisfies Record<AuxiliaryServiceType, { title: string; description: string; icon: typeof Database }>;

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
      const result = await api<{ message: string; model: string; latencyMs: number }>("/api/settings/llm/test", {
        method: "POST",
        body: JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") }),
      });
      setLlmFeedback({ kind: "success", message: `${result.message} · ${result.model} · ${result.latencyMs} ms` });
    } catch (reason) { setLlmFeedback({ kind: "error", message: reason instanceof Error ? reason.message : "连接测试失败" }); }
    finally { setTestingLlm(false); }
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

  async function logout() {
    await api<void>("/api/auth/logout", { method: "POST" });
    router.replace("/login");
  }

  if (loading) return <main className="ardor-workbench grid min-h-screen place-items-center text-muted-foreground">正在载入设置…</main>;
  if (!user || !profile || !llm || !webSearch || !integrations) return <main className="ardor-workbench grid min-h-screen place-items-center px-6 text-red-700">{error || "设置不可用"}</main>;

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
            <Button><Save className="mr-2 size-4" />保存资料</Button>
          </form>
        </section>

        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center justify-between gap-4"><div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">LLM 服务</h2></div>{llm.configured && <span className="flex items-center gap-1 rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="size-3.5" />已配置 {llm.keyHint}</span>}</div>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">Agent 使用 LangChain4j Tool Calling。模型必须支持工具调用；建议先测试连接，再保存配置。</p>
          <form key={JSON.stringify(llm)} className="mt-7 space-y-5" onSubmit={saveLlm}>
            <label className="block text-sm font-medium">API 协议<select className="field mt-2" name="provider" defaultValue={llm.provider}><option value="OPENAI_COMPATIBLE">OpenAI Compatible</option><option value="ANTHROPIC_COMPATIBLE">Anthropic Compatible</option></select></label>
            <label className="block text-sm font-medium">Base URL<input className="field mt-2 font-mono text-sm" type="url" name="baseUrl" defaultValue={llm.baseUrl} maxLength={512} required /><span className="mt-2 block text-xs font-normal leading-5 text-muted-foreground">DeepSeek 示例：OpenAI 填 https://api.deepseek.com；Anthropic 填 https://api.deepseek.com/anthropic。</span></label>
            <label className="block text-sm font-medium">模型名称<input className="field mt-2 font-mono text-sm" name="model" defaultValue={llm.model} maxLength={160} required /></label>
            <label className="block text-sm font-medium">API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} required={!llm.configured} autoComplete="off" placeholder={llm.configured ? `当前已保存 ${llm.keyHint}；输入新 Key 才会替换` : "首次配置必须填写"} /></label>
            <div className="flex flex-wrap gap-3"><Button type="button" variant="outline" disabled={testingLlm || savingLlm} onClick={testLlm}>{testingLlm ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingLlm ? "正在测试…" : "测试连接"}</Button><Button disabled={testingLlm || savingLlm}>{savingLlm ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingLlm ? "正在保存…" : "保存配置"}</Button></div>
            <FeedbackBanner feedback={llmFeedback} />
          </form>
        </section>

        <section className="ardor-panel rounded-[2rem] p-6 md:p-8 lg:col-span-2">
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3"><Globe2 className="size-5 text-primary" /><h2 className="text-xl font-semibold">联网搜索</h2></div>
            {webSearch.configured && <span className="flex items-center gap-1 rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="size-3.5" />已配置 {webSearch.keyHint}</span>}
          </div>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">让 Agent 在遇到最新职位、公司、政策和新闻时主动查证。当前使用 Tavily，Key 只会加密保存。</p>
          <form className="mt-7 max-w-2xl space-y-5" onSubmit={saveWebSearch}>
            <label className="block text-sm font-medium">Tavily API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} required={!webSearch.configured} autoComplete="off" placeholder={webSearch.configured ? `当前已保存 ${webSearch.keyHint}；输入新 Key 才会替换` : "首次配置必须填写"} /></label>
            <p className="rounded-2xl bg-stone-950/[0.04] px-4 py-3 text-xs leading-5 text-muted-foreground">测试只读取 Tavily Key 与账户用量，不执行搜索，不消耗 Search credit。</p>
            <div className="flex flex-wrap gap-3">
              <Button type="button" variant="outline" disabled={testingWebSearch || savingWebSearch} onClick={testWebSearch}>{testingWebSearch ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingWebSearch ? "正在测试…" : "测试连接"}</Button>
              <Button disabled={testingWebSearch || savingWebSearch}>{savingWebSearch ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingWebSearch ? "正在保存…" : "保存配置"}</Button>
            </div>
            <FeedbackBanner feedback={webSearchFeedback} />
          </form>
        </section>

        <section className="ardor-panel rounded-[2rem] p-6 md:p-8 lg:col-span-2">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div><div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">未来能力</h2></div><p className="mt-2 text-sm text-muted-foreground">先保存接入信息，后续启用时无需重新配置账户。</p></div>
            <span className="rounded-full border border-stone-200 bg-white/60 px-3 py-1 text-xs font-medium text-stone-600">预留 · 尚未被 Agent 调用</span>
          </div>
          <div className="mt-7 grid gap-4 md:grid-cols-2">
            {integrations.map((integration) => {
              const detail = integrationDetails[integration.serviceType];
              const Icon = detail.icon;
              return (
                <form key={JSON.stringify(integration)} className="rounded-[1.5rem] border border-stone-200/80 bg-white/55 p-5" onSubmit={(event) => saveIntegration(event, integration.serviceType)}>
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex gap-3"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-stone-950 text-white"><Icon className="size-4" /></span><div><h3 className="font-semibold">{detail.title}</h3><p className="mt-1 text-xs leading-5 text-muted-foreground">{detail.description}</p></div></div>
                    {integration.configured && <span className="shrink-0 text-xs font-medium text-emerald-700">已保存 {integration.keyHint}</span>}
                  </div>
                  <div className="mt-5 grid gap-4 sm:grid-cols-2">
                    <label className="block text-xs font-medium">服务商<input className="field mt-2 text-sm" name="provider" defaultValue={integration.provider} maxLength={120} required placeholder="例如 OpenAI" /></label>
                    <label className="block text-xs font-medium">模型<input className="field mt-2 font-mono text-sm" name="model" defaultValue={integration.model} maxLength={160} required placeholder="模型名称" /></label>
                    <label className="block text-xs font-medium sm:col-span-2">Base URL<input className="field mt-2 font-mono text-sm" type="url" name="baseUrl" defaultValue={integration.baseUrl} maxLength={512} required placeholder="https://api.example.com" /></label>
                    <label className="block text-xs font-medium sm:col-span-2">API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} required={!integration.configured} autoComplete="off" placeholder={integration.configured ? `当前已保存 ${integration.keyHint}；输入新 Key 才会替换` : "首次配置必须填写"} /></label>
                  </div>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <Button type="button" variant="outline" disabled={testingIntegrations[integration.serviceType] || savingIntegrations[integration.serviceType]} onClick={(event) => testIntegration(event, integration.serviceType)}>{testingIntegrations[integration.serviceType] ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingIntegrations[integration.serviceType] ? "正在测试…" : "测试连接"}</Button>
                    <Button disabled={testingIntegrations[integration.serviceType] || savingIntegrations[integration.serviceType]}>{savingIntegrations[integration.serviceType] ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingIntegrations[integration.serviceType] ? "正在保存…" : "保存配置"}</Button>
                  </div>
                  <FeedbackBanner feedback={integrationFeedback[integration.serviceType] ?? null} />
                </form>
              );
            })}
          </div>
        </section>
      </div>
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
