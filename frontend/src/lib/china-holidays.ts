export type ChinaDayInfo = {
  name: string;
  kind: "holiday" | "workday";
  firstDay?: boolean;
};

const DAYS = new Map<string, ChinaDayInfo>();

function addRange(start: string, end: string, name: string) {
  const current = new Date(`${start}T00:00:00+08:00`);
  const last = new Date(`${end}T00:00:00+08:00`);
  while (current <= last) {
    const key = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(current);
    DAYS.set(key, { name, kind: "holiday", firstDay: key === start });
    current.setUTCDate(current.getUTCDate() + 1);
  }
}

function addWorkdays(dates: string[]) {
  for (const date of dates) DAYS.set(date, { name: "调休上班", kind: "workday" });
}

// 国务院办公厅《2026年部分节假日安排的通知》（国办发明电〔2025〕7号）
// https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm
addRange("2026-01-01", "2026-01-03", "元旦");
addRange("2026-02-15", "2026-02-23", "春节");
addRange("2026-04-04", "2026-04-06", "清明");
addRange("2026-05-01", "2026-05-05", "劳动节");
addRange("2026-06-19", "2026-06-21", "端午");
addRange("2026-09-25", "2026-09-27", "中秋");
addRange("2026-10-01", "2026-10-07", "国庆");
addWorkdays(["2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10"]);

export function chinaDayInfo(date: Date) {
  const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
  return DAYS.get(key) ?? null;
}
