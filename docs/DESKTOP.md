# Windows 桌面版

Ardor 桌面版是一个安装在 Windows 上的轻量客户端。双击桌面快捷方式即可打开；后端、数据库、AI 模型和用户数据仍在服务器上，不会随安装包分发。当前若连接本机自托管服务，需要先启动 Docker 中的 Ardor；未来连接公网服务器时，普通用户无需安装 Docker。

## 安装与连接

GitHub Release 提供 `Project-Ardor-Setup-<版本>-x64.exe`，不是 ZIP。安装向导提供简体中文和英语，默认随 Windows 语言选择，也可在安装开始时手动切换；允许选择用户/所有用户安装及目标目录，并创建桌面、开始菜单快捷方式。首次打开默认连接 `http://127.0.0.1:3000`；服务未启动时会看到连接页，可以重试或填写新的服务器根地址。设置页的“桌面应用”区域也可切换地址。公网只接受 HTTPS，本机 HTTP 仅允许 localhost / 127.0.0.1 / ::1。切换前需在系统对话框确认。

已安装的桌面版在启动约 20 秒后自动检查 GitHub Release，随后每 6 小时检查一次；也可在“设置 → 桌面应用”手动检查。发现新版本后自动下载，下载完成时在页面右上角提示；用户点击“安装并重启”才退出应用并执行更新，不会悄悄重启。离线连接页同样保留更新入口。此功能只在已安装版本中可用，开发模式不可检查安装包更新。发布时必须把安装包、`.blockmap` 与 `latest.yml` 放在同一 Release，缺少其中任一文件都不能声称完成了可更新发布。

## 本地构建

在 `desktop` 目录执行 `npm ci`、`npm test` 和 `npm run dist:win`。构建产物在 `desktop/dist/`；此命令生成**未签名的安装包**。不要覆盖已发布的同版本 Git 标签。新版本按 [版本规则](VERSIONING.md) 同步前后端、桌面包、锁文件和发布说明后，再发布新 GitHub Release。Release 发布事件会在 Windows Runner 构建双语 NSIS 安装程序，并把更新元数据上传到该 Release。`v0.3.0` 是首个包含公开桌面安装包的版本，但当时的附件未签名，不能称作“已验证的发布者”。

## Windows 可信签名接入

当前**没有**可信代码签名凭据。GitHub Release 工作流在完全未配置签名时会正常发布未签名安装包，并在日志中明确警告；部分配置则失败，避免误以为已经签名。配置完整时会强制签名和验签：签名失败、签名者不符、时间戳缺失，或 `latest.yml` 的 SHA-512 与安装包不符，都会在上传前失败。未配置签名时仍校验安装包、blockmap 和更新摘要。不会用自签名证书冒充可信签名，也不会重新上传或覆盖 `v0.3.0` 附件。

准备经可信 CA 签发、可用于 Windows Authenticode 代码签名且允许 CI 使用的 PFX 后，在仓库设置中配置：

1. Actions Secret `WIN_CSC_LINK`：PFX 文件的 Base64 内容（electron-builder 也支持安全的证书链接；不要把 PFX 提交到仓库）。
2. Actions Secret `WIN_CSC_KEY_PASSWORD`：该 PFX 的密码。
3. Actions Variable `WIN_SIGNER_THUMBPRINT`：预期签名证书的 40 位 SHA-1 thumbprint。这只是证书标识，不是私钥。证书更新时同步更新此值。

先在受信任的 Windows 环境中确认 PFX 的签名身份、有效期和私钥管理方式，并对 CI 的 Secret 访问权限做最小化配置。签名使用 SHA-256，electron-builder 在打包阶段同时签应用程序与安装器，再生成更新元数据；不要在生成 `latest.yml` 后另外改写 EXE。工作流随后用 Windows Authenticode 验证应用程序和安装器，确认时间戳、签名者和更新摘要后才上传。可本地运行 `npm run dist:win:signed`，再执行 `desktop/scripts/verify-signed-release.ps1 -DistDirectory desktop/dist -ExpectedThumbprint <证书指纹>`；签名密钥仅通过环境变量提供，不写进配置文件。

没有证书时可以继续发布，但 Release 说明与下载入口必须明确提示“未签名，Windows 可能显示未知发布者”；用户应先从项目官方 Release 下载并核对来源，再自行决定是否保留和运行。不要指导用户全局关闭 SmartScreen。首次可信签名发布必须使用新版本和新标签。签名可证明发布者身份与文件完整性，但新软件仍可能因下载量和信誉不足被 SmartScreen 提醒；不承诺签名后立刻消除所有警告。

## 当前限制

- Windows x64 首版；没有 macOS/Linux 安装包。
- 未配置可信证书时，安装包仍未签名，Windows SmartScreen 可能提示未知发布者；Release 会明确标注。
- 真实的跨版本升级必须在连续两个正式 Release 上验收。单次构建只能验证安装包与 `latest.yml` 生成，不能证明自动更新已成功安装。
- 客户端只保存服务器根地址，不保存模型密钥。账号会话由所连接服务器的 Cookie 管理；切换服务器可能需要重新登录。
