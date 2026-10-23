# 领域重构收尾状态

## 2026-10-23 增量核验

- DSH 新增 `e67d89a..97eae20` 的执行图、追踪与滚动摘要已在 `chore/integrate-agent-graph` 整合并补回归。Java 21 干净源码 609 项，0 失败/错误、1 跳过；五组合成 API 浏览器回归和导航合同通过，带数据 V61→V64 升级及摘要归属通过。详见 [本批记录](INTEGRATION_REVIEW_2026-10-23_GRAPH.md)。此处为提交前本地证据，未代替该 PR 的 CI、合并或部署。
- 最新现场：四个日常容器均在运行，login 200、代理匿名鉴权 401、health UP；数据库为 V63。容器镜像无 revision 标签，不根据 tag 猜测准确源码。下方“3000/8080 无监听”是较早快照，已过时。
- DSH 整合 PR #182 已合并为 `2002448`。最终 head `d734f73` 七项 CI 全部成功，包括 Golden Path `39909647640`、真实 Docker 中文 PDF、前端/沙箱 Electron、迁移、版本与启动器。Java 21 隔离库完整后端 572 项，0 失败/错误、1 跳过。具体修复与计量范围见 [整合记录](INTEGRATION_REVIEW_2026-10-23.md)。
- 日常目录仍为 DSH 分支，未切换或覆盖。最近只读探测显示数据库/Redis healthy，但 3000/8080 无进程监听；不把临时验收容器算成实际服务恢复或部署。
- 下一阶段知识工具模块拆分见 [模块合同](ASSISTANT_TOOL_MODULES.md)，仍须核验该阶段自己的 CI 与部署，不以前一阶段通过替代。

核对日期：2026-10-21。当前核对基线：`main` 5f3b3ca（含 #160–#163、#170–#171）；历史部署记录保留原提交，不代表当前运行版本。目标见 [架构演进规划](ARCHITECTURE_EVOLUTION.md)，合同见 [领域合同与迁移基线](DOMAIN_CONTRACTS.md)，包映射见 [后端包结构迁移计划](PACKAGE_MIGRATION_PLAN.md)。本文件只记录**已经核验**的结果，并明确列出未验证项，不把 CI 绿灯或目录整理当作整体完成。

## 1. 阶段状态

| 阶段 | 状态 | 主要交付 |
|---|---|---|
| A 基线与决策 | 已完成 | 演进规划、领域合同、术语/归属/兼容边界（#134 起） |
| B 产品主线与导航 | 已完成 | 稳定 destination ID、求职/学习入口、旧 URL 与侧栏偏好兼容（#134） |
| C 数据语义 | 基本完成 | V50 轮次归属（#135）、V51 来源/目标（#136）、V52 尝试历史与内容版本（#141）、进度查询后端归属（#144）、来源 Handler（#143）、记忆卡/复习归属（#147） |
| D 应用边界 | 基本完成，仍有明确余项 | 面试应用合同（#139）、Agent 执行/提案审计（#142）、面试记录/练习视图（#145）、任务 Handler（#137/#138）、模型协议注册（#146）、学习快照（#170）、日程与重复规则快照（#171）；剩余耦合见第 6 节 |
| E 执行与客户端 | 基本完成 | 取消/引导跨重启恢复（#140）、执行关联与审计（#142）、模型协议适配（#146）、已知 HTTP/网络/解析故障共享分类与流终止（#161/#162）、迁移前会话兼容（#160）；未知故障仍诚实兜底 |
| F 整理与扩展验收 | 部分完成 | 架构边界测试、完成状态文档、本机 Docker 部署与主线验收、目标包结构迁移（B1–B8，#149–#157）；迁移后的完整客户端、恢复与扩展验收仍须逐项核对，不以目录搬迁宣称整体完成 |

## 2. 已实际运行的验证

### 2.1 本机 Docker 构建与部署（现有数据卷升级）

- 在 `main` 工作树执行 `start.ps1`：`mvn package`（0.6.2）+ `docker compose up -d --build` 成功。
- 四个容器运行：backend healthy、frontend Up、postgres/redis healthy；`http://127.0.0.1:3000` 返回 200；`/actuator/health` 返回 `"status":"UP"`。
- **升级路径**：现有 PostgreSQL 数据卷从旧 schema 由 Flyway 升级到 **version 53**（日志：`Successfully applied 53 migrations ... now at version v53`），未重建数据卷、未做破坏性操作。

### 2.2 本机隔离环境主线验收

一次性 PostgreSQL 17（pgvector）+ Redis + fake LLM 服务 + `ardor-backend-0.6.2.jar`（8082 端口）：

- `golden-path-acceptance.mjs`：**PASS** —— persistent async generation/evaluation/retry/ownership、recap → replay → learning/retest → calendar、Agent confirmation、recovery、concurrent isolation。
- `interview-minutes-acceptance.mjs`：**PASS** —— raw preservation、draft-first gate、owner isolation、optimistic editing、confirmation deduplication、immutable snapshots、background review。
- `learning-context-acceptance.mjs`：本机重复运行时在异步学习任务创建处收到 **429（分钟级限流）**，原因是同一隔离 Redis 在连续跑完 Golden Path 后配额窗口未重置；同一脚本在 CI（PR #147 head）的 golden-path 作业中通过。该现象记录为本地环境限制，不作为代码结论。
- 隔离环境在验证后已清理（临时容器/进程删除），未触碰日常开发数据库。

### 2.3 CI 证据

- #140（E1）：golden-path pass（6m24s，含真实 PostgreSQL 全量 `mvn test`）。
- #141（C3 尝试历史）：migrate pass、golden-path pass（8m36s，含 V51→V52 升级探针）。
- #147（记忆卡归属）：golden-path pass（6m23s，含全量测试与 acceptance 脚本）。
- #142–#146 合并前均通过各自路径过滤下的 CI 作业（详见 GitHub PR 记录）。
- #150–#157（包迁移 B1–B8）：每批 golden-path 均通过，7m14s–8m52s；B5 首次运行在端到端步骤卡住，取消重跑后通过，未改代码。

### 2.4 架构边界测试（本次新增）

`backend/src/test/java/com/projectardor/architecture/ArchitectureBoundaryTests.java`，源码级约束，随后端测试运行：

- 面试 Service 不导入自身 Web DTO；
- `learning.review` 不导入 `jobsearch.record`/`learning.practice.question` 的实体/仓储；两个练习/记录域不导入 `learning.review` 仓储；
- `platform.model.llm` 不导入 assistant 业务层；
- `platform.jobs.aijobs` 任务内核不导入各业务域 Web DTO（`common.web` 异常除外）；
- 2026-10-21 新增：旧顶层业务包不存在（`agent`/`recap`/`opportunity`/`interview`/`llm` 等），且目标包路径全部存在。

同时修复了 `llm → agent` 的反向依赖：连接测试的工具调用探测通过 `ToolCallingProbe` 接口由 agent 侧实现，平台层不再直接引用 `LangChainModelFactory`/`CareerAgentTools`。

### 2.5 包迁移后的重建部署核验（2026-10-21）

- `main`（`3a9febf`）执行 `start.ps1`：后端 `mvn package` 0.6.2、`docker compose up -d --build` 成功；四个容器运行，backend healthy。
- `/actuator/health` = `{"groups":["liveness","readiness"],"status":"UP"}`，`http://127.0.0.1:3000` = 200。
- Flyway 仍为 **v53**（53 条迁移，未新增/改写）；现有数据卷升级路径未被破坏；`ddl-auto: validate` 启动通过。
- 导航中的 23 条路由全部 200；未认证旧 API（`/api/memory-cards`、`/api/opportunities`、`/api/learning-plans`、`/api/interview-recaps`、`/api/conversations`、`/api/agent/memory`）均返回 401，说明路由仍在。
- Docker Engine 启动前因运行时 socket 残留失败；按恢复文档对两处 socket 目录做可逆改名备份后 Engine 29.8.2 恢复，随后完成构建部署；未删除备份、未 reset/prune、未动数据卷或 VHDX。详见 [Docker 恢复记录](DOCKER_DESKTOP_SOCKET_RECOVERY.md)。

## 3. 兼容与回退

### 2026-10-21 本机补充验收

- 候选后端连接独立 internal Docker 网络中的临时 PostgreSQL/Redis/fake LLM；固定上游的本机测试入口用于访问，不挂载日常数据卷、不调用真实模型。空库迁移及 JPA validate 启动成功，最新迁移为 53。
- interview-minutes-acceptance 与 learning-context-acceptance 本机通过：原始转录保存、确认快照、后台复盘、双用户隔离、明确来源/独立目标、卡片复习与批量删除范围。仅在合成库调高验收额度，未更改日常额度。
- 当前 main 前端重新 build 后，本机 Edge 跑通 learning-progress-live：实际认证 API/数据库的两轮面试、确认复盘、学习、重答、卡片复习及进度展示，无 API 响应 mock。domain-navigation-ui 也通过；该导航测试使用合成响应，不能替代前一条真实后端验证。首轮因本机旧 .next 产物未命中入口，重建后通过，未将旧构建失败归咎于新代码。
- 停止合成后端后导出数据库并恢复到另一份全新 PostgreSQL：50 张 public 表、148 行的汇总摘要均为 36cfed59122c749e6317ec7a8f478490。备份 SHA256 为 9B8C9E01D5F2A1FCEA7546EC7B415BF01F9D96C368A9F6276F9D483A9DAFCF9C。恢复库由候选 JAR 成功启动，53 条迁移校验成功，健康端点 200。
- 上述恢复仅证明本次合成数据库与应用映射：上传目录为 0 文件，不证明真实上传文件恢复；只使用合成密钥，不证明日常密钥托管、真实供应商配置或生产规模恢复。此前列出的相关未验证项不能因此整体划掉。
- 当前桌面端 17 项自动回归全部通过（服务器根地址、受信任导航、原生菜单、语言、连接探测和更新错误等），版本检查一致为 0.6.2；它不等于安装包人工回归，也不要求仅因后端内部修改重新发安装包。
- 后续补齐带实际材料的合成恢复：50 表/77 行摘要 a86e11dfcf9d3e10b5db19837013ba73 前后一致，1 份 PDF 实际字节通过；空 Redis 后重登仍为原 userId，历史分析/卡片身份和复习状态保留，不提供新 key 的全新岗位分析成功。步骤、备份 SHA256、失败边界和合成密钥限制见 [合成恢复验收](RECOVERY_ACCEPTANCE.md)。这不等于真实部署密钥找回或生产规模演练。
- 流式分类修复 #162 在 main a7e75d0 构建部署；容器与本机 JAR SHA256 均为 a088b5e2ba433288bd84ade89d341103652f49e40fc18e1c27d3207c2b46b791，后端健康、前端 200、Redis PONG、PostgreSQL ready；旧登录会话探针再次通过。上一个已验证镜像保留为 ardor-backend-before-stream-guard:bc91ac1。

- 未改写任何已执行 Flyway 迁移；V40–V53 均为增量迁移，旧列、旧 API、旧 URL、工具名、scope、资源 UUID、桌面协议保持。
- B1–B8 领域包迁移（含 `recap` 拆分、`agent` → `assistant`）不改变表名/列名/JSON 字段；回退应用版本即可，schema 与数据保留。
- `/app/evidence`、`/app/cards`、`/app/opportunities`、`/app/recaps`、`/api/memory-cards`、`/api/interview-recaps`、`/api/opportunities` 继续保留；新入口复用同一实现。
- 已知产品限制：删除学习计划会级联删除其尝试历史（V52 唯一新增级联，不可逆）；已写入文档。

## 4. 未验证项（不得视为已完成）

1. 本机完整 `golden-path-ui.mjs` 与桌面人工回归仍待做。0.7.0 发布分支因界面改版停用了 CI 中的完整 golden-path-ui、domain-navigation-ui、pipeline-ui 及 learning-progress-live；不能把 CI 绿灯当成这些浏览器路径仍有覆盖。本批已按新版菜单与重答显示恢复 learning-progress-live，其他三组仍需按当前交互修复并恢复。历史 learning-context 与浏览器验证的范围和限制见本机补充验收。
2. 备份恢复后的完整客户端操作仍待验证；实际合成 PDF 字节、重登、历史资源/复习状态及恢复加密配置的新调用已补 API 验收，不能扩大为真实用户资料/密钥恢复证明。
3. 真实模型供应商的质量、语音、计费与长时流式行为；测试只使用 fake LLM 与合成数据。
4. 多副本/跨实例行为：取消不跨实例传播，`complete()` 的 TOCTOU 只在单实例假设下安全（文档已声明）。
5. 真实部署密钥保管与找回、生产规模回填/恢复耗时及锁影响、桌面安装包手工回归；合成数据库与文件恢复已有独立验收。
6. E3 已补已知 HTTP/传输故障共享分类：鉴权、接口/模型不存在、限流、DNS、连接、TLS 等使用平台错误码；Agent 超时保留禁止自动重放的 AGENT_UPSTREAM_TIMEOUT。未知异常仍保留旧粗分类兜底，真实供应商及所有异常变体未验证。实际 SDK 合成 HTTP 探针与重试边界见 `docs/MODEL_PROTOCOLS.md`；CI、合并和部署以本批 PR 证据为准，不能仅据此文档视为已部署。
7. 迁移后的完整浏览器/桌面人工回归尚未在本机重跑（CI 有 golden-path 与 UI 作业）；桌面安装包与真实语音链路仍需手工验证。

## 5. 后续建议

### 包迁移后发现的登录兼容故障

2026-10-21 运行日志与合成旧会话探针复现：匿名健康检查与页面返回 200，但持有迁移前登录 cookie 的请求返回 500。Redis 的 JDK 会话载荷仍引用旧 ArdorPrincipal 和 UserRole 全限定类名，B1 的 package 搬迁使旧载荷无法反序列化。因此“未改 HTTP API / 数据库表”不足以证明已有登录会话兼容。

修复由 SessionSerializationConfig 提供 Spring Session 专用序列化器：只将上述两个已知旧类型描述符映射到字段及 serialVersionUID 校验一致的新类型，新写入使用新包名。不保留旧生产业务包、不清空 Redis、不取消密码变更后的会话失效检查。损坏载荷仍失败关闭；不将此兼容映射当成任意类型迁移机制。测试使用合成用户与角色，不读取真实会话或账号。

验证入口：SessionSerializationConfigTests（普通/管理员旧 SecurityContext、身份/角色/凭据快照、当前格式与损坏载荷）以及显式开启 ARDOR_SESSION_COMPAT_RUNTIME=true 的 LegacySessionRuntimeTests（只创建带 TTL 的随机合成会话键，请求本机前端代理并删除自身键；不存在的测试用户应返回 SESSION_EXPIRED 401，而不是反序列化 500）。运行中的旧版本已由后一探针复现 500；修复后部署验证单独记录，不将单元测试等同于部署成功。

1. 在真实供应商与长时流式验收时继续校验 E3 分类及重试边界；未知错误保留诚实兜底，不从供应商文本猜测已执行结果。
2. 保留可重复的合成恢复验收，继续核对真实部署密钥保管边界与最小观测验证（requestId/runId/toolCallId/jobId 串联）。
3. 新增一种训练/来源/服务商时按 [面向扩展的具体合同](ARCHITECTURE_EVOLUTION.md#13-面向扩展的具体合同) 验证「无需改中央分支」。
4. 包结构迁移已完成；后续如需拆分 `common`/`config` 或调整子包，沿用同样分批 + 全量测试 + CI 的流程。

## 6. 对原始 Goal 的收尾核对（不是完成声明）

| 原始要求 | 当前可定位证据 | 尚不能据此宣称完成的部分 |
|---|---|---|
| 求职/学习主线、真实/模拟区分、旧导航兼容 | 导航目录双端一致校验；domain-navigation-ui 的规范/旧路径、参数及账户偏好验证；#134 与后续导航调整 | 本机完整端到端浏览器与安装客户端人工回归尚未全部重跑 |
| 流程—轮次—记录—问题、来源/目标、自主训练和投递计数 | V50–V52；learning-context-acceptance、learning-progress-live；Golden Path 覆盖自主训练与计数；来源 Handler 测试拒绝错误表猜测 | 不把真实模型质量或未测历史数据分布计为通过；新增迁移仍需自己的升级证据 |
| 状态所有权与局部扩展 | 面试/记录/练习/复习/学习/日程应用合同；投递与记忆快照；模拟面试快照；领域任务 Handler、来源 Handler、双模型协议注册及边界测试 | CareerAgentTools 仍引用自动化、日程建议、知识资料和简历/纪要任务响应的 Web 类型；中央工具适配器仍较集中。不能因几个快照完成便划掉整个 D 阶段 |
| 消息—运行—工具—提案—结果、引导取消、幂等和授权 | V53 结构化关联；Golden Path 检查原指令/追加指令各保留一次、跨会话拒绝、重复引导；真实 PostgreSQL 取消及任务 attempt fencing；#160–#162 兼容与故障修复 | 当前单实例限制保留；真实供应商长时流与完整客户端交互不由 mock/单测替代 |
| 版本、备份恢复、旧客户端与实际部署 | #163 v2 独立恢复、存储键反向测试、验证凭据的假模型；17 项桌面自动测试及版本一致性；原有 IPC 能力降级 | 最新合并版本尚未部署日常服务；恢复后完整客户端操作、生产密钥恢复和安装包人工兼容未证明 |

### 本轮补充验证

### 0.7.0 后续：恢复真实学习进度浏览器验收

- 基于 `main` 1894da2，在独立工作树更新 `learning-progress-live.mjs`：使用“定制补强计划”悬停菜单，匹配当前重答次数展示，保留精确问题 ID、纪要引用和打开草稿不写入计划的断言；不改产品代码迎合旧选择器。
- 使用当前源码构建的 0.7.0 后端，在独立 PostgreSQL/Redis/假模型网络中由 Java 21 运行；空库 58 条迁移与 JPA validate 启动成功。不读取日常数据库、模型凭据或上传文件。
- 本机 Edge 无头浏览器通过：两轮真实面试来源、原始转录的纪要确认与复盘、学习计划、重答、记忆卡复习；刷新及 `/app/evidence` 旧入口仍展示同一来源的关联；另一合成账号访问该面试来源返回 404，自身进度为空。所有 API 响应来自真实合成后端，Playwright 仅转发到该后端，不制造响应。
- 已重新接入 Golden Path CI 并加入失败截图。此处本机结果不替代本批 PR 的最终 CI 结果，也不表示完整聊天、导航、任务及桌面人工验收已经恢复。
- 本机前端 typecheck、生产 build 与 lint 通过（lint 保留一条已有的简历预览普通 img 警告）；本批不修改后端生产代码、schema 或桌面协议，不据此宣称已经部署日常服务。

### 最终纪要简化工作流（候选版本，部署另行核对）

- 按最新用户要求，新页面与 Agent 使用最终纪要入口，持久化后直接异步规范化，不再生成 ASR 初稿或等待再次确认。V59 区分模式与任务结果链接，旧记录保持原模式。具体合同、回退限制见 `INTERVIEW_MINUTES_WORKFLOW.md`。
- 本机 Java 21 干净源码完整套件：517 项，0 failures、0 errors、1 skipped（需显式开关的旧会话运行时探针）。最初尝试存在 Docker DNS、未开启合成数据库测试门控/地址及测试角色建 schema 权限问题；修正隔离测试环境后完整重跑，未排除失败用例或放松产品网络策略。两个公共地址分类测试使用测试容器内固定公共域名映射，不是外网供应商连通性证明。
- 实际执行全部迁移及带旧任务的 V58→V59 升级探针；旧文本、归属与旧任务链接保持，新模式链接稳定且不重复投影。
- Edge 真实前端/Java 21 合成后端浏览器通过：提交最终纪要、直接结果展示、保存文本、刷新、重复提交幂等、面试/轮次、通知链接和双用户隔离；强制模型失败后改好配置，点击重试复用保存文本成功。仅调高一次性验收库的额度，不修改日常配额。
- 前端生产构建、typecheck、lint 通过（保留已有 img 警告）；相关队列、Agent 与意图策略测试另行通过。最终 CI、合并与本机日常部署尚需分别核对；不宣称整体 Goal 完成。

### 模拟面试应用视图（已合并 #179）

- HTTP 与 Agent 共用模拟面试所有者限定的 session/progress/evaluation 视图，HTTP 保留 10/6/7 个原字段及嵌套题目/LeetCode 元数据；不新增 schema、接口路径或工具 scope。删除仍只产生已有的目标确认提案，不迁移或删除记录。
- 合同测试覆盖字段顺序与 null、readiness、嵌套 JSON/criteria 不可变、用户归属、取消刷新、答题顺序、HTTP/Agent 相同输入及应用视图。Spring 事务标志探针断言：创建题目/评价的模型回调无活动事务，保存阶段有事务，返回视图前已结束；不是新增真实供应商测试。
- Java 21 干净源码、独立合成 PostgreSQL 的完整套件 532 项：0 failures、0 errors、1 skipped（旧会话运行时探针未开关）。新增合同/架构/Agent 回归均包含在其中，报告留在独立工作树的忽略目录 `backend/target/mock-views-suite-reports`；公共域名分类用例仍采用仅测试容器的公共地址映射，不声称真实供应商连通。
- 简历/资料来源的跨域实体读取、剩余 Web 类型及中央工具装配仍是未完成边界，不因本批改动宣称整体重构完成。CI 和部署结果需另行核对。

### 知识资料应用视图（已合并 #180）

- 应用服务、HTTP 与 Agent 改用资料领域的不可变结果快照，Web 仅映射原字段；不改检索算法、工具名/scope、数据库与通知链接。上传仍接收 MultipartFile，其他简历跨域实体与中央工具边界仍待后续处理。
- 新增合同回归验证文档 7 字段、收录 2 字段、检索 6 字段及 null/分数兼容、收录列表快照、认证用户传参；保留混合排序并补用户限定读取/删除、提交后索引、嵌入失败降级与 limit 下限探针。架构守卫阻止资料服务和助手重新导入知识 Web DTO。
- 本批无 schema 变更；回退应用代码即可使用原数据，不需要修改或删除记录。测试、CI 和实际部署证据分别补记，不以候选实现宣称整体完成。
- 本机 Java 21 干净源码全量测试 541 项：0 failures、0 errors、1 skipped（旧会话运行时探针未开启）；包括独立合成 PostgreSQL 并发测试，报告在忽略目录 `backend/target/knowledge-views-suite-reports`。两处公共域名分类测试仍使用测试容器内地址映射，不是真实供应商连通测试。临时数据库与网络已移除；日常原生服务保持运行，CI 与阶段部署待核验。

### 简历准备上下文（候选版本）

- 前置边界 #179（合并 `47c3fe2`，Golden Path `39716957327`）和 #180（合并 `38f9054`，Golden Path `39740822043`）均已核对成功。本机日常原生服务未切换；不将 CI 或临时容器算作日常部署。

- 模拟训练不再导入资料域 ResumeAnalysis 实体，通过具名且按用户授权的只读应用方法获得脱离持久层的分析快照。报告 HTTP 保持原六字段；不变更评分、工具名、schema 或历史资源 ID。
- 合同测试覆盖双用户分析 ID 读取、嵌套 JSON/null 快照、原报告 JSON 与只读事务声明；模拟训练验证传入快照内容及原分析 ID、模型调用在事务外、外部分析在模型与会话写入前拒绝。架构守卫禁止模拟训练服务依赖简历实体或仓储。
- 无数据迁移，回退应用代码不需改动数据；助手侧简历实体与其他中央耦合仍待后续处理。完整测试、CI 与部署结果需分别核验。
- Java 21 干净源码与一次性合成 PostgreSQL 完整回归 546 项：0 failures、0 errors、1 skipped（旧会话运行时探针未启用）；报告在忽略目录 `backend/target/resume-context-suite-reports`。测试容器内公共地址分类使用固定公共域名映射，不证明真实供应商连通；临时数据库及网络已清理，未动日常卷。CI 与部署尚待核验。

### 先前已合并边界验证

- 独立工作树 head 76bf484（#171 完整变更，合并结果为 5f3b3ca）在 Java 21 / Maven 3.9.11、全新合成 PostgreSQL 下执行完整 `mvn -o -q test`：500 项，0 failures、0 errors、1 skipped。跳过项为需额外显式开关的 `LegacySessionRuntimeTests`，不计为本轮运行时旧会话验证。
- 首轮 3 个错误属于测试环境：internal 网络无法解析公共域名，且只挂载 backend 导致双端导航文件缺失。改为测试专用可解析网络和完整独立工作树挂载后，完整重跑通过；未排除用例、未放松产品出站策略。临时数据库和空测试网络已移除，不动日常卷。
- 前端在同一独立工作树执行 `npm ci --offline`、typecheck、lint、navigation-catalog 及生产 build 均通过。初次因未安装依赖/工作树写入权限未启动成功，修正环境后重跑，不计为代码缺陷或通过证据。
- Edge 无头浏览器执行 workspace-ui、domain-navigation-ui、learning-operation-ui 全部通过。它们运行真实前端，但 API 为合成响应；不等同于本轮新跑了完整真实后端 golden-path-ui。
- 桌面 `npm test` 17 项通过；`node scripts/check-version.mjs` 确认全部构建版本为 0.6.2。首次误用不存在的校验脚本未成功，随后定位并运行仓库真实入口通过。
- #170 CI 39588058531、#163 CI 39590084407、#171 最终 head CI 39591798729 均成功；#170、#163、#171 已合并。CI 的实际主线和浏览器证据与本机合成浏览器结果分别记录，不互相冒充。
- 日常目录仍有另一批未提交的简历 v3/界面改动，当前使用原生开发服务；本轮未切换该目录分支、未覆盖其文件、未重启服务或重建日常容器。最终部署仍是原 Goal 必需项，不能把测试容器启动算成完成部署。
