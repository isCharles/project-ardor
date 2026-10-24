# Agent 执行关联与审计合同

本文件固定 E2 批次落地的「消息—运行—工具—提案—结果」关联与审计事实，对应 [架构演进规划](ARCHITECTURE_EVOLUTION.md) 第 14 节的可追溯要求与阶段 E。它描述当前实现和已验证边界，不是未来完整观测平台的设计稿。

## 1. 事实来源与身份

| 对象 | 事实表 | 稳定身份 | 说明 |
|---|---|---|---|
| 对话文本 | `messages` | `messages.id` | 用户/助手消息原文；仍是聊天的展示事实 |
| 一次运行 | `agent_runs` | `agent_runs.id`（= 浏览器 `requestId`） | 运行状态、失败码、最终助手消息 |
| 工具调用 | `agent_tool_calls` | 行 `id` + 模型侧 `tool_call_id` | 每次调用的授权来源、状态、参数/结果摘要 |
| 提案 | `agent_proposals` | `proposalId`（`agent_proposals.id`） | 确认卡片实体；保留旧 `key` 作为兼容投影 |
| 兼容审计 | `agent_tool_audit` | 行 `id` | 既有审计表，新增 `approval_source` |
| 业务结果 | 各业务表 / `ai_jobs` | 业务 UUID | 由业务 Service 写入；工具只记录关联引用 |

`requestId`、`conversationId`、`runId`、`toolCallId`、`proposalId` 与 `jobId`/业务 ID 是**关联标识**，不是授权凭证。任何审计行、摘要或引用都不能替代业务 Service 的归属与状态校验。

## 2. 关联图

```text
messages.user_id / conversation_id          （现有隔离 + 复合外键）
messages.request_id / run_id    ────►  agent_runs.id
agent_runs.assistant_message_id ────►  messages.id（完成时写入）
agent_tool_calls.user_id / conversation_id / run_id ────► 同用户会话与运行
agent_tool_calls.message_id ────► 本次运行的助手消息（完成时补写）
agent_tool_calls.tool_call_id ────► 模型供应商本轮的 tool call id
agent_tool_calls.result_ref ────► 可选的业务/后台任务 UUID（仅关联，不作完成证明）
agent_proposals.user_id / conversation_id / run_id / message_id ────► 同上
agent_proposals.learning_request_id ────► 用户确认后创建的 ai_jobs.id（学习生成）
agent_tool_audit.run_id ────► agent_runs.id（既有列，不新增外键）
```

历史回填只接受可证明的关系：

- `agent_runs.assistant_message_id` 唯一指向一条助手消息时，该消息获得 `run_id = request_id = 运行 id`。
- 与已关联助手消息紧邻、角色为 `USER` 且内容等于 `agent_runs.message` 的前一条消息获得同一关联。
- 一对多指向、缺少运行、时间无法证明或不满足上述条件的消息保持 `NULL`；不按时间邻近或文本相似猜测。

## 3. 写入合同

### 3.1 运行、消息与工具

- 流式持久运行（`agent_runs` 存在）在每次工具调用开始时写 `agent_tool_calls`，结束时写结果摘要、状态与 `finished_at`。
- 运行成功保存交流后，`messages.request_id/run_id` 与 `agent_runs.assistant_message_id` 在同一事务内落库；工具调用与提案行的 `message_id` 也在该事务内补写。
- 旧同步聊天（没有持久化运行）不创建运行、不写结构化运行关联；回复与旧字段保持原行为。
- 参数与结果只保存 SHA-256 摘要、长度、可选 `result_ref`，不复制提示词、简历、密钥或完整结果正文。

### 3.2 授权来源（`approval_source`）

| 值 | 含义 |
|---|---|
| `AUTO` | 只读调用，或模块权限等级已自动放行的写调用；不经过逐次弹窗 |
| `CONFIRMED` | 用户逐次确认后允许执行；拒绝/过期写 `NOT_EXECUTED` |
| `DELETE_PROPOSAL` | 删除类工具只生成确认卡片，未执行业务删除 |
| `PROPOSAL` | 非删除类确认卡片（当前为 `create_learning_plan`），同样不执行业务写入 |

自动放行仍写既有 `agent_tool_audit`（来源默认 `AUTO`）；逐次确认与卡片生成也写入该表，来源分别为 `CONFIRMED` / `DELETE_PROPOSAL` / `PROPOSAL`。新增列对旧写入者使用默认值，历史行含义不变。

### 3.3 状态（`agent_tool_calls.status`）

| 状态 | 含义 |
|---|---|
| `STARTED` | 已开始（含等待授权），尚无结果 |
| `COMPLETED` | 工具返回了结果；`result_digest`/`result_ref` 可读 |
| `FAILED` | 工具抛错；`error_code=TOOL_FAILED` |
| `NOT_EXECUTED` | 未确认、被拒绝或运行停止导致业务调用未发生 |
| `PROPOSED` | 只生成确认卡片，没有业务写入 |
| `UNKNOWN` | 已开始但运行取消/超时/失败/失联，无法确认结果；`error_code` 记录终止原因 |

取消、总时限、静默超时和运行失败时，只有仍为 `STARTED` 的行改为 `UNKNOWN`，`error_code` 使用 `AGENT_RUN_CANCELLED`、`AGENT_RUN_DEADLINE`、`AGENT_UPSTREAM_SILENT` 或 `AGENT_RUN_INTERRUPTED` 等真实终止码；已完成调用保留 `COMPLETED`。迟到返回的结果只会把 `STARTED/UNKNOWN` 如实补成 `COMPLETED/FAILED`，不会把未执行伪装为成功。

### 3.4 提案生命周期（`agent_proposals.status`）

```text
PROPOSED ──首次用户决定──► CONFIRMED / DISMISSED / EXPIRED
PROPOSED ──运行取消/超时/失败──► CANCELLED
```

- 同一提案的首个决定不可被迟到请求覆盖；旧 `messages.proposal_resolutions` 投影继续按旧 key 保存，旧客户端仍可恢复卡片状态。
- `CONFIRMED` 只表示用户/客户端报告了完成或接受决定，不是业务删除/创建成功的服务端证明；真正业务结果仍以业务表与认证接口为准。
- 回填不解析历史 trace 生成提案行；V53 之前的卡片继续只通过旧 JSON 投影解析，新决定不会伪造历史提案实体。

## 4. API

| 接口 | 说明 |
|---|---|
| `GET /api/agent/runs/{requestId}/tool-calls` | 属主校验后返回结构化工具调用（摘要/状态/授权来源） |
| `GET /api/agent/runs/{requestId}/audit` | 属主校验后返回工具调用、提案生命周期与兼容审计事件 |
| `GET /api/agent/runs/{requestId}` | 新增 `assistantMessageId`（未完成或旧运行可为 null） |
| 消息响应 | 新增 `requestId`/`runId`；历史无关联时为 null；旧字段不变 |
| 流式 `confirm` 事件 / trace 提案 | 新增稳定 `proposalId`；旧 `kind`/`endpoint`/`learningPlan.requestId` 全部保留 |
| `PUT /api/agent/messages/{id}/proposal-resolution` | 仍只记录展示决定；`key` 既接受旧卡片 key，也接受稳定 `proposalId` |

未知或他人的运行统一返回 404，不泄露运行是否存在；审计读取不会绕过运行归属校验。

## 5. 兼容与回退

### 流开始前的错误响应

`/api/agent/messages/stream` 成功响应仍为 SSE。流开始前的额度拒绝、请求校验、资源缺失和现有已分类异常，使用原 `ApiError` JSON 与原 HTTP 状态，不包装为成功 SSE，也不因客户端只接受 SSE 再退化成空白 500。公共异常处理器显式设置 JSON Content-Type，兼容旧客户端；新前端客户端和同站流式代理同时接受 `text/event-stream, application/json`。

额度/限流保持 429、`QUOTA_EXCEEDED`、原 message/retryable，以及 fields 中的 feature/dimension/resetAt 与 Retry-After。代理透传状态、JSON、Cookie/CSRF 和取消 signal，不将拒绝当作可自动重发的模型调用。拒绝请求不创建消息或模型运行；额度数值、权限、请求幂等键和成功流协议均不改变。已开始 SSE 后的运行失败仍走既有 error 事件，不试图改写已提交的 HTTP 状态。未知异常、所有错误码统一及真实供应商故障仍须各自验证，不能将本项当作完整 E3 交付。无迁移，前后端可回退；新后端也兼容旧的仅 SSE Accept。

终止错误必须在输入框上方直接可见，不能因工作记录中保存了相同原因就把外部提示去掉；工作记录默认折叠，仍保留完整尝试历史。自动重试期间继续使用原退避与取消规则，不将额度拒绝标成重连；失败不锁定后续输入，空状态也不重复展示同一条终止原因。

### 数据与客户端兼容

- V53 只新增列、表和索引，并回填可证明的关联；未改已执行迁移、旧字段、旧 API 语义或删除级联。
- 旧客户端忽略新增 JSON 字段，继续用旧卡片 key 与 `proposal_resolutions`；旧写入者不写 `approval_source` 时默认 `AUTO`。
- 回退仅回退应用版本，保留 V53 的增量 schema、已写入审计事实和 Flyway 记录；旧应用忽略新增对象即可。不要执行 V53 文件末尾的历史手动删表/删列示例：它会丢失审计数据，且 Flyway 已执行记录仍在，后续新版不会重新创建这些对象。若确需清理，必须另行批准、备份并使用新的前向迁移；不得改写已执行 V53 或手动删除其历史记录。
- 多副本、跨实例租约仍未实现；本批不改变单实例取消/引导保证。

## 6. 未验证项与限制

- 删除/创建卡片的真正业务结果仍由客户端决定后调用原认证接口；服务端只记录卡片生成与客户端上报的决定，无法证明业务写入是否成功。
- E2 首批未修改前端展示，新 ID 字段仅由 API 输出；后续前端与兼容投影以当前实现为准，不将本条当作全程无前端改动。
- E2 首批的 Golden Path/acceptance 接入曾单独交接，后续 workflow 与验收结果见 `REFACTOR_STATUS.md`，不能用初始批次的未接入说明覆盖已完成验收。
- 真实模型供应商对 `tool_call_id` 的提供情况未逐一验证；缺失时该列为 null，不影响其它关联。
- 多实例部署下的执行归属与审计补写竞争未验证。
