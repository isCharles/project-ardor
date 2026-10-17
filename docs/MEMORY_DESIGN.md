# Ardor 长期记忆设计

状态：P2 已落地（2026-10）。本文替代「记忆内容必须逐字引用用户原话」的旧规则。

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
| `confidence` | 0–1；用户明确要求保存为 1.0，推断为 0.4–0.9 |
| `importance` | 0–5，影响注入顺序与保留 |
| `source_kind` | `USER_EXPLICIT` / `USER_STATED` / `INFERRED` |
| `source_run_id` | 哪次 Agent 运行写入，便于追溯 |
| `valid_from` / `valid_until` | 事实有效区间；冲突时旧条失效而非删除 |
| `superseded_by` | 被哪条新记忆取代 |

读取只取 `valid_until IS NULL` 的条目；历史保留，供审计与用户回看。

## 写入路径

### 热路径（用户显式要求）

用户要求记住 → Agent 调 `add_memory`：内容可归纳，来源记为 `USER_EXPLICIT`，`confidence=1.0`。

纠正事实 → 先 `list_memories` 找到原条目，`update_memory` 原位修正；或后台抽取时以 UPDATE 处理。

### 后台抽取（P2）

每次对话交换提交后发布 `MemoryConsolidationRequested`，`MemoryConsolidationService` 在独立线程池中：

1. 取最近 4 条用户消息；没有记忆信号词直接跳过；
2. 每用户 5 分钟最多运行一次，避免每轮都调模型；
3. 把最近用户发言与当前有效记忆（≤50 条）交给快速模型，要求输出 `ADD` / `UPDATE` 候选；
4. 校验：`content` 非空且 ≤500 字，`evidence` 必须是用户消息中的逐字片段（防外部内容注入），UPDATE 目标必须属于该用户且有效；
5. 应用：ADD 以 `INFERRED` 写入；UPDATE 通过 `replace()` 将旧事实写入失效区间并插入新事实，保留历史。

外部网页/知识库内容不会成为用户事实：候选必须带用户消息中的原话证据。

## 读取路径（P3）

`MemoryEmbeddingService.context(userId, userMessage)` 在每轮构建系统提示时选择要注入的记忆：

- 用户配置了向量模型：用 query embedding 走 pgvector 余弦检索，再按 `0.6 × 相似度 + 0.25 × 重要性 + 0.15 × 时效` 排序，取 2400 字符预算内的条目；
- 未配置向量模型或检索失败：退回全部有效记忆，按下述规则排序并受预算限制；
- 记忆写入后由 `MemoryEmbeddingScheduler` 每 120 秒补齐缺失向量（每个用户每轮最多 20 条），写入路径不等待 embedding 调用；
- 相似度查询按 `(user_id, embedding_dim, embedding_model)` 过滤，换 embedding 模型后旧向量不会被误比较。

## 后续（P4）

- 低置信推断自动过期、重复事实定期合并。
- 记忆面板展示来源与「已过时」状态，支持一键纠正。
- embedding 模型切换后的旧向量重算。
