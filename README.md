# Project Ardor

Project Ardor 是一个面向多用户的 AI Career Agent。V0.1 围绕一条可演示的求职闭环：理解简历、澄清目标、进行文本模拟面试、生成结构化反馈，并把反馈转化为后续待办。

当前仓库已完成多会话 Career Agent、总体记忆及管理员工作台。登录后直接进入以输入框为视觉中心的 Agent 对话页；简历、面经、模拟面试、知识库、日历与记忆卡也可独立使用。管理员可统一配置主模型、向量模型、联网搜索、备用模型、ASR 与 TTS，普通用户默认继承，也可在设置中保存个人覆盖配置。登录会话由 Redis 持久化 30 天，业务数据保存在 PostgreSQL。所有 API Key 均由服务端 AES-256-GCM 加密，读取接口只返回尾号提示。

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

打开 `http://localhost:3000`，创建账号后直接进入 Agent 对话页。模型配置支持 `OpenAI Compatible` 与 `Anthropic Compatible` 两种协议；可以先点“测试连接”，确认 Base URL、模型、API Key 和 Tool Calling 可用后再保存。首次保存必须填写 API Key，之后留空会保留原 Key。

要启用管理工作台，请在 `.env` 的 `ARDOR_ADMIN_EMAILS` 填写一个或多个已注册邮箱（逗号分隔），然后重启后端并重新登录。管理员可查看系统总量、管理账户状态和角色、配置全体用户默认使用的 API；管理员不能在工作台读取用户简历、消息或面经正文。个人配置始终优先于管理员默认，删除个人配置即可恢复继承。

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

## 升级说明：Postgres 镜像已切换到 pgvector

知识库语义检索需要 `vector` 扩展，`docker-compose.yml` 的 postgres 镜像已从 `postgres:17-alpine`
换成 `pgvector/pgvector:pg17`。两者同为 PostgreSQL 17，数据目录格式一致，已有 Volume 可以直接挂载，
但底层 libc 从 musl 变成 glibc，文本索引的排序规则可能不同。升级后执行一次重建索引：

```powershell
docker compose up -d postgres
docker exec project-ardor-postgres-1 psql -U ardor -d ardor -c "REINDEX DATABASE ardor;"
```

语义检索需要在 `设置 → 未来能力 → 向量模型` 配置一个 OpenAI 兼容的 Embedding 服务
（`/embeddings` 接口）。没有配置时知识库自动退化为关键词检索，功能不受影响。
上传文档会在事务提交后自动向量化；之前上传或因网络波动遗留的文档由后台按批补齐。

出于 SSRF 防护，用户填写的 Base URL 默认必须解析到公网地址。本地自托管模型
（Ollama、LM Studio 等）需要在 `.env` 设置 `ARDOR_ALLOW_PRIVATE_API_BASE_URLS=true` 才能使用。

## 容器 DNS

后端容器固定了上游 DNS(`ARDOR_DNS_PRIMARY` / `ARDOR_DNS_SECONDARY`,默认 223.5.5.5 与 119.29.29.29)。
Docker 内嵌解析器会转发给宿主机配置的上游,在部分 Windows / Docker Desktop 环境下上游不应答,
表现为所有 LLM 调用在发出请求前就失败,报错是「Base URL 的主机暂时无法解析」。

**如果你的宿主机能解析公共 DNS 解析不了的名字**——企业 VPN 的 split-horizon DNS,
或只能通过内网主机名访问的自建模型(即设置 `ARDOR_ALLOW_PRIVATE_API_BASE_URLS=true` 的场景)
——请在 `.env` 里把这两个变量指向你的内网解析器。否则会出现和上面完全相同的报错,很容易误判成同一个问题。

`host.docker.internal` 通过 `extra_hosts` 显式映射,不受上述设置影响。

## 重要边界

- 业务身份只能来自 Spring Security 认证上下文，API 不接受可信的前端 `user_id`。
- Repository 必须使用当前用户限定查询；跨实体关联还由数据库复合外键校验所有者一致。
- 所有 schema 变更只通过 Flyway 新增 migration，已经应用的 migration 不允许修改。
- 页面操作和 Agent Tool 必须调用同一组应用服务。
- 简历分析与模拟面试会使用当前用户自己的 LLM 配置；页面与 Agent Tool 复用同一组 `ResumeService` / `InterviewService`。
- Agent 使用 LangChain4j AI Services 进行工具选择与多回合 Tool Calling；可信用户身份在服务端绑定，模型不能传入或覆盖 `userId`。
- 会话上下文只来自当前会话；总体记忆是单独、可见、可编辑的用户级数据，不等于把全部历史消息塞入提示词。
- 知识库使用 pgvector 做混合检索：稠密向量（用户自备 Embedding 服务）+ 关键词词频，按 RRF 融合。检索一律先按 `user_id` 过滤再算相似度。
- Agent 永远不会自己删除数据：所有 `delete_*` 工具只返回 `CONFIRMATION_REQUIRED`，并在对话里给出一个写明真实对象名称的确认按钮，删除由用户点击后从浏览器直接调用对应 REST 接口完成。按钮上的名称由服务端读取对象本身生成，不来自模型。
- 个人资料保存 IANA 时区，Agent 的相对日期与记忆卡日程按用户时区计算；默认 `Asia/Shanghai`。
- 重复日程（每天、每周、每月）由 `task_series` 保存规则，并按用户时区提前展开成日历上真实的待办，因此单次可以独立完成、修改或删除。后台任务持续把展开窗口推到约 120 天以后；取消规则只撤回尚未开始、用户也没动过的后续日程。
- 模拟面试支持文字与语音两种方式。浏览器与后端通过鉴权 WebSocket 保持实时语音会话，
  麦克风音频以 16 kHz PCM 分片送入阿里云百炼流式 ASR，题目由 24 kHz PCM 流式 TTS
  边生成边播放；服务端 VAD 在停顿后自动完成转写，用户开口会立即停止 AI 朗读。
  原始录音不会持久化，最终确认的文字仍复用现有面试答案和评价链路；原有整段 HTTP
  ASR/TTS 接口继续保留为兼容能力。
- 当前不包含多 Agent 或 LangGraph。Redis 目前只负责登录会话；简历任务队列使用 PostgreSQL 保证持久化。

详细设计见 [项目范围](docs/PROJECT.md)、[架构说明](docs/ARCHITECTURE.md)、[数据库设计](docs/DATABASE.md)、[路线图](docs/ROADMAP.md) 与 [AI 开发指南](docs/AI_GUIDE.md)。
