# 数据库设计

## 设计原则

- PostgreSQL 是唯一事实来源。
- 主键使用应用生成的 UUID，避免暴露顺序 ID。
- 所有核心业务表直接保存 `user_id`，方便授权查询、索引和未来向量过滤。
- `created_at`、`updated_at` 使用带时区时间；应用统一按 UTC 写入。
- `user_profiles.timezone` 保存 IANA 时区标识，用于把 UTC 时间转换成用户本地日期。
- 灵活但需要持久化的 LLM 结构化输出使用 JSONB；稳定、常查的业务状态使用普通列。
- 所有变更通过 Flyway migration 管理。

## 初版实体

| 表 | 用户归属 | 作用 | 关键关系或约束 |
| --- | --- | --- | --- |
| `users` | 自身 | 登录主体、账户状态与 USER / ADMIN 角色 | email 唯一；只存密码摘要 |
| `user_profiles` | `user_id` | 展示信息、目标岗位、偏好 | 每用户至多一条 |
| `llm_provider_configs` | `user_id` | 用户自备模型服务和加密 API Key | 每用户至多一条；只存密文、IV 和尾号提示 |
| `system_api_configs` | 系统 | 管理员配置的全局 API 默认值 | 每种服务至多一条；密钥只存密文、IV 和尾号提示 |
| `resumes` | `user_id` | 文件元数据、解析文本与解析状态 | 按用户和创建时间索引 |
| `resume_analyses` | `user_id` | 可复用的结构化分析 | 关联同一用户的 resume |
| `conversations` | `user_id` | 可持续的 Agent 会话和上下文 | active / archived |
| `messages` | `user_id` | 用户、助手、系统和 Tool 消息 | `context_references` 保存随消息发送的简历、面经、知识文档或学习计划引用 |
| `user_agent_memories` | `user_id` | 跨会话共享、用户可管理的总体记忆 | 每个用户最多一份，最多 4000 字符 |
| `interview_sessions` | `user_id` | 面试目标、模态和生命周期 | 模态可扩展，V0.1 只使用 TEXT |
| `interview_questions` | `user_id` | 有序问题和评价标准 | session 内序号唯一 |
| `interview_answers` | `user_id` | 用户答案 | 每题最多一个答案 |
| `interview_evaluations` | `user_id` | 总分和结构化反馈 | 每场面试最多一个评价 |
| `interview_recaps` / `interview_recap_questions` | `user_id` | 真实面试材料及逐题复盘 | 问题按用户与面经关联；删除面经时清理问题 |
| `memory_cards` | `user_id` | 可复习的面试题和知识卡 | 可关联一条同用户面经问题 |
| `interview_replay_attempts` | `user_id` | 真实问题的历次重新作答及 AI 比较 | 关联同用户面经问题；`(user_id, request_id)` 防重复提交；复测时记录实际回答的变式题目 |
| `tasks` | `user_id` | 后续行动及状态 | 可追溯来源面试；重复安排的每一次也是这里的一行；复测任务通过复合外键关联同用户的面经问题和回放尝试，同一问题最多一项待完成复测 |
| `task_series` | `user_id` | 重复日程的规则（按天、按周、按月） | 每条规则提前展开约 120 天的 `tasks`；`(series_id, occurrence_date)` 唯一 |
| `learning_plans` | `user_id` | 学习主题、讲解、练习、评分与巩固状态 | 每个计划最多关联一条 `LEARNING` 日历任务；未掌握时更新原任务日期 |
| `events` | `user_id` | 有明确时间段的计划 | 结束时间必须晚于开始时间 |

## JSONB 结构契约

`resume_analyses.analysis` 至少应包含：

```json
{
  "education": [],
  "experience": [],
  "projects": [],
  "skills": [],
  "strengths": [],
  "weaknesses": [],
  "possibleTargetRoles": []
}
```

`interview_evaluations.evaluation` 至少应包含：

```json
{
  "strengths": [],
  "weaknesses": [],
  "knowledgeGaps": [],
  "communicationIssues": [],
  "suggestedNextSteps": []
}
```

JSONB 仍需在应用层映射为有类型的 DTO，并记录 `prompt_version` 与 `model_name`，以便回放和升级。

## 隔离示例

禁止：

```java
repository.findById(resumeId);
```

要求：

```java
repository.findByIdAndUserId(resumeId, currentUserId);
```

跨实体写入时还必须让数据库验证两个实体属于相同用户。V1 migration 已使用复合唯一键和复合外键落实此约束。

## Flyway 规则

1. migration 位于 `backend/src/main/resources/db/migration`。
2. 已在共享环境运行的 migration 永不修改、重命名或删除。
3. schema 变化通过新的 `V<n>__description.sql` 前向演进。
4. 本地测试和生产部署使用同一组 migration。
5. 不使用 Hibernate 自动建表；`ddl-auto=validate` 只做映射校验。

## Phase 2 密钥存储

- API Key 使用 AES-256-GCM，每次更新生成独立 12 字节 IV。
- 用户 UUID 作为 GCM 附加认证数据，密文不能直接换给另一用户解密。
- 读取配置只返回 `configured`、供应商、Base URL、模型和 `keyHint`，不返回密文或明文。
- 服务端主密钥来自 `ARDOR_ENCRYPTION_KEY`，不进入数据库；更换主密钥前必须设计轮换流程。

## 后续评审项

- 邮箱已统一执行 `strip + lowercase`，数据库通过 CHECK 约束保持规范化。
- 决定 UUID v4 或 v7，并在应用层统一生成。
- 根据实际查询计划补充或调整索引。
- pgvector 已启用（V16）。`knowledge_chunks.embedding` 是不带维度的 `vector` 列，另存 `embedding_dim` 与 `embedding_model`；
  每个用户自带 Embedding 服务，维度各不相同，所以相似度查询必须同时限定 `user_id` 和 `embedding_dim`，只比较同一向量空间的行。
  不带维度的列无法建 HNSW/IVFFlat 索引；先按 `user_id` 过滤后做精确 KNN，在当前每用户切片量级下足够。
  若某个部署统一了 Embedding 模型，可固定维度后再加 `USING hnsw (... vector_cosine_ops)`。
