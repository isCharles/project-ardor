import type { Metadata } from "next";
import { LocaleProvider } from "@/lib/locale";
import { DesktopFrame } from "@/components/ardor/desktop-frame";

import "./globals.css";

export const metadata: Metadata = {
  title: "Ardor",
  description: "An AI career agent for resumes, interviews, learning, and momentum.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body><LocaleProvider><DesktopFrame>{children}</DesktopFrame></LocaleProvider></body>
    </html>
  );
}
