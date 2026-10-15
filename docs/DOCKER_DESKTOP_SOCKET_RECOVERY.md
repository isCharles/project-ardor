# Docker Desktop 启动时的 AF_UNIX socket 故障记录

> 记录日期：2026-10-01，Asia/Shanghai。本文记录本机实测，不把临时恢复称为 Docker 的永久修复。后续 Agent 请先核对当前状态和日志，不要照搬旧结论。

## 2026-10-15 一次受控启动仍被新 Secrets socket 阻断

08:49（Asia/Shanghai）先分别在受限环境及正常本机权限下核验 Engine，两次均无可用管道，不是仅凭权限错误判断停机。只执行一次恢复脚本：08:49:01 保留 `Docker\run.socket-backup-20261015-084901-b0fcb2ce`，08:49:02 保留 `docker-secrets-engine.socket-backup-20261015-084902-d618e324`，随后启动 Desktop。08:51:04 达到 120 秒上限，脚本中止，未再次尝试。

只读日志复核发现 00:49:03 UTC（本地 08:49:03）后端因 `initializing Secrets Engine` 退出：新路径 `docker-secrets-engine/engine.sock` 的 remove 返回 `The file cannot be accessed by the system`，并附 `listener: The filename, directory name, or volume label syntax is incorrect`。00:49:06 UTC 再次记录同一错误并上报。08:51 后查询未见 Desktop/后端进程。旧目录保留操作本次也未能解决新 socket 初始化问题，不能宣称已永久修复。

本次未删除任何备份，未重置、prune 或改动 VHDX/Junction、镜像、卷及正式数据库。Ardor 本机部署和新队列迁移验收因此尚未完成；编译、单元和模拟浏览器测试继续独立执行，不能替代本机服务验收。

## 2026-10-10 可逆恢复也未能启动：新 Secrets socket 再次阻断

这次 Ardor Golden Path 的本机验收前，`docker info` 找不到 Engine 管道。依照本仓库边界，**只运行一次** `scripts/Start-DockerDesktopSafely.ps1`。其独立日志记录：23:44:47、23:44:48（Asia/Shanghai）分别将 `Docker\run` 和 `docker-secrets-engine` 两个旧运行目录改名保留为 `.socket-backup-20261010-234447-953dbff8`、`.socket-backup-20261010-234448-9bfc5575`，随即启动 Docker Desktop；23:46:52 因 Engine 仍未响应而中止，不再自动尝试。旧目录是保留副本，**没有删除**。

Docker 后端日志在 2026-10-10 15:44:55 UTC（本地 23:44:55）显示启动失败：初始化 Secrets Engine 时，移除**新建** `docker-secrets-engine\engine.sock` 返回 `The file cannot be accessed by the system`，随后后端退出。次日只读复核中，Docker Desktop/后端进程均未运行，`docker info` 仍提示缺少 `docker_engine` 管道；两个新运行目录都是普通目录，分别含 `dockerInference`、`engine.sock` 重解析点。这说明“启动前同时移开两处旧 socket”**这次也不足以恢复**，不能再把脚本视作必定成功的修复；但现有证据不能证明是谁或什么顺序造成新 socket 再次阻断。

本次没有运行第二次恢复脚本、Factory Reset、`prune`，没有删除备份，也没有改动 Docker 数据盘 Junction、E 盘 VHDX、Ardor 镜像/卷或项目数据。Ardor 的本机容器与端口因此**未能验收**；同一轮代码改动仅在 GitHub Actions 的一次性 PostgreSQL/Redis/假模型环境通过端到端测试。后续排查应先核对当前进程、最新后端日志和两处运行目录，不要机械重复改名；如需升级 Docker 或做更深层系统干预，应另行制定备份与回退办法。

## 2026-10-05 本机端口转发中断与 Secrets Engine 再次复发

本机 v0.5.0 前后端容器显示运行、后端/PostgreSQL/Redis 健康，但 Windows 访问 `127.0.0.1:3000` 和 `:8080` 时收到空响应；从前端容器内部访问自身为 200、访问后端为 401。监听端口属于 `com.docker.backend.exe`，因此这一次 Ardor 的“服务器无法连接”首先定位到 Windows 侧 Docker 端口转发，而非应用服务或数据库。正常执行 `docker desktop restart --timeout 120` 后，新后端日志在 11:26:16（Asia/Shanghai）报 `docker-secrets-engine/engine.sock` 无法移除，Engine 未能重新启动；CLI 重启等待未正常完成。

随后 `docker desktop stop --timeout 90` 报告 Desktop 未运行，独立检查也确认 Docker Desktop/后端进程均退出。两处运行时目录均为普通目录，分别只含白名单内的 `dockerInference` 与 `engine.sock` 重解析点。**仅执行一次** `scripts/Start-DockerDesktopSafely.ps1`：它把两处目录原样改名保留为 `run.socket-backup-20261005-113327-19ede9a7` 与 `docker-secrets-engine.socket-backup-20261005-113327-99361dac`，11:33:35 Engine 29.5.3 恢复响应。没有删除备份、重置 Docker、清理镜像/卷或触碰 E 盘 VHDX。

恢复后四个 Ardor 容器运行，后端/PostgreSQL/Redis 健康；Windows 访问登录页为 200、未认证后端为 401，Redis 返回 `PONG`，PostgreSQL `pg_isready` 接受连接。此记录只证明本次可逆恢复有效；端口转发为何先断开、Docker 4.77 为何仍遗留 AF_UNIX socket，均未证明上游根因。

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

## 2026-10-15：独立于 Docker 的原生 socket 对照

本次没有成功恢复 Engine。此前安全恢复脚本的一次启动仍因新的 Inference/Secrets Engine socket 失败；没有循环重跑恢复脚本。随后用 PowerShell/.NET 在自建临时目录中测试 `AddressFamily.Unix`、`SocketType.Stream`、`ProtocolType.Unspecified`：依次执行 `Bind(UnixDomainSocketEndPoint)`、`Listen(1)`、`Dispose()`，最后用 `System.IO.File.Delete` 删除同一 socket。以下记录是本机实测，不是上游问题的根因结论。

| 对照 | 结果 |
| --- | --- |
| 用户 `%TEMP%`（短路径 `C:\Users\ADMINI~1\AppData\Local\Temp`） | Bind/listen/close 后删除失败：`The file cannot be accessed by the system`；条目显示 `Archive, ReparsePoint` |
| 用户 Temp 的完整长路径 | 同样删除失败，排除了单纯的 8.3 路径别名解释 |
| 宿主提权执行，从创建开始测试完整用户 Temp 路径 | 同样删除失败，不能归为仅受限执行环境中的删除权限问题 |
| 同一用户 Temp 内缩短到 52 字符的 socket 路径 | 同样删除失败，不能简单归为超长路径 |
| `C:\Windows\Temp` | 同一套 .NET bind/listen/close/delete 成功，随后清理自建空目录 |
| `E:\Temp` 与项目构建临时目录中的直接 E 盘路径 | 同一套操作成功，已清理这些自建探针目录 |
| 自建 C 盘 Temp Junction → 独立 E 盘临时目录 | Bind 返回 `Only one usage of each socket address ... is normally permitted`；目标实际为空。核验目标后仅移除了该临时 Junction 和空目标，**没有**将此方案用于 Docker |

因此不能声称“整个 C 盘不支持 AF_UNIX”，也不能据此断定 Docker 的 E 盘 VHDX 迁移损坏。问题至少能在当前用户 Temp 路径的独立原生程序中复现，但原因仍可能涉及目录权限、文件系统过滤、系统策略或 AF_UNIX 驱动行为；尚未用证据区分这些解释。

辅助只读检查：Windows 11 Pro `10.0.26100`，上次启动为本机时间 `2026-10-13 12:22:43`；Docker Desktop `4.77.0.228796`。C/E 均报告 NTFS、Healthy。`C:\Users`、用户目录、AppData、Local、Temp 均没有显示 Junction；用户 Temp 和 Windows Temp 的目录大小写敏感标志均关闭。用户 Temp 目录属主为 `BUILTIN\Administrators` 且继承 ACL，Windows Temp 为 `NT AUTHORITY\SYSTEM` 且保护 ACL；这只是差异，尚未证明 ACL 是原因，没有放宽任何目录权限。

无法删除的四个自建探针保留如下，均只含本次 socket，不含应用数据；不要因此递归清理整个 Temp：

- `C:\Users\Administrator\AppData\Local\Temp\ardor-socket-ffdf4782\probe.sock`
- `C:\Users\Administrator\AppData\Local\Temp\ardor-long-socket-05b9f060\p.sock`
- `C:\Users\Administrator\AppData\Local\Temp\ardor-host-afunix-64db6af4\p.sock`
- `C:\Users\Administrator\AppData\Local\Temp\a1db23ba\s`

### 此版本的帮助参数也有副作用

尝试读取已安装 `resources\com.docker.backend.exe --help` 的参数时，它打印帮助及 `failed to parse args: flag: help requested` 后，**仍继续进入 monitor/services 启动流程**。2026-10-15 `06:59:35 UTC` 启动，随后再次因 `...\Docker\run\dockerInference` 删除失败进入错误退出；`06:59:45 UTC` monitor 以状态 150 退出。执行会话最终确认进程结束，之后查询没有后端残留进程。

不要再把这个二进制的 `--help` 当作只读检查，也不要据此再次启动它。本文只保留最小错误证据，不复制完整设置日志。此次没有修改 Docker 设置、目录 Junction、数据卷或 VHDX，没有执行 reset/prune。下一轮应优先调查用户目录与系统临时目录的原生行为差异，再评估经过验证的系统或软件修复；目前没有证明可靠的运行目录重定向方案。
