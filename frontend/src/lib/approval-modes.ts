import { CircleCheck, Hand, Settings2, ShieldAlert, type LucideIcon } from "lucide-react";

export type ApprovalMode = "ASK" | "AUTO" | "FULL" | "CUSTOM";

export const approvalModes: Array<{ mode: ApprovalMode; icon: LucideIcon; label: [string, string]; description: [string, string] }> = [
  { mode: "ASK", icon: Hand, label: ["Ask for approval", "每次询问"], description: ["Ask before changes.", "写入前先询问。"] },
  { mode: "AUTO", icon: CircleCheck, label: ["Approve for me", "自动判断"], description: ["Routine actions run automatically.", "常规自动，重要操作询问。"] },
  { mode: "FULL", icon: ShieldAlert, label: ["Full access", "完全权限"], description: ["Deletion and suggestion cards still ask.", "删除和建议卡仍需确认。"] },
  { mode: "CUSTOM", icon: Settings2, label: ["Custom", "自定义"], description: ["Permissions by module.", "按模块设置。"] },
];
