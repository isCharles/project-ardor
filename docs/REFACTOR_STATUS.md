# 领域重构收尾状态

核对日期：2026-10-21。基线：`main` 的包结构迁移收尾（B1–B8，PR #149–#157）。目标见 [架构演进规划](ARCHITECTURE_EVOLUTION.md)，合同见 [领域合同与迁移基线](DOMAIN_CONTRACTS.md)，包映射见 [后端包结构迁移计划](PACKAGE_MIGRATION_PLAN.md)。本文件只记录**已经核验**的结果，并明确列出未验证项，不把 CI 绿灯或目录整理当作整体完成。

## 1. 阶段状态

| 阶段 | 状态 | 主要交付 |
|---|---|---|
| A 基线与决策 | 已完成 | 演进规划、领域合同、术语/归属/兼容边界（#134 起） |
| B 产品主线与导航 | 已完成 | 稳定 destination ID、求职/学习入口、旧 URL 与侧栏偏好兼容（#134） |
| C 数据语义 | 基本完成 | V50 轮次归属（#135）、V51 来源/目标（#136）、V52 尝试历史与内容版本（#141）、进度查询后端归属（#144）、来源 Handler（#143）、记忆卡/复习归属（#147） |
| D 应用边界 | 基本完成 | 面试应用合同（#139）、Agent 执行/提案审计（#142）、面试记录/练习视图（#145）、任务 Handler（#137/#138）、模型协议注册（#146） |
| E 执行与客户端 | 基本完成 | 取消/引导跨重启恢复（#140）、执行关联与审计（#142）、模型协议适配（#146）；聊天错误码统一仍待做 |
| F 整理与扩展验收 | 已完成 | 架构边界测试、完成状态文档、本机 Docker 部署与主线验收、目标包结构迁移（B1–B8，#149–#157）与迁移后重建部署核验 |

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

- 未改写任何已执行 Flyway 迁移；V40–V53 均为增量迁移，旧列、旧 API、旧 URL、工具名、scope、资源 UUID、桌面协议保持。
- B1–B8 领域包迁移（含 `recap` 拆分、`agent` → `assistant`）不改变表名/列名/JSON 字段；回退应用版本即可，schema 与数据保留。
- `/app/evidence`、`/app/cards`、`/app/opportunities`、`/app/recaps`、`/api/memory-cards`、`/api/interview-recaps`、`/api/opportunities` 继续保留；新入口复用同一实现。
- 已知产品限制：删除学习计划会级联删除其尝试历史（V52 唯一新增级联，不可逆）；已写入文档。

## 4. 未验证项（不得视为已完成）

1. `learning-context-acceptance.mjs` 在本机隔离环境的独立重复运行（本地 429 限流；CI 已覆盖同一脚本）。
2. 浏览器端 `golden-path-ui.mjs` / `domain-navigation-ui.mjs` 的本机人工运行（CI 有对应作业，但本次未在本机跑浏览器）。
3. 真实模型供应商的质量、语音、计费与长时流式行为；测试只使用 fake LLM 与合成数据。
4. 多副本/跨实例行为：取消不跨实例传播，`complete()` 的 TOCTOU 只在单实例假设下安全（文档已声明）。
5. 备份/恢复演练、生产规模回填耗时与锁影响、桌面安装包手工回归。
6. E3 剩余：Agent 聊天错误码仍为粗分类（`AGENT_LLM_FAILED`/`AGENT_TEMPORARILY_UNAVAILABLE`），尚未统一到 `llm` 的结构化错误分类；`docs/MODEL_PROTOCOLS.md` 只承诺现有行为保持。
7. 迁移后的完整浏览器/桌面人工回归尚未在本机重跑（CI 有 golden-path 与 UI 作业）；桌面安装包与真实语音链路仍需手工验证。

## 5. 后续建议

1. 完成 E3 的聊天错误码统一（使用 `llm` 错误分类，保留旧错误码兼容层）。
2. 补备份/恢复演练与最小观测验证（requestId/runId/toolCallId/jobId 串联）。
3. 新增一种训练/来源/服务商时按 [面向扩展的具体合同](ARCHITECTURE_EVOLUTION.md#13-面向扩展的具体合同) 验证「无需改中央分支」。
4. 包结构迁移已完成；后续如需拆分 `common`/`config` 或调整子包，沿用同样分批 + 全量测试 + CI 的流程。
