export function resumeScoreBand(score: number, t: (en: string, zh: string) => string): string {
  if (score >= 85) return t("Top tier", "顶尖");
  if (score >= 70) return t("Competitive", "有竞争力");
  if (score >= 55) return t("Below average", "中下");
  if (score >= 40) return t("Draft", "初稿");
  return t("High risk", "高风险");
}
