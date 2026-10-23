# Agent Graph Runtime · P1

P0 的 [状态机](AGENT_GRAPH_RUNTIME_P0.md) 已接入真实流式聊天，但**只对显式只读复杂任务生效**。

## 触发条件

用户消息同时包含：

- “只读” / “read-only” / “readonly”
- 以及“盘点 / 审计 / 复盘 / 规划 / 分析 / audit”之一

例如：

```text
请做一次只读盘点审计：读取我的简历、知识库、学习计划、面试和日历，
找出三个准备断层，并给出建议。不要修改任何数据。
```

普通对话继续走原来的 `CareerAgentService` tool loop，不受影响。

开关：

```yaml
app:
  agent:
    graph:
      enabled: true
```

## 真实执行路径

```text
PLAN（无工具）
  -> GATHER（只读工具，最多 3 个/轮）
  -> OBSERVE
  -> REFLECT（SUFFICIENT / NEED_MORE）
  -> GATHER（可选第二轮）
  -> REFLECT
  -> ANSWER（无工具）
```

GATHER 使用独立的 `AiServices`，工具 map 由 `ApprovedAgentTools.wrap` 物理过滤；
只读工具名从根工具与已注册模块共同发现，知识库适配器也在其中。
写工具不会出现在模型可见的工具面中。每轮实际执行最多 3 次读取，结果记录真实工具名，
不依赖模型自报或仅在提示词中限制次数。各阶段使用同一历史快照，不共享可变的临时工具消息。

## Trace

每个阶段写入 `THOUGHT` span：

```text
THOUGHT phase.plan
LLM      llm.call.1
THOUGHT phase.gather
LLM      llm.call.2
TOOL     list_resumes
TOOL     list_learning_plans
THOUGHT phase.reflect
...
THOUGHT phase.answer
LLM      llm.call.8
```

V62 migration 扩展了 `agent_trace_spans.kind` 约束，允许 `THOUGHT`。
运行追踪可保存有长度上限且经过常见凭据脱敏的输入/输出预览，不保存完整 prompt 或私有 chain-of-thought；
预览仍属于用户私有内容，沿用运行记录的归属授权，不是公开日志。

同步图阶段也绑定运行取消租约：停止/超时会中断等待并阻止后续阶段和工具；
服务不能证明第三方已经撤销所有消耗。成功、失败及持久化异常都释放用户锁，失败已报告的 token
同样计入账本。模型不提供 token usage 时仍不能凭估算宣称精确计费。

## SSE / UI

新增 `phase` 事件：

```json
{ "type": "phase", "content": "PLAN", "label": "制定执行计划", "elapsedMs": 1200 }
```

聊天页把 phase 追加到 live execution trace，阶段标题随当前 phase 改变；开发者 trace 页支持 `THOUGHT` 节点和按 startedAt 排序的执行图。

## 测试

```bash
cd backend
mvn -o -q -Dtest=AgentGraphRuntimeTests test

cd ../frontend
npm run typecheck
ARDOR_UI_BROWSER_CHANNEL=msedge node tests/agent-trace-ui.mjs
```

## 暂不包含

- P2 的持久化 checkpoint 和写操作 HITL；
- 真实的数据库 checkpoint 表（当前 runtime 支持 `CheckpointStore`，单次流式 run 使用内存/no-op）；
- 多轮人工修改后的 resume；
- 对普通任务开启图模式。

P2 会把现有 `AgentToolApprovals` / confirmation UI 映射为 `AWAIT_HUMAN` 中断，并加入 `agent_run_interrupts` 和 resume API。

## Live human approval gate

The P1 graph also supports a live plan-approval interrupt when the user asks to
confirm the plan, for example:

```text
请做一次只读盘点审计，并在开始前让我确认计划……
```

After PLAN is generated, the graph calls the existing `AgentToolApprovals`
flow with `graph.plan`. The current `OperationPermissions` card shows the plan;
approving resumes execution, rejecting aborts the read-only graph. The approval
is live-process state, not yet a durable checkpoint/interrupt: a process restart
still loses the pending approval. Durable `agent_graph_interrupts` + resume API
remain the next P2 increment.
