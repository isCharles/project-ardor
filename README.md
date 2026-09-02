# Project Ardor

Project Ardor 是一个面向多用户的 AI Career Agent。V0.1 围绕一条可演示的求职闭环：理解简历、澄清目标、进行文本模拟面试、生成结构化反馈，并把反馈转化为后续待办。

当前仓库已完成 **Phase 4：多会话 Career Agent 与总体记忆**。登录后直接进入以输入框为视觉中心的 LangChain4j Agent 对话页；左侧可新建、切换、重命名、归档或永久删除会话。每段会话拥有独立的最近上下文，同时用户级“总体记忆”会跨会话保存稳定的职业背景、长期目标和沟通偏好，并允许用户随时查看、编辑或清空。Agent 可读取简历、提交或查询异步分析、创建并推进模拟面试。简历分析和模拟面试仍保留独立手动页面，个人资料与 LLM 配置位于设置页。登录会话由 Redis 持久化并保留 30 天，对话与总体记忆保存在 PostgreSQL。API Key 使用服务端 AES-256-GCM 加密保存，不会通过读取接口返回明文。

## 仓库结构

```text
project-ardor/
├─ backend/                 # Java 21 + Spring Boot + LangChain4j + Flyway
├─ frontend/                # Next.js App Router + TypeScript + Tailwind + shadcn/ui
├─ docs/                    # 产品、架构、数据库、路线图与 AI 开发规则
├─ docker-compose.yml       # 本地 PostgreSQL、Redis、后端和前端
├─ start.ps1                # Windows 一键构建并启动
├─ .env.example             # 非敏感环境变量示例
└─ README.md
```

## 一键启动

需要 Maven 和 Docker Desktop。先把 `.env.example` 复制为 `.env`，并替换开发示例加密密钥：

```powershell
$keyBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
[Convert]::ToBase64String($keyBytes)
```

把输出写入 `.env` 的 `ARDOR_ENCRYPTION_KEY`，然后运行：

```powershell
.\start.ps1
```

打开 `http://localhost:3000`，创建账号后直接进入 Agent 对话页。右上角可随时进入简历分析、模拟面试、总体记忆或设置。模型配置支持 `OpenAI Compatible` 与 `Anthropic Compatible` 两种协议；可以先点“测试连接”，确认 Base URL、模型、API Key 和 Tool Calling 可用后再保存。首次保存必须填写 API Key，之后留空会保留原 Key。

DeepSeek 可直接按以下方式填写：

- OpenAI Compatible：Base URL `https://api.deepseek.com`
- Anthropic Compatible：Base URL `https://api.deepseek.com/anthropic`

> 不要在已有用户配置后随意更换 `ARDOR_ENCRYPTION_KEY`，否则原有 API Key 将无法解密。开发示例中的全零密钥禁止用于部署。

## 开发模式

只启动数据库和会话存储：

```powershell
docker compose up -d postgres redis
```

后端固定以 Java 21 为编译目标和容器运行时。当前 Windows 环境可用 Maven 打包后通过 Compose 运行：

```powershell
cd backend
mvn test
mvn -DskipTests package
cd ..
docker compose up -d --build backend
```

启动前端：

```powershell
cd frontend
npm install
npm run dev
```

打开 `http://localhost:3000`。

## 验证命令

```powershell
cd backend
mvn test

cd ..\frontend
npm run lint
npm run typecheck
npm run build

cd ..
docker compose config
```

项目默认把 PostgreSQL 映射到宿主机 5433，后端映射到 8080，前端映射到 3000。Redis 只在 Compose 内网开放 6379，并使用 `ardor-redis-data` Volume 持久化 30 天登录会话。简历原文件保存在 `ardor-resume-data` Volume；PostgreSQL 保存用户归属、解析文本、异步分析任务、分析结果、面试状态、答案和评价。

## 重要边界

- 业务身份只能来自 Spring Security 认证上下文，API 不接受可信的前端 `user_id`。
- Repository 必须使用当前用户限定查询；跨实体关联还由数据库复合外键校验所有者一致。
- 所有 schema 变更只通过 Flyway 新增 migration，已经应用的 migration 不允许修改。
- 页面操作和 Agent Tool 必须调用同一组应用服务。
- 简历分析与模拟面试会使用当前用户自己的 LLM 配置；页面与 Agent Tool 复用同一组 `ResumeService` / `InterviewService`。
- Agent 使用 LangChain4j AI Services 进行工具选择与多回合 Tool Calling；可信用户身份在服务端绑定，模型不能传入或覆盖 `userId`。
- 会话上下文只来自当前会话；总体记忆是单独、可见、可编辑的用户级数据，不等于把全部历史消息塞入提示词。
- 当前不包含 RAG、语音、搜索、pgvector、多 Agent 或 LangGraph。Redis 目前只负责登录会话；简历任务队列使用 PostgreSQL 保证持久化。

详细设计见 [项目范围](docs/PROJECT.md)、[架构说明](docs/ARCHITECTURE.md)、[数据库设计](docs/DATABASE.md)、[路线图](docs/ROADMAP.md) 与 [AI 开发指南](docs/AI_GUIDE.md)。
