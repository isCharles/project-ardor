"use client";

import { KeyRound, LoaderCircle } from "lucide-react";
import Link from "next/link";
import { FormEvent, useState } from "react";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

export function PasswordSettings() {
  const { t } = useLocale();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [changed, setChanged] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    const form = event.currentTarget;
    const data = new FormData(form);
    const currentPassword = String(data.get("currentPassword") ?? "");
    const newPassword = String(data.get("newPassword") ?? "");
    const confirmation = String(data.get("confirmation") ?? "");
    if (newPassword !== confirmation) {
      setError(t("The new passwords do not match", "两次输入的新密码不一致"));
      return;
    }
    setBusy(true);
    try {
      await api<void>("/api/auth/change-password", {
        method: "POST",
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      form.reset();
      setChanged(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Could not change password", "修改密码失败"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
      <div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">{t("Account security", "账号安全")}</h2></div>
      {changed ? (
        <div className="mt-7 space-y-4">
          <p role="status" className="text-sm text-emerald-800">{t("Password updated. Other devices will need to sign in again.", "密码已更新。其他设备再次请求时须重新登录。")}</p>
          <Button asChild><Link href="/login">{t("Sign in again", "重新登录")}</Link></Button>
        </div>
      ) : (
        <form className="mt-7 space-y-5" onSubmit={submit}>
          <label className="block text-sm font-medium">{t("Current password", "当前密码")}<input className="field mt-2" type="password" name="currentPassword" autoComplete="current-password" required /></label>
          <label className="block text-sm font-medium">{t("New password", "新密码")}<input className="field mt-2" type="password" name="newPassword" autoComplete="new-password" minLength={8} maxLength={72} required /></label>
          <label className="block text-sm font-medium">{t("Confirm new password", "确认新密码")}<input className="field mt-2" type="password" name="confirmation" autoComplete="new-password" minLength={8} maxLength={72} required /></label>
          {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
          <Button disabled={busy}>{busy && <LoaderCircle className="mr-2 size-4 animate-spin" />}{busy ? t("Changing…", "正在修改…") : t("Change password", "修改密码")}</Button>
          <p className="text-xs text-muted-foreground">{t("Your current password is required. This session ends immediately; other sessions expire on their next request.", "需要当前密码；修改后当前会话立即退出，其他设备再次请求时失效。")}</p>
        </form>
      )}
    </section>
  );
}
