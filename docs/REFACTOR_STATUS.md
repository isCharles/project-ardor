# 领域重构收尾状态

核对日期：2026-10-20。基线：`main` 的 flashcard ownership 合并（#147）及其之前批次。目标见 [架构演进规划](ARCHITECTURE_EVOLUTION.md)，合同见 [领域合同与迁移基线](DOMAIN_CONTRACTS.md)。本文件只记录**已经核验**的结果，并明确列出未验证项，不把 CI 绿灯或目录整理当作整体完成。

## 1. 阶段状态

| 阶段 | 状态 | 主要交付 |
|---|---|---|
| A 基线与决策 | 已完成 | 演进规划、领域合同、术语/归属/兼容边界（#134 起） |
| B 产品主线与导航 | 已完成 | 稳定 destination ID、求职/学习入口、旧 URL 与侧栏偏好兼容（#134） |
| C 数据语义 | 基本完成 | V50 轮次归属（#135）、V51 来源/目标（#136）、V52 尝试历史与内容版本（#141）、进度查询后端归属（#144）、来源 Handler（#143）、记忆卡/复习归属（#147） |
| D 应用边界 | 基本完成 | 面试应用合同（#139）、Agent 执行/提案审计（#142）、面试记录/练习视图（#145）、任务 Handler（#137/#138）、模型协议注册（#146） |
| E 执行与客户端 | 基本完成 | 取消/引导跨重启恢复（#140）、执行关联与审计（#142）、模型协议适配（#146）；聊天错误码统一仍待做 |
| F 整理与扩展验收 | 本次完成可见部分 | 架构边界测试、完成状态文档、本机 Docker 部署与主线验收；长期包结构未整体搬迁 |

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

### 2.4 架构边界测试（本次新增）

`backend/src/test/java/com/projectardor/architecture/ArchitectureBoundaryTests.java`，源码级约束，随后端测试运行：

- 面试 Service 不导入自身 Web DTO；
- `learning.review` 不导入 `recap` 的实体/仓储；`recap` 不导入 `learning.review` 仓储；
- `llm`（平台模型层）不导入 `agent` 业务层；
- `aijobs` 任务内核不导入各业务域 Web DTO（`common.web` 异常除外）。

同时修复了 `llm → agent` 的反向依赖：连接测试的工具调用探测通过 `ToolCallingProbe` 接口由 agent 侧实现，平台层不再直接引用 `LangChainModelFactory`/`CareerAgentTools`。

## 3. 兼容与回退

- 未改写任何已执行 Flyway 迁移；V40–V53 均为增量迁移，旧列、旧 API、旧 URL、工具名、scope、资源 UUID、桌面协议保持。
- 领域包移动（如 `recap` → `learning.review`）不改变表名/列名/JSON 字段；回退应用版本即可，schema 与数据保留。
- `/app/evidence`、`/app/cards`、`/app/opportunities`、`/app/recaps`、`/api/memory-cards`、`/api/interview-recaps`、`/api/opportunities` 继续保留；新入口复用同一实现。
- 已知产品限制：删除学习计划会级联删除其尝试历史（V52 唯一新增级联，不可逆）；已写入文档。

## 4. 未验证项（不得视为已完成）

1. `learning-context-acceptance.mjs` 在本机隔离环境的独立重复运行（本地 429 限流；CI 已覆盖同一脚本）。
2. 浏览器端 `golden-path-ui.mjs` / `domain-navigation-ui.mjs` 的本机人工运行（CI 有对应作业，但本次未在本机跑浏览器）。
3. 真实模型供应商的质量、语音、计费与长时流式行为；测试只使用 fake LLM 与合成数据。
4. 多副本/跨实例行为：取消不跨实例传播，`complete()` 的 TOCTOU 只在单实例假设下安全（文档已声明）。
5. 备份/恢复演练、生产规模回填耗时与锁影响、桌面安装包手工回归。
6. E3 剩余：Agent 聊天错误码仍为粗分类（`AGENT_LLM_FAILED`/`AGENT_TEMPORARILY_UNAVAILABLE`），尚未统一到 `llm` 的结构化错误分类；`docs/MODEL_PROTOCOLS.md` 只承诺现有行为保持。
7. 目标包结构（`jobsearch`、`materials`、`assistant`、`platform` 等）只是长期方向，尚未整体搬迁；搬迁须按领域分批且行为不变。

## 5. 后续建议

1. 完成 E3 的聊天错误码统一（使用 `llm` 错误分类，保留旧错误码兼容层）。
2. 补备份/恢复演练与最小观测验证（requestId/runId/toolCallId/jobId 串联）。
3. 新增一种训练/来源/服务商时按 [面向扩展的具体合同](ARCHITECTURE_EVOLUTION.md#13-面向扩展的具体合同) 验证「无需改中央分支」。
4. 如继续目标包结构，先完成剩余领域所有权合同，再按领域做 `git mv` 搬迁 PR，每批全量测试 + CI。
