# 后端包结构与命名迁移计划

日期：2026-10-21。状态：已完成（B1–B8 全部合并，本机 Docker 部署核验通过）。目标结构来自 [架构演进规划](ARCHITECTURE_EVOLUTION.md) 的领域地图；本文件把「旧包 → 新包」落成可执行批次。**只移动 package/import/文件与必要的类名位置，不改变任何行为、数据、接口或协议。**

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
| `AgentModelProtocol`、`Anthropic/OpenAiAgentModelProtocol`、`LangChainModelFactory`、`ModelReasoningPolicy` | `assistant.execution.service.*`（实际执行：这些适配器使用 execution 内部的 stream lease / leased HTTP client，移动到 `platform.model.llm.agent` 会强制扩大可见性；平台层只保留通用网关与协议接口） |
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

## 7. 完成记录（2026-10-21）

| 批次 | PR | 内容 | CI |
|---|---|---|---|
| B1 | #150 | applications/calendar/auth/profile → 目标包 | golden-path pass 7m37s |
| B2 | #151 | resume/knowledge → `materials.*` | golden-path pass 7m31s |
| B3 | #152 | llm/speech/websearch/integrations/aijobs/background/usage/health → `platform.*` | golden-path pass 8m04s（另有 quota job pass） |
| B4 | #153 | interview → `learning.practice.mockinterview` | golden-path pass 8m52s |
| B5 | #154 | opportunity → `jobsearch.interview` | golden-path pass 7m39s；首次运行卡在端到端步骤，取消重跑后通过（未改代码） |
| B6 | #155 | recap 拆分 → `jobsearch.record`、`learning.practice.question`、`learning.review.web`、`learning.plan.origin` | golden-path pass 7m14s |
| B7 | #156 | learning → `plan` / `progress` / `practice.exercises`；补齐 B1/B2 测试目录 | golden-path pass 7m26s |
| B8 | #157 | agent → assistant 五个子域 + json；新增目标结构/旧包守卫 | golden-path pass 7m59s |

每批本地均以 `mvn -o -q clean test` 验证（全量后端测试通过），并修复了搬迁后才暴露的源码路径断言、ServiceLoader 资源（`META-INF/services/dev.langchain4j.spi.json.JsonCodecFactory`）与跨子包可见性问题。实际结构为：

```text
com.projectardor
├─ jobsearch/{applicationactivity,interview,record}
├─ learning/{plan,practice/{exercises,mockinterview,question},review,progress}
├─ materials/{resume,knowledge}
├─ assistant/{conversation,execution,tools,proposal,memory,json}
├─ scheduling/
├─ platform/{identity,model,jobs,health,usage,notification}
├─ common/ · config/ · admin/
```

与计划的差异：`auth` 落在 `platform.identity.auth`（避免与 `profile` 目录冲突）；Agent 模型适配器留在 `assistant.execution.service`（依赖 execution 内部的 stream lease / leased HTTP client，移到 platform 会扩大可见性）；`ArdorJsonCodecFactory` 留在 `assistant.json`；目标地图中的 `platform.storage` 未单独建立——当前唯一文件存储归属 `materials.resume.storage`（简历材料域），没有跨域共享存储实现，若将来出现跨域存储需求再提升到 `platform.storage`。数据库表/列、Flyway、HTTP API/URL、工具名与 scope、响应 JSON、桌面协议均未改动。

### 迁移后部署核验（2026-10-21）

- `main`（`3a9febf`）执行 `start.ps1`：`mvn package` + `docker compose up -d --build` 成功；四个容器运行，backend healthy、frontend/postgres/redis 正常。
- `/actuator/health` = `{"groups":["liveness","readiness"],"status":"UP"}`；`http://127.0.0.1:3000` = 200。
- Flyway 仍为 **v53**（53 条迁移，未新增/改写），`ddl-auto: validate` 启动通过。
- 导航中的 23 条旧/新路由全部 200（`/app/evidence`、`/app/cards`、`/app/opportunities`、`/app/job-search`、`/app/recaps`、`/app/replay`、`/app/learning/*` 等）。
- 未认证旧 API 均返回 401（路由存在）：`/api/memory-cards`、`/api/opportunities`、`/api/learning-plans`、`/api/interview-recaps`、`/api/conversations`、`/api/agent/memory`。
- 恢复 Docker 时只对两处运行时 socket 目录做了可逆改名备份（未删除、未 reset/prune、未动数据卷），Engine 29.8.2 恢复后完成上述构建。

未验证项：迁移后完整浏览器/桌面人工回归、真实模型/语音/计费行为、多副本限制、备份恢复演练、E3 聊天错误码统一，见 [领域重构收尾状态](REFACTOR_STATUS.md)。
