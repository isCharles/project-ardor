# Ardor 长期记忆设计

状态：P1 已落地（2026-10）。本文替代「记忆内容必须逐字引用用户原话」的旧规则。

## 为什么重做

旧实现把记忆当成「用户原话的复读」：`add_memory` / `update_memory` 要求内容逐字出现在用户本轮消息里。它想防的是「把网页/知识库内容写成用户偏好」，但用错了手段：

- 正常记忆无法存在——用户说「三年制」，不能归纳成「研二、三年制、2028 届」；
- 用户的"纠正"无法落地——研一改成研二只能并存两条互相矛盾的记忆；
- 真正的注入仍防不住——外部内容可以逐字复述用户的话。

主流实现（[Mem0](https://arxiv.org/abs/2504.19413)、[Zep](https://arxiv.org/abs/2501.13956)、[LangMem](https://github.com/langchain-ai/langmem)、ChatGPT 记忆）都把记忆当作**带来源与时效的事实表**：抽取、归并、冲突失效，而不是逐字校验。

## 模型

每条记忆是一行 `user_agent_memory_items`：

| 字段 | 含义 |
|---|---|
| `content` | 规范化事实，可以归纳，不必逐字 |
| `kind` | `profile` / `preference` / `goal` / `constraint` / `state` / `fact` |
| `confidence` | 0–1；用户明确要求保存为 1.0，推断为 0.6–0.9 |
| `importance` | 0–5，影响注入顺序与保留 |
| `source_kind` | `USER_EXPLICIT` / `USER_STATED` / `INFERRED` |
| `source_run_id` | 哪次 Agent 运行写入，便于追溯 |
| `valid_from` / `valid_until` | 事实有效区间；冲突时旧条失效而非删除 |
| `superseded_by` | 被哪条新记忆取代 |

读取只取 `valid_until IS NULL` 的条目；历史保留，供审计与用户回看。

## 写入路径（P1）

- 用户要求记住 → Agent 调 `add_memory`：内容可归纳，来源记为 `USER_EXPLICIT`。
- 用户纠正事实 → 先 `list_memories` 找到原条目，用 `update_memory` 原位修正；旧值不再需要逐字引用。
- 用户直接编辑 → 记忆面板 PATCH 单条，或新增条目。
- 明确删除 → 现有提案确认流程。

外部网页/知识库内容不得直接写成个人事实：系统提示要求只把用户本轮提供的信息写入记忆；不满足时先向用户澄清。记忆写入仍受 Agent 权限等级与审批弹窗约束。

## 读取路径（P1）

`AgentMemoryService.content()` 按有效条目生成注入文本。P2 起按向量相似度 + 重要性 + 时效排序并受 token 预算限制。

## 后续（P2/P3）

- 后台记忆管理器：对话结束后抽取候选，向量相似 top-k 后由模型选择 ADD / UPDATE / NOOP；冲突写入失效区间。
- pgvector 检索打分、低置信推断过期、合并去重任务。
- 记忆面板展示来源与「已过时」状态，支持一键纠正。
