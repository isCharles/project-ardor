import Link from "next/link";

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
  return (
    <main className={styles.page}>
      <div className={styles.light} aria-hidden="true" />
      <header className={styles.header}>
        <Link href="/" className={styles.brand} aria-label="返回 Ardor 首页">
          <span className={styles.brandMark} aria-hidden="true">✦</span>
          Ardor
        </Link>
      </header>
      <div className={styles.layout}>
        <div className={styles.statement} aria-hidden="true">
          <span>下一步，</span>
          <span>继续向前。</span>
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
