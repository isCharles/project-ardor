# 准入分层与保证清单

Ardor 的一次付费操作要穿过四层互相独立的闸门。它们口径不同、存储不同，因此**不能互相替代**：业务额度是产品权益，供应商限额是外部约束，并发许可是资源保护，Token 预算是费用控制。本文写清每层现在保证什么、什么只是估算。

## 分层

| 层 | 约束对象 | 存储 | 失败时行为 |
| --- | --- | --- | --- |
| 业务请求额度（月/周） | 用户 + 功能在一个业务周期内可发起多少次 | PostgreSQL `usage_quota_counters` | 拒绝（`GUARD_UNAVAILABLE`，HTTP 503） |
| 短时突发限流（分钟） | 单用户、全站单功能在一分钟内的请求速率 | Redis 单键 Lua | 拒绝（同上） |
| 供应商调用限额（QPM/TPM） | 一个供应商账户在滚动窗口内的请求与 token 速率 | Redis（见「尚未实现」） | 见下 |
| 并发许可 | 同一时刻真正发出的外部调用数 | 进程内 `Semaphore`（见「尚未实现」） | `LLM_BUSY` 可重试 |
| Token 预算 | 用户本月可消耗的 token（套餐 + 资源包） | PostgreSQL `usage_token_ledger` | 拒绝（`TOKEN_LIMIT`） |

## 判定顺序与补偿方向

`QuotaService.consume` 按「先可自愈、后持久」扣减：

1. Token 预算预检（读持久账本，不写）。
2. 幂等标记预读：已扣过的 `requestId` 直接放行，连突发窗口都不碰。
3. Redis 突发窗口（用户/分 → 全站/分）。
4. PostgreSQL 周期计数（月 + 周在**同一个事务**里判定并递增）。

任何一步拒绝，都只回滚它自己前面的**突发**计数（`quota-burst-refund.lua`）。持久层永远排在最后，因为它写下的东西无法安全撤销：一次已经发给供应商的请求可能已经计费，退还额度等于白送。反过来，突发计数即便补偿失败，也会在 120 秒内自然过期，最多让某一分钟略紧。

Redis 与 PostgreSQL 之间**没有**分布式事务，也不需要：崩溃后可能出现的偏差只有一个方向——突发窗口多算了 1（自愈），周期计数要么整笔提交要么整笔不提交。

## 硬保证

- **同一事务内不会只扣一半维度。** 月与周用 `INSERT ... SELECT ... WHERE limit > 0 ON CONFLICT DO UPDATE ... WHERE used < limit`，行数为 0 即拒绝并回滚整个事务，包括已写的幂等标记。
- **多实例抢最后一个单位只会赢一次。** 周期行上的行锁由 PostgreSQL 提供；24 个并发调用上限 5 时，恰好 5 个成功（`QuotaCounterStorePostgresTests`）。
- **额度不随 Redis 清空而复活。** 月/周计数只在 PostgreSQL；`FLUSHALL` 之后用户仍然用不了超出套餐的额度。
- **重复 `requestId` 只扣一次，且跨实例、跨重启成立。** 幂等标记是主键 `(user_id, feature, request_id)`，保留 40 天后由 `purgeStaleMarks` 清理。
- **依赖不可用不会静默放行。** Redis 突发窗口或 PostgreSQL 计数无法回答时返回 `GUARD_UNAVAILABLE`（HTTP 503 + `Retry-After`），错误码仍是 `QUOTA_EXCEEDED`，前端沿用既有额度文案。
- **周期口径唯一。** 月 = UTC 自然月，周 = 周一 00:00 UTC 起（键就是那个周一的日期），重置时间与展示时间由同一个 `QuotaPeriods` 计算，不再出现「按周编号键、按周一重置」的错配。

## 估算与误差边界

- **Token 预算是「事后结算」的预检。** 判定读的是已结算账本，正在飞行中的请求尚未入账，所以并发请求可能合计超出预算约「一个请求的输出上限」。收紧方式是预占（见下）。
- **月/周额度统计的是「已接收的操作」，不是成功回答。** 供应商返回失败也不退还额度，因为上游可能已经计费。
- **突发窗口以 UTC 分钟对齐。** 它有意保留固定窗口的粗糙：跨分钟边界的突发最多允许 2× 每分钟上限，这是限流而非权益，收紧由供应商层负责。
- **管理员改额度只影响之后的判定**，不改写历史计数；套餐降级也不会回收已用掉的额度。

## 迁移影响

- 新增 `V96__add_durable_quota_counters.sql`（两张表 + 一个索引，纯新增）。
- 升级瞬间，本周期内的 Redis 旧计数不再被读取：当月/当周已用量按 PostgreSQL 里的 0 重新起算，用户会看到一次性的额度回补。需要精确结转时，可在切换前把 `ardor:quota:user-month:*` / `ardor:quota:user-week:*` 的值写入新表。
- 旧的 `quota-consume.lua` 与其 CI 脚本随之下线，突发窗口改用两个单键脚本。

## Redis 拓扑约束

所有 Lua 脚本**每次只碰一个键**（`quota-burst-consume.lua`、`quota-burst-refund.lua`）。原 `quota-consume.lua` 一次操作 5 个键且没有 hash tag，在 Redis Cluster 上会因 CROSSSLOT 直接失败——那会让每个受额度保护的接口报错。当前部署是单机 Redis（`spring.data.redis.host/port`，未配置 cluster），但这条约束写进代码注释与测试，避免以后换拓扑时踩雷。

## 尚未实现（本清单的诚实边界）

1. **供应商 QPM/TPM 仍是固定 UTC 分钟键，且按模型名分桶。** 官方口径是账户/组织级、Anthropic 为持续补充的令牌桶、Kimi 按 `max_completion_tokens` 预留、DeepSeek 与智谱只公布并发。需要改为按凭据分桶的严格滚动窗口/令牌桶，并区分输入与输出预算。
2. **并发许可仍是进程内信号量。** 多实例部署时实际上限是 `实例数 × max-concurrent-calls`，且流式调用若拿不到终态回调会一直占着许可。需要带 TTL 与 fencing token 的 Redis 租约。
3. **Token 只有结算、没有预占。** 且只有 Agent 路径写账本：简历分析、面经整理、学习计划、语音、嵌入等 JSON 网关调用不记账，供应商返回的 `usage` 被丢弃。
4. **未被额度覆盖的入口：** 模型列表探测（`/api/settings/llm/models`）、后台嵌入回填调度器、Tavily 抓取。
