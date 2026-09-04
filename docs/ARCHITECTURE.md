# 架构说明

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
- `ResumeService`、`InterviewService`、`PlannerService` 承担校验、授权、事务与业务状态转换。
- Agent Tool 只是薄适配器，不能复制或绕过 Service 逻辑。
- 对话历史保存在 `conversations/messages`；用户可以拥有多个未归档会话，请求明确携带当前 `conversationId`，每次只把该会话的最近窗口装入 Chat Memory。
- `user_agent_memories` 保存跨会话总体记忆。总体记忆以可见文本注入系统上下文，用户可编辑或清空；Agent 只能通过受控 Tool 覆盖更新，且提示词禁止保存凭据、一次性任务和未经确认的推测。
- 当前会话上下文、用户总体记忆和简历/面试业务数据是三个独立边界，不能互相替代。

### 数据归属与授权

认证成功后，后端从 Security Context 解析当前用户；请求体、查询参数和 Header 中的 `user_id` 均不可信。Service 把当前 `userId` 传给 Repository，Repository 使用 `id + user_id` 查询。找不到属于当前用户的数据时返回不存在，避免泄露其他用户资源是否存在。

数据库在业务表保存 `user_id`，并对跨业务实体关系使用 `(id, user_id)` 复合外键，防止同一事务内错误地建立跨用户引用。这是 Repository 隔离之外的第二道防线。

### 文件保存

数据库只保存简历元数据、校验和、存储键、解析文本和分析结果。开发环境可使用本地对象存储适配器；公开上线前应使用 S3 兼容对象存储、私有 Bucket、短时签名下载和恶意文件检查。文件内容不能直接使用用户提供的文件名作为磁盘路径。

## 建议后端包结构

```text
com.projectardor
├─ auth
├─ profile
├─ resume
├─ agent
├─ interview
├─ planner
├─ config
└─ system
```

每个业务模块按实际需要放置 controller、service、repository、domain 和 dto；不要提前为尚未存在的用例建立空层级。

## API 约定

- 基础前缀：`/api`
- 对外使用资源化 URL；状态转换可使用明确的动作端点。
- 错误响应使用统一结构并带可追踪 request ID。
- 所有输入使用 DTO 和 Bean Validation，不直接暴露 JPA Entity。
- 时间在数据库保存 `TIMESTAMPTZ`，API 使用 ISO 8601，用户时区单独保存。

## 部署演进

本地可使用 Docker Compose 同时运行 PostgreSQL、Redis、Java 21 后端和 Next.js 前端，也可在宿主机分别开发。公开上线时保持单一后端应用、单一数据库与 Redis 会话存储。只有出现经过测量的容量或团队边界问题时才讨论拆分服务。
