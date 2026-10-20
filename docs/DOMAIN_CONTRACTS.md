# Ardor 领域合同与迁移基线

核对日期：2026-10-19。主分支基线包含 PR #133 的确认纪要流程。目标见 [整体架构演进规划](ARCHITECTURE_EVOLUTION.md)。本文件区分当前合同、兼容适配与尚待实施的模型，不替代原生 Goal。

## 1. 术语与状态所有权

| 对象 | 所有者 | 当前实现与状态 | 不得混同 |
|---|---|---|---|
| 真实面试流程 / InterviewProcess | 求职 | `opportunity.JobOpportunity`；SCHEDULED、AWAITING_RESULT、NEXT_ROUND、REJECTED | 不是模拟会话，也不是完整投递漏斗 |
| 面试轮次 / InterviewRound | 求职 | `OpportunityRound`，轮次号、环节、时间和时长 | 学习任务不是面试轮次 |
| 面试记录与问题 | 求职 | `recap.InterviewRecap` 关联流程与可空轮次；问题通过报告继承 | 不推测历史轮次，不重复要求填写父对象拥有的信息 |
| 原始转录、确认纪要与复盘版本 | 求职 | 原始任务保留原文；`interview_minutes` 可编辑；`interview_minutes_reviews` 保存不可变确认快照与异步结果 | Agent 不能代替用户确认；报告不是原文；新增轮次关联必须覆盖整条来源链 |
| 投递活动 | 求职 | 日计数、目标与提醒 | 日计数不等于逐份投递记录 |
| 学习计划 | 学习 | SCHEDULED、IN_PROGRESS、NEEDS_REVIEW、COMPLETED | 来源与目标面试是关联，不是全部学习都必须有面试父对象 |
| 学习尝试与内容版本 | 学习 | V52 新增 `learning_attempts`（每轮提交一行不可变事实）与 `learning_plans.content_version`/`exercises_version`；旧 `last_evaluation.attempts` 仅作兼容投影 | 尝试历史不是最后分数缓存；版本号不是资源 UUID；回填的 LEGACY 行不伪造评分模型 |
| 模拟面试 | 学习训练 | `interview` 包与既有 session 状态 | 名称必须明确 Mock Interview；真实面试保留 Interview |
| 重答与复测 | 学习训练 | `InterviewReplayAttempt` 等现有实现 | 不是录音播放，不与模拟会话共用万能状态表 |
| 记忆卡 | 学习复习 | learning.review 拥有实体、仓储、FlashcardService 和复习历史；旧接口为传输适配，见 [复习合同](FLASHCARD_REVIEW.md) | 不属于面经才能存在，不与 Agent 长期记忆混同 |
| 补强进度 | 学习查询 | LearningProgressService 通过具名跨域接口分页聚合问题、学习、重答与卡片复习；旧 evidence 路径兼容 | 不新建 Evidence CRUD；分数/次数不是能力证明；来源不等于准备目标，见 [查询合同](LEARNING_PROGRESS.md) |
| 简历与知识资料 | 资料 | 现有 resume、knowledge、解析/索引 | 报告是派生结果，不能覆盖源版本 |
| 日程与重复事项 | 日程 | calendar；关联其他领域安排 | 日历完成不能替代学习评分或面试结果 |
| 对话与运行 | 助手 | conversation/message/run/tool/proposal 等既有边界；V53 起工具调用与提案有结构化事实表 | 模型回复、通知与业务写入不是同一事实 |

## 2. 路径兼容，不改变身份

规范入口收拢在 `/app/job-search` 和 `/app/learning`。旧页面与规范路径复用同一个实现，保留 query string 与资源 ID；不得复制一套写入流程。旧 API 的语义不因产品命名而改变。

| 稳定 destination ID | 规范页面 | 旧页面 / 对外 API 保持的含义 |
|---|---|---|
| job-search.interviews | `/app/job-search/interviews` | `/app/opportunities`、`/api/opportunities`：真实面试 |
| job-search.applications | `/app/job-search/applications` | `/app/applications`：投递计数 |
| job-search.resumes | `/app/job-search/resumes` | `/app/resumes`：简历 |
| job-search.notes | `/app/job-search/notes` | `/app/recaps`、`/api/interview-recaps`：面试记录；旧独立入口仅作兼容/快捷方式 |
| learning | `/app/learning` | 主题学习原路径与 `?id=` 保留 |
| learning.mock-interviews | `/app/learning/practice/mock-interviews` | `/app/interviews`、`/api/interviews` 继续且只表示模拟面试 |
| learning.question-practice | `/app/learning/practice/questions` | `/app/replay?question=…&retest=…`：单题训练与复测 |
| learning.flashcards | `/app/learning/review` | `/app/cards`、`/api/memory-cards`：复习 |
| learning.materials | `/app/learning/materials` | `/app/knowledge`：资料 |
| learning.progress | `/app/learning/progress` | `/app/evidence`：只读补强视图 |

日程 actionPath、任务 resultPath、旧聊天链接、浏览器收藏和桌面原生桥仍可能持有旧 URL，不能删除兼容页。AgentFeature.INTERVIEWS 仍是原有模拟意图，ToolModule.INTERVIEWS 仍是原有授权范围；新导航层不会重新解释它们。

## 3. 侧栏偏好合同

- 目录清单发布为前端与后端各一份 `navigation-catalog.json`，内容必须一致；后端测试比较两份发布资源，避免独立构建产生漂移。目录是 UI 合同，不是授权注册表。
- 默认固定 Chat、Job search、Learning、Calendar。旧账户的显式固定项迁成相应子入口，不静默恢复默认。自定义界面按领域分组，仍可固定子入口。
- `GET /api/profile/sidebar` 在原字段之外提供 `destinationIds`；新 PUT 使用 `destinationIds`，旧 PUT 的 `hrefs` 继续适配。二者只能选一组，并继续验证 expectedUserId 与 expectedRevision。
- `preferences.sidebar` 保留旧的 hrefs/revision 结构；`preferences.navigation` 保存 ID 与同一 revision。两者通过一次受用户及版本限定的 SQL 原子写入，不产生两套独立偏好真相源。
- 新字段版本与 sidebar 不一致时，表示旧服务器曾修改配置，重新按旧 hrefs 解析，不读取过期投影。旧客户端无法区分共享兼容 URL 的领域入口与子入口时，保存保留当前已选 ID，而非猜测替换。
- 本机缓存升级到 v3，读取旧 v2 缓存和 v1 选择；不删除旧本机配置。未知新 ID 使新版配置解析/保存停止，不用默认布局覆盖服务器。
- 不兼容的新前端连接旧后端时，按旧 hrefs 协议降级；旧界面只能表达它认识的入口，不能承诺它展示新的完整领域层级。

## 4. 删除与数据迁移基线

A/B 阶段不改 schema；C 的轮次关联通过 V50 新增可空列与复合外键，详见 [面试记录归属](INTERVIEW_RECORD_CONTEXT.md)。不改资源 UUID 或扩大删除行为。现有约束核对：

- 删除真实流程会级联删除其轮次；记录/分析任务等流程关联按 V40/V41 置空，不因此删除记录或学习成果。
- 删除面试记录会级联其问题；问题删除会级联重答/复测，卡片的问题关联置空。该历史行为在后续迁移前必须单独确认是否调整，不能悄悄扩大。
- 删除学习计划会标记创建请求的删除状态，删除计划；日程的学习计划外键按既有迁移级联处理。
- 新增轮次关联可空、核验用户及流程；历史项保留未指定，可按流程统计核对。不得回填假轮次。
- V51 将规范学习来源区分为记录与问题，原 RECAP/sourceId 仅作兼容投影；无法可靠解析的历史项标为 LEGACY_RECAP。准备目标独立保存、不从来源推断。见 [学习来源与目标合同](LEARNING_ORIGIN_CONTEXT.md)。
- V52 新增学习尝试历史与内容/题目版本，旧 JSONB 投影、旧字段和资源 UUID 保持兼容；回填只从旧投影还原可证明字段，缺少 rubric/model 的历史行标 `legacy` 而不伪造；不一致历史（投影条数 > attempt_count）回填后 `attempt_no` 可超过 `attempt_count`，运行时按已存最大序号继续、允许跳号且不改写 `attempt_count`；删除学习计划通过复合外键不可逆级联删除尝试，是本批唯一新增的删除级联。见 [学习尝试历史与内容版本合同](LEARNING_ATTEMPT_HISTORY.md)。

E2 的 V53 只新增可空列、新表和索引：`messages.request_id/run_id` 仅回填可证明的已完成交流（`agent_runs.assistant_message_id` 唯一指向 + 紧邻且内容一致的用户消息），无法证明的历史保持 NULL；新增 `agent_tool_calls`、`agent_proposals` 记录工具调用与提案生命周期，`agent_tool_audit` 增加 `approval_source` 且旧行默认 AUTO。旧 `messages.tool_calls`/`proposal_resolutions` JSON 投影继续写入；提案首决定与旧 key 兼容。提案的 CONFIRMED 是用户决定报告，不是业务写入成功的证明；已开始但未返回结果的工具在取消/超时/失败时记为 UNKNOWN。详见 [Agent 执行关联与审计合同](AGENT_EXECUTION_CONTRACTS.md)。

## 5. 分阶段交付与未完成项

本批覆盖 A/B：正式规划、领域合同、稳定入口目录、偏好兼容、新路径/旧路径共存与领域导航。以相关测试、CI、合并和部署证据为验收依据；本文件不预先声称这些检查已通过。

本地回归入口：后端 `SidebarPreferencesServiceTests` / `SidebarPreferencesControllerTests` 覆盖旧 URL 映射、双投影原子保存、版本冲突与账户切换拒绝；前端 `navigation-catalog.mjs` 核对两份目录与真实页面，`domain-navigation-ui.mjs` 验证新旧路径、真实/模拟区分、训练参数、纪要编辑及 ID 偏好保存；`workspace-ui.mjs` 保留旧后端协议与账户隔离回归。新增检查接入 CI。它们不替代后续数据迁移、恢复演练和完整主线验收。

回退本批前端可恢复旧菜单；旧后端只读取保留的 `sidebar` 投影。若旧后端修改偏好，递增的 sidebar revision 使新后端忽略过期 navigation 投影，再从旧 URL 适配。保留既有页面与 API，不需要降级数据库或删除用户偏好。

D 的任务边界批次通过领域 Handler 注册执行与重试，应用命令不再复用 Web DTO，详见 [领域任务边界](DOMAIN_JOB_HANDLERS.md)。旧任务类型、payload 和结果链接保持兼容；未知类型拒绝，不回落到其他领域。此处仅约束该批实际列出的边界。

后续仍包含 C–F 的剩余验收：训练/进度查询的后端归属、其他跨域接口和工具解耦、Agent 运行/引导/恢复合同、模型能力与错误分类、观测/备份/部署及真实扩展验收。各批测试、CI、合并及部署应分别核验，普通导航整理或 Handler 注册不能被算作全部目标的完成证据。学习尝试历史与内容版本合同已由 V52 落地，见 [学习尝试历史与内容版本合同](LEARNING_ATTEMPT_HISTORY.md)；它不等于 C 阶段其余训练/进度归属已完成。
