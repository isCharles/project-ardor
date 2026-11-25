# Ardor Agent 工程审计与 SDD 提案

> 状态：**Accepted，AEBB-001 已实现，验证中；业务行为保持不变。**
>
> 审计基线：`main@79605b7`（2026-11-25）。本文只描述该提交的代码与静态证据，不把进行中的 PR、本机运行、合成模型测试或历史文档当作已交付能力。PR #328 的删除阻塞与断线恢复不属于本审计基线。
>
> 批准记录：2026-11-25，用户指示“继续就好，直接干完”，授权按 AEBB-001 实施、验证并交付；不授权生产部署。

## 1. 结论摘要

Ardor 已具备较扎实的工程底座：模块化单体、统一 Agent 入口、用户级授权、业务 Service 复用、持久化运行与消息、确定性计划验证、结构化 trace、Redis 速率准入、版本化评测集和覆盖真实数据库的 Golden Path。它已经超过“能调用模型和工具”的个人项目阶段。

距离可重复、可度量、可持续迭代的 Agent 工程系统，关键缺口不是再造一个 Runtime，而是：

1. **运行完成与任务成功尚未形成统一、可审计的证据链。** span `COMPLETED` 不代表业务正确；当前 Outcome 读回主要覆盖日历。
2. **评测已有骨架，但缺少成本、延迟、失败成本、语义校准和持续样本回流。** 不能据此宣称真实模型质量。
3. **Runtime 的核心保证仍是单实例。** 多副本前必须增加执行租约、fencing 与可靠撤销，不能只增加容器副本。
4. **测试数量充足，但 Test Pyramid 中间层偏薄。** 大量单元测试和昂贵 E2E 之间缺少默认运行、快速反馈的真实 PostgreSQL/Spring 集成层；也没有覆盖率门禁。
5. **运行事实、trace、评测报告与业务结果之间存在口径和生命周期缺口。** 需要先把证据合同做稳，再建设仪表盘和复杂实验平台。

建议第一阶段只做 **Agent Evaluation Evidence Baseline v1（AEBB-001）**：固定数据集与留出集、统一指标和未知值口径、记录延迟与成本、让 CI 留存报告、增加一个薄的真实数据库集成测试层，并用 ADR 明确 Runtime 职责。该阶段不改业务行为、不引入新 Agent、不改数据库 schema，也不启用真实模型付费调用。

## 2. 审计范围、方法与证据限制

### 2.1 范围

- 产品与架构：`README.md`、`docs/ARCHITECTURE.md`、`docs/AI_GUIDE.md`、`docs/DOMAIN_CONTRACTS.md`。
- Agent Runtime：`CareerAgentService`、`AgentRunStore`、`AgentRunTracer`、`ApprovedAgentTools`、`AgentPlanner`、`PlanningAgentTools`、`AgentPlanStore`。
- 安全与可靠性：认证归属、工具确认、删除提案、队列、取消/引导、速率与容量准入。
- 可观测性：结构化日志、Micrometer、PostgreSQL trace、可选 OTel。
- 评测与实验：`scripts/agent-eval-*.mjs`、评测集、Golden Path 与浏览器测试。
- 测试基础设施：后端 `pom.xml`、测试类、前端 `package.json`、CI workflows。

### 2.2 方法

采用“代码 → 配置 → 测试 → 文档 → 实际 CI”交叉核对。状态含义：

| 状态 | 含义 |
| --- | --- |
| 已实现 | 基线代码存在可执行实现，并有对应测试或运行入口 |
| 部分实现 | 已有实现，但覆盖范围、保证或闭环不完整 |
| 未实现 | 未找到生产实现或有效测试入口 |
| 建议实现 | 本审计提出的改进，当前不存在 |

### 2.3 限制

- 本阶段没有重跑整个后端测试套件、真实模型评测、负载测试或生产验收；不把历史记录改写成本次结果。
- 静态规模快照：242 个 `backend/src/test/java/**/*Tests.java`、1,387 个 `@Test` 方法、39 个 `frontend/tests` 脚本。这是数量证据，不是覆盖率或质量结论。
- 文档与代码冲突时以当前代码为准，冲突单列于第 10 节。
- 本审计不读取 `.env`、凭据、简历正文或生产私有数据。

## 3. 当前架构与工程能力

### 3.1 架构概览

当前系统是 **Spring Boot 模块化单体 + Next.js 前端 + PostgreSQL/pgvector + Redis + LangChain4j**。后端关键版本为 Java 21 CI 目标、Spring Boot 4.1.1、LangChain4j 1.19.0；本机 JVM 版本较新，不能替代 Java 21 CI 编译证据。

| 层 | 当前职责 | 证据 | 状态 |
| --- | --- | --- | --- |
| 交互层 | App Router 聊天、SSE、恢复轮询、工具/确认展示 | `frontend/src/app/app/page.tsx` | 已实现 |
| Agent Runtime | 上下文装配、模型调用、工具循环、流式输出、停止与引导 | `CareerAgentService.java:49` | 已实现，单实例保证 |
| 工具适配层 | 将业务能力包装为模型工具；统一执行与授权围栏 | `ApprovedAgentTools.java:34`、`CareerAgentTools` | 已实现 |
| 计划层 | 3–6 步计划、跨模块准入、当前步 fence、真实工具结果验证、有限重规划 | `AgentPlanner.java:37`、`PlanningAgentTools` | 部分实现 |
| 业务域 | 学习、面试、投递、简历、知识库、日历等拥有自己的状态与授权 | `docs/DOMAIN_CONTRACTS.md` | 已实现 |
| 持久化 | Flyway、运行/消息/计划/工具调用/trace；队列与恢复快照 | `AgentRunStore.java:30`、`db/migration` | 已实现 |
| 技术设施 | Redis Session、QPM/TPM 准入、本地并发上限、Micrometer、可选 OTel | `ModelCallCapacity`、`docs/OPENTELEMETRY.md` | 部分实现 |
| 质量设施 | 单元/契约测试、真实栈 Golden Path、浏览器脚本、fake-model 评测 | `.github/workflows/golden-path-acceptance.yml` | 部分实现 |

架构判断：现有 `CareerAgentService` 是有界工具调用循环，不是通用 Graph/Workflow 引擎。`docs/AGENT_GRAPH_ARCHITECTURE.md` 属于历史实验设计；当前自然语言请求统一进入聊天 Runtime。继续引入第二个 Agent Runtime 会扩大授权、恢复和评测面，现阶段没有收益证据。

### 3.2 安全与业务边界

**已实现且应保留：**

- HTTP 入口从认证上下文取得用户；业务查询同时绑定资源 ID 与用户 ID。
- 模型输出、网页、上传文件不可信；工具适配器不能替代业务 Service 授权。
- 删除工具只产生带真实目标的提案；最终删除走受鉴权的业务接口。
- 普通写入有具体提案与用户确认；聊天口令和模型自述不能代替批准。
- 服务端工具目录、权限与确认策略限制模型可调用能力。
- `ArchitectureBoundaryTests` 以源码规则保护模块依赖方向与本地工具归属。
- 出站地址安全策略和凭据边界已有专门实现。

**仍需加强：**工具“读/写/破坏性/只提案”的事实分散在注解、`ToolCatalog`、策略集合与提案目录中，尚未形成单一机器可读的 capability manifest。源码架构测试能发现部分越界，不能证明所有运行时授权路径。

## 4. SDD、测试与评测 Gap Analysis

### 4.1 SDD 与决策管理

| 能力 | 状态 | 证据与缺口 |
| --- | --- | --- |
| 规格先行 | 部分实现 | 有需求文档、实施计划与验收记录；未发现统一的 Spec / Clarify / Tasks 模板和状态流 |
| 架构决策持久化 | 部分实现 | 有 `ARCHITECTURE.md`、`AGENT_RUNTIME.md`、迁移合同等；缺少 ADR 编号、决策状态、替代方案与复核触发条件 |
| 变更影响分析 | 部分实现 | Flyway、发布、恢复文档较完整；Spec 与架构冲突时的正式变更确认流程未固化 |
| 任务追踪 | 部分实现 | 仓库存在阶段计划，但规格、任务、测试、验收结果之间没有稳定关联 |
| 文档真实性 | 部分实现 | 已发现留出集数量、准入状态、历史 Runtime 等过期表述 |

建议采用最小 SDD，而非新建需求平台：一个 Spec 文档包含背景、范围、非目标、验收标准、风险与待确认决策；一个 Task 列表关联测试和验证证据；重要跨模块决策使用短 ADR。工具可以后置，合同先落地。

### 4.2 Test Pyramid

| 层级 | 当前证据 | 状态 | 主要缺口 |
| --- | --- | --- | --- |
| 单元/纯领域测试 | 1,387 个 `@Test`；策略、状态机、评分器、权限、取消竞态等 | 已实现 | 数量不能证明关键路径覆盖；无 JaCoCo 门禁 |
| 组件/契约测试 | MockMvc、脚本契约、源码架构规则、fake-model SSE | 已实现 | 不少验证依赖源码字符串或合成轨迹，可能漏掉真实装配问题 |
| 快速真实集成测试 | `AgentPlanFencePostgresTests`、`QuotaCounterStorePostgresTests` 等用独立 schema + Flyway + 真实 JDBC | 部分实现 | 多数由 `ARDOR_ACCEPTANCE_DISPOSABLE_DB=true` 显式启用；未形成每次后端测试默认运行的薄切片 |
| E2E/验收 | Golden Path 启动真实 Spring、PostgreSQL/pgvector、Redis 与 fake 模型；驱动 REST/DB 读回 | 已实现 | 成本高、反馈慢；9 个日历 Outcome case 不能代表全部业务域 |
| 浏览器回归 | 39 个独立脚本，含聊天、trace、恢复等 | 部分实现 | 缺少统一测试命令与稳定的环境/数据隔离说明 |
| 真实模型质量评测 | `scripts/agent-eval.mjs` 手动授权入口 | 部分实现 | CI 不运行；无固定样本窗口、校准集和持续趋势 |
| 安全测试 | 权限、归属、提案、出站策略、跨用户测试 | 部分实现 | 未见系统性依赖扫描/SAST/DAST 门禁与定期攻击面复核 |
| 性能/可靠性测试 | 单次慢响应诊断、并发/超时单测、恢复验收 | 未实现 | 未见 k6/JMeter/Gatling、容量基准、p95 SLO、故障注入或长期 soak |

**Test Pyramid 判断：**底层丰富、顶层昂贵，中间层不足。下一步不应把更多业务域立即塞进 Golden Path，而应先提供 3–5 个默认运行的真实 PostgreSQL 集成测试，覆盖运行完成事务、用户隔离、trace 写入和取消终态。

### 4.3 Output / Outcome / Trajectory Evaluation

| 维度 | 当前实现 | 状态 | 缺口 |
| --- | --- | --- | --- |
| Output：答案结构与质量 | `agent-eval-judge.mjs` 的 `rubric.v1` 检查非空、短语、禁止表述、工具与终态 | 部分实现 | 主要是词法/规则，未经人工校准，不能衡量语义正确性 |
| Outcome：业务终态 | `agent-eval-e2e.mjs` 通过 REST 读回日历状态；另检查禁止副作用 | 部分实现 | 当前只覆盖 9 个日历相关案例；学习、面试、记忆、知识库缺少终态断言 |
| Trajectory：过程合规 | 期望/禁止工具、真实执行、提案、安全拒绝、参数、重复写、结果预览 | 已实现 | 仍是单次 trace 规则；没有长期样本分布、原因聚类与自动回流 |
| Execution Success | 报告保存 `runStatus`、工具状态和环境错误 | 部分实现 | 尚无独立指标明确表达“运行机制按合同完成” |
| Task Success | `metrics.taskSuccess` 排除环境错误后按业务断言评分 | 已实现 | 业务断言覆盖面窄；不能将执行失败与环境失败混为一类 |
| 失败成本 | 真实模型脚本已计算逐 case `costUsd` 和已知汇总 `knownCostUsd`；公共报告丢弃逐 case 成本 | 未实现 | 没有统一的失败任务成本、环境错误成本、每成功任务成本或成本完整度门禁 |

现有 `AgentTraceStore` 明确把 trace 写成可选诊断数据，写失败不影响业务结果；因此 Trace 完整性也不能等同于任务成功。未来报告应分别记录 `runCompleted`、`executionContractPassed`、`taskSuccess` 和 `businessReadBackAvailable`。

### 4.4 数据集、指标与实验治理

**已实现：**

- `scripts/agent-eval-cases.json` 有 `datasetVersion=2026-10-28.agent-eval.v3`、30 个案例、representative/challenge 分层和 4 个 `heldOut=true`。
- 报告固定代码 SHA、dataset/prompt/tool-schema 哈希、模型、有效参数、样本数和逐 case 结果。
- 指标使用显式分子/分母；Unknown Usage 保持 `null`。
- `agent-eval-diff.mjs` 支持历史 diff；demo 用植入缺陷的合成轨迹验证评分器。

**未实现/部分实现：**

- 数据集没有独立的不可变 split manifest；开发集与留出集缺少变更审批、泄漏检查和冻结哈希。
- `agent-eval-report.mjs` 未保存逐 case `durationMs`，尽管评分观测里已有该值；没有 p50/p95、总成本、失败成本、每成功任务成本。
- 没有固定真实模型评测窗口、重复采样、随机种子/温度策略记录、样本量门槛或人工标注一致性。
- 没有统一的 Workflow / ReAct / Plan-and-Execute 对照实验合同。当前统一聊天 Runtime 是默认选择，不应为了实验重新引入关键词选择的第二 Runtime。
- 没有生产失败样本脱敏入库、人工复核、版本化晋升和回归门禁。
- CI 没有上传 evaluation report artifact；只上传浏览器失败截图。

## 5. Runtime 职责与可复用基础设施

### 5.1 Runtime 当前职责

| 职责 | 现状 | 边界 |
| --- | --- | --- |
| 上下文与记忆装配 | `AgentContextBuilder` 统一处理提示、记忆、引用与压缩 | 不消费其他域 HTTP DTO |
| 模型调用与工具循环 | LangChain4j AI Services；工具轮次上限 8，顺序工具上限 12 | 有界循环，不是持久工作流引擎 |
| 计划与验证 | Java 校验结构、工具、模块和 verify；按真实结果推进 | 只作用于当前进程中的工具循环 |
| 权限与确认 | 中央工具围栏 + 具体提案 + 业务接口授权 | 模型不能自我批准 |
| 执行归属与停止 | `activeStreams` / `AgentStreamLease`、用户锁、迟到回复拦截 | **单实例**；无跨实例撤销总线 |
| 持久化与恢复 | run/message/progress/queue 持久化；历史可读恢复 | 不持久化模型对话位置、工具循环栈或审批状态，不能从中断处自动续跑 |
| 可观测性 | 产品 trace + 指标 + 日志 + 可选 OTel | trace 丢失不改变业务结果；语义正确性不在 trace 内 |

关键证据：`CareerAgentService.java:76-77` 使用进程内 `activeStreams` 和 `userLocks`；`docs/AGENT_RUNTIME.md:62-68` 明确列出多副本 TOCTOU、派发与撤销限制。`AgentRunStore.complete()` 保存最终交换与运行完成，但中断恢复只恢复可读快照，不恢复模型执行。

### 5.2 计划层

`AgentPlanner` 已提供有价值的确定性约束：3–6 步、至少两个模块、已注册工具、机器可检查 verify、当前步顺序、失败后有限重规划。`doneWhen` 只供人阅读，验证使用真实工具结果，而不是模型声称“完成”。

缺口：`AgentPlanStore.chargeModelCall()` 位于 `AgentPlanStore.java:266`，但未找到生产调用者；持久化的 `model_call_ceiling` 当前不是实际预算围栏。第一阶段只记录该事实，不擅自改变执行行为。是否启用必须另开 Spec，明确超限终止、重规划计费与用户提示。

### 5.3 可复用基础设施

| 资产 | 复用方式 | 不应做的事 |
| --- | --- | --- |
| `AgentRunStore` / `messages` / `agent_tool_calls` | 关联运行、工具尝试、审批与业务结果 | 为评测另造一套运行状态 |
| `AgentTraceStore` / trace API | 逐 case 拉取完整分页轨迹，生成诊断证据 | 把 trace 当业务完成真相 |
| `AgentRunTracer` / Micrometer / OTel | 保留产品 trace 为权威，技术 trace 做关联 | 重复实现一套 span 生命周期 |
| `agent-eval-cases.json` / assertions / report / diff | 扩展现有评分与报告合同 | 新建平行评测框架 |
| Golden Path disposable stack | 运行真实 DB/API/fake-model 验收 | 把 fake model 结果标成真实模型质量 |
| 独立 schema + Flyway 测试模式 | 抽成薄的默认集成测试层 | 所有单测都启动完整应用 |
| `ArchitectureBoundaryTests` | 增加少量高价值边界规则 | 用源码字符串测试替代行为测试 |
| 自适应面试 Java 状态机 | 作为“Java 拥有状态与策略、LLM 只生成内容”的域内参考 | 把它的领域状态机强行提升为所有 Agent 的全局 Runtime |

### 5.4 Trace 与数据生命周期

产品 trace 默认保留 30 天，`AgentTraceStore.cleanup()` 只清理 `agent_trace_spans`。消息、运行 payload、提案和审计事实没有进入同一清理策略。trace 属性有 4,000 字符上限并避免保存原始 prompt/参数/正文，这是良好起点；仍需统一业务数据保留、删除、导出与评测样本脱敏规则。

`docs/TRACE_STORAGE_CONVERGENCE.md` 指出当前工具 span 挂在 LLM span 下，LLM 时长可能包含工具时间。归因前应先修正层级或提供明确计时口径，不能把现有 `durationMs` 直接解释为纯模型延迟。

## 6. 最重要风险与 QA 缺口

| ID | 优先级 | 风险与触发路径 | 影响 | 当前控制 | 建议 |
| --- | --- | --- | --- | --- | --- |
| R1 | P0（扩容前） | 多副本取消/引导落在非持有者；`complete()` 状态更新存在 TOCTOU | 旧副作用继续、取消被覆盖、用户串行被破坏 | 文档明确单实例限制 | 分布式执行租约 + fencing token + 可靠撤销；完成/派发按 token 验证 |
| R2 | P0 | span 完成或模型“已创建”被当作任务成功 | 错误答案、漏执行、重复写被漏报 | 计划 verify、部分 E2E 读回 | 独立 Execution/Task Success，扩大业务终态读回 |
| R3 | P0 | 留出集同时被查看和用于调参；文档称 3 个而数据有 4 个 | 指标虚高、回归不可信 | heldOut 标记 | 冻结 split manifest、哈希与变更记录，held-out 只用于晋级评审 |
| R4 | P0 | Usage 缺失被汇总成零或缺失成本未披露 | 每成功任务成本严重低估 | Unknown 保持 null | 成本完整度分母；缺失时拒绝发布总成本结论 |
| R5 | P1 | `model_call_ceiling` 被误认为已强制执行 | 计划循环成本与运行时间突破预期 | 工具轮次上限、重规划次数上限 | 明确标为未启用，或另立 Spec 启用 |
| R6 | P1 | 真实模型质量只靠 fake model 与规则 rubric | 上线后语义退化无法提前发现 | 手动真实模型入口 | 离线、授权、固定样本的真实模型回归 |
| R7 | P1 | 业务消息/运行/trace 生命周期不统一 | 存储增长、隐私删除不完整、样本越权 | trace 30 天清理、摘要存储 | 统一保留与脱敏合同，按域落实 |
| R8 | P1 | 无性能基准与 p95 SLO | 容量、慢供应商和队列堆积只能在事后发现 | 超时、并发限制、速率准入 | 先建基线，再逐步做负载与 soak |
| R9 | P1 | 工具风险元数据分散 | 新工具漏分类而绕过提案/权限 | 中央执行围栏、架构测试 | 单一 capability manifest 与生成校验 |
| R10 | P1 | 文档过期但被当作当前合同 | 错误部署和错误验收 | 多份运行时/恢复文档 | ADR 状态、文档负责人、代码引用与复核日期 |

## 7. P0 / P1 / P2 路线图

成本是相对工程量（S/M/L），不是工期承诺。每项先以证据验证，再决定是否扩大。

| 优先级 | 改进 | 收益 | 成本 | 验证方法 |
| --- | --- | --- | --- | --- |
| P0 | AEBB-001：数据集/split、指标、延迟与成本、CI 报告、真实 PG 薄切片、Runtime ADR | 让后续每次 Agent 改动有可比较、可复算、可追溯的证据 | M | 已实现；本地合同/演示/PG 验证通过，完整 CI 验证中 |
| P0 | 修复审计发现的文档冲突并标注历史文档 | 降低错误部署与错误验收概率 | S | 文档 diff 与逐条代码引用核对 |
| P0（扩容前） | 分布式执行租约与 fencing | 多副本取消、串行和完成状态正确 | L | 双实例竞态、网络分区、持有者失联、迟到完成测试 |
| P1 | Outcome 覆盖扩展至学习、面试、记忆、知识库 | 从“工具跑过”升级为“业务做对” | M–L | 每个域至少一个读回成功、一个禁止副作用、一个归属失败 case |
| P1 | 固定窗口真实模型离线评测与人工校准 | 发现 fake model 测不出的语义退化 | M | 重复采样、标注一致性、置信区间、held-out 晋级 |
| P1 | Trace 层级与计时口径收敛 | 准确区分模型、工具、审批等待 | M | 已知时钟 fixture；层级和时长断言；新旧 trace 兼容 |
| P1 | 性能与可靠性基线 | 支撑容量规划和 SLO | M | p50/p95、错误率、排队、soak、故障注入 |
| P1 | 数据保留、脱敏与生产反馈入库 | 形成合规、可持续的反馈闭环 | M–L | 删除/导出/越权测试；脱敏抽样；审计记录 |
| P2 | Workflow / ReAct / Plan-and-Execute 受控实验 | 用数据决定是否需要更复杂 Runtime | M–L | 同模型、同工具、同数据集、同预算；报告质量/成本/延迟 |
| P2 | 评测仪表盘与原因聚类 | 降低趋势分析和分诊成本 | M | 从既有报告生成，不另存第二真相源 |
| P2 | 按需启用计划模型调用预算 | 控制长计划成本 | S–M | 超限、重规划、取消、用户提示的专门 Spec 与测试 |

## 8. 第一阶段 Spec：Agent Evaluation Evidence Baseline v1

### 8.1 Specify

**Spec ID：** AEBB-001  
**状态：** Accepted，已实现，验证中  
**目标：** 在不改变用户业务行为的前提下，让 Ardor 的 Agent 评测报告可复算、可比较、可审计，并明确 Execution Success 与 Task Success。

**范围：**

1. 从现有 30 case 数据集生成独立 split manifest，固定 4 个 held-out ID。
2. 统一 Output / Outcome / Trajectory / Execution / Task 指标和分母。
3. 将现有观测的逐 case 延迟写入报告，增加汇总与缺失标记。
4. 在显式价格表可用时计算已知成本、失败成本、环境错误成本和每成功任务成本；价格不可用时保持未知。
5. Golden Path 上传 demo 与 e2e JSON 报告，并检查合同回归。
6. 增加默认在 CI 运行的薄真实 PostgreSQL 集成切片。
7. 用 ADR 记录 Runtime 职责、单实例边界和非目标。
8. 修复本审计列出的文档数量/状态冲突。

### 8.2 Clarify（本提案采用的默认口径）

| 问题 | 采用的合同 |
| --- | --- |
| Execution Success 是什么？ | 运行产生完整、可读取的持久化轨迹，且没有环境/传输错误；它不表示答案正确 |
| Task Success 是什么？ | 按 case 的业务断言与可用的真实终态读回判定；环境错误不进入该分母 |
| Output 分数是什么？ | 当前 `rubric.v1` 诊断分；不得命名为“语义正确率” |
| 成本缺失怎么办？ | 保持 `null`，给出 `costMissingCount`；只有全部样本成本已知时才发布总成本和每成功任务成本 |
| 失败成本如何拆分？ | 期望失败（任务未达成）、环境错误、安全拒绝分别统计；安全拒绝若符合 case 预期属于成功，不额外算失败 |
| 延迟来源？ | 优先使用已持久化 run/trace 时长；没有可靠时长时为 `null`，不从文本猜测 |
| CI 是否调用真实模型？ | 否。CI 门禁只验证评测合同、合成轨迹和 fake-model E2E；真实模型另行授权 |
| 基线是否立即设质量阈值？ | 否。先建立可复算基线；真实模型阈值需经校准和留出集验证 |
| 是否改变 Runtime？ | 否。只补 ADR 和测试，不重构执行路径 |

### 8.3 Acceptance Criteria

- **AC1 数据集冻结：** manifest 能独立证明 30 个 case、4 个 held-out、每个 tier 的数量和 dataset hash；修改 split 必须改变版本并留下说明。
- **AC2 指标合同：** 每项指标包含 `pass / denominator / rate`；`executionSuccess` 与 `taskSuccess` 可分别复算；环境错误单列。
- **AC3 延迟证据：** 每个 case 保存 `durationMs` 或明确缺失原因；汇总包含样本数、p50、p95，绝不把缺失当 0。
- **AC4 成本证据：** 已知 Token 汇总保持现状；新增价格来源/版本、已知成本、失败成本、环境错误成本、`costMissingCount` 和完整度。存在缺失时 `costPerSuccessfulTask=null`。
- **AC5 未知值回归：** 单元测试覆盖缺失 Usage、缺失价格、零成功任务、零样本、混合已知/未知成本，禁止伪零和无穷值。
- **AC6 CI 证据：** Golden Path 上传 evaluation report artifact；报告含 code SHA、dataset/split/prompt/tool-schema hash、模型、参数和生成时间。
- **AC7 回归门禁：** schema/hash/分母/缺失处理/已知确定性缺陷是硬门禁；质量阈值在基线建立前不凭空设置。
- **AC8 集成切片：** 在 Golden Path 已有一次性 PostgreSQL 环境中，用真实 PostgreSQL + Flyway 验证运行完成事务、用户隔离、trace 读写、取消终态；形成 3–5 个可单独运行的薄测试，本地通过明确 profile/环境变量运行。
- **AC9 Runtime ADR：** 明确上下文、循环、权限、计划、持久化、恢复、取消、观测的 Owner，并记录单实例保证与多副本非目标。
- **AC10 无行为变更：** 不修改业务 API、Agent 工具结果、数据库 schema、生产配置或真实模型调用；现有 Golden Path 与相关回归保持通过。
- **AC11 文档一致：** `AGENT_EVALUATION.md` 的 held-out 数量改为 4；过期文档标注 Superseded/Historical；所有数字能追到数据或报告。

### 8.4 Plan

1. **Spec 冻结：** 人工批准本 Spec 与 Clarify 口径；记录非目标和需要后续 Spec 的 Runtime 变更。
2. **合同测试先行：** 为 manifest、指标分母、未知成本/延迟、报告 artifact 字段写失败测试。
3. **最小实现：** 扩展现有 `agent-eval-cases.json` 消费者、`agent-eval-report.mjs` 与 Golden Path，不建立平行数据源。
4. **集成薄切片：** 抽取已有独立 schema/Flyway 测试方式，新增少量运行/trace 集成测试和 CI profile。
5. **ADR 与文档：** 新增 Runtime ADR，更新评测文档，标注历史文档。
6. **Validate：** 运行脚本单测、后端相关测试、真实 PG 集成切片、完整 Golden Path；检查 artifact 可下载和报告可复算。
7. **Deliver：** 提交 PR，附测试命令、真实结果、未验证项、成本/行为变更说明和回退方式。

### 8.5 Tasks（批准后执行）

| Task | 内容 | 依赖 | 完成证据 |
| --- | --- | --- | --- |
| T1 | 冻结 AEBB-001 与指标字典 | 人工批准 | Spec 状态改为 Accepted |
| T2 | split manifest + schema/哈希/held-out 测试 | T1 | 30/4 断言通过；变更 manifest 使版本/哈希变化 |
| T3 | report 延迟、成本、未知值、分母与汇总 | T2 | `agent-eval-report.test.mjs` 边界测试通过 |
| T4 | Golden Path artifact 与回归检查 | T3 | CI 生成并保留 demo/e2e 报告 |
| T5 | 真实 PostgreSQL 运行/trace 集成切片 | T1 | 隔离、事务、取消、trace 测试通过 |
| T6 | Runtime ADR + 文档纠偏 | T1 | 审阅通过；文档数字与数据一致 |
| T7 | 全套 Validate 与交付 PR | T2–T6 | CI 全绿；报告复算；无业务行为 diff |

### 8.6 非目标

- 不新建 Agent Runtime、Graph 引擎或工作流平台。
- 不启用跨副本执行、持久化续跑、自动生产样本采集或真实模型付费 CI。
- 不改变 `model_call_ceiling` 的执行语义。
- 不引入仪表盘、在线评估数据库或新业务 schema。
- 不把 `rubric.v1` 包装成经过校准的语义 Judge。
- 不以降低测试范围来换取 CI 速度。

### 8.7 回退与风险

- 文档/Spec 可独立回退。
- manifest 和报告字段保持向后兼容：旧报告缺少字段时读为未知，不倒推为零。
- CI artifact 上传失败应使证据任务失败，但不能伪装成业务测试通过。
- 若报告 schema 需要破坏性变更，另开版本，不静默改写旧基线。
- 集成测试只使用一次性数据库/独立 schema，禁止连接日常或生产数据库。

## 9. 需要后续单独 Spec 的变更

以下事项会改变可靠性、成本或用户可观察行为，不能借 AEBB-001 顺手实施：

1. **分布式执行 fencing：** 租约 Owner、heartbeat、完成 token、撤销通道、分区行为和旧副作用处理。
2. **持久化续跑：** 模型上下文、工具尝试、审批状态、checkpoint 和副作用幂等的恢复合同。
3. **计划模型预算启用：** 超限行为、重规划计数、计费和用户提示。
4. **真实生产反馈回流：** 采样授权、脱敏、用户隔离、保留期、人工复核与数据集晋升。
5. **Trace 层级重构：** 新旧 span 兼容、LLM/工具计时语义和 UI 展示变化。

## 10. 已核实的文档冲突

| 文档 | 冲突 | 处理建议 |
| --- | --- | --- |
| `docs/AGENT_EVALUATION.md:18-22` | 同段先写 4 个 held-out，后写 3 个；JSON 实际为 4 | 第一阶段修正为 4 |
| `docs/ADMISSION_GUARANTEES.md` | 仍出现“未实现”表述，与已实现的 QPM/TPM/Redis 准入并存 | 逐条按代码更新，保留真实边界 |
| `docs/AGENT_GRAPH_ARCHITECTURE.md` | 历史 Graph 实验与当前统一聊天入口容易被误读 | 已有 Historical/Superseded 状态；AEBB-001 新增 Runtime ADR 链到当前合同 |
| `docs/AGENT_CONTEXT_COMPACTION.md` | 图片相关限制已有过期表述 | 核对当前图片与压缩路径后修正 |
| `docs/TRACE_STORAGE_CONVERGENCE.md` | Stage 0 已落地，但工具 span 仍从属 LLM | 保留阶段状态，明确未完成项，不宣称层级已收敛 |

## 11. 交付记录与剩余边界

2026-11-25 用户批准直接实施。AEBB-001 已落地以下资产：

- `scripts/agent-eval-split.json` / `agent-eval-dataset.mjs`：30 case、15/15 分层、4 held-out 的冻结 manifest；手动调参默认排除 held-out。
- `scripts/agent-eval-assertions.mjs` / `agent-eval-report.mjs`：独立 Execution Success、Trajectory、时长、p50/p95、成本完整度、失败/环境错误成本与每成功任务成本。
- `scripts/agent-eval-contract.mjs`：CI 报告 schema、哈希、分母、未知值和成本口径硬门禁。
- `.github/workflows/golden-path-acceptance.yml`：真实 PostgreSQL 证据切片、demo/E2E 报告校验与 90 天 artifact。
- `AgentExecutionEvidencePostgresTests`：真实数据库归属、trace、完成事务/回滚、取消终态。
- `docs/ADR_AGENT_RUNTIME.md`：Runtime 职责、单实例保证与非目标。

已验证：

- 72 个评测脚本测试通过；零成本 demo 的 30 个基线案例通过，4 个植入回归和 1 个环境错误均被识别。
- 真实 PostgreSQL 17 + Flyway v98 集成切片 4/4 通过。
- PR #328 已在 5 项 CI 全绿后合并为 `0612d1e`；未部署生产。

尚未验证/未纳入本阶段：完整后端测试与 Golden Path CI、真实模型质量、负载性能、多副本 fencing、持久化续跑、生产反馈回流。文档和报告中的合成结果不冒充这些证据。

