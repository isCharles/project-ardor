# Docker Desktop 启动时的 AF_UNIX socket 故障记录

> 记录日期：2026-10-01，Asia/Shanghai。本文记录本机实测，不把临时恢复称为 Docker 的永久修复。后续 Agent 请先核对当前状态和日志，不要照搬旧结论。

## 2026-10-03 复发与启动脚本编码修复

再次启动时 Engine 管道缺失，Docker Desktop 和后端进程均已退出。两处运行时目录中仅有本脚本白名单内的 socket 重解析点；数据盘 Junction 仍指向 `E:\AppDataMoves\DockerDisk`，VHDX 存在。第一次用 Windows PowerShell 5.1 直接运行恢复脚本时，在执行脚本主体前就因无 BOM 的 UTF-8 中文字符串解析失败。随后用 PowerShell 7 运行同一个脚本：它将 `Docker\run` 和 `docker-secrets-engine` 分别改名保留为 `.socket-backup-20261003-125100-981f6162`、`.socket-backup-20261003-125101-d918ee8e`，一次启动后 Engine 29.5.3 于 12:51:15 响应。四个 Ardor 容器正常，后端、Redis、PostgreSQL 健康，三卷仍在，3000 为 200、未认证 8080 为 401。没有删除备份或触碰 E 盘数据。

已给 `start.ps1` 和 `scripts/Start-DockerDesktopSafely.ps1` 加 UTF-8 BOM，让 Windows PowerShell 5.1 能按 UTF-8 读取中文内容；用本机 5.1 的解析器检查两个脚本均为零错误，并在 Engine 已就绪时实际运行恢复脚本，确认它直接返回“无需恢复”。**尚未为了测试而再次停机制造 socket 故障**；这项改动修的是自动恢复入口的编码兼容性，不是 Docker/Windows 遗留 socket 的上游根因。

当天下午 Engine 再次消失、Docker 进程已退出，受控脚本在 Windows PowerShell 5.1 下又暴露第二个入口问题：`$ErrorActionPreference = "Stop"` 会把失败的 `docker info` 标准错误提升为终止异常，导致脚本在检查两处 socket 之前退出。日志为 `恢复中止：failed to connect to the docker API ... dockerDesktopLinuxEngine ... pipe ... cannot find the file specified`；后端日志最后写入时间仍是 13:11，没有新的 Docker 启动失败日志。独立只读复现中，5.1 对同一探测命令抛出 `RemoteException`。已把 `Test-DockerEngine` 的探测异常转成“Engine 不可用”布尔结果；其他失败仍按脚本原有规则中止，未放宽目录白名单或数据盘边界。

修复后在 17:11 用同一个 Windows PowerShell 5.1 入口实际运行：脚本保留两处新备份 `run.socket-backup-20261003-171125-23852d62` 与 `docker-secrets-engine.socket-backup-20261003-171125-264ade76`，17:11:40 Engine 恢复响应。此次验证的是脚本在故障状态下从头到尾可运行；上游遗留 socket 的根因仍未定位。

## 结论与当前状态

本机 Docker Desktop 4.77.0 曾反复在启动阶段因 Windows 无法移除自身留下的 AF_UNIX socket 而退出。失败发生在 **Docker Engine 启动之前**，不是 Ardor 的容器、镜像、数据卷或 E 盘 VHDX 损坏。`dockerInference` 和 `docker-secrets-engine\engine.sock` 两处会轮流挡住启动；仅处理其中一处再启动，会重新生成它，下一次又从头报错。

2026-10-01 13:04，本仓库的受控恢复脚本在一次 Docker 正常停机后，**启动前同时**保留两处临时目录并重新启动；约 13 秒后 `docker info` 返回 Engine `29.5.3`。复验：Ardor 的四个容器运行，后端、Redis、PostgreSQL 健康；`pg_isready` 接受连接，Redis 返回 `PONG`；前端 HTTP 200，未认证的后端 HTTP 401；三个命名卷仍在。

这证明恢复路径在本机至少成功一次，并**不证明**之后每次都能恢复，也不证明 Windows/Docker 的根因已解决。Docker Desktop 自己的直接启动仍可能再次碰到此故障；经本项目 `start.ps1` 启动时才会运行预检脚本。

## 本机环境与证据

| 项目 | 2026-10-01 实测 |
| --- | --- |
| Windows | Windows 11 Pro，版本 10.0.26100，x64 |
| Docker Desktop | 4.77.0；恢复后的 Engine 为 29.5.3 |
| 后端日志 | `C:\Users\Administrator\AppData\Local\Docker\log\host\com.docker.backend.exe.log` |
| 设置 | `AutoStart=false`、`EnableInference=false`、`EnableDockerAI=false`；关闭 Inference 的设置原本已经是 `false`，仍发生错误 |
| Docker 数据入口 | `C:\Users\Administrator\AppData\Local\Docker\wsl\disk` 是 Junction，指向 `E:\AppDataMoves\DockerDisk` |
| Docker 数据文件 | `E:\AppDataMoves\DockerDisk\docker_data.vhdx` 存在；本次没有移动、覆盖或压缩它 |
| 临时 socket | `%LOCALAPPDATA%\Docker\run\dockerInference`、`%LOCALAPPDATA%\docker-secrets-engine\engine.sock` 均呈现为 `ReparsePoint` |

日志中的两类核心错误（省去用户目录前缀）：

```text
starting services: initializing Inference manager: listening on unix://.../Docker/run/dockerInference: remove .../dockerInference: The file cannot be accessed by the system. (listener: The filename, directory name, or volume label syntax is incorrect.)
starting services: initializing Secrets Engine: listening on unix://.../docker-secrets-engine/engine.sock: remove .../engine.sock: The file cannot be accessed by the system. (listener: The filename, directory name, or volume label syntax is incorrect.)
```

日志时间为 UTC：2026-10-01 03:23:39 的 Inference 错误、04:26:01 的 Secrets Engine 错误、04:28:34 的再次 Inference 错误。末尾的 `listener: The filename...` 不能单独证明路径拼写有误；首要失败是已有 socket 的 `remove` 无法访问。

## 这次实际做过什么

1. 最初 `docker info` 找不到 Engine 管道；3000/8080 页面不可正常使用。核对数据盘 Junction 和 VHDX 后，没有对持久数据动手。
2. 先备份了 `%APPDATA%\Docker\settings-store.json` 到同目录 `settings-store.json.inference-recovery-20261001-1225.bak`。文件里的 `EnableInference` 本来就是 `false`，所以“关掉 Inference”在此版本上不是已验证的修复。
3. 第一次仅将 `%LOCALAPPDATA%\Docker\run` 改名保留为 `run.socket-backup-20261001-1225`，然后启动。报错前移到 `docker-secrets-engine\engine.sock`。
4. 第二次仅将 `%LOCALAPPDATA%\docker-secrets-engine` 改名保留为 `docker-secrets-engine.socket-backup-20261001-1230`，然后启动。**新创建**的 `Docker\run\dockerInference` 又导致同一个错误。这一步证实两个目录不能分两次启动来处理。
5. 第三次在 Docker 进程退出后，保留新 `Docker\run` 为 `run.socket-backup-20261001-124546`；此前 Secrets Engine 目录仍已被移开。此后 Engine 成功启动，四个 Ardor 容器与三个卷可见。
6. 修改仓库 `start.ps1`，将 Docker 预检放到 Java 构建之前；新增 `scripts/Start-DockerDesktopSafely.ps1`，只在 Engine 不可用时检查两处临时目录，且在**同一次启动前**处理两处。旧脚本依赖 `Test-Path` 判断 socket 是否存在；新脚本直接枚举父目录并读取条目元数据，以减少对特殊端点路径可访问性的依赖。**本次保存的旧 socket 上 `Test-Path` 返回了 `True`，因此没有证据证明旧脚本在本次故障中确实发生了假阴性。**
7. 正常执行 `docker desktop stop --timeout 90`，确认 Engine 停止且两处 socket 仍在。运行新脚本后，两个目录分别保留为 `run.socket-backup-20261001-130435-30848eea` 和 `docker-secrets-engine.socket-backup-20261001-130435-619de662`；13:04:48 Engine 响应。没有删除这些备份。

本次**没有** Factory Reset、重装 Docker、prune、删除任何 `.socket-backup-*`、修改镜像/容器/数据卷，亦没有触碰 E 盘 VHDX。旧备份不要因为“看起来只是 socket”就批量删除；其中的重解析点在 Windows 上可能无法正常删除。

## 下次如何处理

在仓库根目录运行：

```powershell
.\start.ps1
```

它先调用 `scripts/Start-DockerDesktopSafely.ps1`。若只需启动 Docker 而不构建 Ardor，可单独运行：

```powershell
.\scripts\Start-DockerDesktopSafely.ps1
```

脚本的自动操作边界：

- Engine 已可用时不移动任何目录。
- Engine 不可用、Docker Desktop 和后端进程均已退出时，才考虑两处**精确路径**。只接受普通目录及列明的 socket 名称、重解析点属性；遇到未知内容或 Junction 会停止，不会推测处理。
- 不删除文件；把含有旧 socket 的目录改名为带时间戳和随机后缀的同级备份，避免与旧备份撞名。Docker 自行重建运行时目录。
- 只启动一次，最多等待 120 秒；失败就停止，不无限循环、不自动 Factory Reset。事件记录于 `%LOCALAPPDATA%\ProjectArdor\docker-recovery.log`，不记录密钥。
- 若 Docker 进程仍在而 Engine 不响应，脚本会等待，仍不响应则停止；它不会强制终止用户进程。先读 Docker 日志并安全退出 Docker，再重试。

这个脚本**不会**在 Windows 登录时自行运行，也不能拦截用户直接点击 Docker Desktop 图标的启动。若将来要覆盖登录启动，需明确选择并验证一个受控登录任务，让 Docker 自带的开机自启保持关闭；不能并行抢跑。本机 `AutoStart=false` 已是现状。

若脚本停止，优先收集：`docker info` 的失败类型、上述后端日志最新 `backend cancelling with error` 行、两处运行时目录的文件名和 `Attributes`、Docker/Windows 版本，以及 `docker desktop stop` 后进程是否真正退出。不要先做 Factory Reset、`prune`、重装或修改数据盘。诊断包可能含路径与配置，上传前应审核并取得用户同意。

## 上游信息与尚待查证

- [Docker Desktop 4.89/4.90 release notes](https://docs.docker.com/desktop/release-notes/) 声称修复“异常退出后遗留卡住的 socket 导致无法启动”；本机仍是 4.77，**尚未测试升级**。该表述也没有保证正常停机后的同类问题都已解决。
- [docker/desktop-feedback#460](https://github.com/docker/desktop-feedback/issues/460) 中有多个 Windows 用户报告 Inference 与 Secrets Engine 的 socket 轮流失败，并描述两目录一起改名的临时办法；后续评论称 4.89/4.90 仍有复发。它是相近案例，不等于本机已证明同一底层原因。
- [docker/desktop-feedback#531](https://github.com/docker/desktop-feedback/issues/531) 记录了同样的 Windows `ERROR_CANT_ACCESS_FILE (1920)` 报错以及升级后依旧发生的案例。
- [docker/desktop-feedback#631](https://github.com/docker/desktop-feedback/issues/631) 给出在另一台 Windows 机器上独立于 Docker 的 AF_UNIX 行为对照；本机**尚未执行**该复现实验，不能直接断言 Windows 内核、权限、杀毒软件或磁盘迁移是根因。本机系统构建号为 26100，与该报告的 26200 不同。

真正的长期解决可能需要 Docker/Windows 上游修复，或在确认兼容性、备份和回退办法后评估升级。现阶段不应凭版本说明直接承诺升级能根治，也不应把目录改名误称为修复了上游缺陷。
