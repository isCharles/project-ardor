# Ardor Agent 权限模型

本文固定 Agent 工具授权的分层设计。目标：用户不必为每次写操作点确认，但任何未经授权的操作仍然逐次弹窗确认。

## 五层结构

| 层 | 决定什么 | 实现 |
|---|---|---|
| 1 归属 | 资源是否属于当前用户 | 各 Service 的 `userId + id` 查询 |
| 2 意图 | 用户这句话是否授权这个操作 | `ToolIntentPolicy` |
| 3 许可 | 这次写操作是否需要用户当场确认 | `ApprovedAgentTools` + `AgentPermissionGrants` |
| 4 破坏性 | 删除类操作只生成提案，绝不直接执行 | `ApprovedAgentTools.PROPOSALS` + 提案卡 |
| 5 审计 | 自动放行、逐次确认与卡片生成都留痕 | `agent_tool_audit` + `agent_tool_calls` |

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
agent_tool_audit(id, user_id, run_id, tool, module, capability, arguments_digest, approval_source, created_at)
agent_tool_calls(id, user_id, conversation_id, run_id, message_id, tool_call_id, tool_name, module, capability,
                 approval_source, arguments_digest, arguments_size, result_digest, result_size, result_ref,
                 status, error_code, started_at, finished_at, created_at, updated_at)
agent_proposals(id, user_id, conversation_id, run_id, message_id, kind, target_id, target_ids, legacy_key,
                label, detail, endpoint, learning_request_id, status, resolution, resolution_source,
                created_at, updated_at, resolved_at)
```

审计只存参数摘要（SHA-256），不存参数正文，避免把敏感内容写入日志表。V53 增加的 `approval_source` 为 `AUTO`（只读或权限等级自动放行）、`CONFIRMED`（逐次确认）、`DELETE_PROPOSAL`/`PROPOSAL`（只生成卡片）；历史行默认 `AUTO`，旧写入者不写该列时行为不变。`agent_tool_calls` 是每次调用的结构化事实（状态可为 `STARTED`/`COMPLETED`/`FAILED`/`NOT_EXECUTED`/`PROPOSED`/`UNKNOWN`），`agent_proposals` 保存稳定 `proposalId` 与卡片生命周期；二者都只作为审计投影，不参与授权判断。详见 [Agent 执行关联与审计合同](AGENT_EXECUTION_CONTRACTS.md)。

## 执行流程

```
工具调用
  → ToolCatalog 查 module/capability（未登记 = 需要确认）
  → 检查运行状态与资源归属（工具输入仍是不可信模型输出）
  → capability = DELETE：构造提案，等待用户点击，不执行
  → grants.level(module) >= capability 最低等级：直接执行，写 AUTO 审计
  → 否则：进入运行级单次确认（现有弹窗），确认后执行并写 CONFIRMED 审计
  → 每次调用另写结构化 agent_tool_calls；取消/超时/失败只把未返回结果的调用记为 UNKNOWN
```

## API

- `GET /api/agent/permissions` → 全部模块及当前等级
- `PUT /api/agent/permissions/{module}` `{level: 0..3}`
- `GET /api/agent/runs/{id}/approvals` → 待确认操作，附带 `module`、`capability`、`suggestedLevel`，供弹窗提供「以后都允许」。
- `GET /api/agent/runs/{id}/tool-calls` / `GET /api/agent/runs/{id}/audit` → 属主校验后的只读执行事实（工具摘要、状态、授权来源、提案生命周期）；未知或他人运行返回 404。
- `PUT /api/agent/messages/{id}/proposal-resolution` `{key, resolution}`：记录本人消息中真实提案的展示决定（`DONE` / `DISMISSED` / `ACCEPTED` / `EXPIRED`）。决定写入消息的 `proposal_resolutions`，重新登录或换设备仍有效；同一提案首个决定不可被迟到请求覆盖。此接口不授权、更不执行任何业务操作。

### 历史交互恢复

- 以服务端保存的决定为准，设备缓存仅作为断网重试的补充。已拒绝、已完成、已接收的建议不恢复成待确认。
- 旧版本没有保存决定时，只恢复最后一条消息中、30 分钟内的未决建议；更早的建议保留在工作记录中并标为过期，不再自动弹窗。用户仍可重新提出操作。
- 切换会话时不携带其他会话的待确认项；运行级确认过滤已处理和过期请求，防止旧轮询响应重新显示它们。

## 安全边界

- 删除、批量清空永远需要提案确认。
- 长期记忆允许归纳，不要求逐字复述；模拟面试回答仍须引用用户原话，不能代答。
- 自动放行仍受运行租约、超时和用户隔离约束。
- 审计只增不改，用户可在后续阶段导出。

## 后续阶段

- P2：`tool` 级覆盖列、时间限定授权（本次对话内允许）。
- P3：风险分级、策略模板（保守/标准/信任）、撤销窗口、管理员上限。
