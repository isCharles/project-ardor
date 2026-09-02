"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useState } from "react";
import { ArrowRight } from "lucide-react";

import { Button } from "@/components/ardor/button";
import { Field, Input } from "@/components/ardor/field";
import { Notice } from "@/components/ardor/page";
import { Wordmark } from "@/components/ardor/wordmark";
import { api } from "@/lib/api";

import styles from "./register.module.css";

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
    <main className={styles.page}>
      <section className={styles.story} aria-label="Ardor 产品介绍">
        <Link href="/" className={styles.brand} aria-label="返回 Ardor 首页">
          <Wordmark size="lg" />
        </Link>

        <div className={styles.storyBody}>
          <p className={styles.eyebrow}>YOUR CAREER, IN MOTION</p>
          <h1>
            你的职业，
            <span>继续向前。</span>
          </h1>
          <p className={styles.lede}>简历、面试、行动。一次连接，持续推进。</p>
        </div>
      </section>

      <section className={styles.formSide}>
        <div className={styles.loginPrompt}>
          <span>已经有账号？</span>
          <Link href="/login">直接登录 <ArrowRight aria-hidden /></Link>
        </div>

        <div className={styles.formWrap}>
          <div className={styles.mobileBrand}>
            <Link href="/" aria-label="返回 Ardor 首页"><Wordmark size="lg" /></Link>
          </div>
          <p className={styles.step}>START HERE</p>
          <h2>现在开始。</h2>
          <p className={styles.description}>一分钟，建立你的职业工作台。</p>

          <form className={styles.form} onSubmit={submit}>
            <Field label="称呼（选填）" htmlFor="displayName">
              <Input
                className={styles.input}
                id="displayName"
                name="displayName"
                maxLength={120}
                autoComplete="name"
                autoFocus
                placeholder="你的名字或昵称"
              />
            </Field>
            <Field label="邮箱" htmlFor="email">
              <Input
                className={styles.input}
                id="email"
                type="email"
                name="email"
                autoComplete="email"
                required
                placeholder="name@example.com"
              />
            </Field>
            <Field label="密码" htmlFor="password">
              <Input
                className={styles.input}
                id="password"
                type="password"
                name="password"
                minLength={8}
                maxLength={72}
                autoComplete="new-password"
                required
                placeholder="至少 8 位字符"
              />
            </Field>
            {error && <Notice tone="bad">{error}</Notice>}
            <Button type="submit" variant="primary" size="lg" className={styles.submit} loading={busy}>
              进入 Ardor <ArrowRight className="size-4" aria-hidden />
            </Button>
          </form>
        </div>
      </section>
    </main>
  );
}
