# 上下文用量与压缩提示

状态：PR #204 首版已部署，但用户要求返工；当前分支尚未部署。首版证据见 [交付状态](REFACTOR_STATUS.md)。

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
- `context-usage-ui.mjs`：旧记录未知、持久化估算、实时压缩/失败、窄屏边界和页面溢出，使用合成 API。

完整百轮语义质量、所有供应商 tokenizer 精度及完整模型窗口目录不由这些测试证明。

## 当前返工验证

本地 Java 21 全套 731 项：0 failures、0 errors、1 显式门控跳过。
隔离合成模型的真实 HTTP 验收通过：每次请求更新、SSE/进度/存档一致、其他用户 404、9 轮触发
真实摘要并保留原消息。前端 lint/build 通过（2 条既有 warning），Edge 上下文比例 25%/75%/溢出、
三行提示、模型切换、刷新、压缩/失败和窄屏回归通过；工作区与权限控件回归通过。
首次新增单测因 mock 嵌套 stubbing 出错，修正夹具后通过；一次回归脚本名输错未执行，
改为现有 `agent-approval-ui.mjs` 后通过。CI、PR 合并与本机部署尚待完成。

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
