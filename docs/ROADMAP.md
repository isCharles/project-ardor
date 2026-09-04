# V0.1 路线图

## 实现顺序

### Phase 1：工程骨架（已完成）

- 单仓库目录、编码与忽略规则。
- PostgreSQL Docker Compose。
- Spring Boot、Spring Security、LangChain4j 依赖和 Flyway。
- 初始数据库 schema 与用户隔离约束。
- Next.js App Router、TypeScript、Tailwind、shadcn/ui 基线。
- 产品、架构、数据库与 AI 协作文档。
- 后端测试、前端 lint/typecheck/build 与前端生产启动验证。
- PostgreSQL 17 容器健康检查、Flyway V1 空库迁移、Java 21 后端启动与健康接口验证。

### Phase 2：真实认证、用户资料与 LLM 配置（已完成）

- 注册、登录、登出、当前用户接口。
- BCrypt 密码摘要、Cookie CSRF Token 与服务端 HttpOnly 会话。
- Redis 持久化登录会话与 30 天持久 Cookie，服务重启后保持登录。
- User/Profile 实体、Repository、Service 和按当前用户限定的访问路径。
- 前端登录注册页与受保护布局。
- 用户自行填写 OpenAI / OpenAI 兼容接口、模型和 API Key。
- API Key 使用 AES-256-GCM 加密，读取接口只返回配置状态和尾号提示。
- 前后端与 PostgreSQL 的 Docker Compose 一键启动。

完成情况：真实 HTTP 链路和数据库密文已验收；接口不接受前端 `user_id`，用户配置只按 Security Context 中的当前用户读取和修改。

### Phase 3：简历分析与文本模拟面试（已完成）

- PDF/DOCX 上传校验与安全存储适配器。
- 文本提取、解析状态和失败恢复。
- `ResumeService` 和结构化 LLM 分析。
- 上传、查看、触发分析的 UI 与 API。
- LLM 配置保存前连接测试、明确错误分类与代理支持。
- PostgreSQL 持久化异步分析队列、后台 Worker、状态轮询与失败重试。
- `createInterview`、`getNextQuestion`、`submitAnswer`、`finishInterview`、`getEvaluation`。
- 基于 Profile、可选简历分析、目标公司和岗位生成 3–10 道题。
- 顺序作答状态机、可恢复会话、结构化评价与完整手动 UI。
- 面向 Agent 的轻量 Tool 适配器，与手动 API 复用同一业务 Service。

完成情况：控制台允许用户独立进入两个模块；简历只解析一次，分析任务异步执行、持久化并可复用；一场文本面试可不绑定简历直接创建，也可恢复、完成并产生持久化评价。手动 API 与 Agent Tool 复用相同 Service。

### Phase 4：Agent 对话与澄清（已完成）

- Conversation/Message 持久化与多会话管理。
- 新建、切换、重命名、归档和永久删除会话。
- 用户级总体记忆的查看、手动维护、清空、Agent 受控更新与跨会话注入。
- LangChain4j AI Services、系统提示词、Chat Memory 和 Tool 适配器。
- 从 Profile 和 Resume Analysis 组装 Agent Context。
- 对目标公司、岗位、方向和 JD 等必需信息进行主动澄清。
- 登录后以极简对话框作为默认主页，以会话侧栏承载历史；手动模块与设置保留轻量快捷入口。

完成情况：Agent 能自动列出简历、提交和查询异步分析、读取分析、列出和创建面试、推进答题并读取评价；信息不足时按提示词先澄清。多段会话分别维护上下文，总体记忆跨会话共享且完全受用户控制。用户身份由服务端绑定，Tool 与手动 API 复用同一 Service。

### Phase 5：Agent 体验深化

- 对话响应流式输出和可取消执行。
- 工具执行过程的可视状态与用户确认机制。
- 增加同一会话并发提交、模型不支持 Tool Calling 与 LLM 超时恢复的专项集成测试。

完成标准：长耗时 Agent 调用可观察、可取消；敏感或高影响工具具备明确确认边界。

### Phase 6：Planner 与 Golden Path

- Task/Event CRUD 与列表 UI。
- `PlannerService` Agent Tools。
- 面试完成后根据评价幂等创建 3 个待办。
- Golden Path 端到端与多用户隔离测试。

完成标准：登录到自动生成待办的链路稳定、可重复演示。

### Phase 7：上线准备

- 生产对象存储、HTTPS、Secure Cookie、密钥管理。
- 登录和 LLM 调用限流、审计、可观测性、备份恢复。
- 数据保留与账户删除流程。
- CI、容器镜像、部署和回滚演练。

## 暂不排期

语音面试已先落地轮次式 MVP；逐帧流式 ASR、实时打断和 WebRTC Speech-to-Speech
在基础链路稳定后再推进。Google Calendar、Multi-Agent 和 LangGraph 仍需在出现真实需求后评估。
Redis 已因持久登录需求提前引入，目前不承担语音数据传输或通用任务队列。
