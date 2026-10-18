# Ardor Agent 权限模型

本文固定 Agent 工具授权的分层设计。目标：用户不必为每次写操作点确认，但任何未经授权的操作仍然逐次弹窗确认。

## 五层结构

| 层 | 决定什么 | 实现 |
|---|---|---|
| 1 归属 | 资源是否属于当前用户 | 各 Service 的 `userId + id` 查询 |
| 2 意图 | 用户这句话是否授权这个操作 | `ToolIntentPolicy` |
| 3 许可 | 这次写操作是否需要用户当场确认 | `ApprovedAgentTools` + `AgentPermissionGrants` |
| 4 破坏性 | 删除类操作只生成提案，绝不直接执行 | `ApprovedAgentTools.PROPOSALS` + 提案卡 |
| 5 审计 | 自动放行的写操作留痕 | `agent_tool_audit` |

权限级别控制前台工具是否逐次确认，删除仍然只出提案卡片。`ToolIntentPolicy` 是可用的意图判别辅助，不应宣称它在每个工具入口都强制执行；实际授权边界是运行状态、明确授予的模块能力或本次界面确认，以及业务归属校验。

## 能力分类

每个 `@Tool` 必须登记模块与能力，登记表是 `ToolCatalog`。启动校验（测试覆盖）保证没有漏登记的工具，未登记的工具按写操作处理，必须弹窗。

| 能力 | 含义 | 最低等级 |
|---|---|---|
| `READ` | 只读查询 | 0 |
| `CREATE` | 新增、生成、提交后台任务 | 1 |
| `UPDATE` | 修改已有数据 | 2 |
| `EXECUTE` | 触发有外部效果的动作（评估、提交面试等） | 3 |
| `DELETE` | 删除 | 永不自动放行 |

## 等级

模块级授权，存储为 `agent_permission_grants.level`（0–3）：

| 等级 | 名称 | 自动放行 |
|---|---|---|
| 0 | 仅查看（默认） | READ |
| 1 | 查 + 增 | READ, CREATE |
| 2 | 查 + 增 + 改 | READ, CREATE, UPDATE |
| 3 | 完全 | READ, CREATE, UPDATE, EXECUTE |

删除永远走提案卡片，不受等级影响。

## 模块

`profile`、`web`、`knowledge`、`learning`、`memory`、`applications`、`calendar`、`resumes`、`interviews`、`recaps`、`cards`、`opportunities`、`system`。

前台工具默认全部为 0：写操作逐次确认。后台长期记忆整理属于独立产品能力，可以自动新增和修正记忆，不逐条弹窗；此处的工具等级不代表关闭自动记忆。记忆来源、置信度、历史及用户编辑/删除入口见 `MEMORY_DESIGN.md`。

## 数据模型

```sql
agent_permission_grants(user_id, module, level, updated_at)   -- 模块覆盖；* 为全局默认
agent_tool_audit(id, user_id, run_id, tool, module, capability, arguments_digest, created_at)
```

审计只存参数摘要（SHA-256），不存参数正文，避免把敏感内容写入日志表。

## 执行流程

```
工具调用
  → ToolCatalog 查 module/capability（未登记 = 需要确认）
  → 检查运行状态与资源归属（工具输入仍是不可信模型输出）
  → capability = DELETE：构造提案，等待用户点击，不执行
  → grants.level(module) >= capability 最低等级：直接执行，写审计
  → 否则：进入运行级单次确认（现有弹窗），确认后执行
```

## API

- `GET /api/agent/permissions` → 全部模块及当前等级
- `PUT /api/agent/permissions/{module}` `{level: 0..3}`
- `GET /api/agent/runs/{id}/approvals` → 待确认操作，附带 `module`、`capability`、`suggestedLevel`，供弹窗提供「以后都允许」。

## 安全边界

- 删除、批量清空永远需要提案确认。
- 长期记忆允许归纳，不要求逐字复述；模拟面试回答仍须引用用户原话，不能代答。
- 自动放行仍受运行租约、超时和用户隔离约束。
- 审计只增不改，用户可在后续阶段导出。

## 后续阶段

- P2：`tool` 级覆盖列、时间限定授权（本次对话内允许）。
- P3：风险分级、策略模板（保守/标准/信任）、撤销窗口、管理员上限。
