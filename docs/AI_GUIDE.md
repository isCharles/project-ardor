# AI 开发指南

本文件约束在 Project Ardor 中工作的 AI 编程助手和工程师。目标是让仓库长期可运行、可测试、可理解，而不是快速堆积不可维护的代码。

## 多用户数据隔离

1. 永远不要信任前端提供的 `user_id`。
2. 当前用户只能从 Spring Security 认证上下文获得。
3. 所有业务读取、更新和删除都必须按资源 ID 与当前用户 ID 联合限定，例如 `findByIdAndUserId`。
4. 新增业务表时必须明确用户归属、授权路径和用户前缀索引。
5. 找不到当前用户的资源时，不泄露其他用户是否拥有同 ID 资源。
6. 未来向量检索必须先按 `user_id` 过滤，再计算相似度。
7. 数据隔离必须有自动化测试，至少覆盖两个用户的交叉访问。

## Flyway 规则

1. 禁止手工修改共享或生产数据库 schema。
2. 所有 schema 变更创建新的 Flyway migration。
3. 已执行的 migration 不得编辑、重命名或删除。
4. JPA 使用 `ddl-auto=validate`，不能依赖 Hibernate 自动更新 schema。
5. migration 必须在干净数据库和已有数据库升级路径上验证。

## Agent 与业务解耦

1. Agent 负责意图、澄清、工具选择与自然语言回复，不承载完整业务逻辑。
2. `ResumeService`、`InterviewService`、`PlannerService` 负责校验、授权、事务和状态转换。
3. Manual UI 与 Agent Tool 必须复用相同 Service，不能各自实现一套规则。
4. Tool 适配器保持轻薄；不得直接越过 Service 操作 Repository。
5. LLM 输出属于不可信输入，进入业务层前必须做 schema 校验和权限检查。
6. 每个 Tool 要有清晰的输入、输出、幂等策略和审计信息。
7. `CareerAgentService` 是运行时边界；不得让 LangChain4j 类型扩散到业务 Service、Repository 或 Web DTO。

## 不过度设计

1. 只抽象已经稳定的边界，不为假设中的未来功能创建大量空接口。
2. 当前不引入微服务、Redis、Kafka、Elasticsearch、向量数据库、LangGraph 或 Python AI Service。
3. 优先完成 V0.1 Golden Path，再根据测试和真实使用数据演进。
4. 修改核心架构、认证方式、数据归属或模块边界前，先在文档或变更说明中解释原因、替代方案和取舍。

## 开发与验证纪律

1. 每次变更尽量保持项目可启动、可构建、可测试。
2. 后端至少运行 `mvnw test`；前端至少运行 lint、typecheck 和 build。
3. 数据库变更必须实际运行 Flyway，不只检查 SQL 文本。
4. 修复失败应基于精确错误和日志；只有重新验证通过后才宣称完成。
5. 新增 API 同时补充输入校验、认证授权、错误响应和测试。
6. 不提交密钥、真实简历、个人数据或包含敏感内容的日志。
7. 中文文档与字符串统一使用 UTF-8；Windows PowerShell 读写时显式指定 UTF-8。

## 完成定义

- 需求范围内的行为已实现，不包含未授权扩展。
- 多用户隔离已审查并测试。
- migration、后端测试、前端检查通过。
- 相关文档与环境变量示例已同步。
- 已说明仍未实现的部分和已知风险。
