import type { Metadata } from "next";

import "./globals.css";

export const metadata: Metadata = {
  title: "Ardor",
  description: "记住你求职进度的 career agent：简历、模拟面试、薄弱点和日程。",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>{children}</body>
    </html>
  );
}
