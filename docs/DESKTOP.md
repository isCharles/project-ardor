# Windows 桌面版

Ardor 桌面版是一个安装在 Windows 上的轻量客户端。双击桌面快捷方式即可打开；后端、数据库、AI 模型和用户数据仍在服务器上，不会随安装包分发。当前若连接本机自托管服务，需要先启动 Docker 中的 Ardor；未来连接公网服务器时，普通用户无需安装 Docker。

## 安装与连接

公开的 [桌面安装包仓库](https://github.com/isCharles/project-ardor/releases/latest) 提供 `Project-Ardor-Setup-<版本>-x64.exe`，不是 ZIP。安装向导提供简体中文和英语，默认随 Windows 语言选择，也可在安装开始时手动切换；允许选择用户/所有用户安装及目标目录，并创建桌面、开始菜单快捷方式。首次打开默认连接 `http://127.0.0.1:3000`；服务未启动时会看到连接页，可以重试或填写新的服务器根地址。设置页的“桌面应用”区域也可切换地址。公网只接受 HTTPS，本机 HTTP 仅允许 localhost / 127.0.0.1 / ::1。切换前需在系统对话框确认。

已安装的桌面版在启动约 20 秒后自动检查公开安装包仓库，随后每 6 小时检查一次；也可在头像上方的更新按钮或“设置 → 桌面应用”手动检查。发现新版本后自动下载，头像上方按钮显示下载进度环及百分比；尚未取得进度时显示加载动画。下载完成后按钮提示“安装并重启”，用户点击才退出应用并执行更新，不会悄悄重启。网页浏览器和未打包的开发客户端不显示安装按钮，原右上角重复提示已移除。离线连接页同样保留更新入口。发布时必须把安装包、`.blockmap` 与 `latest.yml` 放在同一公开 Release，缺少其中任一文件都不能声称完成了可更新发布。v0.4.0 及更早安装包内仍写着私有仓库地址，必须手动安装一次修复版才能切换更新源。

头像上方按钮复用既有 getInfo/checkForUpdates/onUpdateStatus/installUpdate 桥接，不修改 IPC、更新源、下载规则或可信来源检查，因此仅此 UI 调整不要求新桌面安装包。`frontend/tests/desktop-update-ui.mjs` 使用生产页面和合成桥接验证位置、进度、不确定进度、安装点击、就绪状态失效及错误反馈；不会下载或运行真实安装器，不能替代发布升级验收。

## 本地构建

v0.6.0 的自定义窗口顶栏依赖同版本服务器前端：后退/前进仅允许当前服务器的可信历史地址；折叠按钮仅操作对话栏；File/Edit/View/Help 调用固定原生菜单。IPC 仍检查当前主窗口与可信页面来源，不接受任意 URL、命令或脚本。窗口保留原生最小化/最大化/关闭及拖动区，生产配置保持 contextIsolation、sandbox 与 nodeIntegration=false。旧客户端没有新桥能力时，服务器前端保留页面内折叠按钮，不强行显示第二套窗口菜单。

`frontend/tests/desktop-chrome.mjs` 在独立临时用户目录运行生产 main/preload，以模拟接口验证真实窗口，不读取已安装客户端档案；Windows CI 使用默认沙箱执行。本机开发 Electron 曾在 `--version` 就退出 0x80000003，并有临时档案目录 Access denied 证据，不能把这种环境失败当作产品测试通过，也不得为发布关闭生产沙箱。

### v0.6.0 候选窗口验收现场（2026-10-13，尚未发布）

- PR #92 的初始提交 `bd7a703`：网页类型检查、lint、生产构建及合成 API 的 Edge 回归通过，15 个桌面单元测试通过。Windows CI `38375241574` 的 `desktop-window` 失败于启动 Electron，而不是界面断言；不能合并后声称桌面验证完成。
- 本机 Electron 44.5.1 直接 `--version` 返回 `2147483651`（0x80000003），仅加 `--disable-gpu` 同样失败；一次纯版本查询加 `--no-sandbox` 返回 v44.5.1。这只是定位证据，生产代码和提交的测试均未关闭沙箱。
- 一次隔离测试启动到 Inspector 后，报临时档案目录的 Singleton lock / GPUPersistentCache / DevToolsActivePort Access denied。只对下载的开发运行时和一次性合成档案做过窄范围 LPAC 权限实验，但重测仍不能启动；未触碰已安装应用、真实用户档案、系统目录 ACL，测试源码不保留该未证实的权限修改。
- 官方报告存在相似但未证实同因的情形：[Electron #51761](https://github.com/electron/electron/issues/51761)（目录 DACL 与 LPAC）、[#49167](https://github.com/electron/electron/issues/49167)（管理员运行）、[#54614](https://github.com/electron/electron/issues/54614)（44.x / Windows 11 STATUS_BREAKPOINT）。不得把这些报告当作本机根因或已解决证据。
- 后续应先收集完整 Windows CI 启动日志及对应系统/运行时版本，在仍受支持的稳定版本之间做可逆比较，确认正常沙箱启动后再验收窗口、合并与发版。不要用跳过测试、允许失败、系统级 ACL reset 或生产 no-sandbox 来过关。
- 21:15 的后续复验：官方维护版 44.7.0 的 zip 已与官方 SHA-256 清单匹配。项目目录运行仍失败，同一二进制复制到干净系统临时目录后 `--version` 正常；据此测试改为干净运行时副本与规范化临时档案，不改 ACL、不禁用沙箱。测试中的健康探测与业务 API 均为合成输入，实际 main/preload、HTTP 前端、原生窗口和 IPC 仍使用产品实现。已取得真实窗口通过证据；远程 CI、公开安装包及更新元数据仍须分别确认，不能据本机单次测试宣称已发布。

在 `desktop` 目录执行 `npm ci`、`npm test` 和 `npm run dist:win`，产物位于 `desktop/dist/`。源码仓库现已公开：Release 工作流构建和校验安装包、blockmap、latest.yml 后，直接上传当前仓库的 Release，使用自动提供的 `github.token`，无需跨仓库发布密钥。v0.6.1 起客户端从 `isCharles/project-ardor` 检查更新；v0.4.1 至 v0.6.0 仍指向旧分发仓库，删除旧仓库前必须手动升级这些客户端。不可覆盖已发布的标签或附件。

NSIS 在构建中需要运行临时安装器来生成卸载器。本机曾在项目目录运行该临时 EXE 时显示 `Error writing temporary file. Make sure your temp folder is valid.`，退出码为 2；同一文件移到系统临时目录后正常退出并生成卸载器。现在 `dist:win` 会在系统临时目录构建，核对完整安装包与 `latest.yml` 的 SHA-512 后再复制到 `desktop/dist/`。如果仍失败，先检查构建日志中保留的临时输出目录及 `%TEMP%` 的可写性，不要关闭 Windows 安全防护或把未完成的 NSIS stub 当作安装包发布。

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
