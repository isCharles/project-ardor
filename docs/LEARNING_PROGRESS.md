# 学习补强进度查询

补强进度属于学习领域的只读查询，不新增 Evidence 实体、写入接口或一套训练状态机。

## 归属与事实

- GET /api/learning-progress?page=0&size=20 从认证上下文取得用户，每页最多 50 道 WEAK/MIXED 真实面试问题。sourceInterviewId 可限定来源面试，先校验归属；不是备考目标筛选。
- 求职领域的 InterviewQuestionProgressAccess 提供问题与记录、真实流程、轮次引用；学习查询不访问求职 Repository。
- 训练/复习的 QuestionPracticeProgressAccess 提供关联卡片的复习次数，以及重答总次数和最近结果。没有卡背、重答正文或完整模型评价。
- 学习领域读取自己的计划摘要，保留全部单题计划和单独列出的整场面试计划，不只取第一份。准备目标独立展示，不从来源推断。
- 旧 RECAP 来源只通过既有、受用户限定的来源解析接口消歧；不能可靠解析则不关联，不凭 UUID 或页面位置猜测。
- 顶部账户统计覆盖自主学习与复习，即使没有真实面试问题仍显示。已保存尝试数是历史事实行数，不冒充从未保留的历史次数。

所有跨域读取通过具名应用接口；批量查询仅选取当前页面相关字段。最新重答在数据库中分组、取最近一行，不把所有重答正文下载到 Java 或浏览器。

次数、自评、模型结论和最后分数各自保留含义，不合成为“掌握分数”。旧历史缺失不会因这次查询被补造。

## 客户端与恢复

规范路径 /app/learning/progress 与旧 /app/evidence 使用同一实现。来源筛选保留在 URL；来源切换丢弃迟到响应，语言切换不重置分页。

分页使用稳定排序的 offset；不是跨请求数据库快照。新增/删除记录可能使页边界移动，客户端按问题 ID 去重，刷新从第一页重新读取。需要严格快照时应另行引入游标合同，不声称当前已提供。

详细错误默认折叠、长串换行；刷新有进行中反馈。不改变旧资源 ID、训练写入、删除级联、API、任务或桌面原生协议。

## 验证与回退

- LearningProgressServiceTests：来源/目标分离、全部计划保留、旧来源不猜测、独立学习统计、外部用户隔离、分页与领域依赖边界。
- learning-context-acceptance.mjs：隔离真实 PostgreSQL/HTTP 中，两轮来源面试→单题/整场学习→重答→卡片复习→进度，另一场备考目标独立；跨用户和非法分页拒绝。模型使用合成响应，不代表真实模型质量评测。
- frontend/tests/learning-progress-ui.mjs：分页、多个计划/卡片、重答、语言切换、刷新、空状态、旧路径、窄屏和长错误。
- frontend/tests/learning-progress-live.mjs：生产构建连接明确指定的隔离后端，从虚构账号、两轮面试及确认纪要创建学习/重答/卡片复习，再在真实浏览器验证关联结果。所有业务响应来自真实后端与 PostgreSQL，不使用 API mock；LLM 仍为模拟服务。须显式设置 ARDOR_ACCEPTANCE_DISPOSABLE_DB=true、ARDOR_ACCEPTANCE_API_BASE 与 ARDOR_ACCEPTANCE_LLM_BASE，在 frontend 目录执行 node tests/learning-progress-live.mjs；本机浏览器可设置 ARDOR_UI_BROWSER_CHANNEL=msedge。

本批不新增 migration。回退应用即可保留所有原业务事实；旧前端可继续使用原有列表接口。新前端需要具备此查询的后端，不能先单独部署到旧服务器。
