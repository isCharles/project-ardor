import type { Metadata } from "next";
import { DesktopUpdatePrompt } from "@/components/ardor/desktop-update-prompt";
import { LocaleProvider } from "@/lib/locale";

import "./globals.css";

export const metadata: Metadata = {
  title: "Ardor",
  description: "An AI career agent for resumes, interviews, learning, and momentum.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body><LocaleProvider><DesktopUpdatePrompt />{children}</LocaleProvider></body>
    </html>
  );
}
