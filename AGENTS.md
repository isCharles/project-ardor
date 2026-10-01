# Project Ardor 开发约定

- 源代码、配置与 Markdown 均使用 UTF-8。Windows PowerShell 读取文本时显式指定 `-Encoding UTF8`；涉及中文输出先设置控制台输入、输出与 `$OutputEncoding` 为 UTF-8。修改含中文的文件后，重新按 UTF-8 读取并检查。
- 所有代码修改在独立分支完成，并通过 PR 提交。不要未经需要触发付费的 Claude 审阅；当前工作流只有添加 `claude-review` 标签才会触发。
- 在本机运行 Docker/Compose 前，先确认 Engine 是否响应。若 Engine 不可用，优先**只运行一次** `scripts/Start-DockerDesktopSafely.ps1`；它会在安全条件成立时同时处理两处 Docker 临时 socket。若仍失败，停止自动重试并阅读 `docs/DOCKER_DESKTOP_SOCKET_RECOVERY.md`，结合最新日志诊断。
- 不得为修复 socket 错误执行 Factory Reset、`prune`、删除镜像/容器/数据卷，或改动 `C:\Users\Administrator\AppData\Local\Docker\wsl\disk` 与 `E:\AppDataMoves\DockerDisk`。运行时 `.socket-backup-*` 仅保留，不批量清理。
