# 服务端额度与赠送会员

Ardor 由管理员配置模型服务，用户默认继承；个人 API 覆盖暂时保留。额度保护发生在 Ardor 服务端，客户端无法通过改页面绕过。

## 计数规则

- `@QuotaProtected` 标注需要控制成本的 HTTP 操作：Agent 对话、简历分析、面试生成/评价、面经整理与重试、面试复练、学习计划与练习、知识上传/检索/联网研究、API 连接测试、语音识别/合成。实时语音 WebSocket 在创建 ASR/TTS 会话时调用同一个额度服务。
- 月/周额度是**持久**的：PostgreSQL `usage_quota_counters` 每个「用户 + 功能 + 周期」一行，`INSERT ... ON CONFLICT DO UPDATE ... WHERE used < 上限` 让多实例抢同一个单位时只有一个赢家；月与周在同一个事务里判定，周拒绝不会留下月的增量。清空 Redis 不会再送出一份额度。
- 分钟级突发窗口仍在 Redis，但**每次脚本只碰一个键**（`quota-burst-consume.lua` / `quota-burst-refund.lua`），因此在分片 Redis 上同样有效；它们是限流而不是权益，所以后面的持久层拒绝时会退还一个计数，退还失败也只在 120 秒内自然过期。
- 幂等标记落在 PostgreSQL `usage_quota_requests`（主键 `用户 + 功能 + requestId`，保留 40 天后自动清理），因此跨实例、跨重启、跨 Redis 清空都只扣一次。
- 依赖不可用时**不放行**：Redis 突发窗口或 PostgreSQL 计数无法回答时返回 `QUOTA_EXCEEDED` + `dimension=GUARD_UNAVAILABLE` + HTTP 503 + `Retry-After`，前端沿用现有额度文案。
- 完整口径、硬保证与估算边界见 [ADMISSION_GUARANTEES.md](ADMISSION_GUARANTEES.md)。
- 旧版本按 UTC 自然月/自然周在 Redis 计数；切换到持久计数的那一次部署，本周期已用量会从 0 重新起算（一次性回补），需要精确结转时可在切换前把旧 `ardor:quota:user-month:*` / `ardor:quota:user-week:*` 的值写入新表。
- 月额度统计**已接收的操作**，不是成功回答数。流式对话必须携带 `requestId`；同一请求 ID 的重复送达不会再次计数，现有 Agent 执行记录也阻止重复执行。新请求应使用新的 ID。旧的非流式 `/api/agent/messages` 不具备执行幂等性，重复提交会分别计数；当前前端只调用流式接口。异步任务被接受后即计数，后台失败不会自动返还，因为提供商可能已经产生费用。
- `GET /api/usage` 展示当前档位、各功能的月度与自然周已用/上限，以及下月 UTC 重置时间。设置页按“先耗尽的窗口”显示单项额度：FREE 的对话周额度 2 次通常比月额度 5 次更早拒绝请求，此时显示周窗口和“每周重置”，否则显示月窗口。这些数字直接读自 PostgreSQL 周期计数，刷新页面或重启服务都不会回零。

## 赠送会员

数据库迁移为所有现有账户写入 `MEMBER`，并标记赠送来源 `GIFT`；新注册账户在同一事务中获得赠送会员。无到期时间、无自动扣款。管理员可在管理工作台将某个账户改为 `FREE`，也可调整每项功能的免费/月、会员/月、每人/分和全站/分额度。示例默认值中，Agent 对话为免费每月 5 次、会员每月 500 次；其他功能分别设置上限。管理员手动改动会员时来源记为 `ADMIN`，为以后接入付费订阅保留独立的来源字段。

这只是“会员权益和额度”的基础，不包含购买、续费、支付、自动降级或资金处理。管理员 API 在 `/api/admin/**`，沿用现有 ADMIN 身份保护。

## 部署检查

本仓库后端 Dockerfile 复制 `backend/target/ardor-backend-0.2.0.jar`，所以须先在 `backend` 运行 `mvn package`，再执行 `docker compose up -d --build backend frontend`。只运行 `mvn test` 后重建镜像仍会复制旧 JAR，容器可显示健康却不会执行 V26。

部署时确认 PostgreSQL 的 V26 与 V96 迁移成功、实际登录用户能访问 `/api/usage`。分钟突发脚本是 `backend/src/main/resources/redis/quota-burst-consume.lua` 与 `quota-burst-refund.lua`；`quota-redis.yml` 在隔离 Redis 上测试上限、零上限、退还与并发争用。Redis 只承载突发窗口与会话，不再保存用户的月/周权益，因此仍建议开启持久化，但清空它不会退还已用掉的套餐额度。修改额度后，新请求立即读取新策略。`/api/admin/usage-policies` 与会员修改端点由 `SecurityConfig` 的 `/api/admin/**` 规则保护，非管理员均应返回 403，回归测试覆盖这三种请求。
