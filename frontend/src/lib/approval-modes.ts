export type ApprovalMode = "ASK" | "AUTO" | "FULL" | "CUSTOM";

export const approvalModes: Array<{ mode: ApprovalMode; label: [string, string]; description: [string, string] }> = [
  { mode: "ASK", label: ["Ask for approval", "每次询问"], description: ["Read freely; ask before every write.", "查询直接执行，写入前都询问。"] },
  { mode: "AUTO", label: ["Approve for me", "自动判断"], description: ["Handle ordinary changes; ask for broader effects or more than three automatic writes per run.", "普通增改自动执行；影响较大或本轮自动写入超过三次时询问。"] },
  { mode: "FULL", label: ["Full access", "完全权限"], description: ["Create, edit and execute without asking. Deletion and suggestion cards still ask.", "创建、修改与执行直接放行；删除和建议卡仍须确认。"] },
  { mode: "CUSTOM", label: ["Custom", "自定义"], description: ["Use your saved defaults and module permissions.", "使用保存的全局等级和各模块权限。"] },
];
