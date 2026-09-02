"use client";

import { ArrowUpRight, Pencil } from "lucide-react";
import Link from "next/link";
import * as React from "react";

import {
  activeInterview, dueCards, formatWhen, latestResume, memoryLines, nextTask, openTasks,
  weakPoints, type CareerContext,
} from "@/lib/career-context";
import { cn } from "@/lib/utils";
import styles from "./agent-briefing.module.css";

type Row = { series: string; label: string; value: string; href: string; filled: boolean };

function buildRows(context: CareerContext, memory: string): Row[] {
  const resume = latestResume(context);
  const running = activeInterview(context);
  const due = dueCards(context).length;
  const upcoming = nextTask(context);
  const weak = weakPoints(context);
  const memories = memoryLines(memory).length;
  const open = openTasks(context).length;

  const resumeValue = !resume
    ? "还没有简历"
    : resume.analysisId
      ? `${resume.originalFilename} · 已分析`
      : resume.analysisStatus === "QUEUED" || resume.analysisStatus === "RUNNING"
        ? `${resume.originalFilename} · 分析中`
        : `${resume.originalFilename} · 待分析`;

  return [
    { series: "R", label: "简历", value: resumeValue, href: "/app/resumes", filled: !!resume },
    {
      series: "I",
      label: "模拟面试",
      value: running
        ? `${running.targetCompany ? `${running.targetCompany} · ` : ""}${running.targetRole} 进行中`
        : context.interviews.length
          ? `${context.interviews.length} 场已完成`
          : "还没有面试记录",
      href: "/app/interviews",
      filled: context.interviews.length > 0,
    },
    {
      series: "V",
      label: "面经",
      value: context.recaps.length
        ? `${context.recaps.length} 份 · ${weak} 个待巩固`
        : "还没有整理过面经",
      href: "/app/recaps",
      filled: context.recaps.length > 0,
    },
    {
      series: "C",
      label: "记忆卡",
      value: context.cards.length
        ? due
          ? `${due} 张今天到期`
          : `${context.cards.length} 张 · 今天无到期`
        : "还没有卡片",
      href: "/app/cards",
      filled: context.cards.length > 0,
    },
    {
      series: "T",
      label: "日程",
      value: upcoming
        ? `${formatWhen(upcoming.dueAt!)} ${upcoming.title}`
        : open
          ? `${open} 项待安排`
          : "没有安排",
      href: "/app/calendar",
      filled: open > 0,
    },
    {
      series: "M",
      label: "长期记忆",
      value: memories ? `${memories} 条` : "还没有记住任何事",
      href: "#memory",
      filled: memories > 0,
    },
  ];
}

/* The opening screen of the product.

   It used to read "What should we build?" over a gradient — a headline
   borrowed from a code-generation tool, in English, in a Chinese career
   app, telling the user nothing. An agent that claims to hold your career
   state should open by showing that state and naming the next move. */
export function AgentBriefing({
  displayName,
  context,
  memory,
  suggestions,
  onSuggestion,
  onEditMemory,
  disabled,
}: {
  displayName: string | null;
  context: CareerContext;
  memory: string;
  suggestions: string[];
  onSuggestion: (text: string) => void;
  onEditMemory: () => void;
  disabled: boolean;
}) {
  const rows = buildRows(context, memory);
  const known = rows.filter((row) => row.filled).length;
  const name = displayName?.trim();

  return (
    <div className={cn("ardor-rise mx-auto w-full", styles.briefing)}>
      <header className={styles.briefingHeader}>
        <div>
          <p>CAREER SYSTEM / LIVE CONTEXT</p>
          <h2>
            {known === 0
              ? name
                ? `${name}，建立你的职业系统`
                : "建立你的职业系统"
              : name
                ? `${name} 的 Career OS`
                : "你的 Career OS"}
          </h2>
        </div>
        <div className={styles.systemCount}>
          <strong>{String(known).padStart(2, "0")}</strong>
          <span>/ 06 signals online</span>
        </div>
      </header>

      <ul className={styles.signalGrid}>
        {rows.map((row) => {
          const inner = (
            <>
              <span className={styles.signalMeta}><i className={row.filled ? styles.online : undefined} />{row.series} / {row.label}</span>
              <strong>{row.value}</strong>
              <span className={styles.signalState}>{row.filled ? "CONNECTED" : "WAITING"}</span>
            </>
          );
          const shared = cn(styles.signalModule, !row.filled && styles.signalEmpty);
          return (
            <li key={row.series}>
              {row.href === "#memory" ? (
                <button type="button" onClick={onEditMemory} className={shared}>
                  {inner}<Pencil className={styles.signalArrow} aria-hidden />
                </button>
              ) : (
                <Link href={row.href} className={shared}>
                  {inner}<ArrowUpRight className={styles.signalArrow} aria-hidden />
                </Link>
              )}
            </li>
          );
        })}
      </ul>

      <div className={styles.nextActions}>
        <p>NEXT ACTIONS / ASK ARDOR</p>
        <ul>
        {suggestions.map((text) => (
          <li key={text}>
            <button
              type="button"
              disabled={disabled}
              onClick={() => onSuggestion(text)}
              className={styles.suggestion}
            >
              <span>{text}</span><ArrowUpRight aria-hidden />
            </button>
          </li>
        ))}
        </ul>
      </div>
    </div>
  );
}
