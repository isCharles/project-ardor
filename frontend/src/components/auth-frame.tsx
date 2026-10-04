"use client";

import Link from "next/link";

import { LocaleSwitch } from "@/components/ardor/locale-switch";
import { useLocale } from "@/lib/locale";
import styles from "./auth-frame.module.css";

export function AuthFrame({
  title,
  description,
  children,
  footer,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
  footer: React.ReactNode;
}) {
  const { t } = useLocale();
  return (
    <main className={styles.page}>
      <div className={styles.light} aria-hidden="true" />
      <header className={styles.header}>
        <Link href="/app" className={styles.brand} aria-label={t("Go to Ardor", "前往 Ardor")}>
          <span className={styles.brandMark} aria-hidden="true">✦</span>
          Ardor
        </Link>
        <LocaleSwitch />
      </header>
      <div className={styles.layout}>
        <div className={styles.statement} aria-hidden="true">
          <span>{t("Your next", "下一步，")}</span>
          <span>{t("move starts here.", "继续向前。")}</span>
        </div>
        <section className={styles.panel} aria-labelledby="auth-title">
          <h1 id="auth-title" className={styles.title}>{title}</h1>
          <p className={styles.description}>{description}</p>
          <div className={styles.form}>{children}</div>
          <p className={styles.footer}>{footer}</p>
        </section>
      </div>
    </main>
  );
}
