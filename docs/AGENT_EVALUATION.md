# Agent 评测

## 设计原则

「运行完成」「工具 span 是 COMPLETED」都不等于「业务任务成功」。删除工具只生成待确认卡时，
span 仍可能是 `COMPLETED` 且带 `attributes.proposal=true`；用户拒绝的写入是 `NOT_EXECUTED`；
写入已执行但响应丢失时业务状态已经改变。因此评分器按每个 case 声明的真实业务结果判定，
而不是信任 span 状态。核心模块：

- `scripts/agent-eval-dataset.mjs` / `scripts/agent-eval-split.json`：冻结数据集版本、分层与 4 个 held-out 案例；
- `scripts/agent-eval-contract.mjs`：校验 CI 报告的哈希、分母、延迟、成本完整度与未知值口径；
- `scripts/agent-eval-assertions.mjs`：分层评分（`scoreCase`）；
- `scripts/agent-eval-report.mjs`：报告与哈希、带分母的指标；
- `scripts/agent-eval-diff.mjs`：历史 diff 与回归识别；
- `scripts/agent-eval-cases.json`：版本化案例集；用例可选声明 `allowedTools`，一旦声明，trace 里出现列表之外的任何工具都记为失败（含被拒绝或只产生确认卡片的调用）。这是事后评测断言，**不会阻止工具执行**，未声明的用例仍按 `forbiddenTools` / `forbiddenExecuted` 判定；
- `scripts/agent-eval-demo.mjs`：零成本 fixture→报告 diff 演示。

## 数据集

`scripts/agent-eval-cases.json` 当前 `datasetVersion=2026-10-28.agent-eval.v3`，30 个 case（15 representative / 15 challenge，其中 4 个 held out）：

- `tier=representative`：核心读写流程，应稳定通过；
- `tier=challenge`：澄清、审批拒绝、取消、错误参数、资源归属、工具失败、写后响应丢失；
- `heldOut=true`：少量留出案例（4 个），不计入日常调参，用于观察是否过拟合。

每个 case 声明 `prompt`、期望/禁止工具、`requiredExecuted`（必须真正执行而非仅提案）、
`forbiddenExecuted`（禁止真实副作用）、`expectProposed`/`allowProposalOnly`、
`expectSafeRejection`/`expectClarification`、`expectInvalidParams`、`expectToolFailure`、
`expectSingleExecutedWrite` 等。`fixture` 块声明合成业务初态、预期终态、禁止副作用与清理，
供后续端到端驱动使用；评分器只依据录制轨迹，不自行修改数据。

`agent-eval-split.json` 是独立 split manifest：固定 30 个案例、15/15 分层、4 个 held-out ID、
dataset hash 与 split hash。案例 flag、版本或成员变化会使加载失败；变更必须提升
`datasetVersion` 并记录原因。手动真实模型评测默认只选 26 个开发案例，设置
`ARDOR_EVAL_INCLUDE_HELD_OUT=true` 才显式运行留出集。

## 零成本契约演示（CI）

```bash
node --test scripts/agent-eval-assertions.test.mjs scripts/agent-eval-sse.test.mjs \
  scripts/agent-eval-report.test.mjs scripts/agent-eval-diff.test.mjs
node scripts/agent-eval-demo.mjs --out /tmp/ardor-agent-eval-demo
```

演示不调用任何模型，用与真实 `AgentTraceResponse` 同形状的合成轨迹跑完整管线，并故意植入
四个缺陷与一个环境错误：

1. 必需写入只生成提案（未执行）；
2. 只读请求执行了禁止写入（副作用）；
3. 业务错误被回答掩盖为「已成功创建」；
4. 响应丢失后重复写入（两次执行）；
5. 一个 case 没有产生可评分轨迹（环境错误）。

只要评分器漏掉任一缺陷，演示以非零退出码失败。Golden Path CI 执行该演示与上述测试，
**不产生模型费用**。演示证明的是框架与合同，不证明真实模型的语义质量。

## 指标与分母

报告 `metrics` 中每项都给出 `pass / denominator / rate`，分母定义如下：

| 指标 | 分母 | 含义 |
| --- | --- | --- |
| `taskSuccess` | 产生可评分轨迹的 case（排除环境错误） | 是否达到该 case 声明的业务结果 |
| `paramValid` | 期望发生工具交互的 case | 工具参数是否被解析并接受；故意错误参数案例记为 false |
| `toolExecuted` | 要求真实写入的 case | 是否真正执行，而非仅提案或被拒 |
| `safeRejection` | 拒绝/澄清类 case | 禁止的写入是否都未执行 |
| `environmentError` | 全部已尝试 case | 超时/传输/无轨迹，单列，不计入 taskSuccess 分母 |
| `executionSuccess` | 全部已尝试 case | 取得可评分持久轨迹且无环境错误；不表示任务成功 |
| `runCompleted` | 全部已尝试 case | 运行终态为 `COMPLETED`；与任务成功分开 |
| `outcome` | 同 `taskSuccess` | 当前业务结果断言口径；E2E 子集另有真实读回 |
| `trajectory` | 产生可评分轨迹的 case | 工具、计划、权限与运行状态合同是否遵守；答案断言不改写该项 |
| `output` / `rubric` | 有 rubric 的 case | 确定性诊断分，不是校准后的语义正确率 |
| `latency` | 已知时长的 case | 逐 case `durationMs`、known/missing、p50/p95；缺失不为 0 |
| `cost` | 已知价格且用量完整的 case | 已知/缺失成本、总成本、成功/失败/环境错误成本、每成功任务成本 |

`Unknown` Usage 保持为 `null`，绝不置零；报告统计 `usageMissingCount`。
成本缺失同样保持 `null`：只有全部已尝试 case 成本已知时才发布 `totalCostUsd` 和
`costPerSuccessfulTaskUsd`；失败任务与环境错误成本分开统计。缓存 token 若单独计价，
必须同时提供缓存价格与供应商回报，否则该 case 成本为未知。供应商未回报 cache 字段时，
也必须显式提供缓存价格（确认无缓存可填 0），避免把未知账单当成已知基础价。
Trace API 用 `usageMissingCalls` / `cacheUsageMissingCalls` 区分真实 0 与缺失占位 0；
缺失用量和未完成时长在报告中保持 `null`。

## 报告

`buildReport` 保存：代码 SHA、dataset/split/prompt/tool-schema 哈希、模型与有效参数、样本数、
`heldOut` 列表、`evaluatedCaseIds`、每 case 结果（状态、Execution/Task/Trajectory 维度、失败原因、运行状态、工具、业务读回、
Token/缺失标记、时长、成本、rubric）。哈希基于稳定序列化，key 顺序不影响结果。
Golden Path 将 demo 的 baseline/current 与 E2E 报告上传为 `agent-evaluation-evidence` artifact，
保留 90 天，并用 `agent-eval-contract.mjs` 重算 split、dataset/prompt 哈希、逐 case 成本、指标分母、延迟与成本汇总。
报告声明 tool-schema 哈希时必须同时提供 `ARDOR_EVAL_TOOL_SCHEMA_FILE` 指向的真实快照；CI 使用 demo 输出的
`tool-schema.json`，E2E 未导出 schema 时保持 `null`。

## 手动真实模型评测

脚本：`scripts/agent-eval.mjs`，**必须显式授权**后才可运行，CI 不执行。

```powershell
$env:ARDOR_EVAL_ALLOW_REAL_MODEL="true"
$env:ARDOR_SESSION_COOKIE="SESSION=..."
$env:ARDOR_EVAL_REPORT="C:\tmp\eval.json"
$env:ARDOR_EVAL_BASELINE="C:\tmp\eval-baseline.json"   # 可选：与历史报告 diff
$env:ARDOR_EVAL_TOOL_SCHEMA_FILE="C:\tmp\tool-schema.json" # 可选：工具 schema 哈希
$env:ARDOR_EVAL_MAX_CASES="3"
$env:ARDOR_EVAL_INCLUDE_HELD_OUT="false"
$env:ARDOR_EVAL_CONCURRENCY=1
node scripts/agent-eval.mjs
```

行为：

- 每次通过 `/api/agent/messages/stream` 发起新对话；
- **失败或超时也读取** `/api/agent/runs/{requestId}/trace`，部分执行可见；
- 超时先 `POST /api/agent/runs/{requestId}/cancel`，再按实际轨迹判定；
- 缺失 Usage 的 case 仍计入样本并标记，已知费用只累计有 Usage 的请求，
  `budget.note` 明确说明真实花费可能更高；报告同时给出 `costMissingCount`；
- 可设置 `ARDOR_EVAL_CACHE_READ_PRICE_PER_M` / `ARDOR_EVAL_CACHE_CREATION_PRICE_PER_M`，
  并用 `ARDOR_EVAL_PRICE_SOURCE` 记录价格表来源或版本；价格或 cache 用量未知时停止调度后续案例；
- 达到 Token/费用上限后停止调度**尚未开始**的 case，不谎报中断。

失败分类：`EXPECTATION`（断言不满足）、`TIMEOUT`、`TRANSPORT`、服务端稳定错误码、`UNEXPECTED`。
报告不记录 Prompt 正文、答案正文、API Key 或 Cookie。

## 端到端业务 fixture（一次性栈，零成本）

`scripts/agent-eval-e2e.mjs` 在一次性数据库与确定性 fake 模型上运行日历相关案例：为每个案例
注册全新用户（真实数据隔离，不是换会话），通过 REST 播种初态，驱动真实 Agent 运行时，
处理写入审批，运行结束后通过 REST 读回业务终态，再同时判定轨迹与数据库状态。覆盖读取、
创建、修改、删除仅提案、澄清、错误参数、只读无写入、跨用户资源归属和先读后写多步。
默认只运行 8 个开发案例；`ARDOR_EVAL_INCLUDE_HELD_OUT=true` 时额外运行留出的多步案例，供晋级评审。
删除提案通过 owner/run/proposal 维度的驳回接口释放；只有 fixture 明确允许删除时才调用业务 DELETE。

fake 模型新增 `eval-script-probe`：从用户消息中的 `eval-tools` 指令按轮次发出工具调用，
最后返回 `eval-answer`。CI 在配额放宽后运行该驱动；本机一次性栈验证默认开发集 8/8、
显式 held-out 晋级集 9/9 通过：
taskSuccess、executionSuccess、trajectory 均为 9/9、paramValid 6/7（错误参数案例按预期为 false）、toolExecuted 3/3、
safeRejection 2/2、环境错误 0/9。

该驱动暴露了一个真实口径问题：工具校验/业务错误以文本结果返回时，轨迹仍记为
`COMPLETED` 且 `executed=true`。评分器现在检查工具结果的短错误预览并据此判定失败；
产品侧已改为记录 `FAILED` span，并按原因给出 `INVALID_ARGUMENTS`、`RESOURCE_NOT_FOUND`
或 `TOOL_FAILED` errorCode，评分器保留结果预览启发式以兼容修复前的历史轨迹。

确定性 rubric Judge（`scripts/agent-eval-judge.mjs`，`rubric.v1`）附在每个 e2e 案例上，按固定权重检查答案非空、必含/禁含短语、期望工具是否被记录、禁止副作用是否执行、fixture `expectedEndState` 与业务读回是否一致、答案是否声称了未执行的写入，以及 driver 的业务断言。它只写入报告的 `rubric` 字段、`metrics.rubric` 分母与 `rubricMean`，不改变 taskSuccess、safeRejection、失败列表或退出码，也不调用模型。fixture 出现未支持的状态键会显式判为 rubric 失败，避免静默忽略。

## 限制

- 零成本演示使用合成轨迹，只验证框架与合同，不代表真实模型质量；
- 端到端驱动默认覆盖 8 个日历开发案例，显式开启 held-out 时为 9 个；记忆、面试、知识库等仍以轨迹评分为主，
  尚未全部读回业务终态；fake model 不产生真实费用，因此 E2E 报告的成本保持未知；
- 轨迹接口按 `offset`/`limit` 分页（默认 128、单页上限 500）；两个评测驱动会跟随
  `nextOffset` 读取完整轨迹，页请求失败、缺少分页元数据、span 总数不一致或超过页数上限时
  按环境错误处理，不会用截断前缀评分。真实超过一页的评测运行尚未构造；
- 工具参数的校验错误会记录 `FAILED` span，`INVALID_ARGUMENTS`/`RESOURCE_NOT_FOUND`
  与 `TOOL_FAILED` 区分原因；评分器仍保留结果预览的启发式，以兼容修复前的历史轨迹；
- 工具 schema 哈希需要外部导出的 schema 快照，缺失时报告为 `null` 而非伪造；
- 语义正确性、真实供应商的摘要质量与 tokenizer 精度不在本批次范围；
- rubric Judge 是既定规则的诊断分，未经人工样本校准，不能替代模型评判式的语义质量结论。
