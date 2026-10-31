# 岗位发现与岗位收件箱

岗位发现（V94）是求职域中的一条流程：按用户目标岗位搜索公开职位，去重后保存到个人岗位收件箱，供用户筛选、收藏、忽略和打开原始申请页。它不新增与求职、学习并列的顶层模块，也不属于模拟面试；页面位于 `/app/job-search/discovery`，从求职首页的“岗位收件箱”卡片进入。

## 业务边界

- 一次发现 = `搜索 → 去重 → 保存 → 浏览筛选 → 打开申请链接`。第一版不自动投递、不发送消息、不填写申请表。
- **发现、收藏、忽略、打开申请页都不等于已经投递。** 只有用户在界面上明确确认“简历已经实际发出”后，才可标记为已投递；该操作复用投递节奏的 `RESUME` 计数，每岗位只计一次，不新增投递活动表。
- 打招呼数与简历投递数仍是 [投递节奏](DOMAIN_CONTRACTS.md) 的两个独立口径；岗位发现不改写、不推算历史计数。
- 岗位与面试流程可以人工关联，但发现不会自动创建面试流程，也不写 `opportunities`。
- 登录墙、验证码、访问限制只记录为链接状态并明确展示，不做绕过。岗位关闭或链接不可访问是链接事实，不能当作“用户被拒绝”，也不会自动忽略该岗位。
- 页面事实与模型判断分开保存：`match_reason`/`match_evidence` 是确定性的关键词/地点判断及其页面或摘要依据；未访问到的薪资、经验、截止日期保持 NULL，不由模型补造。

## 数据模型（V94）

外部岗位信息与用户状态分表，避免把收藏/忽略混进公共搜索结果：

| 表 | 所有者 | 内容 |
|---|---|---|
| `job_postings` | 公共岗位事实 | 规范化去重键、公司、标题、地点、申请链接、来源、可空薪资/经验/截止、`link_status`、首次发现与最近核验时间。没有“收藏/忽略”列 |
| `job_posting_sources` | 公共来源 | 每个指向同一岗位的来源与原始 URL，保留多来源，来源失效不丢岗位 |
| `job_posting_discoveries` | 用户 | 用户与岗位唯一关联：`NEW`/`SAVED`/`IGNORED`、匹配理由与依据、发现查询、发现/最近看到时间、`applied_at` |
| `job_discovery_preferences` | 用户 | 上次搜索的目标岗位、地点、关键词 |
| `job_discovery_runs` | 用户 | 一次搜索的业务状态：条件、查询、进度计数、逐查询错误、取消请求与时间；执行仍由 `ai_jobs` 队列承担 |

`background_task_catalog` 视图在 V94 中重建，仅把 `JOB_DISCOVERY` 映射到 `applications` 组和 “Job discovery” 标题；旧视图改名为 `background_task_catalog_before_discovery`，不复制任务数据。V94 同时扩展 `ai_jobs` 的 `ck_ai_jobs_type` 约束以接受新任务类型；不改写 V33，也不复制任务数据。V94 只新增表/索引、替换约束和视图，不改写已执行迁移。

## 去重与来源

`JobPostingKeys.canonicalKey` 在抓取后对**校验过的最终申请 URL**生成身份（搜索结果的原始 URL 只作为来源保留），按以下顺序判断，绝不只按“公司 + 标题”合并：

1. ATS 岗位 ID：Greenhouse `greenhouse:<id>`、Lever/Ashby UUID、SmartRecruiters `<company>:<id>`、Workday `<host>:<RID>`、iCIMS `<host>:<id>`。
2. 其他链接：`url:<host><path><sorted params>`；忽略 scheme、末尾斜杠、fragment、`utm_*` 与常见追踪参数（`gclid`、`fbclid`、`ref`、`lever-source` 等），参数排序后比较。
3. 重复发现同一个规范化身份时复用原岗位及其用户状态；更新岗位事实并追加来源，`NEW` 不会因为再次搜索被重置，`SAVED`/`IGNORED` 保留。
4. 申请链接优先保留雇主/ATS 页面而不是聚合站：来源优先级 ATS = 0、雇主站 = 1、聚合站 = 2；较低优先级的新来源不能覆盖已保存的申请链接。

## 抓取与解析

`SafePageFetcher` 复用 `ExternalBaseUrlPolicy.validatePublicUrl` 的公共地址校验（与 Base URL 校验共享主机/DNS 规则，但保留页面路径和结尾斜杠），并且手动跟随重定向：每一跳都重新校验目标，最多 5 跳；禁止 localhost、私网、链路本地与云元数据地址（含 `169.254.169.254`、`100.100.100.200`、`metadata.google.internal`）。连接 5 秒，整个响应（头与正文）在 8 秒截止时间内读取，超时会取消请求；正文经限流订阅器截断到 512 KB。不携带用户 Cookie 或认证信息，只使用 `ArdorJobDiscovery/1.0` User-Agent。

链接状态：`OPEN`（2xx 且页面无登录/验证标志）、`CLOSED`（404/410）、`RESTRICTED`（401/403/407/429 或页面含验证码/登录提示）、`UNKNOWN`（网络失败、重定向异常或其他状态）。`RESTRICTED` 仍保留岗位并展示来源限制，不用静默删除代替。

`JobPageParser` 优先读取 schema.org `JobPosting` JSON-LD，其次读取 meta/title；公司只取 `hiringOrganization` 或标题中的明确模式，不把站点名当作公司。薪资只取 `baseSalary`，经验只取 `experienceRequirements`，截止只取 `validThrough`；缺失保持空。搜索结果的摘要仅用于匹配依据和兜底标题，不作为页面事实写入薪资等字段。

## 搜索执行

- 入口 `JobDiscoveryService.start` 先校验并保存搜索条件；目标岗位为空时使用当前用户资料中的目标岗位，仍为空则要求澄清，不猜测。
- 条件上限：目标岗位 5 项（每项 80 字符）、地点 5 项、关键词 8 项（60 字符）；查询最多 4 条，每条取 6 个结果，单次最多处理 15 个候选。查询由岗位与地点组合生成，中文岗位追加“招聘”，英文追加“jobs”。
- 每次搜索写入 `job_discovery_runs`，并以 `JOB_DISCOVERY` 任务进入现有 `ai_jobs` 队列；同一用户同时只允许一个进行中的搜索，避免成本失控。请求携带客户端 `requestId`，重复提交同一 ID 返回原运行。
- Worker 在每个查询和候选之间检查取消请求；单条查询失败记录 `SEARCH_FAILED` 并继续，成功结果照常保存。全部查询失败为 `FAILED`，部分失败为 `PARTIAL`，其余为 `COMPLETED`；取消为 `CANCELED`。错误最多保留 20 条，按查询记录 code/message（不输出供应商原始响应头）。
- 重试创建新的运行而不是复用旧运行；去重键与用户状态保证重试不会重复保存或重置状态。后台任务页的重试走 `JobDiscoveryRetryHandler`：已终态的搜索开新运行，尚未结束的搜索只重新排队同一任务，避免并行重复。
- 单个候选保存失败（例如超长公司名）记为 `CANDIDATE_FAILED` 并继续；运行级异常会先把搜索标记为 `FAILED`/`PARTIAL` 再让 AI 任务失败，不让运行永久停在 `RUNNING`。
- 付费优先级沿用现有会员任务优先级；不新增调度器、队列或审批系统。

## HTTP 合同

| 方法与路径 | 说明 |
|---|---|
| `GET /api/job-discovery/preferences` | 返回保存的岗位/地点/关键词和资料中的建议岗位 |
| `PUT /api/job-discovery/preferences` | 保存搜索条件 |
| `POST /api/job-discovery/runs` | 开始搜索，正文 `{ requestId, roles, locations, keywords }`，返回 202 与运行视图 |
| `GET /api/job-discovery/runs/{id}` | 读取状态、进度、计数与逐查询错误 |
| `POST /api/job-discovery/runs/{id}/cancel` | 请求取消；排队任务可直接取消，运行中由 Worker 检查点停止 |
| `POST /api/job-discovery/runs/{id}/retry` | 仅 `FAILED`/`PARTIAL`/`CANCELED` 可重试，创建新运行 |
| `GET /api/job-discovery/postings?state=&q=&limit=&offset=` | 用户岗位收件箱；`state` 为 NEW/SAVED/IGNORED，`q` 搜公司/岗位/地点/匹配理由，limit 1–50 |
| `PATCH /api/job-discovery/postings/{id}` | 正文 `{ state }`，只移动用户状态 |
| `POST /api/job-discovery/postings/{id}/applied` | 正文 `{ confirm: true }`；仅明确确认后写入 `applied_at` 并计一次 `RESUME` |
| `GET /api/job-discovery/summary` | 收件箱/收藏/忽略数量和进行中的运行 |

所有读写都从认证上下文取当前用户，并在 Repository 的 SQL 中按 `user_id` 联合限定；访问他人岗位返回 404 而非泄漏存在性。

## Agent 工具

新增 5 个工具并登记到 `ToolCatalog` 的 APPLICATIONS 模块：`discover_jobs`（CREATE）、`get_job_discovery_run`/`list_discovered_jobs`（READ）、`save_job_posting`/`ignore_job_posting`（UPDATE）。工具复用同一 Service 和用户隔离；岗位列表输出按不可信来源包裹，外部页面内容不能扩大权限或触发业务写入。删除类工具不新增，标记已投递只保留在需要明确确认的界面操作中。

## 前端

求职首页新增“岗位收件箱”预览卡，显示待查看数量、已收藏数量和进行中的搜索。收件箱页面包含：

- 可编辑的搜索条件与资料中的建议岗位；只保存为下次默认条件，是否搜索由用户点击决定。
- 运行状态、进度条、逐查询失败说明、取消和重试；部分失败时保留已成功岗位并明确说明重试不会重复保存。
- 收件箱/已收藏/已忽略筛选、收件箱内搜索和分页。
- 岗位卡展示标题、公司、地点、匹配判断与匹配依据（页面标题/地点或搜索摘要）、来源与发现/核验时间、可空的薪资/经验/截止信息、链接状态和原始申请页（`target=_blank rel="noopener noreferrer"`）。
- 保存/忽略/移回收件箱；“我已投递”先内联询问“简历已经实际发出了吗？”，确认后才调用 applied 接口并提示只计一次。
- 零结果、加载失败、链接无效和缺少页面事实都有对应文案。

## 验收与未验证项

已验收：

- `JobPostingKeysTests`：追踪参数/末尾斜杠/ATS ID 去重，不同岗位不因公司标题相似而合并，来源优先级。
- `JobPageParserTests`：JSON-LD 与 meta 解析，缺失字段不补造，公司不取站点名，地点去重。
- `SafePageFetcherTests`：重定向到私网被拒绝、相对重定向逐跳校验、重定向次数上限、登录/关闭/未知状态。
- `ExternalBaseUrlPolicyMetadataTests`：云元数据地址被拒绝。
- `JobDiscoveryServiceTests`：资料岗位兜底、缺岗位/并发运行拒绝、成功保存不写投递计数、重复发现保留状态、部分失败保留结果、全部失败、重试新运行、取消、只计一次投递、用户隔离。
- `JobDiscoveryUi`（`frontend/tests/job-discovery-ui.mjs`，合成 API 与搜索夹具）：偏好、部分失败、收藏/忽略、申请确认只一次、重试/取消、零结果、外链属性，并断言发现过程没有投递或面试写入。
- V94 已在日常库的 schema-only 副本上以 `ON_ERROR_STOP=1` 应用成功，并核对新表与后台任务视图；副本随后删除。

尚未验收：

- 尚未对真实 Tavily 与真实雇主/ATS 页面做端到端搜索，因此真实站点的 JSON-LD 覆盖率、登录墙识别和来源质量未验证。
- 尚未用浏览器对真实登录账户与真实数据库完整走一遍页面流程；上述 UI 验收使用合成 API。
- 完整后端测试与 CI（golden-path、database-migrations、frontend-quality）待本 PR 推送后核验。
- 一次运行最多 4 次 Tavily 搜索请求；按供应商计费口径约 4 credits/次（页面抓取为直接 HTTP，不产生搜索 API 费用）。在真实账户上执行前应先确认这一预估。

## 扩展点

- 增加搜索引擎或抓取深度：复用 `TavilySearchService` 的接口与 `SafePageFetcher`，不建立第二套任务队列。
- 更多 ATS/招聘站：扩展 `JobPostingKeys` 的 ATS 规则与来源优先级；仍保持“不按公司标题合并”。
- 岗位与面试流程的显式关联：作为用户确认后的独立动作，不在发现流程中自动创建。
- 定时岗位发现：可复用现有自动化任务发起同一 Service；本版未接入。
