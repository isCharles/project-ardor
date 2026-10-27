# 上下文用量与压缩提示

## 容量感知压缩修复（当前分支，未部署）

针对实际 1.22% 占用仍因 8 条旧消息触发摘要的问题，本批移除消息数量触发条件。
压缩、观测和发送前检查共用 `AgentRequestTokens`；工具定义通过 schema-only catalog 计入，
不绑定或执行工具。近期消息数量只决定哪些内容优先原样保留，不再单独触发摘要。

- 已知窗口：硬输入预算 = 窗口 - 实际模型输出上限 - 安全余量；默认安全余量为窗口的 5%。
  软阈值为硬输入预算的 75%，分别可用 `app.agent.context.safety-reserve-ratio` 和
  `app.agent.context.soft-utilization` 配置。系统提示、工具定义、历史、引用和当前请求属于输入，不能重复扣除。
- 未知窗口：继续用 `app.agent.context.max-history-tokens`（默认 12000）作为应用软目标，
  不将其冒充模型窗口或已知硬限制。`max-raw-messages` 不再触发摘要。
- 当前输入及引用本身超出硬预算时直接拒绝，不浪费一次无效摘要。摘要后仍超限也不发送。
  模型适配器在每次实际请求前再次检查，覆盖新追加的工具返回和输出上限覆盖值；
  流式和非流式模型均保持原有 provider、参数、能力、监听器和 lease transport。
  这是针对本地估算的硬拦截，不保证未知供应商 tokenizer 与估算完全一致；不能据此宣称供应商永不拒绝超长输入。
- 摘要调用使用 `CONTEXT_COMPACTION` 用途，嵌套于 `context.load`，与正常回复共用本轮唯一调用序号；
  界面明确显示「上下文压缩」。普通上下文组装与独立摘要不再都以 `llm.call.1` 混淆。

本地 Java 21 全套 742 项（0 失败/错误、1 门控跳过）通过。显式 64K 合成模型的真实 HTTP
验收在第 15 轮触发摘要，核对窗口、预算、独立调用父节点/唯一序号、原消息保留及其他用户 404。
低占用 1M 窗口、工具定义单独触发、超大当前请求拒绝、新工具返回硬拦截、取消及摘要失败均有单测。
前端 lint/build 和轨迹浏览器回归通过；纳入 #207 后的组合回归、CI、合并和部署仍待完成。

这是预算/触发与可追踪性的交付批次，尚不是完整的分层压缩系统：工具输出去重/检索、独立结构化
约束检查点仍待实现；完整百轮已由合成验收覆盖（见“验证入口”），真实供应商摘要语义质量与
tokenizer 精度仍待验收。一次摘要只覆盖实际输入的旧消息，未处理的尾部继续原样保留；
原始记录不删除，取消后不保存迟到摘要。

状态：[PR #206](https://github.com/isCharles/project-ardor/pull/206) 返工已合并并部署本机，revision `43ef68d`。
首版 #204 保留为历史证据，已被当前部署替代；见 [交付状态](REFACTOR_STATUS.md)。

输入框在模型旁显示实际比例环，宽窄屏均只显示图标；悬停、聚焦或点击显示三行提示：
上下文窗口、已用百分比、约用量/容量。未知容量不画虚假进度，压缩中显示旋转状态。
提示框保持在视口内，支持 Escape/外部点击关闭。切换模型后不套用上一模型的占比。

## 数值口径

- `estimatedTokens`：初始组装后，由独立于 trace 开关的 `AgentRequestContextListener` 在每次实际
  模型请求前更新，包含该请求的系统提示、记忆、摘要、近期消息、引用、当前输入、工具定义、
  已返回工具结果与响应格式。使用现有 tokenizer；无对应 tokenizer 时按字符回退估算，工具 schema
  按 SDK 的结构化对象文本估算，不能承诺等同供应商的序列化/tokenizer。
  收到供应商本次请求的有效 input Token 后用该值校准；不累计历史调用、输出 Token 或缓存命中率。
  同一工具循环中每次调用都会更新，迟到的上一调用和结束后的回调不能覆盖新结果。
  草稿未发送前不预测其用量；显示的是最近一次请求输入，不含输出预留。
- `compressionBudgetTokens`：现有应用压缩软目标，默认 12000；不是模型上下文窗口。
- `modelWindowTokens`：`ModelContextCapacity` 只用明确配置或核实过的精确模型/官方 endpoint 组合。
  当前内置 `api.deepseek.com` 的 `deepseek-v4-flash`，容量 1048576；依据
  [官方容量文档](https://api-docs.deepseek.com/quick_start/pricing/) 和
  [官方模型配置](https://huggingface.co/deepseek-ai/DeepSeek-V4-Flash/blob/main/config.json)，核实于 2026-10-25。
  其他模型和中转别名默认未知，不进行前缀猜测。部署方可在 Spring 配置中用
  `app.agent.context.windows.<精确模型ID>=<正整数Token数>` 明确覆盖（同 ID 在此部署内共用）。
  配置 0、负数或非整数时保持未知；不把应用 12K 压缩预算当容量，不改模型调用参数。
- `summaryVersion`、`compactedMessages`：来自实际摘要结果，表示当前摘要版本和本次纳入摘要的消息数。

## 状态、刷新和兼容

流式入口先发 PREPARING，实际需要调用摘要模型时发 COMPACTING；摘要失败则发 COMPACTION_FAILED，
保留原消息，最终快照仍标记失败；完成组装发 READY。失败不冒充成功，也不改变现有模型、授权或
压缩触发条件。没有将隐藏推理文本用于填充状态提示。

同一份结构化数据通过 `context` SSE 事件、owner-scoped progress snapshot 和助手消息的 runTrace 保存。
运行中刷新从进度恢复，完成后从持久化消息读取；旧记录没有此字段时显示未知。
沿用已有的快照 revision 栅栏、user/run 联合归属及取消/截止时间检查；结束后迟到的状态不能覆盖结果。
只保存数值、模型标识及状态，不新增 prompt、简历正文、密钥或完整工具载荷。

无新增 schema/Flyway、工具名或权限变更。旧客户端忽略新的 SSE 类型及可选 JSON 字段；回退时保留
已写入的附加元数据，不清空进度和消息。测试以旧 View 记录结构验证读取新增字段，以无此字段的旧
JSON 验证新版读取；没有改动 Jackson 的全局解析规则。

## 验证入口

- `AgentRequestContextListenerTests`：分别验证工具 schema、工具返回增量、单次供应商用量校准、
  下一次短请求用量降低、上一调用迟到响应及停止后回调栅栏。
- `ModelContextCapacityTests`：精确官方组合、中转未知、配置覆盖及非法配置。
- `AgentContextCompactorTests`：压缩开始和失败状态，以及失败后历史保留。
- `AgentContextBuilderTests`：估算与 trace 属性一致，未知窗口、摘要版本及失败标记。
- `AgentStreamProgressTests` / `AgentContextProgressPostgresTests`：新旧 JSON、重启、双用户隔离、旧快照及迟到事件。
- `CareerAgentRuntimeIntegrationTests`：统一执行入口的事件、消息存档、取消和截止时间。
- `agent-runtime-acceptance.mjs`：真实 HTTP 的 SSE、trace、进度、重新加载消息及其他用户 404；使用合成模型。
- `semantic-recall-acceptance.mjs`：真实 HTTP 跑满 100 轮，第 1 轮植入的事实经历多次摘要重写后仍出现在后续模型请求中
  （且不以原始消息残留的方式出现），原始消息未被删除、请求估算有界；使用会忠实转写暗号的合成模型。
- `context-usage-ui.mjs`：旧记录未知、持久化估算、实时压缩/失败、窄屏边界和页面溢出，使用合成 API。

合成百轮验收证明的是**压缩链路不丢事实**，不是供应商摘要的语义质量：合成摘要按测试约定转写暗号，
真实模型是否保留同样信息仍需真实供应商抽样。所有供应商 tokenizer 精度及完整模型窗口目录亦不由这些测试证明。

## 当前返工验证

本地 Java 21 全套 731 项：0 failures、0 errors、1 显式门控跳过。
隔离合成模型的真实 HTTP 验收通过：每次请求更新、SSE/进度/存档一致、其他用户 404、9 轮触发
真实摘要并保留原消息。前端 lint/build 通过（2 条既有 warning），Edge 上下文比例 25%/75%/溢出、
三行提示、模型切换、刷新、压缩/失败和窄屏回归通过；工作区与权限控件回归通过。
首次新增单测因 mock 嵌套 stubbing 出错，修正夹具后通过；一次回归脚本名输错未执行，
改为现有 `agent-approval-ui.mjs` 后通过。全部 CI 通过（`40198542458`、Golden Path `40198542432`），
合并提交 `01805b0`。本机前后端 `ardor-*-context-ring:43ef68d`，health UP、login 200、V65。
实际 3000 端口的容量环、面试关联、工作区与权限四组 Edge 回归通过，API 均为合成夹具。
仓库外 `../backups/20261026-before-context-ring-43ef68d/` 保存部署前备份、摘要、镜像及回退说明。

## 首版历史验证（不代表返工已验收）

本批本机 Java 21 全套 729 项：0 failures、0 errors、1 显式门控跳过；包括真实 PostgreSQL
进度快照的其他用户拒绝与 revision 栅栏。首次新增存档断言因旧运行时夹具 mock 掉进度而失败，
改用真实进度对象后完整重跑通过，未移除取消/超时断言。
隔离后端的 HTTP 验收在 9 轮合成对话后触发真实摘要模型调用，SSE、trace、progress 与消息元数据一致，
原始用户/助手消息数量保持。初次多轮探针被正常的分钟限流拒绝，随后只提高合成数据库的测试策略，
与 CI 多轮夹具一致，不修改日常服务策略。
前端 lint/build 与四组浏览器回归通过：上下文、工作区、权限、学习操作；上下文窄屏截图已人工检查。
JAR SHA-256：`28FA18C53D6FFE3FE122CC8879BC1E2849E1E75E28C216533CB49AA149DAEA2E`。
上述本机结果不代替本批 CI、合并与日常部署核验。
