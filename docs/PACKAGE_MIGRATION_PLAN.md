# 后端包结构与命名迁移计划

日期：2026-10-20。状态：执行中。目标结构来自 [架构演进规划](ARCHITECTURE_EVOLUTION.md) 的领域地图；本文件把「旧包 → 新包」落成可执行批次。**只移动 package/import/文件与必要的类名位置，不改变任何行为、数据、接口或协议。**

## 1. 不变量（每批都必须保持）

- 数据库表名/列名、Flyway 迁移文件、索引、约束、删除级联不变；不新增迁移。
- HTTP API 路径、请求/响应 JSON 字段、状态码、旧 URL 别名不变。
- Agent 工具名、`@Tool` 描述、`ToolModule`/`AgentFeature`/`CARDS` 等 scope、提案与删除确认合同不变。
- 桌面客户端协议、窗口/导航 IPC、server.json 合同不变。
- JPA 实体**简单类名**不变（`@Entity`/JPQL 使用简单名），`@Table` 不变；`ddl-auto: validate` 必须继续通过。
- 测试只允许改包名/import；断言与测试数据不变。
- 每批：`mvn -o -q -DskipTests test-compile` → 全量 `mvn -o -q test` → push → CI golden-path 通过 → squash 合并。
- 回退：应用版本回退即可；schema、数据、客户端协议均不受影响。

## 2. 保留的基础设施包

| 包 | 保留理由 |
|---|---|
| `common` | 共享 web/security/json 基础设施 |
| `config` | Spring 装配配置 |
| `admin` | 管理端支持入口（规划明确定位为支撑入口，不是业务领域） |

`system.HealthController` 移入 `platform.health`；其余旧业务包在完成后不再作为顶层身份存在。

## 3. 批次与映射

### B1 小域归位（applications / calendar / profile / auth）

| 旧包 | 新包 |
|---|---|
| `applications.*` | `jobsearch.applicationactivity.*` |
| `calendar.*` | `scheduling.*` |
| `profile.*` | `platform.identity.profile.*` |
| `auth.*` | `platform.identity.auth.*` |

### B2 materials（resume / knowledge）

| 旧包 | 新包 |
|---|---|
| `resume.*` | `materials.resume.*` |
| `knowledge.*` | `materials.knowledge.*` |

### B3 platform（llm / speech / websearch / integrations / aijobs / background / usage / system）

| 旧包 | 新包 |
|---|---|
| `llm.*` | `platform.model.llm.*` |
| `speech.*` | `platform.model.speech.*` |
| `websearch.*` | `platform.model.websearch.*` |
| `integrations.*` | `platform.model.integrations.*` |
| `aijobs.*` | `platform.jobs.aijobs.*` |
| `background.*` | `platform.notification.*` |
| `usage.*` | `platform.usage.*` |
| `system.HealthController` | `platform.health.HealthController` |

### B4 模拟面试明确化

| 旧包 | 新包 |
|---|---|
| `interview.*` | `learning.practice.mockinterview.*` |

### B5 真实面试求职化

| 旧包 | 新包 |
|---|---|
| `opportunity.*` | `jobsearch.interview.*` |

### B6 recap 拆分（记录 vs 单题训练）

| 旧内容 | 新包 |
|---|---|
| `InterviewRecap*`、`InterviewRecapJob*`、`InterviewRecapQuestion*`、`InterviewRecapSource`、`InterviewMinutesService`、`InterviewRecapQueueService/Worker`、`InterviewRecordSourceAccess/View`、`InterviewDraftRetryHandler`、`InterviewReviewRetryHandler`、recap 的 minutes/recap 控制器与 DTO | `jobsearch.record.*` |
| `InterviewReplay*`、`InterviewRetest*`、`QuestionPractice*`、`ReplayVerdict`、`QuestionPerformance`、`ReplayEvaluation*`、replay/retest 控制器与 DTO | `learning.practice.question.*` |
| `MemoryCardController`、`MemoryCard*Request`、`MemoryCardResponse` | `learning.review.web.*` |
| `InterviewLearningOriginHandler` | `learning.plan.origin.*` |

### B7 learning 拆分

| 旧内容 | 新包 |
|---|---|
| `LearningPlan`、`LearningAttempt`、`LearningStatus`、`LearningSourceType`、`LearningOriginKind`、两个 Repository、`LearningPlanService`、`LearningPlanAgentSlots`、`LearningPlanCreationStore`、`LearningPlanGenerationGate`、`LearningContextInput/Resolver`、`LearningCreationCommand/JobHandler`、`LearningOriginHandler`、`AutonomousLearningOriginHandler`、plan 相关 web（Plan/Attempt/Arrangement/Origin/EvaluationQueue 控制器与 DTO） | `learning.plan.*` |
| `LearningEvaluationCommand/JobHandler` | `learning.practice.exercises.*` |
| `LearningProgressService`、`LearningProgressController` | `learning.progress.*` |
| `learning.review.*` | 保持不动 |

### B8 agent → assistant

| 旧内容 | 新包 |
|---|---|
| `Conversation`、`ConversationMessage`、`AgentConversationStore`、`ConversationTitle*`、`AgentContext*`、conversation 相关 DTO | `assistant.conversation.*` |
| `CareerAgentService`、`AgentRunStore`、`AgentMessageQueue*`、`AgentStreamLease/Progress`、`AgentProgressStore`、`AgentExecutionAuditStore`、`AgentToolTimings`、`AgentPermissionGrants`、`AgentToolApprovals`、`ApprovedAgentTools`、`LeasedHttpClientBuilder`、`ToolIntent*`、`AgentToolCallingProbe` | `assistant.execution.*` |
| `CareerAgentTools`、`capability.*` | `assistant.tools.*` |
| `AgentProposal*`、proposal 相关 DTO | `assistant.proposal.*` |
| `AgentMemory*`、`MemoryVectorStore`、`AgentMemoryService`、`MemoryConsolidation*`、`MemoryEmbedding*`、`MemoryMaintenanceService`、memory 相关 DTO | `assistant.memory.*` |
| `AgentModelProtocol`、`Anthropic/OpenAiAgentModelProtocol`、`LangChainModelFactory`、`ModelReasoningPolicy` | `platform.model.llm.agent.*` |
| 其余 agent 顶层 `AgentEngine/AgentFeature/AgentResponse/ReasoningDepth` | `assistant.*`（按职责归 conversation/execution） |

## 4. 每批交付清单

1. `git mv` 目录/文件，按映射更新 `package` 与全部 `import`（含测试、`@ComponentScan` 不需要改）。
2. `rg`/grep 检查旧包名残留（README/docs 中需要同步的例外见第 5 节）。
3. 编译、全量后端测试、`node scripts/check-version.mjs`（版本文件不动）。
4. push 分支、开 PR（base main），PR 描述列出移动文件数、残留检查、实际测试结果、未验证项；CI 绿后 squash 合并。
5. 更新本文件对应批次的「已完成」状态与 `REFACTOR_STATUS.md`。

## 5. 文档与架构测试同步

- `ArchitectureBoundaryTests` 的路径/包名同步到新结构，并新增「旧业务顶层包不存在」的断言（B1–B8 完成后）。
- `DOMAIN_CONTRACTS`、`ARCHITECTURE`、`MODEL_PROTOCOLS`、`AGENT_*`、`REFACTOR_STATUS` 中的包名引用同步更新；历史文档（`DOCKER_DESKTOP_SOCKET_RECOVERY` 等）不改。
- 不修改 `docs/ARCHITECTURE_EVOLUTION.md` 的目标地图；它是目标而非现状。

## 6. 完成标准

- B1–B8 全部合并；`com.projectardor` 顶层只剩 `admin`、`common`、`config`、`jobsearch`、`learning`、`materials`、`assistant`、`scheduling`、`platform`。
- 全量后端测试与每批 CI 通过；`ddl-auto: validate` 空库启动通过；本机 Docker 重建部署健康、Flyway 版本不变（仍为最新 V53）、旧路由/旧 API 兼容抽查通过。
- `ArchitectureBoundaryTests` 保护新路径；文档同步完成；未验证项明列。
