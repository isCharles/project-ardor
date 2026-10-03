# 服务端额度与赠送会员

Ardor 由管理员配置模型服务，用户默认继承；个人 API 覆盖暂时保留。额度保护发生在 Ardor 服务端，客户端无法通过改页面绕过。

## 计数规则

- `@QuotaProtected` 标注需要控制成本的 HTTP 操作：Agent 对话、简历分析、面试生成/评价、面经整理与重试、面试复练、学习计划与练习、知识上传/检索/联网研究、API 连接测试、语音识别/合成。实时语音 WebSocket 在创建 ASR/TTS 会话时调用同一个额度服务。
- Redis Lua 脚本一次检查并递增三个维度：**用户 + 功能 + UTC 自然月**、**用户 + 功能 + UTC 分钟**、**全站 + 功能 + UTC 分钟**。任一维度超限时不递增其他维度，返回 HTTP 429、`Retry-After` 和重置时间。Redis 不可用时请求失败关闭，不允许付费操作无计数地继续。
- 月额度统计**已接收的操作**，不是成功回答数。流式对话必须携带 `requestId`；同一请求 ID 的重复送达不会再次计数，现有 Agent 执行记录也阻止重复执行。新请求应使用新的 ID。旧的非流式 `/api/agent/messages` 不具备执行幂等性，重复提交会分别计数；当前前端只调用流式接口。异步任务被接受后即计数，后台失败不会自动返还，因为提供商可能已经产生费用。
- `GET /api/usage` 展示当前档位、各功能本月已用/上限与下月 UTC 重置时间。Redis 计数有过期时间，当前不是支付账本；正式收费前需增加不可篡改的调用/计费流水、对账与退款规则。

## 赠送会员

数据库迁移为所有现有账户写入 `MEMBER`，并标记赠送来源 `GIFT`；新注册账户在同一事务中获得赠送会员。无到期时间、无自动扣款。管理员可在管理工作台将某个账户改为 `FREE`，也可调整每项功能的免费/月、会员/月、每人/分和全站/分额度。示例默认值中，Agent 对话为免费每月 5 次、会员每月 500 次；其他功能分别设置上限。管理员手动改动会员时来源记为 `ADMIN`，为以后接入付费订阅保留独立的来源字段。

这只是“会员权益和额度”的基础，不包含购买、续费、支付、自动降级或资金处理。管理员 API 在 `/api/admin/**`，沿用现有 ADMIN 身份保护。

## 部署检查

本仓库后端 Dockerfile 复制 `backend/target/ardor-backend-0.2.0.jar`，所以须先在 `backend` 运行 `mvn package`，再执行 `docker compose up -d --build backend frontend`。只运行 `mvn test` 后重建镜像仍会复制旧 JAR，容器可显示健康却不会执行 V26。

部署时确认 PostgreSQL 的 V26 迁移成功、Redis AOF 正常、实际登录用户能访问 `/api/usage`。`backend/src/main/resources/redis/quota-consume.lua` 是生产使用的限流脚本；`quota-redis.yml` 在隔离 Redis 上测试月上限、重复 ID 和并发争用。修改额度后，新请求立即读取新策略。`/api/admin/usage-policies` 与会员修改端点由 `SecurityConfig` 的 `/api/admin/**` 规则保护，非管理员均应返回 403，回归测试覆盖这三种请求。
