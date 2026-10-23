# Agent Graph Runtime · P0

这是 [AGENT_GRAPH_ARCHITECTURE.md](AGENT_GRAPH_ARCHITECTURE.md) 的第一阶段实现。

## 已实现

`com.projectardor.assistant.execution.graph.AgentGraphRuntime`

状态机：

```text
PLAN -> GATHER -> OBSERVE -> REFLECT -> ANSWER -> COMPLETED
```

- `Budget`：限制检索轮次和工具调用数；
- `GatherResult`：必须声明本轮使用的工具，写工具会被运行时拒绝；
- `Checkpoint`：每个节点后保存，支持从 `GATHER` 等中间节点恢复；
- `Observer`：阶段、观察、完成事件，后续映射到 SSE / trace；
- `Model` 接口：由 P1 的 LangChain4j adapter 实现，P0 测试使用 fake model。

## 测试

`AgentGraphRuntimeTests` 覆盖：

- 两轮 GATHER 后证据充分；
- 达到最大轮次后仍返回答案；
- 写工具进入只读图被拒绝；
- 从 checkpoint 恢复时不再调用 planner；
- 总工具预算超限被拒绝。

运行：

```bash
cd backend
mvn -o -q -Dtest=AgentGraphRuntimeTests test
```

## P1 接入计划

1. `LangChainAgentGraphModel`：
   - PLAN：无工具 LLM，输出 2–4 步计划；
   - GATHER：最多 3 个只读工具，返回证据摘要；
   - REFLECT：判断 SUFFICIENT / NEED_MORE；
   - ANSWER：无工具 LLM，基于证据回答。
2. 只读工具白名单复用 `ToolIntentPolicy`，不另建权限系统。
3. 在流式聊天里只对显式只读复杂任务启用图模式，普通对话保持原链路。
4. SSE 增加 `phase / plan / observation` 事件；trace 增加 phase span。
5. 前端 live trace 显示阶段和进度，开发者 trace 按阶段分组。

## 暂不做

- 写操作、HITL 持久化：P2；
- 数据库 checkpoint：P1 先使用内存/单次 run 状态，验证行为后再加持久化；
- 新模型供应商、Temporal、Redis Stream。
