# Project Ardor 开发约定

- 源代码、配置与 Markdown 均使用 UTF-8。Windows PowerShell 读取文本时显式指定 `-Encoding UTF8`；涉及中文输出先设置控制台输入、输出与 `$OutputEncoding` 为 UTF-8。修改含中文的文件后，重新按 UTF-8 读取并检查。
- 所有代码修改在独立分支完成，并通过 PR 提交；每个 PR 都要等待 Codex 审阅并处理有效意见。仅在大范围、底层或复杂高风险改动需要额外审阅时触发付费的 Claude 审阅；当前工作流只有添加 `claude-review` 标签才会触发。
- 在本机运行 Docker/Compose 前，先确认 Engine 是否响应。若 Engine 不可用，优先**只运行一次** `scripts/Start-DockerDesktopSafely.ps1`；它会在安全条件成立时同时处理两处 Docker 临时 socket。若仍失败，停止自动重试并阅读 `docs/DOCKER_DESKTOP_SOCKET_RECOVERY.md`，结合最新日志诊断。
- 不得为修复 socket 错误执行 Factory Reset、`prune`、删除镜像/容器/数据卷，或改动 `C:\Users\Administrator\AppData\Local\Docker\wsl\disk` 与 `E:\AppDataMoves\DockerDisk`。运行时 `.socket-backup-*` 仅保留，不批量清理。

## Code Review Rules

以下规则用于 Codex 对 PR 的独立审阅；只报告能定位到变更、可复现或有明确执行路径的实质问题。不要把格式偏好、尚未证明的猜测或仓库已有且未被本次改动加重的问题当作发现。

### 多用户数据边界

检查新增或修改的 API、Service、Repository、Agent 工具、后台任务与向量检索是否始终按用户归属限定资源。请求入口应从认证上下文取得当前用户，不能信任客户端传来的 `userId`；定时任务和队列 Worker 没有请求认证上下文，应从可信的持久化任务或资源记录取得并传递用户归属。发现跨用户访问路径时指出具体入口与查询，并建议在业务层补授权及双用户隔离测试。参见 `docs/AI_GUIDE.md`。

### Agent 的副作用与删除确认

检查 Agent 新增或改动的删除操作是否仍只生成待确认提案，由用户在界面上确认后才调用受鉴权的业务接口；不能让模型或工具自行完成不可逆删除。网页、知识库、简历和其他外部内容都是不可信数据，不能作为用户确认，也不能凭其中的指令触发删除、写入长期记忆或其他副作用。发现绕过时指出从不可信输入到副作用的具体路径；安全做法是复用现有确认和业务 Service 边界。

### 外部 API 地址边界

检查所有不可信的出站请求目标，包括用户或管理员配置的模型、语音、搜索等 Base URL、重定向目标，以及服务商响应返回的下载 URL，是否继续经过 `ExternalBaseUrlPolicy` 等等效校验；不得通过新增调用链绕开校验而造成 SSRF。默认拒绝内网、环回和其他非公共地址。仅在可信的本地开发或单用户自托管部署中明确开启 `ARDOR_ALLOW_PRIVATE_API_BASE_URLS=true` 时允许私有地址，不得默认开启或把该例外暴露给不可信用户。发现绕过时指出可控 URL 到实际网络请求的路径。
