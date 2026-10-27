# 后台 AI 评估

学习练习、面试回放和模拟面试最终评价通过后台任务提交；学习内容生成也使用同一任务机制。简历分析、面经整理和向量化沿用已有异步任务。

## 用户体验

- 提交返回 HTTP 202 与任务 ID，不等待模型完成。
- 页面显示排队、评估中、完成或失败；通常约 30–120 秒是初始参考范围，不是服务时限或精确预测。
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

任务暂不自动清理；不得把包含用户答案的 payload 输出到日志、公开接口或管理员正文视图。未来可配置保留周期、任务优先级与基于历史耗时的估算。轮询不是模型 token 流；对话仍使用现有流式通道。

## 验证

使用隔离数据库与假模型验证 202 立即返回、失败不计分、重试只计一次、跨账号拒绝访问、历史答案保留和模拟面试评价。浏览器验证提交后跳到日历、返回获取反馈，及回放失败后保留草稿再重试。不得对真实用户数据库运行验收造数。
