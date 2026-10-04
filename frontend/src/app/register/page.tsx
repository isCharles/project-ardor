"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";

import { Button } from "@/components/ardor/button";
import { Field, Input } from "@/components/ardor/field";
import { Notice } from "@/components/ardor/page";
import { AuthFrame } from "@/components/auth-frame";
import { api } from "@/lib/api";

export default function RegisterPage() {
  const router = useRouter();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const data = new FormData(event.currentTarget);
    try {
      await api("/api/auth/register", {
        method: "POST",
        body: JSON.stringify({
          displayName: data.get("displayName"),
          email: data.get("email"),
          password: data.get("password"),
        }),
      });
      router.replace("/app");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "注册失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      title="开始使用 Ardor"
      description="建好账号，开始你的下一步。"
      footer={<>已有账号？ <Link href="/login">直接登录</Link></>}
    >
      <form className="space-y-4" onSubmit={submit}>
        <Field label="称呼（选填）" htmlFor="displayName">
          <Input id="displayName" name="displayName" maxLength={120} autoComplete="name" autoFocus placeholder="你的名字或昵称" />
        </Field>
        <Field label="邮箱" htmlFor="email">
          <Input id="email" type="email" name="email" autoComplete="email" required placeholder="name@example.com" />
        </Field>
        <Field label="密码" htmlFor="password">
          <Input id="password" type="password" name="password" minLength={8} maxLength={72} autoComplete="new-password" required placeholder="至少 8 位字符" />
        </Field>
        {error && <Notice tone="bad">{error}</Notice>}
        <Button type="submit" variant="primary" size="lg" className="w-full" loading={busy}>创建账号</Button>
      </form>
    </AuthFrame>
  );
}
