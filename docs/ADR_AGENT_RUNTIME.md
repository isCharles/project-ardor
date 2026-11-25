# ADR：保留有界工具循环作为 Agent Runtime

- 状态：Accepted
- 日期：2026-11-25
- 关联 Spec：AEBB-001
- 范围：运行时职责与工程边界；本 ADR 不改变业务行为

## 背景

Ardor 当前入口是 `CareerAgentService` 驱动的 LangChain4j 有界工具调用循环。它已经承担上下文装配、模型调用、工具执行、权限确认、流式输出、停止与持久化关联。历史 Graph 方案是实验设计，不是当前生产合同。

继续引入第二个 Runtime 会重复授权、恢复、trace 和评测逻辑，并使“哪个路径拥有用户副作用”更难证明。

## 决策

保留单一聊天 Runtime，并明确以下职责：

| 职责 | Owner | 当前边界 |
| --- | --- | --- |
| 上下文、记忆、引用与压缩 | `AgentContextBuilder` / `AgentContextCompactor` | 只装配当前用户可信上下文，不替代业务授权 |
| 模型调用与工具循环 | `CareerAgentService` | 有界轮次；不是通用 Graph 或持久工作流引擎 |
| 工具授权与确认 | `ApprovedAgentTools` / 业务 Service | 模型不能自我批准；最终副作用由受鉴权业务接口执行 |
| 计划准入与验证 | `AgentPlanner` / `PlanningAgentTools` | Java 校验结构、顺序与真实工具结果；`doneWhen` 不是证据 |
| 运行与消息持久化 | `AgentRunStore` / `AgentConversationStore` | 保存运行、交流、进度和队列；不保存模型循环断点 |
| 取消、引导与用户串行 | `CareerAgentService` / `AgentStreamLease` | 进程内执行者保证；多副本需要单独 fencing Spec |
| 执行事实与诊断 | `AgentRunTracer` / `AgentTraceStore` / OTel | 产品 trace 为权威诊断记录；trace 完成不等于任务成功 |
| 质量判定 | `scripts/agent-eval-*` | Output、Outcome、Trajectory 与 Execution Success 分开报告 |

## 保证与非目标

### 当前保证

- 单实例内，取消、引导和同一用户串行由本地执行租约与用户锁协调。
- 运行、消息、队列和进度快照持久化；服务重启后可恢复只读状态。
- 显式取消会阻止本实例保存迟到回复；已经开始的外部操作不能假装撤销。
- 删除与普通写入通过具体提案和业务授权执行，聊天口令不是批准。
- 评测报告固定代码、数据集/split、提示词、工具 schema、模型和参数哈希。

### 当前非目标

- 多副本执行租约、分布式撤销和跨实例完成 fencing。
- 从模型对话位置、工具循环栈或审批中断点自动续跑。
- 通用 Graph/Workflow Runtime。
- 把 `model_call_ceiling` 视为已启用的生产预算；它当前没有生产调用者。
- 用 trace `COMPLETED`、run `COMPLETED` 或模型自述证明业务任务成功。

## 后果

- 新业务能力继续复用现有 Runtime、业务 Service 与工具围栏，不建立平行 Agent 路径。
- 可靠性扩展优先补执行证据和评测，而不是增加架构层数。
- 扩容前必须另立分布式执行 Spec；持久化续跑、计划预算启用和 Trace 层级重构也分别立项。
- 文档、测试和评测必须区分单实例保证、持久化恢复与真实模型质量。

## 验证

- `AgentExecutionEvidencePostgresTests` 在真实 PostgreSQL 验证运行/trace 归属、事务完成/回滚和取消终态。
- `ArchitectureBoundaryTests` 保护单一聊天入口与模块边界。
- Golden Path 验证真实应用栈中的运行、恢复和业务路径；fake model 只证明合同与确定性行为。
