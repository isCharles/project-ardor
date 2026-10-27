# 三条隔离演示

本文件固化三条可重复的演示，每条都包含业务读回、运行轨迹和明确的失败断言。自动化入口都在 Golden Path CI 中执行；本机运行需要一次性隔离栈（独立 Postgres、Redis、后端和 fake-LLM），步骤参考 [`.github/workflows/golden-path-acceptance.yml`](../.github/workflows/golden-path-acceptance.yml)。所有模型输出均来自 `backend/src/test/resources/fake-llm-server.mjs`，不代表真实模型质量。

## 一、日程：读取 → 创建/修改 → 授权或拒绝 → 业务读回

**自动入口**：`node scripts/agent-eval-e2e.mjs`（CI 步骤 “Run Agent business evaluation with business read-back”）。

前置环境：

```bash
ARDOR_ACCEPTANCE_DISPOSABLE_DB=true \
ARDOR_EVAL_E2E=true \
ARDOR_BASE_URL=http://127.0.0.1:8080 \
ARDOR_ACCEPTANCE_LLM_BASE=http://127.0.0.1:8090 \
ARDOR_EVAL_REPORT=target/agent-eval-e2e.json \
node scripts/agent-eval-e2e.mjs
```

每个案例注册全新用户，通过 REST 播种初态，驱动真实 Agent 运行时（fake 模型脚本化工具调用），再通过 REST 读回业务终态；同时抓取完整 Trace 评分。覆盖的 9 个案例：

- 读取日历不产生写入；
- 批准后创建、修改指定任务；
- 删除只生成待确认提案，业务表中任务仍在；
- 信息不足时澄清且不创建；
- 非法参数不创建任务；
- 只读请求不改变任务数量；
- 跨用户更新被拒绝且他人数据不变；
- 先读后写仍由用户确认执行。

判定：`scripts/agent-eval-e2e.mjs` 的业务断言负责数据库读回，`scripts/agent-eval-assertions.mjs` 负责轨迹与安全断言；环境错误与业务失败分开统计。报告写入 `ARDOR_EVAL_REPORT`，包含代码 SHA、数据集/提示/工具哈希和逐 case 结果。手动 UI 路线参考 [GOLDEN_PATH_DEMO.md](GOLDEN_PATH_DEMO.md)。

**能证明**：权限、审批、用户隔离、业务终态和 Trace 在同一真实运行时里一致。**不能证明**：真实模型能否稳定选择正确工具；删除的最终点击仍由界面确认，脚本只验证“仅提案”。

## 二、长对话：摘要进度可见 → 关键约束保留 → 原历史与成本可查

**自动入口 1**：`node backend/src/test/resources/semantic-recall-acceptance.mjs`（CI 分别跑重复压缩和默认预算两种模式）。

```bash
ARDOR_ACCEPTANCE_DISPOSABLE_DB=true \
ARDOR_ACCEPTANCE_API_BASE=http://127.0.0.1:8080 \
ARDOR_ACCEPTANCE_LLM_BASE=http://127.0.0.1:8090 \
node backend/src/test/resources/semantic-recall-acceptance.mjs
ARDOR_ACCEPTANCE_SEMANTIC_MODE=default \
  node backend/src/test/resources/semantic-recall-acceptance.mjs
```

脚本在第 1 轮埋入随机暗号，之后持续对话到百轮，断言：最终回答仍能引用该事实；压缩状态和 `summaryVersion` 只前进不回退；重复压缩模式至少两次、默认 200K 预算模式不超过 20 次；请求估算不超过配额上限；原始用户/助手消息全部保留；最终 Run 状态为 `COMPLETED`。

**自动入口 2**：`ContextComparisonExperimentTests`（PR #249）复用真实 Builder/Compactor/Budget/重复结果压缩，对 20/80/200 轮合成对话比较原文、摘要、摘要+重复只读结果压缩三种视图。报告在 `backend/target/context-comparison/report.json`，逐模式记录最终请求 Token、含摘要与重试的会话总 Token、摘要额外时延、标签保留分子/分母和违规副作用；未知窗口保持 `null`，80/200 轮原文超 32K 已知窗口时单列为失败样本，未当作节省。

```bash
cd backend && mvn -B -q -Dtest=ContextComparisonExperimentTests test
```

成本与历史可在界面和接口核对：`GET /api/agent/runs/{id}/trace` 的 `totals` 给出输入/输出 Token 与调用次数，SSE 的上下文事件显示估算与摘要版本，`ardor_agent_context_compactions` 与 `ardor_agent_context_compaction_failures` 计数摘要写入和失败。

**能证明**：压缩触发、版本推进、原消息保留与成本口径在确定性环境可复算。**不能证明**：真实模型的摘要语义质量、供应商 tokenizer 精度或普适无损；`ContextComparisonExperimentTests` 使用 Fake 摘要器，虚拟时延不能当真实延迟。

## 三、失败或取消：真实终态 → 禁止重复写入 → 队列停止/继续

**自动入口**：`node backend/src/test/resources/golden-path-acceptance.mjs`（CI 步骤 “Run end-to-end Golden Path”）的持久队列段落，以及 `node backend/src/test/resources/agent-runtime-acceptance.mjs` 的 Trace/进度段落。

```bash
ARDOR_ACCEPTANCE_DISPOSABLE_DB=true \
ARDOR_ACCEPTANCE_API_BASE=http://127.0.0.1:8080 \
ARDOR_ACCEPTANCE_LLM_BASE=http://127.0.0.1:8090 \
node backend/src/test/resources/golden-path-acceptance.mjs
```

队列断言：失败消息进入 `FAILED` 后，后续消息保持 `QUEUED` 并暂停；显式 `resume` 后才继续；优先引导只放行所选消息，不释放其他暂停消息；跨用户查询、取消和恢复均 404；排队请求按提交顺序完成，用户消息正文不因优先级倒置；每个 Run 的 Trace 仍可按 requestId 读取。

写入安全断言：

- 同一 run 内第二次相同写入返回 `DUPLICATE_WRITE_BLOCKED`，业务表只产生一条记录（`ApprovedAgentToolsTests`、`ModularAgentToolsTests`）；
- 工具校验/业务错误记录为 `FAILED` span 和失败结果，不伪装成 `COMPLETED`（`AgentToolFailureHandlerTests`、e2e 非法参数案例）；
- 执行结果未知时消息进入 `INTERRUPTED` 并提示先核对，不自动重放（`AgentMessageQueueWorkerTests`）；
- 旧 attempt 的迟到完成、失败和心跳不能覆盖新 attempt（`AiJobAttemptFencingTests`，需一次性 PostgreSQL）。

```bash
cd backend
mvn -B -q \
  -Dtest=ApprovedAgentToolsTests,ModularAgentToolsTests,AgentToolFailureHandlerTests,AgentMessageQueueWorkerTests test
ARDOR_ACCEPTANCE_DISPOSABLE_DB=true \
  mvn -B -q -Dtest=AiJobAttemptFencingTests test
```

**能证明**：失败、拒绝、未知结果和迟到回调都有可读的真实终态，重复写入被拦截，队列按合同暂停/继续。**不能证明**：全业务 Exactly-once；取消不是回滚，已发生的业务副作用仍需读回确认。`recovery-acceptance.mjs` 是额外的两阶段备份/恢复手工探针，需要单独的 fixture 容器，不属于 Golden Path。

## 可选增强：Judge 与 Prompt×Model 的未运行配置

Judge 未接入。确定性断言与安全硬断言已经覆盖评分器的职责，接入 Judge 前需要固定 rubric、人工样本和校准集，且不能放宽安全断言；当前没有这些数据，不产生结论。

Prompt×Model 四组对照未运行，也未获得付费授权。可运行的前置条件如下，满足后再用现有真实模型 harness 采集，不得用 Fake 结果代替：

1. 两版 system prompt（例如现状版与更强调工具边界版），除该变量外 fixture、案例集和采样参数相同；
2. 两种真实模型，通过账户配置切换，每组记录 `ARDOR_EVAL_MODEL` 标签；
3. 同一 `scripts/agent-eval-cases.json` 与相同 fixture，固定 `ARDOR_EVAL_MAX_CASES`、并发、超时与价格；
4. 每组重复运行至少两次，报告分别保存后比较分布，不只比较单次均值；
5. 预算护栏：`ARDOR_EVAL_ALLOW_REAL_MODEL=true`、`ARDOR_EVAL_MAX_COST_USD`、`ARDOR_EVAL_MAX_TOKENS` 必须在运行前设置，超限停止新案例。

现有 `scripts/agent-eval.mjs` 支持模型标签、报告、历史 diff、成本/Token 上限和每 case Trace，但不负责切换账户模型或生成两版 prompt；这些由运行者显式准备。没有授权时不运行、不产生费用、不写“四组实测”。

## 证据层级

| 层级 | 证据 | 说明 |
| --- | --- | --- |
| CI 自动 | Golden Path 全流程（含上述三个演示的自动入口） | 真实数据库、真实 HTTP、fake 模型；每次合并前运行 |
| 单元/合同 | 后端 860+ 项测试、Node 测试 | 真实 PostgreSQL 仅用于显式门控的一次性库 |
| 离线实验 | `ContextComparisonExperimentTests` 报告 | 确定性 Fake，含成本/保留/违规分子分母 |
| 未采集 | 真实模型质量、真实供应商成本与时延 | 需用户明确授权付费后单独运行 |

真实模型脚本（`scripts/agent-eval.mjs` 等）已经存在，但本文件中的所有结果都不来自它们；不得把 Fake 结果写成真实模型评测。
