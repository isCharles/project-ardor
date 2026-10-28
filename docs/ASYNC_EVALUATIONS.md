# 后台 AI 评估

学习练习、面试回放和模拟面试最终评价通过后台任务提交；学习内容生成也使用同一任务机制。简历分析、面经整理和向量化沿用已有异步任务。

## 用户体验

- 提交返回 HTTP 202 与任务 ID，不等待模型完成。
- 页面显示排队、评估中、完成或失败。耗时区间来自同类型最近 20 次完成任务的 p50–p90（不足 5 个样本时为空），样本不足时界面只显示已用时，不用固定数字猜测。
- 页面每 3 秒查询状态；隐藏页面暂停查询，重新聚焦时刷新。用户可以离开页面或重新打开。
- 答案保留到结果提交成功。失败任务可显式重试；已完成学习轮次保留原答案和反馈，新轮次不沿用旧草稿。

## 接口

| 用途 | 提交 |
| --- | --- |
| 学习内容生成 | `POST /api/learning-plans/jobs` |
| 学习练习评估 | `POST /api/learning-plans/{id}/evaluation-jobs` |
| 面试回放评估 | `POST /api/interview-replays/questions/{id}/jobs` |
| 模拟面试评价 | `POST /api/interviews/{id}/evaluation-jobs` |

查询 `GET /api/ai-jobs/{id}` 或 `GET /api/ai-jobs?type=...&resourceId=...`；失败重试 `POST /api/ai-jobs/{id}/retry`。查询只返回当前账号的任务元信息，不暴露原提交正文。旧同步接口暂时保留兼容，但新界面不使用它们执行上述评估。

## 状态和恢复

V33 新增 PostgreSQL `ai_jobs` 表，原提交及状态持久化。Worker 使用 `FOR UPDATE SKIP LOCKED` 领取任务，每个进程默认并行两个，可用 `app.ai-jobs.worker-capacity`（环境变量 `ARDOR_AI_JOB_WORKER_CAPACITY`）调整，槽位与线程池使用同一上限。聊天消息队列同样默认两个槽位，可用 `app.agent.queue.capacity`（`ARDOR_AGENT_QUEUE_CAPACITY`）调整；等待审批时释放槽位，但运行中的行仍受同一用户串行约束。同一用户、同一资源最多一个排队或运行任务。30 秒心跳；运行任务超过 5 分钟无心跳时，未达 `max_attempts` 的自动回到 `QUEUED` 并延迟 60 秒再领取，已达上限的进入 `DEAD`（`AI_JOB_RECOVERY_EXHAUSTED`），需用户核对后手动重试，不无限消耗模型额度。

任务完成、失败、重新排队、死信和心跳都带 `attempts` 条件；旧 attempt 的迟到回调不能改写新 attempt 的状态，也不能覆盖新 attempt 的结果。指标只在该 attempt 真正写入成功时记录。查询与手动操作仍按用户隔离。

## 取消与恢复边界

| 场景 | 当前支持 | 不承诺 |
| --- | --- | --- |
| AI 任务排队中取消 | `POST /api/ai-jobs/{id}/cancel`，仅 `QUEUED` → `CANCELED` | 运行中的 AI 任务没有取消接口，也不会因取消请求中断模型调用 |
| AI 任务失败/死信 | `POST /api/ai-jobs/{id}/retry`，`FAILED`/`DEAD` → `QUEUED` | 不重置原提交，不保证重试一定成功 |
| 聊天消息排队中取消 | `DELETE /api/agent/queue/{id}`，仅 `QUEUED` → `CANCELLED` | 已开始运行的请求不能用排队取消撤回 |
| 运行中聊天取消 | `POST /api/agent/runs/{id}/cancel`，由运行状态机结束 | 取消不是回滚；已发生的业务副作用必须读回后向用户说明 |
| 进程崩溃/调度状态未知 | 聊天队列超过 2 分钟仍为 `DISPATCHING` 且没有 Run 时标记 `INTERRUPTED`，提示先核对操作结果 | 不自动重放未知写操作；`resume` 只释放 `blocked_by` 依赖，不会重发 `INTERRUPTED` 消息 |
| 旧 attempt 迟到 | 数据库 `attempts` 条件隔离；终态结果不被覆盖 | 不能把数据库行原子领取解释成全业务 Exactly-once |

模型/工具/webhook 返回的“已完成”不是业务成功的证明；未知写操作必须先读取真实业务终态，再决定新建请求还是手动继续。

请求 ID 绑定用户、类型、资源与原提交摘要；相同 ID 不能换正文。同一学习轮次提交使用行锁及轮次检查，已提交成功的请求重试不会再次计分。模拟面试评价提交前重新锁定并核对状态，取消或删除不能被迟到的评价覆盖。Agent 调用结束面试返回任务 ID，可通过 `get_ai_job` 查询，不可声称尚未完成的结果已经完成。

任务暂不自动清理；不得把包含用户答案的 payload 输出到日志、公开接口或管理员正文视图。未来可配置保留周期与基于历史耗时的估算。轮询不是模型 token 流；对话仍使用现有流式通道。

## 队列观测与共享模型容量

采样任务每 15 秒读取一次队列表（`app.observability.queue-sample-interval`，环境变量 `ARDOR_QUEUE_SAMPLE_INTERVAL`），因此仪表盘反映的是最近一次采样，而非抓取瞬间。数据库不可用时只跳过本轮采样，不影响请求处理。

| 指标 | 含义 |
| --- | --- |
| `ardor_agent_queue_depth{status}` | 聊天消息按 `QUEUED`/`DISPATCHING`/`RUNNING` 的数量 |
| `ardor_agent_queue_oldest_queued_seconds` | 最早一条排队消息的等待秒数，持续增长表示整体积压 |
| `ardor_agent_queue_priority_depth{status,priority}` | 聊天消息按状态与优先级（0 免费、1 付费）的数量 |
| `ardor_agent_queue_priority_oldest_queued_seconds{priority}` | 各优先级最早排队消息的等待秒数；免费队列持续偏大即饥饿信号 |
| `ardor_agent_queue_claims`、`ardor_agent_queue_wait` | 已领取数量与领取前等待时间，用于吞吐和等待分布 |
| `ardor_agent_queue_failures{reason}` | 未能开始或中断的消息数；`reason` 区分执行器拒绝、启动失败与未知结果 |
| `ardor_ai_jobs_depth{status}` | 后台 AI 任务按 `QUEUED`/`RUNNING` 的数量 |
| `ardor_ai_jobs_oldest_queued_seconds{priority}` | 各优先级最早排队任务的等待秒数；低优先级持续偏大即饥饿信号 |
| `ardor_agent_job_completions{type,status}`、`ardor_agent_job_retries`、`ardor_agent_job_dead_letters` | 后台任务吞吐、失败、重试与死信 |
| `ardor_agent_job_wait{type}` | 后台任务从入队到被领取的等待时间 |
| `ardor_model_capacity_in_use`、`ardor_model_capacity_rejections{surface}` | 共享模型槽位占用与因容量已满被拒绝的调用数 |
| `ardor_model_rate_rejections{reason}` | 因模型分钟窗口被拒绝的调用；`reason` 为 `qpm`/`tpm`/`user-qpm` |
| `ardor_model_rate_degraded` | Redis 不可用或返回未知结果而放行的调用数 |

聊天消息在入队时快照会员优先级（免费 0、付费 1）。领取顺序按 `priority + 等待秒数 / 60` 降序，再按 `sequence`：付费消息先走一级，但每条免费消息每等待 60 秒增加一级，持续到达的付费消息不能无限期饿死免费消息。优先级只在入队时计算，排队期间升级套餐不会改变已排队消息；这是聊天队列的公平护栏，不是完整的 QPM/TPM 配额。后台 AI 任务同样按会员优先级区分，低优先级年龄持续增长才说明饥饿。`GET /api/agent/queue` 为 QUEUED 消息返回 `queuePosition`：按同一排序计算的用户内名次，1 为下一条，包含该用户其他会话；它是顺序名次，不是预计等待时间，前端在没有吞吐样本时不猜 ETA。

在线聊天流式调用、摘要/标题等辅助对话和后台 JSON 评估共用同一进程内的模型并发上限：`app.model.max-concurrent-calls`（环境变量 `ARDOR_MODEL_MAX_CONCURRENT_CALLS`，默认 8）。容量满时立即以可重试的 `LLM_BUSY` 拒绝，不排队等待：在线请求表现为可重试的 `AGENT_TEMPORARILY_UNAVAILABLE`，由浏览器显式重试；后台任务按 30 秒起步、指数增长的退避重新排队，达到 `max_attempts` 后进入死信。供应商返回的 429 也归类为同一可重试错误，走相同退避。AI 任务与后台任务的预计时长不再填固定的 30–120 秒：没有足够历史样本时接口返回未知（`null`），前端只显示已用时，不把猜测当预计时间。

该并发上限只覆盖单个进程。可另行开启跨实例的分钟窗口准入：`app.model.qpm`（每模型每分钟请求数）、`app.model.tpm`（每模型每分钟估算 token 数）、`app.model.user-qpm`（每用户每分钟请求数），环境变量 `ARDOR_MODEL_QPM`、`ARDOR_MODEL_TPM`、`ARDOR_MODEL_USER_QPM`，默认 0 表示关闭。计数器在 Redis 固定 UTC 分钟窗口内原子检查，共享缓存的实例看到同一预算；模型维度按 provider + Base URL + model 指纹，token 维度采用发送前估算的输入加预留输出（后台 JSON 调用只有输入估算），不采用供应商回传的实际用量。任一维度触顶时与并发满一样返回可重试的 `LLM_BUSY`，在线由浏览器重试、后台按既有退避重排。Redis 不可用时放行并增加 `ardor_model_rate_degraded`，避免限速设施故障中断对话。窗口在分钟边界允许短时双倍突发；该层不是计费或供应商配额的精确复刻，也不覆盖语音等其他供应商调用。TPM 必须高于允许的最大单次请求估算，否则该请求会在任何窗口下都被拒绝。

## 验证

使用隔离数据库与假模型验证 202 立即返回、失败不计分、重试只计一次、跨账号拒绝访问、历史答案保留和模拟面试评价。浏览器验证提交后跳到日历、返回获取反馈，及回放失败后保留草稿再重试。不得对真实用户数据库运行验收造数。
