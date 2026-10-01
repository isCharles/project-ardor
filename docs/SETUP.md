# 本地启动与开发

本文保留 README 中的运行说明。Project Ardor 目前是本地自托管的开发版本，公开部署前还需要 HTTPS、密钥管理、备份恢复等上线准备。

## 准备环境

- Windows、Docker Desktop（Docker Engine 已启动）、Java 21 与 Maven。
- 需要使用模型能力时，准备可用的模型 API Key。模型配置可由管理员统一提供，也可由用户自行填写。

在项目根目录复制环境变量示例：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，至少设置强随机的 `POSTGRES_PASSWORD` 和一个独立的 `ARDOR_ENCRYPTION_KEY`。后者是 32 字节随机数的 Base64 表示，可在 PowerShell 中生成：

```powershell
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($keyBytes) } finally { $rng.Dispose() }
[Convert]::ToBase64String($keyBytes)
```

将输出写入 `.env` 的 `ARDOR_ENCRYPTION_KEY`。不要提交 `.env`，也不要在已有用户配置后随意更换加密密钥，否则原有 API Key 将无法解密。

## 一键启动

在项目根目录运行：

```powershell
.\start.ps1
```

脚本会构建后端并通过 Docker Compose 启动 PostgreSQL、Redis、后端与前端。打开 <http://localhost:3000>，注册后进入 Ardor。

在本机，脚本会先检查 Docker Engine；若它未启动，会在严格校验后尝试一次可逆的临时 socket 恢复。详细证据、安全边界与人工排障见 [Docker Desktop socket 故障记录](DOCKER_DESKTOP_SOCKET_RECOVERY.md)。

模型设置支持 OpenAI Compatible 与 Anthropic Compatible 两种协议。填写 Base URL、模型和 API Key 后先测试连接。首次保存必须提供 Key；之后留空会保留已保存的 Key。普通用户默认使用管理员配置，也可以切换到个人配置。

如需启用管理员工作台，在 `.env` 的 `ARDOR_ADMIN_EMAILS` 中填写一个或多个已注册邮箱（逗号分隔），重启后端并重新登录。管理员可以设置全体用户的默认 API、查看汇总信息和管理账户，但不能在工作台读取用户简历或对话正文。

## 分别运行与验证

只启动数据库和会话存储：

```powershell
docker compose up -d postgres redis
```

后端构建、测试与容器更新：

```powershell
cd backend
mvn test
mvn -DskipTests package
cd ..
docker compose up -d --build backend
```

本机开发前端：

```powershell
cd frontend
npm install
npm run dev
```

提交前可运行：

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

默认端口：前端 `3000`、后端 `8080`、PostgreSQL 宿主机 `5433`。Redis 只在 Compose 内网开放。登录会话保存在 Redis，业务数据保存在 PostgreSQL，简历原文件保存在独立 Docker Volume。

## 知识库与已有数据库升级

知识库语义检索需要一个 OpenAI 兼容的 Embedding 服务，可在设置中配置向量模型。未配置时自动使用关键词检索；已上传的资料由后台逐批补齐向量。

Compose 的 PostgreSQL 镜像已从 `postgres:17-alpine` 切换至 `pgvector/pgvector:pg17`。如果升级已有数据卷，先做好备份。两者都使用 PostgreSQL 17，但底层 libc 不同，升级后需要重建文本索引：

```powershell
docker compose up -d postgres
docker exec project-ardor-postgres-1 psql -U ardor -d ardor -c "REINDEX DATABASE ardor;"
```

## 网络配置

用户填写的模型 Base URL 默认必须解析到公网地址。只有可信的单用户、自托管场景确实需要访问局域网模型时，才在 `.env` 设置 `ARDOR_ALLOW_PRIVATE_API_BASE_URLS=true`。

后端容器使用 `ARDOR_DNS_PRIMARY` / `ARDOR_DNS_SECONDARY` 作为上游 DNS，默认值在 `.env.example` 中。企业 VPN、split-horizon DNS 或内网模型若需要内部域名解析，应改为实际的内部 DNS；否则可能出现「Base URL 的主机暂时无法解析」。若 Docker 无法直接访问外部模型服务，还可配置 `ARDOR_HTTP_PROXY_HOST` / `ARDOR_HTTP_PROXY_PORT`。

更多实现和安全边界见 [架构说明](ARCHITECTURE.md)、[数据库设计](DATABASE.md) 与 [AI 开发指南](AI_GUIDE.md)。
