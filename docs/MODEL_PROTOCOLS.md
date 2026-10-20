# 模型协议适配边界

模型服务仍由现有连接配置、密钥加密和 ExternalBaseUrlPolicy 管理。协议身份 LlmProvider 不是模型品牌，也不是 Agent 功能、导航 ID 或授权范围；同一兼容协议可用于不同服务商和模型。

## 执行合同

- 普通分析由 CompatibleLlmGateway 根据注册的 JsonModelProtocol 调用协议适配器。适配器拥有 HTTP 路径、请求体、鉴权头与返回结构；网关保留错误分类。业务 Service 继续只使用 LlmGateway。
- Agent 由 LangChainModelFactory 根据注册的 AgentModelProtocol 创建同步或流式模型。协议适配器拥有 SDK 参数；工厂保留当前用户配置选择、超时、代理及取消租约的 transport 包装。
- ModelProtocols 在启动时拒绝重复、缺失及无身份的注册；不按集合顺序猜一个实现，不在协议调用失败后试另一种协议。
- 现有协议为 OPENAI_COMPATIBLE、ANTHROPIC_COMPATIBLE。接入新品牌但沿用兼容协议只需连接配置；新增真正不同的协议须声明稳定身份，并提供 JSON 与 Agent 适配器及合同测试，不修改中央执行分支或业务 Service。
- Agent 适配器可提供自己的 reasoning 映射。现有模型仍复用 ModelReasoningPolicy 的保守映射；未知模型不会凭名称自动获得“原生思考控制”。该静态映射不是服务商实时能力探测。

Spring 生产装配使用注册的适配器列表。保留的显式构造器用于既有非 Spring 调用/测试的两种默认协议，不是另一套生产注册表。

## 请求与失败语义

保留已有 JSON 分析的 temperature、Anthropic max_tokens、输出截断拒绝和空内容拒绝。JsonModelProtocol 返回原始模型内容，领域仍由现有 LlmJsonParser/schema 校验，不在协议层把不合法 JSON 变成业务事实。

Anthropic 的存储 Base URL 沿用普通分析网关的服务根地址合同：JSON 请求追加 /v1/messages；SDK 自己追加 /messages，因此 Agent 适配器追加 /v1 前缀。修复前同一根地址会在 Agent 路径丢失 /v1；本地实际 SDK HTTP 测试复现并覆盖了此差异。自托管网关的原有路径前缀保留；不自动试探备用路径或改投另一种协议。

普通分析错误继续区分请求/鉴权/不存在/繁忙/上游 HTTP、DNS、连接、超时、TLS、网络、空输出、截断及无法解析。错误提示不拼接供应商响应体或密钥；可重试标记保留。Agent 的既有错误分类与浏览器可见重试流程保持，非流式 SDK 内部重试仍为零，流式取消仍经 AgentStreamLease；这不承诺远端生成已停止或已经发生的工具写入可以回滚。

## 验证与演进边界

- CompatibleLlmGatewayTests 保留两种 JSON 协议的请求与截断合同。
- ModelProtocolContractTests 验证注册、失败不跨协议回退、两种 HTTP 协议的状态码/重试标记、网络分类、空/坏输出以及错误提示不包含供应商正文。
- AgentModelProtocolTests 验证协议自己的 reasoning、配置与超时、零隐藏重试、流式取消 transport，以及两种实际 SDK 向本地 HTTP 服务发送同步/流式请求并解析响应；同步覆盖根路径和自托管路径前缀，流式覆盖分段文本和结束事件，并通过 AiServices 完成一次真实的工具调用—结果回传—最终回复。不是只测试一个假注册类。
- 测试使用合成回复，不代表真实供应商可用性、模型质量或所有供应商流式事件格式均已验证。完整异步/流式恢复仍由 Golden Path 验收覆盖。

测试运行时使用项目要求的 Java 21；本机 Java 25 曾在测试 HTTP 服务启动前报 Unable to establish loopback connection，不能把该环境故障当成协议逻辑失败，也不能因此跳过网络合同测试。

本批不更改数据库、连接配置 JSON、外部地址许可、额度、业务状态或桌面协议。可回退应用而保留 schema、连接与历史；不为未知模型发明能力，也不提前引入插件市场或微服务。
