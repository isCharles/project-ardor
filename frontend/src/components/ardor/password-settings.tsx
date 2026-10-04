"use client";

import { KeyRound, LoaderCircle } from "lucide-react";
import Link from "next/link";
import { FormEvent, useState } from "react";

import { Button } from "@/components/ui/button";
import { api } from "@/lib/api";

export function PasswordSettings() {
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
      setError("两次输入的新密码不一致");
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
      setError(reason instanceof Error ? reason.message : "修改密码失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="ardor-panel rounded-[2rem] p-6 md:p-8">
      <div className="flex items-center gap-3"><KeyRound className="size-5 text-primary" /><h2 className="text-xl font-semibold">账号安全</h2></div>
      {changed ? (
        <div className="mt-7 space-y-4">
          <p role="status" className="text-sm text-emerald-800">密码已更新。其他设备再次请求时须重新登录。</p>
          <Button asChild><Link href="/login">重新登录</Link></Button>
        </div>
      ) : (
        <form className="mt-7 space-y-5" onSubmit={submit}>
          <label className="block text-sm font-medium">当前密码<input className="field mt-2" type="password" name="currentPassword" autoComplete="current-password" required /></label>
          <label className="block text-sm font-medium">新密码<input className="field mt-2" type="password" name="newPassword" autoComplete="new-password" minLength={8} maxLength={72} required /></label>
          <label className="block text-sm font-medium">确认新密码<input className="field mt-2" type="password" name="confirmation" autoComplete="new-password" minLength={8} maxLength={72} required /></label>
          {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
          <Button disabled={busy}>{busy && <LoaderCircle className="mr-2 size-4 animate-spin" />}{busy ? "正在修改…" : "修改密码"}</Button>
          <p className="text-xs text-muted-foreground">需要当前密码；修改后当前会话立即退出，其他设备再次请求时失效。</p>
        </form>
      )}
    </section>
  );
}
