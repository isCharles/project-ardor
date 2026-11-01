# 执行记录与追踪存储职责

本文回答三件事：哪份数据必须完整、哪份数据允许丢失、详细 Span 到底由谁维护。
它是分阶段迁移的依据；实现之前不改动任何已执行迁移，也不删除旧轨迹数据。

## 一、按数据类别定职责

| 类别 | 现在存在哪 | 完整性要求 | 目标归属 | 理由 |
| --- | --- | --- | --- | --- |
| Run 生命周期与结论（状态、错误码、取消/截止、requestId 幂等） | `agent_runs` | **必须完整**，用户与运维据此判断发生了什么 | PostgreSQL，业务层同步写 | 结论不是遥测副产品 |
| 审批与提案决定 | `agent_proposals`、`agent_tool_audit.approval_source`、`agent_permission_grants` | **必须完整**，删除等副作用据此执行 | PostgreSQL | 授权边界，不能靠采样 Trace 复原 |
| 业务操作结果与幂等依据 | `messages`（`request_id`/`run_id`）、`agent_plans`/`agent_plan_steps`、各模块业务表 | **必须完整** | PostgreSQL | 用户历史记录与重放判断的唯一依据 |
| 计费与额度 | `usage_token_ledger`、`usage_quota_counters`/`usage_quota_requests` | **必须完整**，且只用供应商回报的真实用量 | PostgreSQL | 计费与准确统计不得从 Trace 推算 |
| 后台任务状态与重试 | `ai_jobs`（`attempts`、claim/complete/fail 栅栏）、`agent_message_queue` | **必须完整**（状态与次数） | PostgreSQL | 恢复与重入据此判断 |
| 每次尝试的详细过程（模型/工具/基础设施 Span 的时序、父子关系、预览） | `agent_trace_spans` + OTel→Jaeger **两套** | 允许丢失或过期，但必须如实标注 | OTel 单一发射源；PostgreSQL 只保留产品轨迹页需要的 owner-scoped 投影 | 详细 Span 是诊断，不是业务结论 |

判断口径：**「某步骤是否执行」以审计表、计划步骤与任务状态为准，绝不以 Trace 中有没有 Span 为准。**
Trace 缺失只说明没记录到，不说明没发生。

## 二、当前必须修掉的三处耦合

1. **业务汇总依赖遥测开关。** `agent_runs` 的调用数、Token、模型、prompt 版本只由
   `AgentRunTracer.finish` 调用 `AgentTraceStore.updateRunAggregates` 写入，而 `finish` 前置条件是
   `active()`（`app.agent.trace.enabled` 为真且 RUN Span 插入成功，见 `AgentRunTracer.java:216-248`）。
   关掉轨迹或 Span 写失败时，持久化的 Run 记录会静默停留在 0/NULL。这些数字属于业务事实，必须由业务层写。
2. **同一棵 Span 树被搭两次。** `AgentTraceStore`/`AgentRunTracer` 手工维护父子、耗时与 summary，
   `TechnicalRunTrace` 又按相同形状与名称镜像一棵（`agent_trace_spans.otel_trace_id`/`otel_span_id` 做映射）。
   两份生命周期与计时逻辑必然漂移，且每个 Span 的 input/output/cache token 与 `agent_runs` 汇总、
   `usage_token_ledger` 三处重复。
3. **轨迹缺失被当成记录不存在。** `AgentTraceController.trace` 在没有 Span 但 Run 存在时返回 404
   「执行记录不存在」（`AgentTraceController.java:34-40`），前端按不存在处理并重试 10 次。
   「仍在导出」「未采样」「超过保留期」「观测后端不可用」「确实没有」是五种不同事实，界面必须分开说。

另有一处事务风险：`AgentRunStore.complete` 是 `@Transactional`，内部调用
`AgentExecutionAuditStore.linkRunExchange`，该调用吞掉 `RuntimeException`；同一 PostgreSQL 事务里
失败的语句仍可能让提交回滚，而 `messages.request_id/run_id` 只在这里写入。业务链接不应寄存在轨迹写入的副作用里。

## 三、层级合同

当前工具 Span 挂在提出它的 LLM Span 之下：`AgentModelTraceListener.java:130-132` 按请求 ID 绑定父 Span，
`ApprovedAgentTools.startToolSpan`（`ApprovedAgentTools.java:313-320`）再取该父节点。模型是先回答、
工具后执行，父子关系在这里表达的是「谁提到的」，不是「谁包含谁」，还会让 LLM 时长包含工具时长。

目标形状：

```text
Run
└── Turn（一次模型请求—响应—其工具执行的迭代；既不是聊天气泡，也不是计划步骤）
    ├── LLM      duration = 模型请求到回答结束
    ├── TOOL     duration = 工具实际执行；与 LLM 同级
    └── CONTEXT / COMPACTION / PLAN  与两者同级
```

- 因果关系用 `toolCallId` 与 OTel Span Link 表达（LLM Span → 由它产生的 TOOL Span），不用父子包含。
- 后台任务不伪造父 Span 时长：以 `jobId`/`attempt` 关联，父 Run 不因异步任务结束而延长。
- 计时规则：每个 Span 只测自己的墙钟时间；父 Turn 时长 = 其开始到最后一个子 Span 结束，
  **子 Span 时长不累加到父节点**（现有 `AGENT_TRACE_VIEW.md` 的口径保持不变）。
- 状态永不合并：未执行、仅提议、失败、被打断、结果未知都不写成成功；`UNKNOWN` 只能由真实回执改写。

## 四、采样与保留（按当前规模）

- 部署是单机 Docker、单一操作者、Jaeger all-in-one（1.62.0，Badger，168 小时 TTL，仅 127.0.0.1）
  且不在主 compose 里。这个量级**不需要为采样引入集群或采样器**。
- Agent 轨迹保持 100% 记录：一次 Run 只有个位数到十几个 Span，成本远低于其带来的可解释性。
- PostgreSQL 投影保留 30 天（`AgentTraceStore.java:227-236`），供产品轨迹页查历史；
  观测后端保留窗口独立，界面按上面五种状态如实区分。
- 敏感内容处理发生在采样与导出之前：`TracePreview`（900 字符）与 attributes 4000 字符上限已经存在于
  PostgreSQL 路径；OTel 导出继续只走 `GenAiSemconv` 白名单，不把 prompt、工具正文、简历内容交给导出器。
  观测系统故障不得阻断业务执行——这一点已由批量导出与 `ardor.otel.export.failures` 计数保证，保持即可。

## 五、分阶段迁移与回退

| 阶段 | 内容 | 结束条件 | 回退 |
| --- | --- | --- | --- |
| 0 | 解开业务事实对遥测的依赖：Run 汇总由业务层写；`messages` 与 Run 的业务链接不再经由轨迹写入；Trace API 区分「Run 存在但没有/未准备好轨迹记录」并返回可判定状态而非 404 | 关闭 `app.agent.trace.enabled` 时，Run 的调用数、Token、模型与历史记录仍完整；轨迹页在无 Span 时说明原因 | 单阶段回滚，无 schema 变更 |
| 1 | 一棵树：Span 生命周期只由 OTel 形状的产生器发出，PostgreSQL 投影由该产生器写入；引入 Turn 层，工具改为同级 + `toolCallId` Link；删除镜像树与重复计时/汇总 | 同一 Run 只有一处产生父子与耗时；旧数据仍可读 | 保留旧产生器开关，可切回手工建树 |
| 2 | 去重复字段：停止在 Span attributes 中重复 token/计划事实；产品页只读投影，观测细节跳观测后端 | 每个业务事实只有一个权威存储；`agent_trace_spans` 不删行 | 只停写新逻辑，历史行保留 |
| 3 | 可选：观测后端可用时在产品页给出技术子树深链 | 五种状态在界面可区分且互不冒充 | 关掉入口 |

- 每阶段独立 PR、可独立回滚；已执行迁移不改写，需要新字段用新迁移并验证升级路径。
- 双写只在阶段 1 的切换窗口内存在，必须有明确范围（同一 Run 的 Span 集合）、结束条件（旧产生器删除）
  和差异校验（对比两棵树的 Span 数、父子关系与耗时差值，差值仅允许来自计时起点定义变化）。
- 验证一律用 Fake 模型与本地夹具，不消耗付费额度；覆盖两个用户交叉查询、导出延迟、采样缺失、
  观测后端离线、重启持久性、旧记录可读、异步任务关联、层级与计时、遥测丢失时业务状态与计费仍正确。

## 六、尚未确定、必须先验证再实现

- 观测后端选型：现用 Jaeger all-in-one + Badger 只有单机能力，产品侧不把它当作权威存储。
  是否升级版本或换后端，取决于阶段 1 之后谁需要长期看技术子树；**不因接入需要而预设集群**。
  任何切换都先用查询 API、分页、保留策略与隔离边界实测，再决定。
- 产品是否最终只读投影、把完整 Span 交给观测后端，取决于阶段 0/1 的实测数据量与运维成本，本文不预设结论。
