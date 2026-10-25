# Ardor 领域合同与迁移基线

### 助手执行入口与授权口径（本批实现，待部署验收）

普通聊天统一使用同一流式执行入口；“只读”等用户措辞不选择独立 Graph 运行时。
Graph/List 仅展示同一份持久化执行记录，历史阶段记录保留可读。
`AgentContextBuilder` 统一组装系统提示、用户长期记忆、摘要、近期消息、
中断轮次与引用资料；摘要和引用不提升为系统指令。压缩预算包含系统提示与展开后的
当前输入，是可观测的软目标，不通过静默删除消息硬凑预算。

生产工具绑定不再根据用户句子命中哪些动作词、模块词来放行写入。模型根据可信对话
理解操作意图；既有账户/模块权限决定免确认范围，权限不足则请求具体工具参数的确认。
删除仍只能提出真实目标的界面确认提案；用户隔离、状态校验、取消栅栏、写入去重、
答案来源校验不因此放宽。新方案不表示模型可把外部引用指令当作用户请求。

后续权限设置按“每次询问、自动判断、完全权限、自定义”组织；当前 0–3 存储/API
合同仍保留，尚未完成该四模式迁移。旧配置必须保留原授权范围，不把旧等级 1 或 2
静默映射为更宽权限。“自动判断”需有可验证的操作风险与影响范围规则，不能回到
自然语言关键词白名单或以模型自述代替授权。见 [执行轨迹合同](AGENT_TRACE_VIEW.md)。

权限模式候选方案（尚未部署）：新增独立的账户模式记录，缺省保留 CUSTOM 语义。
ASK 查询自动、写入确认；FULL 已登记的非删除写入自动；AUTO 允许普通单条创建/修改，
重复日程、定时自动化、结束/取消模拟面试、覆盖投递总数/节奏先确认，同一轮超过三次
自动写入后继续确认；CUSTOM 读取原全局等级与模块覆盖。模式选择不抹掉自定义配置。
旧客户端写入等级或模块覆盖时回到 CUSTOM；旧 GET 字段保留，新模式提供保守数字投影。
删除与提案专用适配器仍走既有真实目标界面确认，不能由 FULL 绕过。

知识工具采用进程内模块提供者，执行/连接探针共享发现入口；授权、取消、去重、审计与删除确认仍由原宿主执行边界统一管理，见 [助手工具模块合同](ASSISTANT_TOOL_MODULES.md)。它不改变业务资源所有者或工具 scope。

自动化工具使用同一模块机制及 `AutomationView` 应用快照；手动 HTTP 与工具共用原有用户限定校验，调度及 claim 仍属自动化自身。删除工具只在读取真实目标后提出原宿主确认卡，不直接调用删除 Service。工具名、SYSTEM scope、12 字段 JSON、URL 与调度语义不变；没有新增表、权限设置或数据回填。

历史基线包含 PR #133 的确认纪要流程；最新最终纪要模式及兼容边界见下表和 [纪要工作流](INTERVIEW_MINUTES_WORKFLOW.md)。目标见 [整体架构演进规划](ARCHITECTURE_EVOLUTION.md)。本文件区分当前合同、兼容适配与尚待实施的模型，不替代原生 Goal。

## 1. 术语与状态所有权

### 工具与 HTTP 的应用结果边界

学习计划工具由助手域的 `LearningAgentToolProvider` 适配；列表、详情、改期和开始学习调用原 `LearningPlanService` 用户限定快照。创建只构造既有 `LearningCreationCommand`，由宿主记录确认卡及审计；明确来源和独立准备目标、旧 RECAP 兼容、logicalActionId/runId 请求身份算法不变。目标或来源的业务授权仍在确认后的学习应用入口，不因模型能提出建议而授权写入。删除先读取真实所有者目标再提出原确认端点，不直接删除；没有新权限、schema、级联或客户端协议。

日程/重复安排工具的协议适配归助手域 `SchedulingAgentToolProvider`，状态与事务仍归 Scheduling 的应用服务。中央宿主只保留共同执行安全边界，不新增这些工具的平行实现；模型参数不能指定用户。八个旧工具名称、参数和返回投影保持不变，删除仍须真实目标的界面确认。适配器拆分不改变规则物化、时区、保留历史或级联范围，无数据迁移。

个人资料跨域读取使用四字段 `ProfileView`（称呼、定位、目标岗位、时区），不输出用户 ID、偏好或持久实体；手动 HTTP 保留四字段，Agent 保留原三个字段，学习/复习/模拟面试/投递/日程和对话读取同一所有者快照。资料写入仍由原 Service 校验，实体 get/update 收为私有实现，公开的 view/updateView 保留读/写事务边界；侧栏偏好与管理员/认证角色不混入此视图。不是对外 Java SDK 迁移，不改 HTTP 或桌面协议。

日程删除目标使用 `CalendarTaskView`；重复安排删除目标使用现有 `TaskSeriesView` 的规则元数据投影（不展开 upcoming、不物化实例）。查询仍按资源和用户联合限定；删除提案标签、详情与原 API 端点不变，取消重复安排保留历史及已修改实例的规则不变。此批只收敛读取边界，无数据或桌面协议迁移。

简历资料由 `ResumeService` 在用户限定读取内输出 `ResumeView` 元数据与已有 `ResumeAnalysisSnapshot`。列表在资料域按原创建时间排序选择每份简历的首份分析，保留原分数范围；队列状态仍由原队列读取，不新增第二份任务状态。HTTP 保留列表十一字段、报告六字段；工具保留列表七字段和分析四字段，并接入现有模块提供者。应用层校验显式分析 ID 与简历父 ID 一致，不把父子归属校验留给 HTTP。源文件、评分算法、存储键和任务调度不变；本批无数据迁移。

助手的简历/学习上下文引用，以及学习删除目标，读取具名应用视图；不再依赖这些领域的实体。删除仍仅提出真实所有者目标的确认卡，业务删除范围与认证 API 不变。

日程建议使用 Scheduling 的不可变 `CalendarAdviceView`；手动接口保留五字段 HTTP 响应适配。既有截断、去重、旧文本规范化、用户主键与删除范围保持不变，工具适配移到现有模块提供者，不新建授权或状态来源。

简历分析工具直接使用既有队列的七字段 `TaskResult`；HTTP 继续映射原响应。面试记录任务共享 `InterviewRecordTaskView` 作为十字段兼容投影，集中保留旧 `nextAction/draftUrl` 的工作流提示和别名链接；HTTP 和工具不再互相导入。该投影不是新业务实体或任务执行器，不重写任务、来源、状态和 worker fencing。无数据迁移，回退应用即可。

| 对象 | 所有者 | 当前实现与状态 | 不得混同 |
|---|---|---|---|
| 真实面试流程 / InterviewProcess | 求职 | `jobsearch.interview.domain.JobOpportunity`；SCHEDULED、AWAITING_RESULT、NEXT_ROUND、REJECTED | 不是模拟会话，也不是完整投递漏斗 |
| 面试轮次 / InterviewRound | 求职 | `OpportunityRound`，轮次号、环节、时间和时长 | 学习任务不是面试轮次 |
| 面试记录与问题 | 求职 | `jobsearch.record.domain.InterviewRecap` 关联流程与可空轮次；问题通过报告继承 | 不推测历史轮次，不重复要求填写父对象拥有的信息 |
| 最终纪要与规范化结果；历史转录/确认版本 | 求职 | 新提交直接保存最终纪要并异步规范化；V59 模式区分旧转录任务；旧 `interview_minutes` / `interview_minutes_reviews` 历史保留 | 新入口不要求原始转录或二次确认；Agent 不得改写用户提交来源；结果不是事实源；轮次关联覆盖整条来源链 |
| 投递活动 | 求职 | 日计数、目标与提醒 | 日计数不等于逐份投递记录 |
| 学习计划 | 学习 | SCHEDULED、IN_PROGRESS、NEEDS_REVIEW、COMPLETED；LearningPlanView 为 HTTP/Agent 共用的领域应用快照，见 [快照合同](LEARNING_ATTEMPT_HISTORY.md#学习计划应用快照) | 来源与目标面试是关联，不是全部学习都必须有面试父对象 |
| 学习尝试与内容版本 | 学习 | V52 新增 `learning_attempts`（每轮提交一行不可变事实）与 `learning_plans.content_version`/`exercises_version`；旧 `last_evaluation.attempts` 仅作兼容投影 | 尝试历史不是最后分数缓存；版本号不是资源 UUID；回填的 LEGACY 行不伪造评分模型 |
| 模拟面试 | 学习训练 | `learning.practice.mockinterview` 与既有 session 状态 | 名称必须明确 Mock Interview；真实面试保留 Interview |
| 重答与复测 | 学习训练 | `InterviewReplayAttempt` 等现有实现 | 不是录音播放，不与模拟会话共用万能状态表 |
| 记忆卡 | 学习复习 | learning.review 拥有实体、仓储、FlashcardService 和复习历史；旧接口为传输适配，见 [复习合同](FLASHCARD_REVIEW.md) | 不属于面经才能存在，不与 Agent 长期记忆混同 |
| 补强进度 | 学习查询 | LearningProgressService 通过具名跨域接口分页聚合问题、学习、重答与卡片复习；旧 evidence 路径兼容 | 不新建 Evidence CRUD；分数/次数不是能力证明；来源不等于准备目标，见 [查询合同](LEARNING_PROGRESS.md) |
| 简历与知识资料 | 资料 | 现有 resume、knowledge、解析/索引 | 报告是派生结果，不能覆盖源版本 |
| 日程与重复事项 | 日程 | calendar；关联其他领域安排 | 日历完成不能替代学习评分或面试结果 |
| 对话与运行 | 助手 | conversation/message/run/tool/proposal 等既有边界；V53 起工具调用与提案有结构化事实表 | 模型回复、通知与业务写入不是同一事实 |

普通日程与重复规则的 HTTP、Agent 读写共享日程应用层 `CalendarTaskView` / `TaskSeriesView`，不以 Web DTO 作为工具输出合同；沿用原有用户限定查询、GENERAL 修改限制及删除提案确认。写操作在实体生命周期回调刷新后生成不可变快照，保留旧字段、时间戳与 actionPath；重复规则保留更新/保留实例计数，以及按用户和规则查出的实际实例日期，不另生成虚拟日程。实体接口继续为现有领域安排维护方提供兼容入口。

模拟面试的 HTTP 与 Agent 入口共享 `MockInterviewSessionView` / `MockInterviewProgressView` / `MockInterviewEvaluationView`，Web Response 只映射原有 JSON 字段，工具不再依赖模拟面试 Web 类型。问题视图保留 Hot 100 编程题元数据及原 readiness 条件，评价 JSON 递归快照保留 null；取消和回答写入在返回视图前刷新。`createView` / `finishView` 不建立外层事务，模型调用仍在既有短写事务之外。原实体方法供现有领域协作者兼容使用，本批不修改数据库、API URL、scope 或授权语义。

## 2. 路径兼容，不改变身份

模拟训练通过资料应用层 `ResumeService.analysisContext(userId, analysisId)` 获取 `ResumeAnalysisSnapshot`，不再读取简历分析实体。快照保留分析 ID、简历 ID、分析正文、模型、提示词版本和时间，嵌套 JSON 递归只读；资料层仍按分析 ID 与当前用户一起授权。旧报告响应适配相同快照，六个 JSON 字段不变；读取不会启动新分析、读取简历文件或写入记录。助手简历工具与上下文后续也已切到应用视图；`analysisContext` 的原准备接口合同仍保留。

知识资料的应用服务与 Agent 共用 `KnowledgeDocumentView` / `KnowledgeResearchView` / `KnowledgeSearchHit`，HTTP 层仅适配原有文档、收录结果与检索字段。资料服务不再依赖自身 Web DTO；检索正文仍是不可信来源材料，不因类型变更获得执行权限。原有用户限定读取、混合检索排序、嵌入失败的词法降级、提交后索引与删除确认保持不变。文件上传暂仍接收 Spring `MultipartFile`，不宣称完全脱离传输框架。

投递节奏返回 `ApplicationRhythmView`，HTTP 保留 `ApplicationRhythmResponse` 映射；每天 40 份仍是一个计数，不创建岗位实体。长期记忆服务、整理器与工具使用 `AgentMemoryView` / `AgentMemoryItemView`；HTTP 仍适配原字段，当前摘要与含失效项的历史列表保持不同含义。此次类型迁移不改变来源校验、可信度、自动维护、去重、有效期或删除确认，不迁移表、不改写数据。

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
| learning.materials | `/app/materials` | `/app/knowledge`、`/app/learning/materials`：Ardor 的资料与索引（RAG 来源与检索测试），不是学习动作；ID 沿用旧值以兼容侧栏偏好 |
| learning.progress | `/app/learning/progress` | `/app/evidence`：只读补强视图 |

`learning.materials` 只在 ID 上保留 `learning.` 前缀（侧栏偏好兼容）；目录里它的 `domain` 为 `null`，因此归入侧栏“工作台”，不再出现在学习域导航中。资料与索引是页面级入口，不建立前端“资料域”；简历仍作为求职流程中的一个页面，旧版本折叠在历史里。

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

截至 2026-10-21，A–F 的主要批次已合并（PR #134–#157）；后端包结构迁移（B1–B8，PR #149–#157）已完成，`main` 顶层包为 `jobsearch/learning/materials/assistant/scheduling/platform` 与基础设施包，并已通过本机 Docker 重建部署核验。本文件记录已核验的合同，完整证据与未验证项见 [领域重构收尾状态](REFACTOR_STATUS.md)。包移动本身不替代数据迁移、恢复演练、部署验收与旧客户端兼容核验。

本地回归入口：后端 `SidebarPreferencesServiceTests` / `SidebarPreferencesControllerTests` 覆盖旧 URL 映射、双投影原子保存、版本冲突与账户切换拒绝；前端 `navigation-catalog.mjs` 核对两份目录与真实页面，`domain-navigation-ui.mjs` 验证新旧路径、真实/模拟区分、训练参数、纪要编辑及 ID 偏好保存；`workspace-ui.mjs` 保留旧后端协议与账户隔离回归。新增检查接入 CI。它们不替代后续数据迁移、恢复演练和完整主线验收。

回退本批前端可恢复旧菜单；旧后端只读取保留的 `sidebar` 投影。若旧后端修改偏好，递增的 sidebar revision 使新后端忽略过期 navigation 投影，再从旧 URL 适配。保留既有页面与 API，不需要降级数据库或删除用户偏好。

D 的任务边界批次通过领域 Handler 注册执行与重试，应用命令不再复用 Web DTO，详见 [领域任务边界](DOMAIN_JOB_HANDLERS.md)。旧任务类型、payload 和结果链接保持兼容；未知类型拒绝，不回落到其他领域。此处仅约束该批实际列出的边界。

E3 已通过 #161/#162 补已知 HTTP、网络及解析故障的共享分类与流终止；#163 已补严格合成数据库/文件/个人模型配置恢复。未知异常仍保留旧粗分类兜底。当前仍待完成：剩余应用/Web DTO 边界、完整客户端恢复及最新版本部署验收；多副本限制不变（取消不跨实例传播、complete 的 TOCTOU 仅在单实例假设下安全）。目标包结构已于 2026-10-21 完成（PR #149–#157）；历史部署与当前代码的证据必须按提交区分，详见 [领域重构收尾状态](REFACTOR_STATUS.md) 第 6 节。
