# 目标简历能力与证据映射

对应 `交给项目Agent的实施需求.md` 的五项目标能力。状态只有三种：**现已能写**（有代码、测试和可重复证据）、**仍需实测**（机制存在，但结论依赖尚未授权的真实模型/供应商运行）、**不建议写**（未实现、未验证或会夸大边界）。

真实模型付费评测尚未获得授权，因此任何关于真实模型质量、真实供应商成本或语义可靠性的结论都属于 **仍需实测**。本文件不复制简历正文；需要改写的措辞可由简历聊天按此映射处理。

## 1. 基于 AI Services 的多步工具调用，具备能力权限、人工审批、持久 Run、逻辑动作幂等及取消边界

**状态：现已能写。**

| 能力 | 证据 |
| --- | --- |
| AI Services 多步工具调用 | `CareerAgentService`、`ApprovedAgentTools`、`SchedulingAgentToolProvider` 等模块化工具提供者；`ModularAgentToolsTests` 验证工具发现与调用 |
| 能力权限 | `ToolCatalog`/`Capability` 与 `AgentPermissionGrants`；只读、写入、删除分别受控，读请求不进入审批 |
| 人工审批 | 写操作生成待确认提案，用户在界面确认后才执行；删除只提案；`ModularAgentToolsTests`、Golden Path 审批拒绝案例 |
| 持久 Run | `agent_runs`（V22）、`AgentRunStore`、`AgentMessageQueue`；刷新/重启后按 requestId 恢复真实状态 |
| 逻辑动作幂等 | 学习计划槽位（V31）与日历创建槽位（V72）；`CalendarTaskAgentSlots` 按用户 + 逻辑动作 + 语义键（标题/时间/优先级/关联）复用同一任务，原始 JSON 字符串去重不算幂等；`CalendarTaskAgentSlotsPostgresTests` 覆盖“已提交但响应丢失” |
| 取消边界 | 排队取消 `DELETE /api/agent/queue/{id}`；运行取消 `POST /api/agent/runs/{id}/cancel`；未知派发标记 `INTERRUPTED`，不自动重放；迟到 attempt 被 fencing |

**可写边界**：日历创建是跨 Run 语义幂等的实测样本；其他写工具按各自业务主键或请求 ID 防重，不应概括为“所有写操作幂等”。取消不是回滚，已发生的副作用必须读回。不要写“全业务 Exactly-once”。

## 2. 关联 Run、LLM、Tool 及后台任务的执行记录，采集可信 Usage、次数、分段耗时及错误，能复算延迟与成本

**状态：现已能写（token 与延迟可复算）；货币成本需外部价目表，产品未内置单价。**

| 能力 | 证据 |
| --- | --- |
| Run/LLM/Tool 层级 Trace | `AgentTraceStore`、`AgentRunTracer`；tool span 以发起它的 LLM span 为父并带 toolCallId；RUN 根记录 originRun 与自动化 jobId（PR #247） |
| 可信 Usage 与次数 | 区分 assistant/summary 调用，工具区分 requested/attempted/executed；缺失 Usage 保持 `null` 不置零；`/api/agent/runs/{id}/trace` 的 `totals` 来自完成时写入的 RUN 摘要（PR #245/#246） |
| 分段耗时 | run、LLM、tool、TTFT、上下文准备、审批等待、业务执行、排队等待均为 histogram；可复算 P50/P95（PR #247、#251） |
| 错误与失败终态 | 工具校验/资源不存在/执行失败分别记录 `INVALID_ARGUMENTS`、`RESOURCE_NOT_FOUND`、`TOOL_FAILED` 等 FAILED span，不伪装成 COMPLETED（PR #247） |
| 长 Trace | offset/limit 分页、`returnedSpans/totalSpans/nextOffset/truncated`，分页不改变 RUN 级总计（PR #246） |
| 后台任务关联 | AI job completion/retry/dead-letter 指标，任务等待时间与队列深度（PR #251） |

**可写边界**：产品记录 token 数、调用次数、耗时和错误，成本可用“token × 外部价目表”重算；不要写“产品内置计费”。不要写已构造真实超过 128 span 的端到端运行（分页与总数一致性由单元/合同测试覆盖）；排队等待已单独计时，但跨实例 Trace 未实现。

## 3. Token Budget 上下文组装，结合版本摘要、长期记忆与协议保持压缩，有质量和成本对照证据

**状态：机制与离线对照现已能写；真实模型质量与真实成本仍需实测。**

| 能力 | 证据 |
| --- | --- |
| 统一上下文组装 | `AgentContextBuilder` 组装 system prompt、长期记忆、摘要、近期消息、引用资料与预算；所有聊天路径复用 |
| 容量感知预算 | `AgentContextBudgetPolicy` 区分未知窗口与已知窗口；未知模型标 `null`，12K/200K 软预算不当硬上限（PR #241、#208） |
| 版本化摘要 | `AgentContextCompactor` 保留原始消息，摘要版本推进；结构化摘要覆盖目标/决定/纠正/实体/未解决问题；供应商截断检测有限重试（PR #230/#231） |
| 协议保持压缩 | `RepeatedToolResultCompactor` 只压缩完全一致、只读、有配对调用的中间结果，保留首尾和纠正；不压缩错误或写操作 |
| 质量/成本对照 | `ContextComparisonExperimentTests`（PR #249）对 20/80/200 轮比较原文/摘要/摘要+重复读，报告保留分子分母、违规副作用、总 Token、摘要时延和 32K 窗口投影；丢失标签对照证明指标非空转 |
| 可观察性 | `ardor_agent_context_compactions`、`ardor_agent_context_compaction_failures` 计数；Trace 区分 assistant/summary 调用 |

**可写边界**：离线实验使用 Fake 摘要器，虚拟时延不是真实延迟；真实模型的摘要质量、供应商 tokenizer 精度和“普适无损”未验证。不要写历史提升百分比或“保证不丢信息”。语义召回验收（百轮暗号）证明的是合成协议下的事实保留，不是真实模型语义可靠性。

## 4. 业务 Golden Dataset、确定性断言、分层结果及历史回归，条件具备后再加校准 Judge 和 Prompt×Model 四组对照

**状态：前半句现已能写；Judge、Prompt×Model 四组不建议写。**

| 能力 | 证据 |
| --- | --- |
| 版本化 Golden Dataset | `scripts/agent-eval-cases.json`：24 条案例（13 representative / 11 challenge / 3 heldOut），覆盖读取、创建、修改、澄清、审批拒绝、取消、错误参数、资源归属、工具失败、写后响应丢失 |
| 确定性断言 | `scripts/agent-eval-assertions.mjs`；taskSuccess/paramValid/toolExecuted/safeRejection/environmentError 五个分母分开，禁止副作用与重复写入判失败 |
| 分层结果 | 报告按业务成功与环境错误分层，Unknown Usage 不置零；`scripts/agent-eval-report.mjs` 保存代码 SHA、dataset/prompt/tool-schema 哈希和逐 case 结果 |
| 历史回归 | `scripts/agent-eval-diff.mjs` 对比历史报告，业务回归与环境错误分开 |
| 端到端业务读回 | `scripts/agent-eval-e2e.mjs` 的 9 个日历案例：真实运行时 + REST 播种/读回 + 完整 Trace |
| CI | Golden Path 用 Fake 模型跑 harness 与 e2e，不产生费用 |

**不建议写**：Judge 已接入并校准（未实现）；Prompt×Model 四组已完成（未运行，且未获付费授权）；真实模型基准分数。可写“已交付可运行的 harness、Fake 验证和真实模型运行配置，等待授权后采集真实数据”。Judge 与四组对照只能写成计划。

## 5. PostgreSQL 队列、SKIP LOCKED、可配置并发、有限重试、失联恢复、用户任务顺序和必要的模型准入

**状态：现已能写。**

| 能力 | 证据 |
| --- | --- |
| PostgreSQL 队列 + SKIP LOCKED | `agent_message_queue`（V36）、`ai_jobs`（V33）；`FOR UPDATE SKIP LOCKED` 领取（PR #250 及既有实现） |
| 可配置并发 | `ARDOR_AGENT_QUEUE_CAPACITY`、`ARDOR_AI_JOB_WORKER_CAPACITY`；`AgentMessageQueueWorkerTests` 验证不超过配置槽位 |
| 有限重试与死信 | `max_attempts`、指数退避 30s→600s、`DEAD`/`AI_JOB_RECOVERY_EXHAUSTED`；旧 attempt 的迟到回调被 `attempts` 条件隔离（`AiJobAttemptFencingTests`） |
| 失联恢复 | 心跳；超过 5 分钟无心跳按 `max_attempts` 回到 `QUEUED` 或死信；未知派发标记 `INTERRUPTED` 不盲重放 |
| 用户任务顺序 | 按 `sequence` 串行，同一用户同一时刻只有一个运行；审批等待释放槽位但不破坏顺序；失败暂停后续消息，显式 resume 才继续 |
| 队列观测 | `ardor_agent_queue_depth`、`ardor_agent_queue_oldest_queued_seconds`、`ardor_agent_queue_claims`、`ardor_agent_queue_failures`、`ardor_ai_jobs_depth`、`ardor_ai_jobs_oldest_queued_seconds{priority}`、job wait 与完成/重试/死信计数（PR #251） |
| 必要模型准入 | 进程内共享 `ARDOR_MODEL_MAX_CONCURRENT_CALLS`（默认 8）；可选 Redis 分钟窗口 `ARDOR_MODEL_QPM` / `ARDOR_MODEL_TPM` / `ARDOR_MODEL_USER_QPM`（默认 0 关闭）跨实例限制每模型请求、估算 token 与每用户请求；触顶返回 `LLM_BUSY`，后台按既有退避重排；供应商 429 同样分类 |

**可写边界**：这是进程内并发护栏加可选的 Redis 分钟窗口，不是供应商配额的精确复刻；QPM/TPM 默认关闭，token 使用发送前估算而非供应商实际用量，分钟边界允许短时双倍突发。聊天队列已按会员优先级与 60 秒老化排序（V76），但多副本压力下的公平与吞吐仍待实测。不要写“精确分布式配额”“与供应商限额一致”或“绝不丢任务”。

## 汇总

| 目标能力 | 状态 |
| --- | --- |
| 1. 多步工具调用、权限、审批、持久 Run、逻辑幂等、取消边界 | 现已能写（幂等以日历创建为实测样本） |
| 2. Trace、Usage、次数、分段耗时、错误、延迟/成本复算 | 现已能写（货币成本需外部价目表） |
| 3. Token Budget、版本摘要、长期记忆、协议保持压缩 | 机制与离线对照现已能写；真实质量/成本仍需实测 |
| 4. Golden Dataset、确定性断言、分层结果、历史回归 | 现已能写；Judge 与 Prompt×Model 四组不建议写 |
| 5. PostgreSQL 队列、SKIP LOCKED、并发、重试、恢复、顺序、模型准入 | 现已能写（进程内护栏，非 QPM/TPM 平台） |

### 不建议写进简历的表述

- 全业务 Exactly-once、绝不重复写入、跨服务事务。
- 跨实例全局 QPM/TPM、分布式限流、多副本公平调度。
- 真实模型质量分数、成本节省百分比、Judge 校准结论、Prompt×Model 四组实测。
- 超过 128 span 的真实端到端运行已验收（只有合同/单元测试）。
- 百轮摘要“无损”或适用于所有供应商（只有 Fake 协议的合成证据）。
- Kafka、Redis Stream、Kubernetes、多 Agent 平台、通用 DAG 编辑器等未落地的架构词。
