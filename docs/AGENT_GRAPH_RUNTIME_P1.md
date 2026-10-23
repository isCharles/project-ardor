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

GATHER 使用独立的 `AiServices`，工具 map 由 `ApprovedAgentTools.wrap(...,
READ_ONLY_TOOL_NAMES)` 物理过滤；写工具不会出现在模型可见的工具面中。

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

V62 migration 扩展了 `agent_trace_spans.kind` 约束，允许 `THOUGHT`。该 span 只保存阶段摘要元数据，不保存 prompt、工具参数、工具结果或私有 chain-of-thought。

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
