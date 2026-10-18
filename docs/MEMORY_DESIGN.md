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

这是用户确定的自动记忆能力，不对每一条新增/修正弹权限框。用户仍可查看、编辑、删除记忆；前台工具权限等级不是自动记忆开关。

每次对话交换提交后发布 `MemoryConsolidationRequested`，`MemoryConsolidationService` 在独立线程池中：

1. 取最近 4 条用户消息；没有记忆信号词直接跳过；
2. 每用户 5 分钟最多运行一次，避免每轮都调模型；
3. 把最近用户发言与当前有效记忆（≤50 条）交给快速模型，要求输出 `ADD` / `UPDATE` 候选；
4. 校验：`content` 非空且 ≤500 字，`evidence` 必须来自用户消息，UPDATE 目标必须属于该用户且有效。片段存在性不等于语义真实性，也不能完全识别用户粘贴的外部内容，不应宣称已彻底防止记忆污染；
5. 应用：ADD 以 `INFERRED` 写入；UPDATE 通过 `replace()` 将旧事实写入失效区间并插入新事实，保留历史。

不直接从工具返回的网页/知识库正文抽取个人事实；模型仍可能误解用户消息中的引用，用户可在记忆面板纠正。

候选置信度会随 INFERRED 记录持久化。V47 将旧版误存为 1.0 的推断记忆调整为保守默认 0.7，不能声称恢复了已丢失的原估计。原位编辑会清空旧向量；异步向量写回检查原内容，避免覆盖编辑后的状态。未配置个人向量模型时，补齐任务遵循管理员默认配置。

## 读取路径（P3）

`MemoryEmbeddingService.context(userId, userMessage)` 在每轮构建系统提示时选择要注入的记忆：

- 用户配置了向量模型：用 query embedding 走 pgvector 余弦检索，再按 `0.6 × 相似度 + 0.25 × 重要性 + 0.15 × 时效` 排序，取 2400 字符预算内的条目；
- 未配置向量模型或检索失败：退回全部有效记忆，按下述规则排序并受预算限制；
- 记忆写入后由 `MemoryEmbeddingScheduler` 每 120 秒补齐缺失向量（每个用户每轮最多 20 条），写入路径不等待 embedding 调用；
- 相似度查询按 `(user_id, embedding_dim, embedding_model)` 过滤，换 embedding 模型后旧向量不会被误比较。

## 维护任务（P4）

`MemoryMaintenanceService` 每 24 小时运行一次（启动 5 分钟后首跑）：

- **低置信推断过期**：`INFERRED` 且 `confidence < 0.5`、有效超过 60 天的记忆写入失效区间；用户明确提供的记忆永不自动过期；
- **重复合并**：内容规范化后，较短且被较长事实完整包含的条目失效，`superseded_by` 指向保留的较长条目；不做删除。

`MemoryEmbeddingScheduler` 的补齐范围包含「模型已更换」的旧向量（`embedding_model` 与当前配置不同），切换 embedding 模型后会自动重算。

记忆面板可以勾选「显示已过时的记忆」，查看失效条目的失效日期，并一键「恢复为当前记忆」（新建一条相同内容的有效记忆）。
