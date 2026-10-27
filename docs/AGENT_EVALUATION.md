# Agent 评测

## CI：确定性 Fake LLM

CI 不调用真实模型。已有的 `golden-path-acceptance.yml` 使用
`backend/src/test/resources/fake-llm-server.mjs` 和 `golden-path-acceptance.mjs`
覆盖 Agent 工具确认、恢复、隔离、学习/面经闭环等确定性路径。新增的
`AgentToolRecoveryTests` 用进程内 Fake `ChatModel` 验证工具失败不会中止整轮，
且安全错误结果不会把异常原文返回给模型。

真实模型的输出有随机性，不进入阻塞式 CI。

## 手动：真实模型评测

脚本：`scripts/agent-eval.mjs`

用例：`scripts/agent-eval-cases.json`

```powershell
$env:ARDOR_EVAL_ALLOW_REAL_MODEL="true"
$env:ARDOR_SESSION_COOKIE="SESSION=..."
$env:ARDOR_BASE_URL="http://127.0.0.1:3000"
$env:ARDOR_EVAL_MAX_CASES="3"
$env:ARDOR_EVAL_CONCURRENCY="1"
$env:ARDOR_EVAL_MAX_TOKENS="200000"
$env:ARDOR_EVAL_MAX_COST_USD="0.50"
$env:ARDOR_EVAL_INPUT_PRICE_PER_M="0.15"
$env:ARDOR_EVAL_OUTPUT_PRICE_PER_M="0.60"
node scripts/agent-eval.mjs
```

每次通过 `/api/agent/messages/stream` 发起新对话，结束后读取
`/api/agent/runs/{requestId}/trace` 的实际 Token 和工具调用。脚本不会记录 Prompt、
答案正文、API Key 或 Cookie；报告只包含 case id、状态、失败分类、工具名和 Token/费用汇总。

预期工具只有在轨迹中出现 `COMPLETED` 调用才算通过；被拒绝、失败或仍在运行
不能算成功。禁止的工具即使被拦截也算一次尝试。运行轨迹缺失或未完成时不判为
通过。该断言只检查记录到的工具状态，不证明工具结果、最终答案的语义正确性；
返回业务错误文本但未抛异常的调用、仅创建建议卡的调用仍可能记为 `COMPLETED`。
CI 使用 `node --test scripts/agent-eval-assertions.test.mjs` 验证断言本身。
流式采集按 SSE 事件边界增量解析，覆盖多字节文本、跨分片和 CRLF；
格式错误不会被悄悄忽略。事件解析失败不代表模型回答本身错误。

失败分类：

- `EXPECTATION`：工具或答案断言不满足；
- `TIMEOUT`：单 case 超时；
- `TRANSPORT`：HTTP/SSE/Trace 传输失败；
- `QUOTA_*`、`LLM_*`、`AGENT_*`：服务端返回的稳定错误码；
- `UNEXPECTED`：未归类异常，报告不包含原始 Prompt/答案。

预算规则：

- 只限制**尚未开始**的 case；已开始请求允许完成，不谎报中断；
- 达到 Token 或费用上限后停止调度剩余 case，并以非零退出码提示；
- 并发默认 1，避免多个真实模型请求同时占用额度；
- 每个 case 有独立超时，失败会被记录并继续下一个 case；
- 需要 `ARDOR_EVAL_ALLOW_REAL_MODEL=true` 才允许运行，防止误触真实计费。

## 面试提问链路

`InterviewCodingQuestionTests` 和 `InterviewService` 现在会拒绝：

- 重复题目；
- 实际有效题目少于用户请求数量；
- 过长或空题目。

`submitAnswer` 对同一面试使用行锁串行化，且把并发重复提交映射为
「该题已经回答」，不会覆盖已保存答案。
