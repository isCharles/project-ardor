# 架构说明

本文说明现有实现与工程边界；整体演进方向见 [架构演进规划](ARCHITECTURE_EVOLUTION.md)，术语、兼容入口、状态所有权和阶段边界见 [领域合同](DOMAIN_CONTRACTS.md)。不能把规划中的未来模型当成已实现的事实。

## 关键技术决策

### 单体单仓库

前端和后端放在一个仓库，后端采用模块化单体。V0.1 的规模不需要微服务；单体更容易保持事务一致性、调试 Golden Path 和持续交付。

### 认证方式

V0.1 采用邮箱密码登录、Spring Security 和服务端 HttpOnly Cookie 会话。会话由 Spring Session 写入 Redis，浏览器持久 Cookie 与服务端会话均保留 30 天，后端或主机重启不会要求重新登录。密码只保存经过自适应哈希算法处理的摘要，绝不保存明文。该方案比自建 JWT 刷新体系更小、更容易安全撤销，并适合前后端同站部署。

Phase 2 已实现 BCrypt（cost 12）、Cookie CSRF Token、会话固定攻击防护、Redis 持久会话、统一 JSON 认证错误和生产环境可配置的 Secure Cookie。登录限流、生产 HTTPS、密钥托管与进一步防用户枚举属于上线准备项。将来如需第三方登录，可在不改变业务数据归属模型的前提下增加 OIDC。

### Java 21 与版本线

后端固定 Java 21，使用 Spring Boot 4.1.1 与 LangChain4j 1.19.0。Agent 采用 LangChain4j AI Services、Chat Memory 和 Tool Calling；运行时先解析用户个人配置，没有个人配置时回退到管理员设置的系统默认。简历分析、面经与面试内部的结构化 LLM 调用仍通过稳定的 `LlmGateway` 边界执行。个人和系统 API Key 均以 AES-256-GCM 加密，接口只返回尾号提示。

### 管理边界

`/api/admin/**` 在服务端要求 `ROLE_ADMIN`。管理员可维护系统 API 默认值、用户角色和账户状态，并查看不含正文的聚合数量；工作台不提供读取用户消息、简历正文、面经正文或知识库正文的接口。初始管理员由 `ARDOR_ADMIN_EMAILS` 提升，避免在代码或 migration 中写死账户。

### Agent 与业务分层

```text
Web / Manual UI ─┐
                 ├─> Application Services ─> Repository ─> PostgreSQL
Agent Tools ─────┘           ↑
                             │
CareerAgentService ─> LangChain4j AI Services + Chat Memory + Tools
```

- `CareerAgentService` 是当前 Agent 运行时边界；LangChain4j 类型不扩散到业务 Service。
- Agent 负责理解意图、发现信息缺口、选择 Tool 和组织回复。
- `ResumeService`、`OpportunityService`（真实面试）、`InterviewService`（模拟面试）、`LearningPlanService` 与日程等应用 Service 承担校验、授权、事务与业务状态转换。
- 真实面试通过 `InterviewProcessCommands` 与不可变 `InterviewProcessView` 提供应用合同，HTTP 和 Agent 各做边界适配；旧 `Opportunity` API 和实体身份保留。其他领域仍需逐步清理，不能据此声称全站已脱离 Web DTO。
- Agent Tool 只是薄适配器，不能复制或绕过 Service 逻辑。
- 对话历史保存在 `conversations/messages`；用户可以拥有多个未归档会话，请求明确携带当前 `conversationId`，每次只把该会话的最近窗口装入 Chat Memory。
- 当前长期记忆使用 `user_agent_memory_items` 条目，`AgentMemoryService` 汇总仍有效的内容；旧 `user_agent_memories` 是历史兼容结构，不是新的单一真相源。用户可查看、修改和删除，自动维护仍须满足来源、敏感信息和业务授权约束。
- 当前会话上下文、用户总体记忆和简历/面试业务数据是三个独立边界，不能互相替代。

### 后台任务与通知

面试转录先保存原文并生成事实初稿，用户在编辑区校正、确认纪要版本后，才由后台生成详细复盘。原始转录、可编辑纪要、不可变确认快照和报告独立保留；Agent 不提供代确认工具。详见 [确认纪要工作流](INTERVIEW_MINUTES_WORKFLOW.md)。

简历、面经及通用 AI 评估保留各自持久化队列，向量化以知识片段为待处理来源。统一任务入口通过只读视图投影它们的状态；已读结果单独按用户、任务及完成版本保存，不依赖浏览器本地缓存判断完成。通知不能执行业务操作，重试由原业务 Service 校验。详见 [后台任务与结果提醒](BACKGROUND_TASKS.md)。

### 工作台交互

窄图标侧栏按账户保存并在浏览器与桌面间同步；版本检查防止旧设置静默覆盖，原本地配置可迁移和找回。桌面对话栏与聊天并排，设置按类别和 API 服务分区展示。界面偏好不承担业务授权，通知入口不代替具体结果的已读确认。详见 [工作台交互与偏好](WORKSPACE_UI.md)。

### 数据归属与授权

认证成功后，后端从 Security Context 解析当前用户；请求体、查询参数和 Header 中的 `user_id` 均不可信。Service 把当前 `userId` 传给 Repository，Repository 使用 `id + user_id` 查询。找不到属于当前用户的数据时返回不存在，避免泄露其他用户资源是否存在。

数据库在业务表保存 `user_id`，并对跨业务实体关系使用 `(id, user_id)` 复合外键，防止同一事务内错误地建立跨用户引用。这是 Repository 隔离之外的第二道防线。

### 文件保存

数据库只保存简历元数据、校验和、存储键、解析文本和分析结果。开发环境可使用本地对象存储适配器；公开上线前应使用 S3 兼容对象存储、私有 Bucket、短时签名下载和恶意文件检查。文件内容不能直接使用用户提供的文件名作为磁盘路径。

## 当前模块与目标归属

现有生产包包含 `opportunity`、`applications`、`recap`、`interview`、`learning`、`resume`、`knowledge`、`calendar`、`agent`、`aijobs`、`background`、`llm`、`speech`、`usage`、`profile`、`auth`、`admin`、`integrations`、`websearch` 与基础配置。`interview` 目前仍实现模拟面试，真实面试由 `opportunity` 实现；`recap` 仍保留记录与重答，卡片与复习历史已由 `learning.review` 管理，见 [学习复习合同](FLASHCARD_REVIEW.md)。

目标归属为求职、学习、资料、助手、日程和平台的逻辑边界，并非一次性移动全部目录。普通业务服务不得继续扩大跨域 Repository 访问；现存耦合按阶段替换。导航收拢不代表这些内部边界已经完成，具体迁移合同见上方文档。

模型连接使用按协议注册的 JSON 与 Agent 适配器；业务只依赖应用级网关，不承担供应商请求结构。启动校验注册完整性；配置与外部地址校验、错误分类及取消租约保持独立。具体路径、扩展方式、验证与限制见 [模型协议适配边界](MODEL_PROTOCOLS.md)。

## API 约定

- 基础前缀：`/api`
- 对外使用资源化 URL；状态转换可使用明确的动作端点。
- 错误响应使用统一结构并带可追踪 request ID。
- 所有输入使用 DTO 和 Bean Validation，不直接暴露 JPA Entity。
- 时间在数据库保存 `TIMESTAMPTZ`，API 使用 ISO 8601，用户时区单独保存。

## 部署演进

本地可使用 Docker Compose 同时运行 PostgreSQL、Redis、Java 21 后端和 Next.js 前端，也可在宿主机分别开发。公开上线时保持单一后端应用、单一数据库与 Redis 会话存储。只有出现经过测量的容量或团队边界问题时才讨论拆分服务。
