# Agent 评测

## 设计原则

「运行完成」「工具 span 是 COMPLETED」都不等于「业务任务成功」。删除工具只生成待确认卡时，
span 仍可能是 `COMPLETED` 且带 `attributes.proposal=true`；用户拒绝的写入是 `NOT_EXECUTED`；
写入已执行但响应丢失时业务状态已经改变。因此评分器按每个 case 声明的真实业务结果判定，
而不是信任 span 状态。核心模块：

- `scripts/agent-eval-assertions.mjs`：分层评分（`scoreCase`）；
- `scripts/agent-eval-report.mjs`：报告与哈希、带分母的指标；
- `scripts/agent-eval-diff.mjs`：历史 diff 与回归识别；
- `scripts/agent-eval-cases.json`：版本化案例集；
- `scripts/agent-eval-demo.mjs`：零成本 fixture→报告 diff 演示。

## 数据集

`scripts/agent-eval-cases.json` 当前 `datasetVersion=2026-10-27.agent-eval.v2`，24 个 case：

- `tier=representative`：核心读写流程，应稳定通过；
- `tier=challenge`：澄清、审批拒绝、取消、错误参数、资源归属、工具失败、写后响应丢失；
- `heldOut=true`：少量留出案例（3 个），不计入日常调参，用于观察是否过拟合。

每个 case 声明 `prompt`、期望/禁止工具、`requiredExecuted`（必须真正执行而非仅提案）、
`forbiddenExecuted`（禁止真实副作用）、`expectProposed`/`allowProposalOnly`、
`expectSafeRejection`/`expectClarification`、`expectInvalidParams`、`expectToolFailure`、
`expectSingleExecutedWrite` 等。`fixture` 块声明合成业务初态、预期终态、禁止副作用与清理，
供后续端到端驱动使用；评分器只依据录制轨迹，不自行修改数据。

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

`Unknown` Usage 保持为 `null`，绝不置零；报告统计 `usageMissingCount`。

## 报告

`buildReport` 保存：代码 SHA、dataset/prompt/tool-schema 哈希、模型与有效参数、样本数、
`heldOut` 列表、每 case 结果（状态、五维、失败原因、运行状态、工具、Token/缺失标记）。
哈希基于稳定序列化，key 顺序不影响结果。

## 手动真实模型评测

脚本：`scripts/agent-eval.mjs`，**必须显式授权**后才可运行，CI 不执行。

```powershell
$env:ARDOR_EVAL_ALLOW_REAL_MODEL="true"
$env:ARDOR_SESSION_COOKIE="SESSION=..."
$env:ARDOR_EVAL_REPORT="C:\tmp\eval.json"
$env:ARDOR_EVAL_BASELINE="C:\tmp\eval-baseline.json"   # 可选：与历史报告 diff
$env:ARDOR_EVAL_TOOL_SCHEMA_FILE="C:\tmp\tool-schema.json" # 可选：工具 schema 哈希
$env:ARDOR_EVAL_MAX_CASES="3"
node scripts/agent-eval.mjs
```

行为：

- 每次通过 `/api/agent/messages/stream` 发起新对话；
- **失败或超时也读取** `/api/agent/runs/{requestId}/trace`，部分执行可见；
- 超时先 `POST /api/agent/runs/{requestId}/cancel`，再按实际轨迹判定；
- 缺失 Usage 的 case 仍计入样本并标记，已知费用只累计有 Usage 的请求，
  `budget.note` 明确说明真实花费可能更高；
- 达到 Token/费用上限后停止调度**尚未开始**的 case，不谎报中断。

失败分类：`EXPECTATION`（断言不满足）、`TIMEOUT`、`TRANSPORT`、服务端稳定错误码、`UNEXPECTED`。
报告不记录 Prompt 正文、答案正文、API Key 或 Cookie。

## 端到端业务 fixture（一次性栈，零成本）

`scripts/agent-eval-e2e.mjs` 在一次性数据库与确定性 fake 模型上运行日历相关案例：为每个案例
注册全新用户（真实数据隔离，不是换会话），通过 REST 播种初态，驱动真实 Agent 运行时，
处理写入审批，运行结束后通过 REST 读回业务终态，再同时判定轨迹与数据库状态。覆盖读取、
创建、修改、删除仅提案、澄清、错误参数、只读无写入、跨用户资源归属和先读后写多步。

fake 模型新增 `eval-script-probe`：从用户消息中的 `eval-tools` 指令按轮次发出工具调用，
最后返回 `eval-answer`。CI 在配额放宽后运行该驱动；本机一次性栈验证 9/9 通过：
taskSuccess 9/9、paramValid 6/7（错误参数案例按预期为 false）、toolExecuted 3/3、
safeRejection 2/2、环境错误 0/9。

该驱动暴露了一个真实口径问题：工具校验/业务错误以文本结果返回时，轨迹仍记为
`COMPLETED` 且 `executed=true`。评分器现在检查工具结果的短错误预览并据此判定失败；
产品侧已改为记录 `FAILED` span，并按原因给出 `INVALID_ARGUMENTS`、`RESOURCE_NOT_FOUND`
或 `TOOL_FAILED` errorCode，评分器保留结果预览启发式以兼容修复前的历史轨迹。

## 限制

- 零成本演示使用合成轨迹，只验证框架与合同，不代表真实模型质量；
- 端到端驱动当前只覆盖日历相关 9 个案例；记忆、面试、知识库等仍以轨迹评分为主，
  尚未全部读回业务终态；
- 轨迹接口按 `offset`/`limit` 分页（默认 128、单页上限 500）；两个评测驱动会跟随
  `nextOffset` 读取完整轨迹，页请求失败、缺少分页元数据、span 总数不一致或超过页数上限时
  按环境错误处理，不会用截断前缀评分。真实超过一页的评测运行尚未构造；
- 工具参数的校验错误会记录 `FAILED` span，`INVALID_ARGUMENTS`/`RESOURCE_NOT_FOUND`
  与 `TOOL_FAILED` 区分原因；评分器仍保留结果预览的启发式，以兼容修复前的历史轨迹；
- 工具 schema 哈希需要外部导出的 schema 快照，缺失时报告为 `null` 而非伪造；
- 语义正确性、真实供应商的摘要质量与 tokenizer 精度不在本批次范围。
