# 上下文用量与压缩提示

状态：PR #204 已合并并部署本机；CI、实际镜像及回归证据见 [交付状态](REFACTOR_STATUS.md)。

输入框的上下文图标展示最近一次组装的提示 Token 估算，悬停、聚焦或点击可看详情。
窄屏只显示图标；提示框放在视口内，支持 Escape/外部点击关闭。

## 数值口径

- `estimatedTokens`：由统一 `AgentContextBuilder` 的 tokenizer/估算器计算，包括系统提示、长期记忆、
  摘要、选中的近期消息、恢复的中断指令和展开的引用及当前输入。不是计费 Token，不包含工具定义、
  后续工具返回和输出预留；草稿或模型变化后，要等下一次实际组装才有新估算。
- `compressionBudgetTokens`：现有应用压缩软目标，默认 12000；不是模型上下文窗口。
- `modelWindowTokens`：仅在有可信容量来源时才能填写。当前不从模型名或厂商别名猜容量，因此为 null，
  界面显示未知，不显示虚假占用百分比。百分比仅适用于已知模型容量。
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

- `AgentContextCompactorTests`：压缩开始和失败状态，以及失败后历史保留。
- `AgentContextBuilderTests`：估算与 trace 属性一致，未知窗口、摘要版本及失败标记。
- `AgentStreamProgressTests` / `AgentContextProgressPostgresTests`：新旧 JSON、重启、双用户隔离、旧快照及迟到事件。
- `CareerAgentRuntimeIntegrationTests`：统一执行入口的事件、消息存档、取消和截止时间。
- `agent-runtime-acceptance.mjs`：真实 HTTP 的 SSE、trace、进度、重新加载消息及其他用户 404；使用合成模型。
- `context-usage-ui.mjs`：旧记录未知、持久化估算、实时压缩/失败、窄屏边界和页面溢出，使用合成 API。

完整百轮语义质量、所有供应商 tokenizer 精度及真实模型窗口目录不由这些测试证明。

本批本机 Java 21 全套 729 项：0 failures、0 errors、1 显式门控跳过；包括真实 PostgreSQL
进度快照的其他用户拒绝与 revision 栅栏。首次新增存档断言因旧运行时夹具 mock 掉进度而失败，
改用真实进度对象后完整重跑通过，未移除取消/超时断言。
隔离后端的 HTTP 验收在 9 轮合成对话后触发真实摘要模型调用，SSE、trace、progress 与消息元数据一致，
原始用户/助手消息数量保持。初次多轮探针被正常的分钟限流拒绝，随后只提高合成数据库的测试策略，
与 CI 多轮夹具一致，不修改日常服务策略。
前端 lint/build 与四组浏览器回归通过：上下文、工作区、权限、学习操作；上下文窄屏截图已人工检查。
JAR SHA-256：`28FA18C53D6FFE3FE122CC8879BC1E2849E1E75E28C216533CB49AA149DAEA2E`。
上述本机结果不代替本批 CI、合并与日常部署核验。
