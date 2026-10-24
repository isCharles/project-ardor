"use client";

import { MarkdownContent } from "./markdown-content";
import { useLocale } from "@/lib/locale";
import { associateLearningFeedback, type LearningAnswer, type QuestionFeedback, type LearningReviewSummary } from "@/lib/learning-feedback";

export function LearningAttemptReview({ items, questionFeedback, summary }: {
  items: LearningAnswer[]; questionFeedback?: QuestionFeedback[]; summary: LearningReviewSummary;
}) {
  const { t } = useLocale();
  const { perQuestion, unmatched } = associateLearningFeedback(items, questionFeedback);
  const strengths = Array.isArray(summary.strengths) ? summary.strengths.filter((value) => typeof value === "string") : [];
  const gaps = Array.isArray(summary.gaps) ? summary.gaps.filter((value) => typeof value === "string") : [];
  return <div className="mt-5 space-y-7">
    <section aria-label={t("Question-by-question review", "逐题批改")}>
      <h3 className="text-sm font-semibold text-stone-900">{t("Question-by-question review", "逐题批改")}</h3>
      <div className="mt-4 divide-y divide-stone-200/80">{items.map((item, index) => <article key={item.exerciseId ?? index} className="py-5 first:pt-0" aria-label={t(`Question ${index + 1}`, `第 ${index + 1} 题`)}>
        <p className="font-medium leading-7 text-stone-900">{index + 1}. {item.question}</p>
        <div className="mt-3 rounded-xl bg-stone-100/70 p-4">
          <h4 className="text-xs font-semibold text-stone-500">{t("Your answer", "你的回答")}</h4>
          <p className="mt-2 whitespace-pre-wrap break-words text-sm leading-7 text-stone-700">{item.answer}</p>
        </div>
        <div className="mt-3 border-l-2 border-violet-300 pl-4">
          <h4 className="text-xs font-semibold text-violet-700">{t("Feedback", "批改意见")}</h4>
          {typeof item.choiceCorrect === "boolean" && <p className="mt-2 text-sm font-medium">{item.choiceCorrect ? t("System check: correct", "系统判定：正确") : t("System check: incorrect", "系统判定：错误")}</p>}
          {perQuestion[index].length ? perQuestion[index].map((entry, i) => <MarkdownContent key={i} className="mt-2 text-sm leading-7 text-stone-700">{entry.feedback}</MarkdownContent>)
            : <p className="mt-2 text-sm leading-6 text-stone-500">{unmatched.length
              ? t("Feedback could not be reliably linked to this question. See the unassigned feedback below.", "反馈未能可靠对应到这道题，请查看下方“待对应反馈”。")
              : t("No linked feedback was returned for this question.", "本次评估未返回可对应到这道题的批改意见。")}</p>}
        </div>
      </article>)}</div>
    </section>
    {unmatched.length > 0 && <section aria-label={t("Unassigned feedback", "待对应反馈")} className="rounded-2xl border border-amber-200 bg-amber-50/60 p-4">
      <h3 className="text-sm font-semibold text-amber-900">{t(`Unassigned feedback (${unmatched.length})`, `待对应反馈（${unmatched.length}）`)}</h3>
      <p className="mt-2 text-xs leading-6 text-stone-600">{t("These comments were saved, but their question identity is missing or ambiguous. They are shown in full instead of guessed or hidden.", "这些评语已保存，但题目标识缺失或无法唯一对应。这里完整保留，不猜测配题，也不隐藏。")}</p>
      <div className="mt-3 divide-y divide-amber-200/70">{unmatched.map((entry, index) => <div key={index} className="py-3">
        {entry.question && <p className="text-sm font-medium leading-7 text-stone-800">{entry.question}</p>}
        <MarkdownContent className="mt-2 text-sm leading-7 text-stone-700">{entry.feedback}</MarkdownContent>
      </div>)}</div>
    </section>}
    {(summary.feedback || strengths.length > 0) && <section aria-label={t("Round summary", "本轮总结")} className="border-t border-stone-200 pt-5">
      <h3 className="text-sm font-semibold text-stone-900">{t("Round summary", "本轮总结")}</h3>
      {summary.feedback && <MarkdownContent className="mt-3 text-sm leading-7 text-stone-700">{summary.feedback}</MarkdownContent>}
      {strengths.length > 0 && <div className="mt-4"><h4 className="text-xs font-semibold text-stone-600">{t("What went well", "已掌握的部分")}</h4><ul className="mt-2 list-disc space-y-2 pl-5 text-sm leading-7">{strengths.map((value, i) => <li key={i}><MarkdownContent>{value}</MarkdownContent></li>)}</ul></div>}
    </section>}
    {(summary.nextFocus || gaps.length > 0) && <section aria-label={t("Next steps and knowledge gaps", "下一步与知识补强")} className="border-t border-stone-200 pt-5">
      <h3 className="text-sm font-semibold text-stone-900">{t("Next steps and knowledge gaps", "下一步与知识补强")}</h3>
      {summary.nextFocus && <MarkdownContent className="mt-3 text-sm leading-7 text-stone-700">{summary.nextFocus}</MarkdownContent>}
      {gaps.length > 0 && <div className="mt-4"><h4 className="text-xs font-semibold text-stone-600">{t("Knowledge to strengthen", "需要补强的知识点")}</h4><ul className="mt-2 list-disc space-y-2 pl-5 text-sm leading-7">{gaps.map((value, i) => <li key={i}><MarkdownContent>{value}</MarkdownContent></li>)}</ul></div>}
    </section>}
  </div>;
}
