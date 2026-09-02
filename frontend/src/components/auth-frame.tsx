import Link from "next/link";

import { Wordmark } from "@/components/ardor/wordmark";

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
    <main className="grid min-h-dvh place-items-center bg-[var(--ardor-surface)] px-5 py-12">
      <div className="w-full max-w-[22rem]">
        <Link href="/" className="inline-block rounded-[var(--ardor-radius-sm)]">
          <Wordmark />
        </Link>
        <h1 className="t-title-1 mt-10 text-[var(--ardor-ink)]">{title}</h1>
        <p className="t-body mt-2 text-[var(--ardor-ink-3)]">{description}</p>
        <div className="mt-8">{children}</div>
        <p className="t-body mt-7 border-t border-[var(--ardor-rule)] pt-5 text-[var(--ardor-ink-3)]">
          {footer}
        </p>
      </div>
    </main>
  );
}
