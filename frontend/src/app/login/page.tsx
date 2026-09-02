"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";

import { Button } from "@/components/ardor/button";
import { Field, Input } from "@/components/ardor/field";
import { Notice } from "@/components/ardor/page";
import { AuthFrame } from "@/components/auth-frame";
import { api } from "@/lib/api";

export default function LoginPage() {
  const router = useRouter();
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
      setError(reason instanceof Error ? reason.message : "登录失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthFrame
      title="继续"
      description="登录后回到你上次停下的地方。"
      footer={
        <>
          还没有账号？{" "}
          <Link href="/register" className="text-[var(--ardor-ink)] underline decoration-[var(--ardor-accent-line)] underline-offset-4 hover:decoration-[var(--ardor-accent)]">
            创建一个
          </Link>
        </>
      }
    >
      <form className="space-y-4" onSubmit={submit}>
        <Field label="邮箱" htmlFor="email">
          <Input id="email" type="email" name="email" autoComplete="email" required autoFocus />
        </Field>
        <Field label="密码" htmlFor="password">
          <Input id="password" type="password" name="password" autoComplete="current-password" required />
        </Field>
        {error && <Notice tone="bad">{error}</Notice>}
        <Button type="submit" variant="primary" size="lg" className="w-full" loading={busy}>
          登录
        </Button>
      </form>
    </AuthFrame>
  );
}
