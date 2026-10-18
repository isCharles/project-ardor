# 2026-10-17 变更审查清单

> 给审阅者的自包含说明。所有改动都在一个功能分支上，尚未建 PR。

## 0. 审查范围

| 项 | 值 |
|---|---|
| 仓库 | `project-ardor` |
| 分支 | `feat/opportunity-pipeline`（已推送，`origin/feat/opportunity-pipeline` = HEAD） |
| 基线 | `753ac7d`（当天 11:04 合入 main 的 #124 文档提交） |
| HEAD | `ce0cd0d` |
| 提交数 | **38 个代码提交**（本说明文档为第 39 个 `docs:` 提交） |
| 改动规模 | **93 files changed, 4160 insertions(+), 463 deletions(-)**（代码提交范围；不含本说明文档） |
| 涉及模块 | 面试流程、面经、学习、Agent UI、Agent 权限、长期记忆 |

复现命令：

```bash
git fetch origin
git log --reverse --oneline 753ac7d..ce0cd0d
git diff --stat 753ac7d..ce0cd0d
git diff 753ac7d..ce0cd0d
```

注意：`753ac7d` 之前是同一天上午其他工作的提交（#123/#124），不在本清单内。

## 1. 面试流程（opportunity pipeline）

新增「面试流程」一级实体：一场面试有生命周期状态与多轮记录，Mock 面试、日历任务、面经都可挂到流程下。

**后端**

- `opportunity/domain/`：`JobOpportunity`、`OpportunityRound`、`OpportunityStatus`（4 态：已约面 / 等通知 / 下一轮 / 被拒绝）、`RoundStage`
- `opportunity/service/OpportunityService`：所有写操作的唯一入口；创建/更新/删流程、增删改轮次；所有查询带 `userId`
- `opportunity/web/`：`/api/opportunities` CRUD + 轮次接口
- Agent 工具新增：`list_job_opportunities`、`get_job_opportunity`、`create_job_opportunity`、`update_job_opportunity`、`add_job_opportunity_round`、`update_job_opportunity_round`；删除类进入 `PROPOSALS`（只出提案卡）：`delete_job_opportunity`、`delete_job_opportunity_round`
- 关联：`interview_sessions.job_opportunity_id`、`tasks.job_opportunity_id`（`V41`）
- 状态映射：旧 7 态收敛为 4 态（`V42`）

**前端**

- 新页面 `frontend/src/app/app/opportunities/page.tsx`：表格、4 态下拉、按日期升/降序、行展开显示绑定的面经/记忆卡/学习计划、JD 与约面邮件两行（默认折叠、点击预览浮窗）、编辑弹窗、删除确认
- 侧栏新增「面试流程」入口；`/app/recaps` 从侧栏移除（仅保留深链接）
- 一处修复：轮次时长为 null 时 `update_job_opportunity_round` 不再拆箱 NPE（回归测试已补）

**涉及提交**：`e4ee5fd`、`2b9f055`、`eb094c3`、`efe112d`、`8621bd8`、`32bb430`、`c5690a5`、`51c489d`、`1420900`、`6889036`、`387ed6e`、`2e1483b`、`257c187`、`ae81ad2`、`28e543c`、`309a741`

## 2. 面经改为「面试下的文档」

面经不再保存自己的公司/岗位/面试时间；只保留文档内容与 `job_opportunity_id`。

- `V43__strip_recap_metadata.sql`：删除 `interview_recaps.company / target_role / occurred_at`
- 创建面经必须指定面试：`InterviewRecapCreateRequest.jobOpportunityId` 必填；队列层校验归属
- 删除 `PATCH /api/interview-recaps/{id}/metadata` 接口、`update_interview_recap_metadata` Agent 工具及其意图策略
- Agent 提示词改为「公司/岗位/时间去改面试流程」
- 前端面经页：去掉元数据表单与标签，整理面经表单新增「所属面试」下拉
- 兼容说明：`job_opportunity_id` 在库中仍可空（历史面经），强制绑定只做在写入入口

**涉及提交**：`4070eee`

## 3. 学习模块

- **选择题/判断题确定性判分**：生成时保存正确选项下标；评分时系统判定 `choiceCorrect`，整个练习都是选择题时分数直接算数；提示词禁止因「没解释选项」扣分
- **新增判断题**（`TRUE_FALSE`）：固定「正确/错误」两选项，与选择题同样系统判分；题量从 2–4 提到 3–6，强制混题型
- **审阅布局**：去掉顶部重复评分卡，只保留「第 N 次回答」历史（最新一次默认展开），并把「下一步/薄弱点」放进最新一次展开内容
- **来源显示**：学习计划的「查看来源面经」改为显示面试岗位名（`plan.sourceId → recap → jobOpportunityId → targetRole`），并修复旧数据 `sourceId` 指向面经题目 id 的 404 情况
- **页面精简**：删除按钮收进右上角 ⋯ 菜单、标题降级、去掉 hero/副标题重复、进度条改细、例子/易踩坑默认折叠
- **后台任务红点**：学习页会一次性把当前计划下所有已结束任务（含 FAILED）标已读；公共钩子同步修复 FAILED 不消红点
- **学习完成横幅**：只显示进行中的创建任务，历史完成不再每次访问重现
- **对话标题**：只在第一轮生成一次，后续轮次不再改写

**涉及提交**：`14a43b5`、`3f135ab`、`8c38703`、`bd5526d`、`9911e12`、`d7a0318`、`336a99f`、`035858a`、`600132d`、`a9705b8`、`c588a83`、`1041aba`

相关文件：`learning/service/LearningPlanService`、`learning/web/LearningPlanResponse`、`frontend/src/app/app/learning/page.tsx`、`frontend/src/lib/background-tasks.ts`、`agent/service/AgentConversationStore`、`recaps/page.tsx`

## 4. Agent 权限等级

用户截图确认的交互：输入框内提供「仅查看 / 查+增 / 查+增+改 / 完全权限」快捷选择；设置页有模块级覆盖。

**后端**

- `agent/capability/`：`Capability`（READ/CREATE/UPDATE/EXECUTE/DELETE）、`ToolModule`（13 个模块）、`ToolCatalog`（63 个工具显式登记；未登记工具默认需要确认）
- `V44__add_agent_permission_grants.sql`：`agent_permission_grants`（每用户每模块一行、全局 `*` 默认值）+ `agent_tool_audit`（自动放行审计，只存参数 SHA-256）
- `AgentPermissionGrants`：全局默认 + 模块覆盖；`autoApproves` 只对非 DELETE 且等级足够的工具返回 true
- `ApprovedAgentTools`：栅栏在弹窗前查询授权；放行则跳过弹窗并记审计；未授权仍走原单次确认
- `AgentToolApprovals.View` 新增 `module/capability/suggestedLevel`，供弹窗「以后都允许」
- API：`GET /api/agent/permissions`、`PUT/DELETE /api/agent/permissions/{module}`（`*` 为全局）
- 破坏性操作永远只出提案，不受等级影响
- 修复：记忆写入的原话校验从「弹窗后」前移到「弹窗前」，避免用户先同意再被规则拒绝导致反复弹窗

**前端**

- 输入框内权限选择器（`frontend/src/app/app/page.tsx`）+ 设置页 `AgentPermissionsPanel`（全局默认 + 每模块跟随/覆盖）
- 审批弹窗增加「以后都允许 Ardor 修改『某模块』」勾选

**涉及提交**：`2289ff3`、`456f459`

## 5. 长期记忆系统（P1–P4）

重新设计记忆模型：从「必须逐字复述用户原话」改为带来源与时效的事实表。设计文档：[docs/MEMORY_DESIGN.md](MEMORY_DESIGN.md)。

- `V45__add_memory_fact_metadata.sql`：`kind / confidence / importance / source_kind / source_run_id / valid_from / valid_until / superseded_by`
- `V46__add_memory_embeddings.sql`：`embedding / embedding_dim / embedding_model / embedded_at` + 索引
- `add_memory` / `update_memory` 不再要求逐字；保留一条逐字规则只用于「模拟面试回答」（`submit_interview_answer`）
- 后台抽取 `MemoryConsolidationService`：对话提交后异步运行；最近 4 条用户消息 + 记忆信号词过滤；每用户 5 分钟节流；候选必须有用户消息中的逐字 `evidence`；UPDATE 目标必须是该用户有效记忆；纠正通过「旧条失效 + 插入新条」
- 检索 `MemoryEmbeddingService`：pgvector 余弦 + `0.6/0.25/0.15` 打分 + 2400 字符预算；无向量模型时降级为重要性/时效排序；`MemoryEmbeddingScheduler` 每 120 秒补齐（含 embedding 模型变更重算）
- 维护 `MemoryMaintenanceService`：每日运行；`INFERRED` 且 `confidence<0.5` 超过 60 天失效；短事实被长事实完整包含时合并（失效不删除）
- 前端记忆面板：逐条编辑/新增/删除/清空；显示来源与可信度；可勾选查看「已过时」历史并恢复为当前记忆
- 系统提示词：内存区改为「长期记忆」，使用本轮检索结果

**涉及提交**：`680db67`、`510b890`、`ea5bcf5`、`79ba290`、`ce0cd0d`

## 6. 数据库迁移与升级影响

本次新增 7 个迁移（均已在本机库执行成功；Flyway 只能向前，删列/改约束不可回滚）：

| 版本 | 文件 | 内容 | 风险 |
|---|---|---|---|
| V40 | `V40__add_job_opportunities.sql` | 面试流程表 + 轮次子表 + 面经/任务关联列 | 新表，安全 |
| V41 | `V41__link_interviews_and_tasks_to_opportunities.sql` | `interview_sessions` / `tasks` 增加 `job_opportunity_id` | 加列，安全 |
| V42 | `V42__simplify_opportunity_statuses.sql` | 7 态收敛为 4 态并映射旧值 | 涉及既有数据映射，升级前建议备份 |
| V43 | `V43__strip_recap_metadata.sql` | 删除面经三列 | **不可逆的数据删除**；历史孤儿面经不自动绑定 |
| V44 | `V44__add_agent_permission_grants.sql` | 授权表 + 审计表 | 纯新增，默认 L0 = 旧行为 |
| V45 | `V45__add_memory_fact_metadata.sql` | 记忆事实元数据列 | 纯加列，旧行默认 `USER_EXPLICIT/1.0` |
| V46 | `V46__add_memory_embeddings.sql` | 记忆向量列 + 索引 | 纯加列；`CREATE EXTENSION IF NOT EXISTS vector` |

升级注意事项：

- V43 会永久删除三列；若线上存在依赖这些列的导出/报表需先处理
- V44 默认全部模块 L0，用户此前行为不变
- V45 旧记忆全部视为用户明确提供（confidence=1.0），不会被自动过期
- V46 需要数据库已安装 pgvector（知识库此前已用）

## 7. 接口 / Agent 工具契约变更（可能影响其他客户端）

**新增**

- `/api/opportunities` 及轮次 CRUD
- `/api/agent/permissions`、`PUT/DELETE /api/agent/permissions/{module}`
- `POST /api/agent/memory/items`（手动新增记忆）

**语义变更**

- `POST /api/interview-recaps`：`jobOpportunityId` 必填
- 删除 `PATCH /api/interview-recaps/{id}/metadata`
- `GET /api/agent/memory/items` 响应增加 kind/confidence/sourceKind/valid* 字段
- `GET /api/agent/runs/{id}/approvals` 响应增加 module/capability/suggestedLevel
- `PATCH /api/agent/memory`（PUT 整体记忆）仍存在但新面板不再使用

**Agent 工具**

- 新增 8 个面试流程工具（其中 2 个删除类只提案）
- `organize_interview_recap` 增加必填 `opportunityId`
- 删除 `update_interview_recap_metadata`
- 记忆工具内容规则变化（见 §5）
- 新增 `ToolCatalogTests` 防止工具漏登记

## 8. 前端 UI 变更

- 新页面：`/app/opportunities`（面试流程）
- 学习页整体精简 + 判断题 + 审阅历史布局 + 来源岗位名
- 聊天输入框：权限选择器
- 设置页：Agent 权限面板（全局 + 模块覆盖）
- 记忆面板：逐条编辑 + 历史/恢复
- 执行轨迹（思考输出）：运行中自动展开，开始输出/结束后折叠
- 后台任务红点、学习完成横幅、对话标题等修复

## 9. 本机开发库数据操作（不在代码中，审查需知）

因调试需要，我在本机开发库执行过一次性数据操作，**未写入迁移**：

1. 删除测试账号 `29e1bdb0…` 下 1 篇未绑定面试的旧面经（及其 4 道题，级联删除；删除前确认无记忆卡/学习计划引用）
2. 记忆清理：把旧「…研究生一年级…」更正为「硕士研究生二年级（三年制）」；「现在研二了」标记失效（`superseded_by` 指向更正条）；旧重复条目在维护任务/合并中失效
3. 后台抽取在开发对话中实际产生了新记忆条目（INFERRED）

线上/他人库不应执行第 9 节的 SQL；代码路径本身不包含这些一次性操作。

## 10. 验证证据

| 项 | 结果 |
|---|---|
| 后端测试（`mvn test`，最终） | **293 passed, 0 failed** |
| 前端 `npm run lint` | 通过 |
| 前端 `npx tsc --noEmit` | 通过 |
| Flyway V40–V46 | 均 `success = true` |
| 部署 | `docker compose build backend frontend` + `up -d`；`/actuator/health` 200；`/app`、`/app/learning`、`/app/recaps`、`/app/settings` 200（验证时容器在运行） |
| 未运行的验证 | 浏览器 E2E / 自动化 UI 回归（仓库暂无前端测试设施）；记忆向量检索在未配置 embedding 模型时仅验证了降级路径 |

> 当前环境 Docker Engine 已停止，重新验证前需先启动 Docker Desktop（`scripts/Start-DockerDesktopSafely.ps1` 一次）。

## 11. 建议审查重点

1. **数据隔离**：所有新 Service/Repository 是否都以 `userId + resourceId` 查询；Agent 工具是否可能跨用户
2. **迁移安全**：V42 状态映射、V43 删列、V44 默认 L0 的兼容性
3. **权限栅栏**：`ToolCatalog` 未登记工具是否确实 fail-closed；自动放行是否只跳过弹窗、不绕过意图校验；删除是否永远只出提案
4. **记忆写入**：后台抽取的 `evidence` 锚定是否能挡住外部内容；UPDATE 目标归属；`replace/supersede` 是否只失效不删除
5. **异步与并发**：`MemoryConsolidationService` 的 `@Async + AFTER_COMMIT`；每用户节流为进程内内存（多实例部署会失效，需确认是否接受）
6. **数据泄露**：审计表只存参数摘要；日志未打印对话正文
7. **前端**：权限选择器写入全局级别的影响范围；记忆面板恢复/失效展示

## 12. 已知限制 / 待决问题

- 记忆维护阈值（`confidence < 0.5`、60 天）为硬编码常量，未做成配置
- 后台记忆抽取会增加额外 LLM 调用；当前用「信号词 + 5 分钟节流」控制成本
- embedding 模型切换后的重算逻辑已实现但未在配置了向量模型的用户上实际验证（开发库没有该配置）
- 权限等级为「全局默认 + 模块覆盖」，没有 single-tool 例外和时间限定授权（文档中列为后续）
- 前端无自动化测试；UI 验证为手工 + lint/typecheck
- 记忆历史/恢复功能依赖新增的 `POST /memory/items`，老客户端不受影响
- 分支已推送但未建 PR

## 附录 A：提交清单（38）

| 时间 | 提交 | 说明 |
|---|---|---|
| 17:44 | e4ee5fd | 面试流程实体 + 生命周期 |
| 17:48 | 2b9f055 | Mock 面试/日历任务挂到流程 |
| 17:49 | eb094c3 | 已有面经挂到未关联流程 |
| 18:17 | efe112d | 流程内展示面经 |
| 18:22 | 8621bd8 | 流程内展示记忆卡/学习计划 |
| 18:25 | 32bb430 | 只渲染实际存在的行 |
| 18:28 | c5690a5 | Agent 完整 CRUD 面试流程 |
| 18:31 | 51c489d | 状态收敛为 4 态 |
| 18:32 | 1420900 | 面经题目来源的学习计划归入流程 |
| 18:36 | 6889036 | 允许任意状态修改（误点可改回） |
| 18:39 | 387ed6e | JD / 约面邮件行 |
| 18:45 | 2e1483b | 修复轮次时长 null 拆箱 |
| 18:45 | 257c187 | JD / 邮件行可点击 |
| 18:47 | ae81ad2 | JD / 邮件行可编辑 |
| 18:49 | 28e543c | 默认折叠、无内联预览 |
| 18:52 | 309a741 | 按面试日期排序 |
| 19:01 | 4070eee | 面经改为面试下的文档 |
| 20:55 | 14a43b5 | 选择题按选项判分 |
| 20:55 | 3f135ab | 去掉最新评分重复渲染 |
| 20:59 | 8c38703 | 审阅只保留回答历史 |
| 20:59 | bd5526d | 判断题 + 题量增加 |
| 21:01 | 9911e12 | 最新一次显示下一步/薄弱点 |
| 21:07 | d7a0318 | 学习完成横幅不再重现 |
| 21:09 | 336a99f | 对话标题只生成一次 |
| 21:10 | 70cc3a2 | 执行轨迹运行中展开 |
| 21:14 | 035858a | 学习任务红点标已读 |
| 21:17 | 600132d | 学习页精简 |
| 21:20 | a9705b8 | 来源显示岗位名 |
| 21:23 | c588a83 | 修复题目 id 来源 404 |
| 21:26 | 1041aba | 「优化计划」改为开放提问 |
| 21:29 | df5820d | 思考输出到达时展开 |
| 21:35 | 2289ff3 | 记忆预校验前移 |
| 21:42 | 456f459 | 模块级 Agent 权限等级 |
| 22:13 | 680db67 | 记忆允许归纳（过渡） |
| 22:18 | 510b890 | 记忆事实表 P1 |
| 22:26 | ea5bcf5 | 后台记忆抽取 P2 |
| 22:32 | 79ba290 | 向量检索 P3 |
| 22:47 | ce0cd0d | 记忆维护 + 历史面板 P4 |

## 附录 B：全量文件清单

运行 `git diff --name-status 753ac7d..ce0cd0d` 获取。按目录统计：

| 目录 | 文件数 |
|---|---|
| backend/src/main | 65 |
| backend/src/test | 14 |
| frontend/src | 12 |
| docs | 2（AGENT_PERMISSIONS.md、MEMORY_DESIGN.md） |
