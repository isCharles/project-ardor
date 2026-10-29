"use client";

import { Activity, Bot, CheckCircle2, ChevronRight, CircleAlert, Database, Gift, Globe2, KeyRound, LoaderCircle, LogOut, Mic, RefreshCw, RotateCcw, Save, UserRound, Volume2, Wifi } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, MouseEvent, useEffect, useState } from "react";

import { Button } from "@/components/ui/button";
import { Dialog } from "@/components/ardor/overlay";
import { DesktopSettings } from "@/components/ardor/desktop-settings";
import { PasswordSettings } from "@/components/ardor/password-settings";
import { AgentPermissionsPanel } from "@/components/ardor/agent-permissions-panel";
import { ApiError, api } from "@/lib/api";
import { useLocale } from "@/lib/locale";
import { usageFeatureLabels } from "@/lib/usage-features";
import { LocaleSwitch } from "@/components/ardor/locale-switch";
import { ProfileAvatarSettings } from "@/components/ardor/profile-avatar";

const settingsCategories = [
  { id: "general", en: "General & sidebar", zh: "通用与侧栏" },
  { id: "agent", en: "Agent permissions", zh: "Agent 权限" },
  { id: "profile", en: "Profile", zh: "个人资料" },
  { id: "security", en: "Account security", zh: "账户安全" },
  { id: "services", en: "Models & services", zh: "模型与服务" },
  { id: "usage", en: "Usage", zh: "使用额度" },
  { id: "desktop", en: "Desktop app", zh: "桌面应用" },
] as const;

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
type LlmModelListResponse = { models: string[] };
type HealthServiceType = "PRIMARY_LLM" | "WEB_SEARCH" | AuxiliaryServiceType;
type HealthResult = { kind: "pending" | "success" | "error"; message: string };
type Plan = { code: "FREE" | "PLUS" | "PRO"; nameZh: string; nameEn: string; priceCents: number; currency: string; billingInterval: string; tokenLimit: number; paymentEnabled: boolean };
type TokenPack = { code: string; nameZh: string; nameEn: string; tokenAmount: number; priceCents: number; currency: string };
type UsageSnapshot = { tier: "FREE" | "PLUS" | "PRO"; plan: Plan | null; planExpiresAt?: string | null; resetsAt: string; features: Array<{ feature: string; used: number; limit: number; weeklyUsed?: number; weeklyLimit?: number }>; tokens: { used: number; limit: number; packBalance?: number }; plans: Plan[]; packs?: TokenPack[] };

export default function SettingsPage() {
  const router = useRouter();
  const { locale, t } = useLocale();
  const integrationDetails = {
    EMBEDDING: { title: t("Embedding model", "向量模型"), description: t("For semantic knowledge search and long-term memory.", "用于知识库语义检索与长期记忆。"), icon: Database },
    ASR: { title: t("Speech recognition (ASR)", "语音识别 ASR"), description: t("Transcribes answers in voice interviews.", "用于语音面试的回答转写。"), icon: Mic },
    TTS: { title: t("Text to speech (TTS)", "语音合成 TTS"), description: t("Reads voice-interview questions aloud.", "用于朗读语音面试题目。"), icon: Volume2 },
    FALLBACK_LLM: { title: t("Fallback LLM", "备用 LLM"), description: t("A backup when the primary model is unavailable.", "主模型不可用时的备用线路。"), icon: Bot },
  } satisfies Record<AuxiliaryServiceType, { title: string; description: string; icon: typeof Database }>;
  const healthServiceLabels: Record<HealthServiceType, string> = {
    PRIMARY_LLM: t("Primary LLM", "主 LLM"), WEB_SEARCH: t("Web search", "联网搜索"),
    EMBEDDING: integrationDetails.EMBEDDING.title, ASR: integrationDetails.ASR.title,
    TTS: integrationDetails.TTS.title, FALLBACK_LLM: integrationDetails.FALLBACK_LLM.title,
  };
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [profile, setProfile] = useState<Profile | null>(null);
  const [llm, setLlm] = useState<LlmConfig | null>(null);
  const [webSearch, setWebSearch] = useState<WebSearchConfig | null>(null);
  const [integrations, setIntegrations] = useState<AuxiliaryApiConfig[] | null>(null);
  const [usage, setUsage] = useState<UsageSnapshot | null>(null);
  const [redeemCode, setRedeemCode] = useState("");
  const [redeeming, setRedeeming] = useState(false);
  const [redeemFeedback, setRedeemFeedback] = useState<InlineFeedback | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const [testingLlm, setTestingLlm] = useState(false);
  const [savingLlm, setSavingLlm] = useState(false);
  const [testingWebSearch, setTestingWebSearch] = useState(false);
  const [savingWebSearch, setSavingWebSearch] = useState(false);
  const [llmFeedback, setLlmFeedback] = useState<InlineFeedback | null>(null);
  const [modelOptions, setModelOptions] = useState<string[]>([]);
  const [fetchingModels, setFetchingModels] = useState(false);
  const [modelFeedback, setModelFeedback] = useState<InlineFeedback | null>(null);
  const [webSearchFeedback, setWebSearchFeedback] = useState<InlineFeedback | null>(null);
  const [testingIntegrations, setTestingIntegrations] = useState<Partial<Record<AuxiliaryServiceType, boolean>>>({});
  const [savingIntegrations, setSavingIntegrations] = useState<Partial<Record<AuxiliaryServiceType, boolean>>>({});
  const [integrationFeedback, setIntegrationFeedback] = useState<Partial<Record<AuxiliaryServiceType, InlineFeedback>>>({});
  const [showPersonalApis, setShowPersonalApis] = useState(false);
  const [healthDialogOpen, setHealthDialogOpen] = useState(false);
  const [testingAll, setTestingAll] = useState(false);
  const [healthResults, setHealthResults] = useState<Record<HealthServiceType, HealthResult> | null>(null);
  const [category, setCategory] = useState<string>("general");
  const [apiService, setApiService] = useState<HealthServiceType>("PRIMARY_LLM");

  useEffect(() => {
    Promise.all([
      api<CurrentUser>("/api/auth/me"),
      api<Profile>("/api/profile"),
      api<LlmConfig>("/api/settings/llm"),
      api<WebSearchConfig>("/api/settings/web-search"),
      api<AuxiliaryApiConfig[]>("/api/settings/integrations"),
      api<UsageSnapshot>("/api/usage").catch(() => null),
    ])
      .then(([currentUser, currentProfile, currentLlm, currentWebSearch, currentIntegrations, currentUsage]) => {
        setUser(currentUser); setProfile(currentProfile); setLlm(currentLlm);
        setWebSearch(currentWebSearch); setIntegrations(currentIntegrations); setUsage(currentUsage);
      })
      .catch((reason) => {
        if (reason instanceof ApiError && reason.status === 401) return router.replace("/login");
        setError(reason instanceof Error ? reason.message : t("Could not load settings", "无法加载设置"));
      })
      .finally(() => setLoading(false));
  }, [router, t]);

  async function runSave(action: () => Promise<string>) {
    setError(""); setNotice("");
    try { setNotice(await action()); }
    catch (reason) { setError(reason instanceof Error ? reason.message : t("Could not save", "保存失败")); }
  }

  async function refreshUsage() {
    setUsage(await api<UsageSnapshot>("/api/usage"));
  }

  async function redeem(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const code = redeemCode.trim();
    if (!code) return;
    setRedeeming(true);
    setRedeemFeedback({ kind: "pending", message: t("Redeeming…", "正在兑换…") });
    try {
      if (/^PACK/i.test(code.replace(/[^A-Za-z0-9]/g, ""))) {
        const result = await api<{ nameZh: string; nameEn: string; tokens: number; balance: number }>("/api/usage/pack-codes/redeem", {
          method: "POST", body: JSON.stringify({ code }),
        });
        setRedeemFeedback({ kind: "success", message: t(`${formatTokens(result.tokens)} pack tokens added`, `已到账 ${formatTokens(result.tokens)} Token 资源包`) });
      } else {
        const result = await api<{ tier: "PLUS" | "PRO"; nameZh: string; nameEn: string }>("/api/usage/redeem", {
          method: "POST", body: JSON.stringify({ code }),
        });
        setRedeemFeedback({ kind: "success", message: t(`Upgraded to ${result.nameEn}`, `已升级到 ${result.nameZh}`) });
      }
      setRedeemCode("");
      await refreshUsage();
    } catch (reason) {
      setRedeemFeedback({ kind: "error", message: reason instanceof Error ? reason.message : t("Could not redeem this code", "兑换失败") });
    } finally {
      setRedeeming(false);
    }
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
      return t("Profile saved", "个人资料已保存");
    });
  }

  async function saveLlm(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setSavingLlm(true); setLlmFeedback({ kind: "pending", message: t("Saving securely…", "正在加密保存…") });
    try {
      const updated = await api<LlmConfig>("/api/settings/llm", {
        method: "PUT",
        body: JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") }),
      });
      setLlm(updated);
      (form.elements.namedItem("apiKey") as HTMLInputElement).value = "";
      setLlmFeedback({ kind: "success", message: `${t("Saved securely", "已加密保存")}${updated.keyHint ? ` · ${updated.keyHint}` : ""}` });
    } catch (reason) {
      setLlmFeedback({ kind: "error", message: reason instanceof Error ? reason.message : t("Could not save", "保存失败") });
    } finally { setSavingLlm(false); }
  }

  async function testLlm(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form;
    if (!form || !form.reportValidity()) return;
    const data = new FormData(form);
    setTestingLlm(true); setLlmFeedback({ kind: "pending", message: t("Testing model connection…", "正在测试模型连接…") });
    try {
      const body = JSON.stringify({ provider: data.get("provider"), baseUrl: data.get("baseUrl"), model: data.get("model"), apiKey: data.get("apiKey") });
      let result: { message: string; model: string; latencyMs: number } | null = null;
      for (let attempt = 0; attempt <= 5; attempt += 1) {
        if (attempt > 0) {
          setLlmFeedback({ kind: "pending", message: t(`Network issue · retry ${attempt}/5…`, `网络波动，正在进行第 ${attempt}/5 次重试…`) });
          await new Promise((resolve) => window.setTimeout(resolve, Math.min(1500 * (2 ** (attempt - 1)), 8000)));
        }
        try {
          result = await api<{ message: string; model: string; latencyMs: number }>("/api/settings/llm/test", { method: "POST", body });
          break;
        } catch (reason) {
          if (!(reason instanceof ApiError) || reason.body.retryable !== true || attempt === 5) throw reason;
        }
      }
      if (!result) throw new Error(t("Connection test returned no result", "连接测试未返回结果"));
      setLlmFeedback({ kind: "success", message: `${result.message} · ${result.model} · ${result.latencyMs} ms` });
    } catch (reason) { setLlmFeedback({ kind: "error", message: reason instanceof Error ? reason.message : t("Connection test failed", "连接测试失败") }); }
    finally { setTestingLlm(false); }
  }

  async function fetchModelOptions(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form;
    if (!form) return;
    const data = new FormData(form);
    const baseUrl = String(data.get("baseUrl") ?? "").trim();
    if (!baseUrl) {
      setModelFeedback({ kind: "error", message: t("Enter a Base URL first.", "请先填写 Base URL。") });
      return;
    }
    setFetchingModels(true);
    setModelFeedback({ kind: "pending", message: t("Reading models from the endpoint…", "正在从服务端读取模型…") });
    try {
      const result = await api<LlmModelListResponse>("/api/settings/llm/models", {
        method: "POST",
        body: JSON.stringify({
          provider: data.get("provider"),
          baseUrl,
          apiKey: data.get("apiKey"),
        }),
      });
      setModelOptions(result.models);
      setModelFeedback({ kind: "success", message: t(`${result.models.length} models found. Pick one below.`, `找到 ${result.models.length} 个模型，可在下方选择。`) });
    } catch (reason) {
      setModelFeedback({ kind: "error", message: reason instanceof Error ? reason.message : t("Could not fetch models", "获取模型列表失败") });
    } finally {
      setFetchingModels(false);
    }
  }

  function fillPackyPreset() {
    const form = document.getElementById("personal-api-primary-llm");
    if (!(form instanceof HTMLFormElement)) return;
    const model = (form.elements.namedItem("model") as HTMLInputElement).value.toLowerCase();
    const claude = /claude|opus|sonnet|haiku/.test(model);
    (form.elements.namedItem("provider") as HTMLSelectElement).value = claude ? "ANTHROPIC_COMPATIBLE" : "OPENAI_COMPATIBLE";
    (form.elements.namedItem("baseUrl") as HTMLInputElement).value = claude ? "https://cf.api.fan" : "https://cf.api.fan/v1";
    setModelOptions([]);
    setModelFeedback({ kind: "success", message: claude
      ? t("Packy Claude endpoint filled: Anthropic Compatible + https://cf.api.fan. Fetch models next.", "已按 Claude 填入 Packy Anthropic 端点；下一步获取模型列表。Packy 的 Claude 模型走 Anthropic 协议，不要用 OpenAI 的 /v1。")
      : t("Packy OpenAI endpoint filled: OpenAI Compatible + https://cf.api.fan/v1. Fetch models next.", "已按 OpenAI 兼容填入 Packy 端点；下一步获取模型列表。") });
  }

  function fillFallbackPackyPreset(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form;
    if (!form) return;
    const provider = form.elements.namedItem("provider");
    const baseUrl = form.elements.namedItem("baseUrl");
    const model = form.elements.namedItem("model");
    if (provider instanceof HTMLSelectElement) provider.value = "ANTHROPIC_COMPATIBLE";
    if (baseUrl instanceof HTMLInputElement) baseUrl.value = "https://cf.api.fan";
    if (model instanceof HTMLInputElement && !model.value.trim()) model.value = "claude-opus-5-5";
    setIntegrationFeedback((current) => ({ ...current, FALLBACK_LLM: {
      kind: "success",
      message: t("Packy fallback endpoint filled. Enter the Packy key, then save or test the connection.",
        "已填入 Packy 备用端点。填写 Packy API Key 后保存或测试连接即可。"),
    } }));
  }

  async function testAllConnections() {
    const serviceTypes: HealthServiceType[] = ["PRIMARY_LLM", "WEB_SEARCH", "EMBEDDING", "ASR", "TTS", "FALLBACK_LLM"];
    const integrationConfigs = integrations ?? [];
    const pending = Object.fromEntries(serviceTypes.map((type) => [type, { kind: "pending", message: t("Waiting…", "等待检测…") }])) as Record<HealthServiceType, HealthResult>;
    setHealthResults(pending);
    setHealthDialogOpen(true);
    setTestingAll(true);

    const update = (type: HealthServiceType, result: HealthResult) => {
      setHealthResults((current) => current ? { ...current, [type]: result } : current);
    };
    const formData = (id: string) => {
      const form = document.getElementById(id);
      if (!(form instanceof HTMLFormElement)) throw new Error(t("Configuration form is not loaded yet", "配置表单尚未载入"));
      return new FormData(form);
    };
    const run = async (type: HealthServiceType, action: () => Promise<string>) => {
      update(type, { kind: "pending", message: t("Connecting…", "正在连接…") });
      try {
        update(type, { kind: "success", message: await action() });
      } catch (reason) {
        update(type, { kind: "error", message: reason instanceof Error ? reason.message : t("Connection failed", "连接失败") });
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
        const usage = result.usage == null || result.limit == null ? "" : t(` · usage ${result.usage}/${result.limit}`, ` · 用量 ${result.usage}/${result.limit}`);
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
    setSavingWebSearch(true); setWebSearchFeedback({ kind: "pending", message: t("Saving securely…", "正在加密保存…") });
    try {
      const updated = await api<WebSearchConfig>("/api/settings/web-search", {
        method: "PUT",
        body: JSON.stringify({ apiKey: data.get("apiKey") }),
      });
      setWebSearch(updated);
      (form.elements.namedItem("apiKey") as HTMLInputElement).value = "";
      setWebSearchFeedback({ kind: "success", message: `${t("Saved securely", "已加密保存")} · ${updated.keyHint}` });
    } catch (reason) {
      setWebSearchFeedback({ kind: "error", message: reason instanceof Error ? reason.message : t("Could not save", "保存失败") });
    } finally { setSavingWebSearch(false); }
  }

  async function testWebSearch(event: MouseEvent<HTMLButtonElement>) {
    const form = event.currentTarget.form;
    if (!form || !form.reportValidity()) return;
    const data = new FormData(form);
    setTestingWebSearch(true); setWebSearchFeedback({ kind: "pending", message: t("Checking Tavily API key and usage…", "正在验证 Tavily API Key 与用量…") });
    try {
      const result = await api<{ message: string; latencyMs: number; usage: number | null; limit: number | null }>("/api/settings/web-search/test", {
        method: "POST",
        body: JSON.stringify({ apiKey: data.get("apiKey") }),
      });
      const usage = result.usage == null || result.limit == null ? "" : t(` · usage ${result.usage}/${result.limit}`, ` · 当前用量 ${result.usage}/${result.limit}`);
      setWebSearchFeedback({ kind: "success", message: `${result.message} · ${result.latencyMs} ms${usage}` });
    } catch (reason) { setWebSearchFeedback({ kind: "error", message: reason instanceof Error ? reason.message : t("Web-search test failed", "联网搜索测试失败") }); }
    finally { setTestingWebSearch(false); }
  }

  async function saveIntegration(event: FormEvent<HTMLFormElement>, serviceType: AuxiliaryServiceType) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setSavingIntegrations((current) => ({ ...current, [serviceType]: true }));
    setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "pending", message: t("Saving securely…", "正在加密保存…") } }));
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
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "success", message: `${t("Saved securely", "已加密保存")} · ${updated.keyHint}` } }));
    } catch (reason) {
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "error", message: reason instanceof Error ? reason.message : t("Could not save", "保存失败") } }));
    } finally {
      setSavingIntegrations((current) => ({ ...current, [serviceType]: false }));
    }
  }

  async function testIntegration(event: MouseEvent<HTMLButtonElement>, serviceType: AuxiliaryServiceType) {
    const form = event.currentTarget.form;
    if (!form || !form.reportValidity()) return;
    const data = new FormData(form);
    setTestingIntegrations((current) => ({ ...current, [serviceType]: true }));
    setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "pending", message: t("Checking service URL and API key…", "正在验证服务地址和 API Key…") } }));
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
      setIntegrationFeedback((current) => ({ ...current, [serviceType]: { kind: "error", message: reason instanceof Error ? reason.message : t("Connection test failed", "连接测试失败") } }));
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
      setError(reason instanceof Error ? reason.message : t("Could not restore admin defaults", "无法恢复管理员默认配置"));
    }
  }

  async function logout() {
    await api<void>("/api/auth/logout", { method: "POST" });
    router.replace("/login");
  }

  if (loading) return <main className="ardor-workbench grid min-h-screen place-items-center text-muted-foreground">{t("Loading settings…", "正在载入设置…")}</main>;
  if (!user || !profile || !llm || !webSearch || !integrations) return <main className="ardor-workbench grid min-h-screen place-items-center px-6 text-red-700">{error || t("Settings unavailable", "设置不可用")}</main>;
  const allApiConfigs = [llm, webSearch, ...integrations];
  const personalApiCount = allApiConfigs.filter((item) => item.personalOverride).length;
  const inheritedApiCount = allApiConfigs.filter((item) => item.configurationSource === "ADMIN").length;
  const apiServiceRows: Array<{ type: HealthServiceType; label: string; icon: typeof KeyRound; configured: boolean; personal: boolean }> = [
    { type: "PRIMARY_LLM", label: healthServiceLabels.PRIMARY_LLM, icon: KeyRound, configured: llm.configured, personal: llm.personalOverride },
    { type: "WEB_SEARCH", label: healthServiceLabels.WEB_SEARCH, icon: Globe2, configured: webSearch.configured, personal: webSearch.personalOverride },
    ...integrations.map((integration) => ({
      type: integration.serviceType,
      label: integrationDetails[integration.serviceType].title,
      icon: integrationDetails[integration.serviceType].icon,
      configured: integration.configured,
      personal: integration.personalOverride,
    })),
  ];
  const englishUsageLabels: Record<string, string> = {
    AGENT_CHAT: "Agent chat", RESUME_ANALYSIS: "Resume analysis",
    INTERVIEW_CREATE: "Mock interviews", INTERVIEW_RECAP: "Interview recaps",
  };

  return (
    <main className="ardor-workbench min-h-screen px-5 py-6 md:px-10 md:py-8">
      
      <header className="mx-auto flex max-w-6xl flex-wrap items-end justify-between gap-6 pb-9 pt-10">
        <div><h1 className="text-4xl font-semibold tracking-tight md:text-5xl">{t("Settings", "设置")}</h1><p className="mt-3 text-muted-foreground">{user.email} · {t("Stay signed in for 30 days", "登录状态保留 30 天")}</p></div>
        <Button variant="outline" onClick={logout}><LogOut className="mr-2 size-4" />{t("Log out", "退出登录")}</Button>
      </header>

      {(notice || error) && <div className={`mx-auto mb-6 max-w-6xl rounded-2xl px-5 py-4 text-sm ${error ? "bg-red-50 text-red-700" : "bg-emerald-50 text-emerald-800"}`}>{error || notice}</div>}

      <div className="mx-auto grid max-w-6xl items-start gap-6 md:grid-cols-[13rem_minmax(0,1fr)]">
        <nav aria-label={t("Settings categories", "设置分类")} className="flex gap-1 overflow-x-auto rounded-2xl border border-white/80 bg-white/70 p-2 md:sticky md:top-6 md:flex-col">
          {settingsCategories.map((item) => <button key={item.id} type="button" aria-current={category === item.id ? "page" : undefined} onClick={() => { setCategory(item.id); setNotice(""); setError(""); }} className={`shrink-0 rounded-xl px-4 py-3 text-left text-sm transition ${category === item.id ? "bg-violet-100 font-medium text-violet-800" : "text-stone-600 hover:bg-white"}`}>{t(item.en, item.zh)}</button>)}
        </nav>
        <div className="min-w-0">
        <section className={category === "general" ? "ardor-panel rounded-[2rem] p-6 md:p-8" : "hidden"}>
          <h2 className="text-xl font-semibold">{t("General & sidebar", "通用与侧栏")}</h2>
          <div className="mt-6 divide-y divide-stone-200/60">
            <div className="flex flex-wrap items-center justify-between gap-4 py-4"><span className="text-sm font-medium">{t("Language", "语言")}</span><LocaleSwitch /></div>
            <div className="flex flex-wrap items-center justify-between gap-4 py-4"><div><p className="text-sm font-medium">{t("Sidebar modules", "侧栏模块")}</p><p className="mt-1 text-xs text-stone-500">{t("Keep your essentials visible, tuck the rest into More.", "常用模块常驻，其余收进更多。")}</p></div><Button variant="outline" onClick={() => window.dispatchEvent(new Event("ardor:customize-sidebar"))}>{t("Customize sidebar", "自定义侧栏")}</Button></div>
          </div>
        </section>
        <div className={category === "agent" ? "space-y-6" : "hidden"}><AgentPermissionsPanel /></div>
        <div className={category === "desktop" ? "space-y-6" : "hidden"}><DesktopSettings /><p className="text-sm text-stone-500">{t("Server connection and updates are available in the Ardor desktop app.", "服务器连接与应用更新在 Ardor 桌面版中可用。")}</p></div>
        <div className={category === "usage" ? "space-y-6" : "hidden"}>
        {usage?.tokens && Array.isArray(usage.plans) && <section className="ardor-panel rounded-[2rem] p-6 md:col-span-2 md:p-8">
          <div className="flex flex-wrap items-center justify-between gap-3"><div><h2 className="text-xl font-semibold">{t("Usage", "使用额度")}</h2><p className="mt-1 text-xs text-muted-foreground">{t("Current token counts cover Agent chat only. Token and module request limits both apply.", "当前 Token 统计仅覆盖 Ardor 对话；Token 额度与各模块请求次数限制同时生效。")}</p></div><span className="rounded-full bg-violet-50 px-3 py-1 text-xs font-medium text-violet-700">{usage.plan ? (locale === "en" ? usage.plan.nameEn : usage.plan.nameZh) : usage.tier}</span></div>
          <div className="mt-5 rounded-2xl border border-violet-100 bg-violet-50/45 p-4">
            <div className="flex items-baseline justify-between gap-3"><span className="text-xs font-medium text-violet-900">{t("Monthly tokens", "本月 Token")}</span><span className="text-xs tabular-nums text-violet-700">{formatTokens(usage.tokens.used)} / {usage.tokens.limit > 0 ? formatTokens(usage.tokens.limit) : t("Unlimited", "不限")}</span></div>
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-white/80"><div className="h-full rounded-full bg-violet-600 transition-[width]" style={{ width: usage.tokens.limit > 0 ? `${Math.min(100, Math.round(usage.tokens.used / usage.tokens.limit * 100))}%` : "0%" }} /></div>
            <p className="mt-2 text-[11px] text-violet-700/80">{t("Recorded from actual model usage; resets monthly.", "按模型实际返回的 Token 记录；每月重置。")}</p>
            {(usage.tokens.packBalance ?? 0) > 0 && <div className="mt-3 flex items-baseline justify-between gap-3 border-t border-violet-100 pt-3"><span className="text-xs font-medium text-violet-900">{t("Pack balance", "资源包余额")}</span><span className="text-xs tabular-nums text-violet-700">{formatTokens(usage.tokens.packBalance ?? 0)} Token</span></div>}
          </div>
          <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">{usage.features.filter((item) => ["AGENT_CHAT", "RESUME_ANALYSIS", "INTERVIEW_CREATE", "INTERVIEW_RECAP"].includes(item.feature)).map((item) => { const weeklyUsed = item.weeklyUsed ?? 0, weeklyLimit = item.weeklyLimit ?? 0; const weeklyBinds = weeklyLimit > 0 && weeklyLimit - weeklyUsed < item.limit - item.used; return <div key={item.feature} data-usage-feature={item.feature} className="rounded-2xl bg-white/70 p-4"><p className="text-xs text-muted-foreground">{locale === "en" ? englishUsageLabels[item.feature] ?? item.feature : usageFeatureLabels[item.feature] ?? item.feature}</p><p className="mt-2 text-lg font-semibold tabular-nums">{weeklyBinds ? `${weeklyUsed} / ${weeklyLimit}` : `${item.used} / ${item.limit}`}</p><p className="mt-1 text-[11px] text-muted-foreground">{weeklyBinds ? t("resets weekly", "每周重置") : t("resets monthly", "每月重置")}</p></div>; })}</div>
          <div className="mt-7"><h3 className="text-sm font-semibold">{t("Plans", "套餐")}</h3><div className="mt-3 grid gap-3 md:grid-cols-3">{usage.plans.map((plan) => <div key={plan.code} data-plan-code={plan.code} className={`rounded-2xl border p-4 ${plan.code === usage.tier ? "border-violet-300 bg-violet-50/50" : "border-stone-200/70 bg-white/60"}`}><div className="flex items-center justify-between gap-2"><span className="text-sm font-semibold">{locale === "en" ? plan.nameEn : plan.nameZh}</span>{plan.code === usage.tier && <span className="rounded-full bg-violet-100 px-2 py-0.5 text-[10px] text-violet-700">{t("Current", "当前")}</span>}</div><p className="mt-2 text-lg font-semibold tabular-nums">{plan.priceCents === 0 ? t("Free", "免费") : `¥${(plan.priceCents / 100).toFixed(0)}`}<span className="ml-1 text-[11px] font-normal text-muted-foreground">{plan.priceCents === 0 ? "" : t("/ month", "/ 月")}</span></p><p className="mt-1 text-xs text-muted-foreground">{plan.tokenLimit > 0 ? `${formatTokens(plan.tokenLimit)} Token` : t("Unlimited tokens", "不限 Token")}</p>{plan.code !== "FREE" && <Button type="button" variant="outline" disabled className="mt-3 w-full text-xs">{plan.code === usage.tier ? t("Current plan", "当前套餐") : t("Payment unavailable", "支付通道暂未开放")}</Button>}</div>)}</div></div>
          {Array.isArray(usage.packs) && usage.packs.length > 0 && <div className="mt-7"><h3 className="text-sm font-semibold">{t("Token packs", "Token 资源包")}</h3><p className="mt-1 text-xs text-muted-foreground">{t("Used automatically once monthly plan tokens run out; packs do not expire.", "本月套餐 Token 用完后自动使用，资源包不会过期。")}</p><div className="mt-3 grid gap-3 md:grid-cols-3">{usage.packs.map((pack) => <div key={pack.code} data-pack-code={pack.code} className="rounded-2xl border border-stone-200/70 bg-white/60 p-4"><span className="text-sm font-semibold">{locale === "en" ? pack.nameEn : pack.nameZh}</span><p className="mt-2 text-lg font-semibold tabular-nums">¥{(pack.priceCents / 100).toFixed(0)}</p><p className="mt-1 text-xs text-muted-foreground">{formatTokens(pack.tokenAmount)} Token</p></div>)}</div></div>}
          <form onSubmit={redeem} className="mt-6 rounded-2xl bg-stone-950/[0.03] p-4"><label htmlFor="redeem-code" className="text-sm font-medium">{t("Redeem a code", "使用兑换码")}</label><div className="mt-2 flex flex-col gap-2 sm:flex-row"><input id="redeem-code" className="field font-mono sm:flex-1" value={redeemCode} onChange={(event) => setRedeemCode(event.target.value.toUpperCase())} placeholder="ARDOR-XXXX-XXXX / PACK-XXXX-XXXX" maxLength={80} autoComplete="off" /><Button disabled={redeeming || !redeemCode.trim()} className="sm:w-auto">{redeeming ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Gift className="mr-2 size-4" />}{redeeming ? t("Redeeming…", "兑换中…") : t("Redeem", "兑换")}</Button></div><p className="mt-2 text-[11px] text-muted-foreground">{t("Codes are issued by an administrator. ARDOR codes change the plan; PACK codes add tokens.", "兑换码由管理员发放；ARDOR 码调整套餐，PACK 码增加 Token。")}</p>{redeemFeedback && <p role={redeemFeedback.kind === "error" ? "alert" : "status"} className={`mt-2 text-xs ${redeemFeedback.kind === "error" ? "text-red-600" : redeemFeedback.kind === "success" ? "text-emerald-700" : "text-stone-500"}`}>{redeemFeedback.message}</p>}</form>
          <p aria-label={t("Plan validity", "套餐有效期")} className="mt-4 text-xs text-muted-foreground">{usage.tier === "FREE" ? t("Free plan", "免费版") : usage.planExpiresAt === null ? t("No expiry", "长期有效") : usage.planExpiresAt && Number.isFinite(Date.parse(usage.planExpiresAt)) ? `${t("Expires", "到期时间")} · ${new Date(usage.planExpiresAt).toLocaleString(locale === "en" ? "en-US" : "zh-CN")}` : t("Expiry unavailable", "有效期未提供")}</p>
        </section>}
        {(!usage?.tokens || !Array.isArray(usage.plans)) && <p className="text-sm text-stone-500">{t("Usage information is currently unavailable.", "暂时无法取得使用额度。")}</p>}
        </div>
        <div className={category === "profile" ? "space-y-6" : "hidden"}>
        <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center gap-3"><UserRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">{t("Profile", "个人资料")}</h2></div>
          <p className="mt-2 text-sm text-muted-foreground">{t("Ardor uses these details as context for your career goals.", "Agent 会把这些信息作为求职上下文。")}</p>
          <ProfileAvatarSettings userId={user?.id ?? null} fallback={user?.email ?? "A"} />
          <form key={JSON.stringify(profile)} className="mt-7 space-y-5" onSubmit={saveProfile}>
            <label className="block text-sm font-medium">{t("Name", "称呼")}<input className="field mt-2" name="displayName" defaultValue={profile.displayName ?? ""} maxLength={120} /></label>
            <label className="block text-sm font-medium">{t("Headline", "当前定位")}<input className="field mt-2" name="headline" defaultValue={profile.headline ?? ""} maxLength={240} placeholder={t("For example: Java backend engineer with 3 years' experience", "例如：3 年经验 Java 后端工程师")} /></label>
            <label className="block text-sm font-medium">{t("Target roles", "目标岗位")}<input className="field mt-2" name="targetRoles" defaultValue={profile.targetRoles.join(", ")} placeholder={t("Java backend, AI engineer", "Java 后端，AI 应用工程师")} /><span className="mt-2 block text-xs font-normal text-muted-foreground">{t("Separate roles with commas", "多个岗位用逗号分隔")}</span></label>
            <label className="block text-sm font-medium">{t("Time zone", "时区")}<select className="field mt-2" name="timezone" defaultValue={profile.timezone}><option value="Asia/Shanghai">{t("China Standard Time", "中国标准时间")}</option><option value="Asia/Tokyo">{t("Japan Standard Time", "日本标准时间")}</option><option value="Europe/London">{t("UK time", "英国时间")}</option><option value="America/New_York">{t("US Eastern Time", "美国东部时间")}</option><option value="America/Los_Angeles">{t("US Pacific Time", "美国西部时间")}</option><option value="UTC">UTC</option></select></label>
            <Button><Save className="mr-2 size-4" />{t("Save profile", "保存资料")}</Button>
          </form>
        </section>
        </div>

        <div className={category === "security" ? "space-y-6" : "hidden"}><PasswordSettings /></div>

        <div className={category === "services" ? "grid gap-6" : "hidden"}>
        {!showPersonalApis ? <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
          <div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">{t("API services", "API 服务")}</h2></div>
          <div className="mt-8 rounded-[1.5rem] bg-gradient-to-br from-orange-50 to-violet-50 p-5">
            <p className="text-sm font-medium">{t("Provided by your administrator", "由管理员统一提供")}</p>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">{t(`${inheritedApiCount} services are ready. No API key needed.`, `已接入 ${inheritedApiCount} 项能力，你无需填写 API Key。`)}</p>
          </div>
          {personalApiCount > 0 && <p className="mt-4 text-xs text-muted-foreground">{t(`${personalApiCount} personal overrides configured.`, `当前另有 ${personalApiCount} 项个人覆盖配置。`)}</p>}
          <Button type="button" variant="outline" className="mt-6" onClick={() => setShowPersonalApis(true)}>{t("Use your own APIs", "使用自己的 API")}</Button>
        </section> : <>
        <section className="flex flex-wrap items-center justify-between gap-4 lg:col-span-2">
          <div><h2 className="text-xl font-semibold">{t("Personal APIs", "个人 API")}</h2><p className="mt-1 text-sm text-muted-foreground">{t("Only services you configure override the administrator defaults.", "只覆盖你主动配置的能力，其余继续使用管理员默认。")}</p></div>
          <div className="flex flex-wrap gap-3">
            <Button type="button" disabled={testingAll} onClick={() => void testAllConnections()}>{testingAll ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Activity className="mr-2 size-4" />}{testingAll ? t("Checking…", "正在检测…") : t("Test all connections", "测试全部连接")}</Button>
            <Button type="button" variant="outline" onClick={() => setShowPersonalApis(false)}>{t("Collapse", "收起")}</Button>
          </div>
        </section>

        <div className="grid items-start gap-4 lg:grid-cols-[15rem_minmax(0,1fr)]">
        <nav aria-label={t("API services", "API 服务")} className="ardor-panel rounded-[1.5rem] p-2">
          {apiServiceRows.map((row) => {
            const Icon = row.icon;
            const selected = apiService === row.type;
            return <button key={row.type} type="button" aria-current={selected ? "page" : undefined} onClick={() => setApiService(row.type)} className={`flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors ${selected ? "bg-violet-50 text-violet-800" : "text-stone-700 hover:bg-stone-50"}`}>
              <Icon className={`size-4 shrink-0 ${selected ? "text-violet-600" : "text-stone-400"}`} aria-hidden="true" />
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium">{row.label}</span>
                <span className={`mt-0.5 block truncate text-[11px] ${row.personal ? "text-violet-600" : row.configured ? "text-stone-400" : "text-amber-600"}`}>
                  {row.personal ? t("Personal", "个人配置") : row.configured ? t("Admin default", "管理员默认") : t("Not configured", "未配置")}
                </span>
              </span>
              <ChevronRight className={`size-3.5 shrink-0 ${selected ? "text-violet-500" : "text-stone-300"}`} aria-hidden="true" />
            </button>;
          })}
        </nav>
        <div className="min-w-0 space-y-6">
        <section className={apiService === "PRIMARY_LLM" ? "ardor-panel flex flex-col rounded-[2rem] p-6 md:p-8" : "hidden"}>
          <div className="flex items-center justify-between gap-4"><div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">{t("LLM service", "LLM 服务")}</h2></div>{llm.configured && <span className="flex items-center gap-1 rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="size-3.5" />{llm.personalOverride ? t("Personal", "个人配置") : t("Admin default", "管理员默认")} {llm.keyHint}</span>}</div>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{t("Ardor uses LangChain4j tool calling. Choose a model that supports tools, test the connection, then save.", "Agent 使用 LangChain4j Tool Calling。模型必须支持工具调用；建议先测试连接，再保存配置。")}</p>
          <form id="personal-api-primary-llm" key={JSON.stringify(llm)} className="mt-7 flex flex-1 flex-col gap-5" onSubmit={saveLlm}>
            <label className="block text-sm font-medium">{t("API format", "API 协议")}<select className="field mt-2" name="provider" defaultValue={llm.provider}><option value="OPENAI_COMPATIBLE">OpenAI Compatible</option><option value="ANTHROPIC_COMPATIBLE">Anthropic Compatible</option></select></label>
            <div>
              <div className="flex items-center justify-between gap-3"><label htmlFor="llm-base-url" className="text-sm font-medium">Base URL</label><button type="button" onClick={fillPackyPreset} className="rounded-full bg-violet-50 px-2.5 py-1 text-[11px] font-medium text-violet-700 hover:bg-violet-100">Packy</button></div>
              <input id="llm-base-url" className="field mt-2 font-mono text-sm" type="url" name="baseUrl" defaultValue={llm.baseUrl} maxLength={512} required />
              <span className="mt-2 block text-xs font-normal leading-5 text-muted-foreground">{t("DeepSeek: https://api.deepseek.com · Packy Claude: Anthropic Compatible + https://cf.api.fan · Packy other models: OpenAI Compatible + /v1.", "DeepSeek 填 https://api.deepseek.com；Packy Claude 选 Anthropic Compatible 并填 https://cf.api.fan；Packy 其他模型选 OpenAI Compatible 并加 /v1。")}</span>
            </div>
            <div>
              <div className="flex items-center justify-between gap-3">
                <label htmlFor="llm-model" className="text-sm font-medium">{t("Model name", "模型名称")}</label>
                <button type="button" disabled={fetchingModels} onClick={(event) => void fetchModelOptions(event)} className="inline-flex items-center gap-1.5 rounded-full bg-violet-50 px-2.5 py-1 text-[11px] font-medium text-violet-700 hover:bg-violet-100 disabled:opacity-50">
                  {fetchingModels ? <LoaderCircle className="size-3 animate-spin" /> : <RefreshCw className="size-3" />}
                  {fetchingModels ? t("Reading…", "读取中…") : t("Fetch models", "获取模型")}
                </button>
              </div>
              <input id="llm-model" className="field mt-2 font-mono text-sm" name="model" defaultValue={llm.model} maxLength={160} required list="llm-model-options" />
              <datalist id="llm-model-options">{modelOptions.map((model) => <option key={model} value={model} />)}</datalist>
              {modelOptions.length > 0 && <select aria-label={t("Available models", "可用模型")} defaultValue="" onChange={(event) => { if (!event.target.value) return; (document.getElementById("llm-model") as HTMLInputElement).value = event.target.value; }} className="field mt-2 font-mono text-sm">
                <option value="">{t(`Select from ${modelOptions.length} fetched models`, `从已获取的 ${modelOptions.length} 个模型中选择`)}</option>
                {modelOptions.map((model) => <option key={model} value={model}>{model}</option>)}
              </select>}
              {modelFeedback && <p role={modelFeedback.kind === "error" ? "alert" : "status"} className={`mt-2 text-xs leading-5 ${modelFeedback.kind === "error" ? "text-red-600" : modelFeedback.kind === "success" ? "text-emerald-700" : "text-stone-500"}`}>{modelFeedback.message}</p>}
            </div>
            <label className="block text-sm font-medium">API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={llm.personalOverride ? t(`Current personal key ${llm.keyHint}; enter a new key to replace it`, `当前个人 Key ${llm.keyHint}；输入新 Key 才会替换`) : llm.configurationSource === "ADMIN" ? t("Leave blank to test the admin default; enter a key to override it", "留空测试管理员默认；填写后创建个人覆盖") : t("An API key is required for initial setup", "首次配置必须填写")} /></label>
            <div className="mt-auto flex flex-wrap gap-3 pt-2"><Button type="button" variant="outline" disabled={testingLlm || savingLlm} onClick={testLlm}>{testingLlm ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingLlm ? t("Testing…", "正在测试…") : t("Test connection", "测试连接")}</Button><Button disabled={testingLlm || savingLlm}>{savingLlm ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingLlm ? t("Saving…", "正在保存…") : t("Save personal configuration", "保存为个人配置")}</Button>{llm.personalOverride && <Button type="button" variant="outline" onClick={() => void restoreAdminDefault("llm")}><RotateCcw className="mr-2 size-4" />{t("Restore admin default", "恢复管理员默认")}</Button>}</div>
            <FeedbackBanner feedback={llmFeedback} />
          </form>
        </section>

        <section className={apiService === "WEB_SEARCH" ? "ardor-panel flex flex-col rounded-[2rem] p-6 md:p-8" : "hidden"}>
          <div className="flex items-center justify-between gap-4">
            <div className="flex items-center gap-3"><Globe2 className="size-5 text-primary" /><h2 className="text-xl font-semibold">{t("Web search", "联网搜索")}</h2></div>
            {webSearch.configured && <span className="flex items-center gap-1 rounded-full bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800"><CheckCircle2 className="size-3.5" />{webSearch.personalOverride ? t("Personal", "个人配置") : t("Admin default", "管理员默认")} {webSearch.keyHint}</span>}
          </div>
          <p className="mt-2 text-sm leading-6 text-muted-foreground">{t("Let Ardor verify current jobs, companies, policies and news. Tavily keys are stored encrypted.", "让 Agent 在遇到最新职位、公司、政策和新闻时主动查证。当前使用 Tavily，Key 只会加密保存。")}</p>
          <form id="personal-api-web-search" className="mt-7 flex flex-1 flex-col gap-5" onSubmit={saveWebSearch}>
            <label className="block text-sm font-medium">Tavily API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={webSearch.personalOverride ? t(`Current personal key ${webSearch.keyHint}; enter a new key to replace it`, `当前个人 Key ${webSearch.keyHint}；输入新 Key 才会替换`) : webSearch.configurationSource === "ADMIN" ? t("Leave blank to test the admin default; enter a key to override it", "留空测试管理员默认；填写后创建个人覆盖") : t("An API key is required for initial setup", "首次配置必须填写")} /></label>
            <p className="rounded-2xl bg-stone-950/[0.04] px-4 py-3 text-xs leading-5 text-muted-foreground">{t("The test checks your Tavily key and usage. It does not run a search or consume search credits.", "测试只读取 Tavily Key 与账户用量，不执行搜索，不消耗 Search credit。")}</p>
            <div className="mt-auto flex flex-wrap gap-3 pt-2">
              <Button type="button" variant="outline" disabled={testingWebSearch || savingWebSearch} onClick={testWebSearch}>{testingWebSearch ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingWebSearch ? t("Testing…", "正在测试…") : t("Test connection", "测试连接")}</Button>
              <Button disabled={testingWebSearch || savingWebSearch}>{savingWebSearch ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingWebSearch ? t("Saving…", "正在保存…") : t("Save configuration", "保存配置")}</Button>
              {webSearch.personalOverride && <Button type="button" variant="outline" onClick={() => void restoreAdminDefault("web-search")}><RotateCcw className="mr-2 size-4" />{t("Restore admin default", "恢复管理员默认")}</Button>}
            </div>
            <FeedbackBanner feedback={webSearchFeedback} />
          </form>
        </section>

            {integrations.map((integration) => {
              const detail = integrationDetails[integration.serviceType];
              const Icon = detail.icon;
              return (
                <form id={`personal-api-${integration.serviceType.toLowerCase()}`} key={JSON.stringify(integration)} className={apiService === integration.serviceType ? "ardor-panel flex flex-col rounded-[2rem] p-6 md:p-8" : "hidden"} onSubmit={(event) => saveIntegration(event, integration.serviceType)}>
                  <div className="flex items-start justify-between gap-3">
                    <div className="flex gap-3"><span className="grid size-9 shrink-0 place-items-center rounded-xl bg-stone-950 text-white"><Icon className="size-4" /></span><div><h3 className="font-semibold">{detail.title}</h3><p className="mt-1 text-xs leading-5 text-muted-foreground">{detail.description}</p></div></div>
                    {integration.configured && <span className="shrink-0 text-xs font-medium text-emerald-700">{integration.personalOverride ? t("Personal", "个人配置") : t("Admin default", "管理员默认")} {integration.keyHint}</span>}
                  </div>
                  <div className="mt-5 grid gap-4 sm:grid-cols-2">
                    {integration.serviceType === "FALLBACK_LLM"
                      ? <label className="block text-xs font-medium">{t("API format", "API 协议")}<select className="field mt-2" name="provider" defaultValue={integration.provider?.toUpperCase().includes("ANTHROPIC") ? "ANTHROPIC_COMPATIBLE" : "OPENAI_COMPATIBLE"}><option value="OPENAI_COMPATIBLE">OpenAI Compatible</option><option value="ANTHROPIC_COMPATIBLE">Anthropic Compatible</option></select></label>
                      : <label className="block text-xs font-medium">{t("Provider", "服务商")}<input className="field mt-2 text-sm" name="provider" defaultValue={integration.provider} maxLength={120} required placeholder={t("For example, OpenAI", "例如 OpenAI")} /></label>}
                    <label className="block text-xs font-medium">{t("Model", "模型")}<input className="field mt-2 font-mono text-sm" name="model" defaultValue={integration.model} maxLength={160} required placeholder={t("Model name", "模型名称")} /></label>
                    <label className="block text-xs font-medium sm:col-span-2">Base URL<input className="field mt-2 font-mono text-sm" type="url" name="baseUrl" defaultValue={integration.baseUrl} maxLength={512} required placeholder="https://api.example.com" /></label>
                    {integration.serviceType === "FALLBACK_LLM" && <div className="flex flex-wrap items-center gap-2 sm:col-span-2"><span className="text-xs text-muted-foreground">{t("Relay preset", "中转站预设")}</span><button type="button" onClick={(event) => fillFallbackPackyPreset(event)} className="rounded-full bg-violet-50 px-2.5 py-1 text-[11px] font-medium text-violet-700 hover:bg-violet-100">Packy Claude</button><span className="text-[11px] leading-5 text-muted-foreground">{t("Anthropic Compatible + https://cf.api.fan; use the matching Packy model ID.", "选 Anthropic Compatible，Base URL 填 https://cf.api.fan，模型名使用 Packy 的 Claude ID。")}</span></div>}
                    <label className="block text-xs font-medium sm:col-span-2">API Key<input className="field mt-2 font-mono text-sm" type="password" name="apiKey" maxLength={4096} autoComplete="new-password" data-1p-ignore data-lpignore="true" placeholder={integration.personalOverride ? t(`Current personal key ${integration.keyHint}; enter a new key to replace it`, `当前个人 Key ${integration.keyHint}；输入新 Key 才会替换`) : integration.configurationSource === "ADMIN" ? t("Leave blank to test the admin default; enter a key to override it", "留空测试管理员默认；填写后创建个人覆盖") : t("An API key is required for initial setup", "首次配置必须填写")} /></label>
                  </div>
                  <div className="mt-auto flex flex-wrap gap-2 pt-6">
                    <Button type="button" variant="outline" disabled={testingIntegrations[integration.serviceType] || savingIntegrations[integration.serviceType]} onClick={(event) => testIntegration(event, integration.serviceType)}>{testingIntegrations[integration.serviceType] ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Wifi className="mr-2 size-4" />}{testingIntegrations[integration.serviceType] ? t("Testing…", "正在测试…") : t("Test connection", "测试连接")}</Button>
                    <Button disabled={testingIntegrations[integration.serviceType] || savingIntegrations[integration.serviceType]}>{savingIntegrations[integration.serviceType] ? <LoaderCircle className="mr-2 size-4 animate-spin" /> : <Save className="mr-2 size-4" />}{savingIntegrations[integration.serviceType] ? t("Saving…", "正在保存…") : t("Save configuration", "保存配置")}</Button>
                    {integration.personalOverride && <Button type="button" variant="outline" onClick={() => void restoreAdminDefault(integration.serviceType)}><RotateCcw className="mr-2 size-4" />{t("Restore admin default", "恢复管理员默认")}</Button>}
                  </div>
                  <FeedbackBanner feedback={integrationFeedback[integration.serviceType] ?? null} />
                </form>
              );
            })}
        </div>
        </div>
        </>}
        </div>
        </div>
      </div>

      <Dialog
        open={healthDialogOpen}
        onClose={() => setHealthDialogOpen(false)}
        title={t("Connection health check", "连接健康检查")}
        description={testingAll ? t("Checking each service. You can close this and keep using settings.", "正在逐项检查，可关闭后继续使用设置页。") : t("Checks complete. Results remain until you close this dialog.", "检查完成。结果会保留到你手动关闭。")}
        footer={<Button type="button" onClick={() => setHealthDialogOpen(false)}>{t("Close", "关闭")}</Button>}
      >
        <div className="space-y-2">
          {(Object.keys(healthServiceLabels) as HealthServiceType[]).map((type) => {
            const result = healthResults?.[type] ?? { kind: "pending", message: t("Waiting…", "等待检测…") };
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

function formatTokens(value: number) {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value % 1_000_000 === 0 ? 0 : 1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(value % 1_000 === 0 ? 0 : 1)}K`;
  return String(value);
}
