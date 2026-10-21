# 合成数据恢复验收

本探针用于架构迁移后的恢复回归，不是生产备份工具。它只创建合成账号、合成 PDF、假模型配置、报告和独立记忆卡；不会读取日常凭据或个人资料。数据库摘要相等只证明数据复制，不能代替文件字节、重新认证和解密后的实际调用验证。

## 入口与保护

入口为 backend/src/test/resources/recovery-acceptance.mjs，分 seed 与 verify 两阶段。必须明确设置 ARDOR_ACCEPTANCE_DISPOSABLE_DB=true、ARDOR_ACCEPTANCE_API_BASE 和 ARDOR_RECOVERY_MANIFEST。

- API 只接受独立本机 HTTP 端口，拒绝日常 3000/8080 及默认 HTTP/HTTPS 端口。这不能自动证明该端口是测试库，运行者仍须先核对容器连接与数据卷。
- seed 的 ARDOR_ACCEPTANCE_LLM_BASE 必须为专用假模型容器 ardor-recovery-model 的 8090 端口，不接受真实供应商地址。该容器运行仓库 fake-llm-server.mjs。
- manifest 是带固定合成标识的测试夹具，含本次随机合成账号凭据及资源 ID；只放在被忽略的 backend/target 内，不提交、不打印、不替换成真实账号。文件必须不存在，避免覆盖之前的验收证据。
- v2 探针要求 ARDOR_RECOVERY_DB_CONTAINER：seed 为 ardor-recovery-db，verify 为 ardor-recovery-restored。只查询本次合成用户及简历 ID 对应的 storage_key，不扫描日常数据库。
- verify 需要 ARDOR_RECOVERY_UPLOAD_ROOT，按数据库中的准确 storage_key 检查恢复目录；同时在 ardor-recovery-backend 容器中检查 ARDOR_RESUME_STORAGE=/app/data/resumes 下相同键的实际文件摘要。仅在其他路径找到相同字节不能通过。
- 假模型容器必须设置 ARDOR_FAKE_EXPECTED_API_KEY=fake-only，拒绝错误凭据。恢复后不重新提供密钥，必须保留 PERSONAL 来源、personalOverride、provider、baseUrl、model、keyHint，再通过一次不命中缓存的模型调用。不能用继承的管理员配置替代个人配置。

## 执行顺序

1. 建立全新的 PostgreSQL 17/pgvector、Redis、假模型和应用实例。与日常 Compose 分离，不挂载日常数据卷。将上传目录显式绑定到专用夹具目录；测试密钥只用于此隔离实例。内部网络的本机入口只转发到固定测试后端，不提供通用出站代理。
2. 使用 seed 阶段创建合成账号、加密的 fake-only 模型配置、上传并解析 PDF、生成历史分析、创建独立卡片并复习一次。保存合成 manifest。
3. 等待异步工作结束，停止夹具后端。备份数据库与上传目录，记录备份 SHA256、文件数/字节摘要和按表名/行内容排序的数据库全表摘要。先检查文件实际存在，导出命令成功不等于文件已导出。备份不包含 PostgreSQL 角色定义时，恢复端须预先创建对应的合成角色。
4. 在另一份新建空 PostgreSQL 恢复备份，先比较全表摘要。将上传文件复制到独立恢复目录，保持存储键与相对路径；使用全新 Redis 和原合成加密密钥启动应用，不连接原数据库，也不依赖旧 session。
5. 使用原 manifest 与恢复目录运行 verify。它重新登录并核对原 userId，核对简历/历史分析/卡片身份与复习进度，读取实际 PDF 字节，再以全新的岗位上下文发起分析，强制使用保存并恢复的加密配置，不能命中原报告缓存。
6. 最后停止且仅停止本轮的合成容器，移除空测试网络。保留本轮受忽略的备份和 manifest 供复核；不要以清理夹具为由 prune、重置 Docker 或删除日常卷。

```powershell
# 仅为已核对的独立测试栈；以下值不能指向日常服务。
$env:ARDOR_ACCEPTANCE_DISPOSABLE_DB = 'true'
$env:ARDOR_ACCEPTANCE_API_BASE = 'http://127.0.0.1:18083'
$env:ARDOR_ACCEPTANCE_LLM_BASE = 'http://ardor-recovery-model:8090'
$env:ARDOR_RECOVERY_MANIFEST = '<本次 backend/target 夹具目录>/manifest.json'
$env:ARDOR_RECOVERY_DB_CONTAINER = 'ardor-recovery-db'
node backend/src/test/resources/recovery-acceptance.mjs seed

# 完成上述冻结、备份、恢复、独立目录与空 Redis 启动后再执行，不能直接跳过恢复。
$env:ARDOR_RECOVERY_UPLOAD_ROOT = '<本次 backend/target 夹具目录>/restored-uploads'
$env:ARDOR_RECOVERY_DB_CONTAINER = 'ardor-recovery-restored'
node backend/src/test/resources/recovery-acceptance.mjs verify
```

## 2026-10-21 实测

以下记录属于旧 v1 探针：证明数据库复制、文件字节存在及功能可用，但没有严格证明文件存储键关联和个人配置凭据恢复。v2 已收紧这些断言，拒绝旧 manifest；完整 v2 恢复演练尚未重跑，不能沿用下列摘要宣称 v2 通过。

- 应用基线 main a7e75d0（含旧会话兼容、流式错误修复），隔离服务使用与日常服务相同的应用镜像，但独立数据库/文件/Redis及合成密钥。
- 源/目标数据库均为 50 张 public 表、77 行，全表汇总摘要 a86e11dfcf9d3e10b5db19837013ba73 一致；导出 SHA256 为 CB42D3B9E0815DA205D2A008A2890A8A0D660D8205CCFC192B5EAB4C06D96204。
- 复制并恢复 1 份实际 PDF；verify 的文件字节、空 Redis 后重登、历史报告、复习状态和新分析全部通过。恢复应用的 Flyway/JPA 校验与启动成功。
- 第一轮临时目录导出未取得文件，未算通过；改用明确的合成文件绑定目录并从新空库重新 seed 后完成本轮。未将缺失文件用重新生成的 PDF 冒充恢复文件。

本次不是生产数据恢复演练，不证明真实密钥的离线保管与找回、真实供应商可用性、计费、多副本一致性、生产规模恢复时间或桌面安装包人工兼容。日常 ARDOR_ENCRYPTION_KEY 必须由部署者安全备份；仅恢复数据库不能找回丢失的密钥。不得把合成密钥用于日常环境。
