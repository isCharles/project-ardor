# 模型协议适配边界

模型服务仍由现有连接配置、密钥加密和 ExternalBaseUrlPolicy 管理。协议身份 LlmProvider 不是模型品牌，也不是 Agent 功能、导航 ID 或授权范围；同一兼容协议可用于不同服务商和模型。

## 执行合同

- 普通分析由 CompatibleLlmGateway 根据注册的 JsonModelProtocol 调用协议适配器。适配器拥有 HTTP 路径、请求体、鉴权头与返回结构；网关保留错误分类。业务 Service 继续只使用 LlmGateway。
- Agent 由 LangChainModelFactory 根据注册的 AgentModelProtocol 创建同步或流式模型。协议适配器拥有 SDK 参数；工厂保留当前用户配置选择、超时、代理及取消租约的 transport 包装。
- 连接测试的 JSON 探测属于平台层；Tool Calling 探测需要 Agent 的聊天模型工厂与工具 schema，因此通过 `ToolCallingProbe` 接口由 Agent 侧实现。`llm` 包不导入 `agent` 包，依赖方向由架构边界测试守护。
- ModelProtocols 在启动时拒绝重复、缺失及无身份的注册；不按集合顺序猜一个实现，不在协议调用失败后试另一种协议。
- 现有协议为 OPENAI_COMPATIBLE、ANTHROPIC_COMPATIBLE。接入新品牌但沿用兼容协议只需连接配置；新增真正不同的协议须声明稳定身份，并提供 JSON 与 Agent 适配器及合同测试，不修改中央执行分支或业务 Service。
- Agent 适配器可提供自己的 reasoning 映射。现有模型仍复用 ModelReasoningPolicy 的保守映射；未知模型不会凭名称自动获得“原生思考控制”。该静态映射不是服务商实时能力探测。

Spring 生产装配使用注册的适配器列表。保留的显式构造器用于既有非 Spring 调用/测试的两种默认协议，不是另一套生产注册表。

## 请求与失败语义

保留已有 JSON 分析的 temperature、Anthropic max_tokens、输出截断拒绝和空内容拒绝。JsonModelProtocol 返回原始模型内容，领域仍由现有 LlmJsonParser/schema 校验，不在协议层把不合法 JSON 变成业务事实。

Anthropic 的存储 Base URL 沿用普通分析网关的服务根地址合同：JSON 请求追加 /v1/messages；SDK 自己追加 /messages，因此 Agent 适配器追加 /v1 前缀。修复前同一根地址会在 Agent 路径丢失 /v1；本地实际 SDK HTTP 测试复现并覆盖了此差异。自托管网关的原有路径前缀保留；不自动试探备用路径或改投另一种协议。

普通分析与聊天对可确定的 HTTP/传输失败共用平台 LlmFailureClassifier。AgentModelFailures 仅在助手边界将 SDK 异常类型和状态码转换为平台分类，平台不依赖 SDK 或助手。鉴权失败为 LLM_AUTH_FAILED、接口或模型不存在为 LLM_NOT_FOUND、限流为 LLM_BUSY；网络细分 DNS、连接、TLS 和普通 IO，不把供应商正文或密钥拼入提示。既有空输出、截断、无法解析和领域异常保持原合同。

聊天的连接/读取超时继续使用 AGENT_UPSTREAM_TIMEOUT 且 retryable=false，即使 SDK 外层包装为超时异常，也不能自动重放可能已执行工具的请求。其他可重试提示仍受 AgentRunStore 的 tool_started 限制：工具已开始则不自动重试。非流式与流式错误出口都应用分类；错误码和安全提示保存在运行记录中，再通过既有 SSE errorCode/content/retryable 字段返回。不增加必填响应字段，旧浏览器/桌面仍按通用错误提示工作；未知异常保留原有 AGENT_LLM_FAILED / AGENT_TEMPORARILY_UNAVAILABLE 兼容兜底，不声称它们已有精确原因。

SDK 内部同步重试仍为零，流式取消仍经 AgentStreamLease。这不承诺远端生成已停止、所有供应商异常均被识别或已发生的工具写入可以回滚。真实供应商质量、计费和长时流式行为不由合成协议测试证明。

失败分类的两个特殊边界：Jackson 2 的 JsonProcessingException 虽继承 IOException，但属于不可重试的 LLM_INVALID_RESPONSE，不是网络断开；Jackson 3 同类解析异常采用相同合同。SDK 的 UnresolvedModelServerException（即使不带 cause）以及 JDK UnresolvedAddressException 映射为 LLM_DNS_FAILED，不回落为不可重试的未知模型错误。包装的领域 LlmCallException 仍优先保持原合同。

真实 SDK 验收还复现 Anthropic 流式解析回调异常被 SSE parser 忽略：上层既收不到最终结果，也收不到错误。LeasedHttpClientBuilder 在既有传输边界包装回调，先将 RuntimeException 交给 onError 并关闭本次响应体，再让解析器继续其清理；同一失败只转交一次，之后的事件/关闭不能伪造成功。不增加自动重试，也不声称关闭本地响应体会撤销远端请求或业务操作。实际坏 SSE JSON 探针同时覆盖两个协议，已有正常文本/工具回合/取消合同继续运行。

## 验证与演进边界

- CompatibleLlmGatewayTests 保留两种 JSON 协议的请求与截断合同。
- ModelProtocolContractTests 验证注册、失败不跨协议回退、两种 HTTP 协议的状态码/重试标记、网络分类、空/坏输出以及错误提示不包含供应商正文。
- AgentModelProtocolTests 验证协议自己的 reasoning、配置与超时、零隐藏重试、流式取消 transport，以及两种实际 SDK 向本地 HTTP 服务发送同步/流式请求并解析响应；同步覆盖根路径和自托管路径前缀，流式覆盖分段文本和结束事件，并通过 AiServices 完成一次真实的工具调用—结果回传—最终回复。不是只测试一个假注册类。
- AgentModelProtocolTests 的错误响应探针向两个实际 SDK 返回 400/401/403/404/429/503，核对共享错误码与提示不包含供应商正文；AgentStreamFailureTests 验证包装后的传输故障、SDK 类型、已分类领域异常与超时禁止自动重放。
- 测试使用合成回复，不代表真实供应商可用性、模型质量或所有供应商流式事件格式均已验证。完整异步/流式恢复仍由 Golden Path 验收覆盖。

测试运行时使用项目要求的 Java 21；本机 Java 25 曾在测试 HTTP 服务启动前报 Unable to establish loopback connection，不能把该环境故障当成协议逻辑失败，也不能因此跳过网络合同测试。

本批不更改数据库、连接配置 JSON、外部地址许可、额度、业务状态或桌面协议。可回退应用而保留 schema、连接与历史；不为未知模型发明能力，也不提前引入插件市场或微服务。
