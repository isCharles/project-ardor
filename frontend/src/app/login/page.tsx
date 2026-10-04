"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";

import { Button } from "@/components/ardor/button";
import { Field, Input } from "@/components/ardor/field";
import { Notice } from "@/components/ardor/page";
import { AuthFrame } from "@/components/auth-frame";
import { api } from "@/lib/api";
import { useLocale } from "@/lib/locale";

export default function LoginPage() {
  const router = useRouter();
  const { t } = useLocale();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const data = new FormData(event.currentTarget);
    try {
      await api("/api/auth/login", {
        method: "POST",
        body: JSON.stringify({ email: data.get("email"), password: data.get("password") }),
      });
      router.replace("/app");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t("Sign in failed", "登录失败"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      title={t("Welcome back", "欢迎回来")}
      description={t("Sign in and pick up where you left off.", "登录 Ardor，继续推进。")}
      footer={
        <>
          {t("New to Ardor?", "还没有账号？")}{" "}
          <Link href="/register">
            {t("Create an account", "创建账号")}
          </Link>
        </>
      }
    >
      <form className="space-y-4" onSubmit={submit}>
        <Field label={t("Email", "邮箱")} htmlFor="email">
          <Input id="email" type="email" name="email" autoComplete="email" required autoFocus />
        </Field>
        <Field label={t("Password", "密码")} htmlFor="password">
          <Input id="password" type="password" name="password" autoComplete="current-password" required />
        </Field>
        {error && <Notice tone="bad">{error}</Notice>}
        <Button type="submit" variant="primary" size="lg" className="w-full" loading={busy}>
          {t("Sign in", "登录")}
        </Button>
      </form>
    </AuthFrame>
  );
}
