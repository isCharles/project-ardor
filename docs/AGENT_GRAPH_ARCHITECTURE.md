# Agent Graph Runtime · 架构设计

> 状态：历史实验设计，已由统一聊天执行入口取代。下文的 P0/P1 图运行时、关键词路由和独立阶段预算不是当前合同；当前行为见 `AGENT_TRACE_VIEW.md`。Graph 仅作为同一份 trace 的展示视图，历史数据仍可读取。
> 目标：把 Ardor 从“单次 tool-calling loop”升级为可解释、可中断、可恢复的 Agent Graph Runtime，作为项目工程深度的核心展示。

## 1. 现状与问题

当前 Agent 基于 LangChain4j `AiServices` 自动 tool loop：

```text
user message
  -> LLM
  -> N tool calls（一次可能返回 12 个）
  -> 自动执行工具
  -> tool results 回给 LLM
  -> 下一次 LLM
  -> final answer
```

它能工作，但存在三个架构级问题：

1. **没有显式步骤**。模型可以一次决定所有工具，用户只看到“调用工具”，看不到计划、证据分层和阶段结论。
2. **没有持久化检查点**。中断/失败后只能重启整轮，无法从已完成步骤恢复；工具副作用幂等靠零散逻辑保护。
3. **HITL 不是图节点**。当前写入确认是工具执行前的一次性审批，不是可暂停、可恢复、可修改参数的执行状态。

最新复杂 run 的实测：

- 4 次 LLM 调用
- 17 次 tool 调用
- 67.1s
- 第一次 LLM 在 1.8s 内直接发起 12 个工具
- trace 中所有 TOOL 都是 RUN 的直接子节点，没有 Thought/Observation/Reflect 层

## 2. 业界调研

### 2.1 Anthropic：workflow 与 agent 要分开

Anthropic 的 [Building effective agents](https://www.anthropic.com/research/building-effective-agents) 把 agentic system 分为 **workflow**（代码预定义路径）和 **agent**（模型动态决定路径），并建议：

- 从最简单的方案开始；
- 优先显式展示 planning steps；
- 复杂任务可以用 prompt chaining、routing、orchestrator-workers、evaluator-optimizer 等组合；
- 不要为了“像 Agent”而增加不必要的自主性。

对 Ardor 的启示：复杂求职任务适合 **workflow + bounded agent**，不是无限自主循环。多阶段流程由代码控制边界，模型只在阶段内决策。

### 2.2 LangGraph：checkpoint + interrupt 是核心，不是画图

LangGraph 的 [interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) 和 [checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers) 把两件事做成运行时能力：

- 图执行到中断点暂停；
- 通过 checkpointer 持久化状态，恢复时从同一 thread/checkpoint 继续；
- 支持 time travel、人工审批、修改后恢复；
- 状态写入必须在副作用之前，恢复后不能重复执行已完成的工具。

这比“图形化 trace”更重要：**图是执行模型，trace 只是观察结果。**

### 2.3 OpenAI Agents SDK：审批是 run-wide interruption

[OpenAI Agents SDK HITL](https://openai.github.io/openai-agents-python/human_in_the_loop/) 的做法：

- 工具声明 `needs_approval`；
- runner 在工具执行前暂停，返回 `interruptions`；
- 把结果转成可序列化的 `RunState`；
- 用户 `approve/reject` 后从 state 恢复；
- 审批决策可以 per-call 或 sticky；
- 可保存/恢复长时审批状态，且失败关闭（无法安全解析参数时必须人工审批）。

对 Ardor 的启示：审批信息必须是 **结构化 interruption**，不能只是聊天里说“已确认”。

### 2.4 Spring AI Alibaba：Java 生态的 ReactAgent + HITL Hook

[Spring AI Alibaba HITL](https://java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop/) 提供了接近我们需求的 Java 方案：

- `ReactAgent` + `HumanInTheLoopHook`；
- 可配置哪些工具需要审批；
- 支持 `approve / edit / reject` 三类决策；
- 依赖 checkpoint saver 持久化图状态；
- 中断恢复需要 thread ID 绑定执行。

但它会引入另一套 Agent/Graph 抽象，和现有 LangChain4j、权限、审计、确认 UI 需要适配。对简历项目，**自建一个薄图运行时并吸收其语义**更容易讲清楚设计能力，也不会被框架 API 绑死。若后续要复用生态，可以保留 `GraphRuntime` 接口，之后接 LangGraph4j 或 Spring AI Alibaba 作为适配器。

### 2.5 Temporal：durable execution 的参考，但不建议现在引入

Temporal 的 durable execution 适合长时间、跨服务、失败重试的工作流；其 LangGraph 插件也证明了“图 + 持久化执行”是生产方向。但 Ardor 是单体 Spring Boot + PostgreSQL，引入 Temporal 会增加部署和运维复杂度。当前只需 **数据库 checkpoint + 状态机**，不必上 Temporal。

## 3. 目标架构

### 3.1 核心：Agent Graph Runtime

```text
                    ┌──────────────┐
                    │    PLAN      │
                    └──────┬───────┘
                           │
              ┌────────────▼────────────┐
              │  GATHER (max 3 tools)   │
              └────────────┬────────────┘
                           │
                    ┌──────▼───────┐
                    │   OBSERVE    │
                    └──────┬───────┘
                           │
                    ┌──────▼───────┐
                    │   REFLECT    │
                    └──────┬───────┘
                           │
              ┌────────────┴────────────┐
              │                         │
       ┌──────▼──────┐          ┌───────▼────────┐
       │  ANSWER      │          │  PROPOSE/WRITE │
       └─────────────┘          └───────┬────────┘
                                        │
                                 ┌──────▼────────┐
                                 │ AWAIT_HUMAN   │
                                 └──────┬────────┘
                                        │
                              ┌─────────┴─────────┐
                              │                   │
                       ┌──────▼──────┐     ┌──────▼──────┐
                       │   EXECUTE   │     │  REJECTED   │
                       └──────┬──────┘     └──────┬──────┘
                              └──────────┬────────┘
                                    ┌────▼────┐
                                    │ ANSWER  │
                                    └─────────┘
```

### 3.2 节点职责

| 节点 | 是否调用 LLM | 职责 |
|---|---|---|
| `PLAN` | 是 | 把用户请求拆成 2–4 步，输出可见计划摘要 |
| `GATHER` | 是 | 每轮最多 3 个只读工具，禁止一次批量 12 个 |
| `OBSERVE` | 否 | 汇总工具结果、错误、证据覆盖情况 |
| `REFLECT` | 是 | 判断证据够不够、下一步查什么、是否进入分析 |
| `PROPOSE` | 是 | 生成写入提案，不执行 |
| `AWAIT_HUMAN` | 否 | 持久化中断，等待用户 approve/edit/reject |
| `EXECUTE` | 否 | 只执行已批准的工具调用，幂等 |
| `ANSWER` | 是 | 基于确认后的证据生成最终回答 |
| `EVALUATE` | 可选 | 检查答案引用、权限、格式和事实一致性 |

### 3.3 分支策略

- 简单任务（例如“读一下我的简历”）直接走 `GATHER -> ANSWER`，不强制 PLAN；
- 复杂任务（跨 ≥3 个模块，或用户明确要求规划/审计）走完整图；
- 任何写入必须经过 `PROPOSE -> AWAIT_HUMAN -> EXECUTE`；
- 任何工具失败进入 `REFLECT`，由模型决定重试读操作或降级，不重试写入；
- 预算耗尽进入 `ANSWER`，明确标注“部分完成”。

## 4. 持久化模型

### 4.1 扩展 `agent_runs`

新增字段（新 migration，不改历史 migration）：

```sql
ALTER TABLE agent_runs
  ADD COLUMN graph_version VARCHAR(32),
  ADD COLUMN current_node VARCHAR(32),
  ADD COLUMN checkpoint_seq INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN interrupt_id UUID,
  ADD COLUMN resume_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN budget_json JSONB NOT NULL DEFAULT '{}'::jsonb;
```

### 4.2 checkpoint 表

```sql
CREATE TABLE agent_run_checkpoints (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  run_id UUID NOT NULL,
  seq INTEGER NOT NULL,
  node VARCHAR(32) NOT NULL,
  state JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (run_id, seq),
  FOREIGN KEY (run_id, user_id) REFERENCES agent_runs(id, user_id) ON DELETE CASCADE
);
```

规则：

- 每个节点执行完成后写入 checkpoint；
- checkpoint 里的 `state` 只放结构化状态、工具结果摘要、预算和下一个 node；
- 不在 checkpoint 中放无界的工具原始输出；
- 写入前先 checkpoint，再执行副作用；
- 用 `state_version` 做乐观锁，防止并发 resume。

### 4.3 HITL 中断表

```sql
CREATE TABLE agent_run_interrupts (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  run_id UUID NOT NULL,
  node VARCHAR(32) NOT NULL,
  tool_name VARCHAR(80) NOT NULL,
  arguments JSONB NOT NULL,
  reason VARCHAR(240) NOT NULL,
  status VARCHAR(16) NOT NULL, -- PENDING / APPROVED / REJECTED / EXPIRED
  decision JSONB,
  idempotency_key VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  UNIQUE (run_id, idempotency_key)
);
```

## 5. HITL 协议

### 5.1 SSE 事件

新增事件类型：

```text
phase            { phase: "PLAN" | "GATHER" | ... }
plan             { steps: [...] }
tool_batch       { tools: [...] }
observation      { summary, count, failed }
interrupt        { interruptId, toolName, reason, argumentsDigest }
resumed          { decision, interruptId }
answer_delta     { text }
evaluation       { status, reasons }
```

### 5.2 Resume API

```http
POST /api/agent/runs/{runId}/resume
Idempotency-Key: {key}

{
  "interruptId": "...",
  "decision": "APPROVE | EDIT | REJECT",
  "editedArguments": {...}
}
```

校验：

- run 必须属于当前用户；
- interrupt 必须属于 run 且状态 PENDING；
- checkpoint 必须存在且 state_version 匹配；
- 同一 idempotency key 只能产生一次副作用；
- APPROVE 原参数执行；EDIT 校验后替换参数；REJECT 生成 rejection observation，回到 `ANSWER` 或 `REFLECT`。

### 5.3 当前实现的迁移

现有 `AgentToolApprovals` / pending confirmation 已经解决“写操作必须按钮确认”，不要推倒重来：

- 第一阶段把现有确认事件映射到 `interrupt`；
- 第二阶段把它抽象为 `PROPOSE/AWAIT_HUMAN` 节点；
- 删除操作、学习计划确认等继续复用现有业务确认卡，不新增口令确认。

## 6. “一步步思考”的正确做法

不要把模型私有 chain-of-thought 原样暴露给用户。应该展示：

- **计划**：准备查什么、预计几步；
- **动作**：本轮调用了哪些工具；
- **观察摘要**：拿到了什么、缺什么、是否失败；
- **决策摘要**：为什么进入下一步；
- **最终回答**：基于证据，不重复过程。

每条进度限制在 1–2 句，例如：

```text
先盘点简历、面试和日历三类数据，再判断准备断层。
已读取 3 个模块；简历显示 Agent 深度不足，继续补面经和学习记录。
证据已齐，发现 3 个冲突，开始生成 7 天计划。
```

这样既有“像 Harness 的过程感”，又不会泄漏隐私或把 token 浪费在冗长思维链上。

## 7. 工具与预算

- `GATHER` 每轮最多 3 个只读工具；
- 每轮 `REFLECT` 后决定继续/结束；
- 总预算：最多 8 轮、16 个工具、90 秒墙钟、单 run 最大 token；
- 达到预算时强制进入 `ANSWER`，说明未完成部分；
- 写入工具不计入普通工具预算，但必须独立 HITL；
- 失败读操作允许在 `REFLECT` 中重试一次，失败写操作绝不自动重试。

## 8. 与现有 trace 的关系

现有 `agent_trace_spans` 是观察层，不应和图运行时混为一谈：

- 图节点执行产生 `PLAN / REFLECT / GATHER / AWAIT_HUMAN` span；
- LLM、TOOL 仍是子 span；
- `parent_span_id` 反映真实层级；
- 前端 trace 页按阶段分组，支持点开节点查看摘要；
- 不把 prompt、工具参数正文、模型私有 CoT 写入 trace。

## 9. 分阶段落地

### P0：领域模型 + 设计验证

- 写本设计文档、SQL migration 草案；
- 用 mock 工具写 3 个状态机测试；
- 暂不改线上 Agent 行为。

### P1：只读复杂任务

- `PLAN -> GATHER(batch<=3) -> OBSERVE -> REFLECT -> ANSWER`；
- SSE phase/plan/observation 事件；
- 前端 live trace 显示阶段与进度；
- trace 增加 phase span；
- 用真实复杂任务对比延迟、token、工具覆盖率。

### P2：HITL 持久化

- 写入提案进入 `AWAIT_HUMAN`；
- 新增 resume API + 幂等键；
- 支持 approve/edit/reject；
- checkpoint 恢复测试：进程中断后 resume 不重复写；
- 现有确认 UI 迁移到 interrupt。

### P3：评估与观测

- 10–20 个 golden tasks（复杂审计、写操作、失败恢复、超预算）；
- 指标：成功率、工具冗余率、恢复成功率、重复副作用数、首进度延迟、总延迟；
- trace 抽样检查权限和隐私边界；
- 可在 CI 中用 mock tools 跑，不调用真实模型。

## 10. 为什么这比“接一个框架”更适合招牌项目

面试官通常会追问：

- 为什么需要多阶段？哪些步骤必须由代码控制？
- 中断后如何恢复？如何保证不重复写入？
- 如何避免把 chain-of-thought 泄漏给用户？
- 如何评估一个 Agent 是否真的变好？
- 失败、超时、预算耗尽时系统如何降级？

这套设计能直接回答这些问题：

- **边界**：代码控制阶段和预算，模型只做阶段内决策；
- **可靠性**：checkpoint + 乐观锁 + 幂等键；
- **安全**：HITL 在图节点上，写入不可绕过；
- **可解释**：计划/动作/观察摘要，不是黑箱；
- **可评估**：mock tools + golden tasks + 可量化指标；
- **工程取舍**：不引入 Temporal/Redis Stream，单体 Spring + PostgreSQL 足够，避免过度设计。

## 11. 参考

- Anthropic, [Building effective agents](https://www.anthropic.com/research/building-effective-agents)
- LangChain, [Interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts)
- LangChain, [Checkpointers](https://docs.langchain.com/oss/python/langgraph/checkpointers)
- OpenAI Agents SDK, [Human-in-the-loop](https://openai.github.io/openai-agents-python/human_in_the_loop/)
- Spring AI Alibaba, [人工介入（Human-in-the-Loop）](https://java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop/)
- LangGraph4j, [Graph engineering for Java](https://github.com/langgraph4j/langgraph4j)

## Implementation status

- P0 graph state machine: implemented (`AgentGraphRuntime`, tests).
- P1 explicit read-only complex-task route: implemented behind
  `app.agent.graph.enabled`, with `phase` SSE events and `THOUGHT` trace spans.
- P2 durable checkpoint + write HITL: not implemented yet.

## P2 status

Live PLAN approval is implemented through the existing operation approval UI.
Durable checkpoint/interrupt persistence and resume across process restarts are
still a separate P2 increment; the live gate is intentionally not presented as
durable.
