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
- **供应商速率桶要么整笔扣、要么整笔不扣。** 一次 Lua 判定同一凭据的全部桶（账户/模型 × 请求/Token，外加每用户请求），任一桶不足就一个都不写；拿到许可但被后续层拒绝的调用会把扣掉的容量退回（`model-rate-refund.lua`）。退回只认还没到期的一笔：`model-rate-admit.lua` 会把这次写入的到期时刻一并返回，退回晚到而该桶已被后来的调用重新计时时，脚本什么都不做——否则等于把别人付过的等待抹掉，出站速率反而超过配置。Redis 无法回答时不再放行，而是 `LLM_GUARD_UNAVAILABLE`（503 + `Retry-After`），因为一个看不见的限流器比一次可见的重试更坏。

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

Lua 脚本**每次只碰一个键，或者把全部键放进同一个 hash tag**。`quota-burst-consume.lua` 与
`quota-burst-refund.lua` 是单键；`model-rate-admit.lua` 与 `model-rate-refund.lua` 一次操作同一凭据的
多个桶，因此每个键都带 `{account}` 标签——它们必然落在同一个 slot，跨 slot 的原子判定在 Cluster 上根本
不允许存在。原 `quota-consume.lua` 一次操作 5 个键且没有 hash tag，在 Redis Cluster 上会因 CROSSSLOT
直接失败——那会让每个受额度保护的接口报错。当前部署是单机 Redis（`spring.data.redis.host/port`，未配置
cluster），但这条约束写进代码注释与测试，避免以后换拓扑时踩雷。

桶的微秒算术与退回方向只能在真 Redis 上验，因此 `quota-redis.yml` 除突发脚本外还在隔离 Redis 上跑
`backend/src/test/resources/redis/model-rate-test.sh`：空桶首个调用放行并回报到期时刻、同一微秒的第二个
调用被指名拒绝且不写任何桶、到期前退回把容量还原、到期后退回什么也不动、宽突发下前一笔的退还不影响后一笔。

## 尚未实现（本清单的诚实边界）

1. **供应商 QPM/TPM 已改为按凭据分桶的连续补充令牌桶**（`SlidingRateWindow` + `model-rate-admit.lua`）：
   跨分钟边界不再能拿到 2× 每分钟上限，同一凭据下的不同模型名共用账户级预算。仍未做的部分：
   Token 维度用的是发送前估算（输入估算 + 预留输出），不是供应商回传值；供应商真实返回的
   `Retry-After` 头没有被解析回填，等待时间来自本地桶的排空估计；桶状态只在 Redis，清库即清零债务。
2. **并发许可仍是进程内信号量。** 多实例部署时实际上限是 `实例数 × max-concurrent-calls`，且流式调用若拿不到终态回调会一直占着许可。需要带 TTL 与 fencing token 的 Redis 租约。
3. **Token 只有结算、没有预占。** 且只有 Agent 路径写账本：简历分析、面经整理、学习计划、语音、嵌入等 JSON 网关调用不记账，供应商返回的 `usage` 被丢弃。
4. **未被额度覆盖的入口：** 模型列表探测（`/api/settings/llm/models`）、后台嵌入回填调度器、Tavily 抓取。
