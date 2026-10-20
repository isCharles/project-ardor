# 学习尝试历史与内容版本合同

本文件约束 V52 引入的「每次学习提交与评价是一等事实」以及讲解/题目版本合同。它回答：一次提交为什么被评、评的是哪一版题目、由哪个模型和提示版本评判；同时说明旧 `learning_plans.last_evaluation.attempts` 投影、回填、升级探针和回退方式。

相关合同：[领域合同与迁移基线](DOMAIN_CONTRACTS.md)、[学习来源与准备目标合同](LEARNING_ORIGIN_CONTEXT.md)、[数据库设计](DATABASE.md)。

## 1. 数据模型

### `learning_plans` 新增版本列（V52）

| 列 | 含义 | 写入规则 |
|---|---|---|
| `content_version` | 讲解内容版本 | 新生成计划为 1；讲解被重新生成或替换时递增。历史计划升级后保持 1，不伪造重生成次数。 |
| `exercises_version` | 题目集版本 | 新生成计划为 1；未达标后由 `nextExercises` 替换整套题目时递增。历史计划升级后保持 1。 |

版本号不是资源 UUID，也不替代 `learning_plans.id`。围栏按入口分别生效，调用方不能把同步入口的字段当成异步入口的围栏：

- 同步 `POST /api/learning-plans/{id}/attempts`：可回传读到的 `attemptCount` 与 `exercisesVersion`（请求字段名为 `expectedAttemptCount` 与 `exercisesVersion`）。
- 异步 `POST /api/learning-plans/{id}/evaluation-jobs`：只接受 `expectedAttemptCount`；**不读取 `exercisesVersion`**，该字段即使被发送也不会参与校验（不要依赖它被服务端忽略以外的任何行为）。
- 两端的围栏都只在对应值仍匹配当前行时评分，否则 409。

题目本身带稳定 `exerciseId`：新生成或替换的每道题都有 UUID 字符串标识，随 `learning_plans.exercises` 持久化，并在尝试快照中重复保存。V52 不为历史计划的已有题目补造 ID；历史题目在下一次题目集替换时获得新的稳定 ID，历史答案仍留在旧尝试快照里。

### `learning_attempts`（V52 新增）

每次成功评分写入一行不可变事实：

| 列 | 含义 |
|---|---|
| `id` | 应用生成 UUID |
| `user_id`、`plan_id` | 所有者与所属学习计划；复合外键 `(plan_id,user_id) -> learning_plans(id,user_id)` |
| `attempt_no` | 该计划内的提交序号，从 1 开始；`(user_id,plan_id,attempt_no)` 唯一 |
| `request_id` | 可选提交身份：异步评分使用 AI Job ID，重试复用；`(user_id,request_id)` 部分唯一（仅非空） |
| `exercise_snapshot` | 本次实际作答的题目快照（JSONB 数组），包含 question/type/options/rubric/correctOption/exerciseId |
| `answers` | 本次提交的答案数组（字符串，按题目顺序） |
| `score` | 0–100；`legacy` 行可空 |
| `evaluation` | 本轮评价 JSONB：feedback、questionFeedback、strengths、gaps、nextFocus、score、contentVersion、exercisesVersion，以及选择题判定 `choices` |
| `model_name`、`prompt_version` | 评分模型与评价提示版本；当前为 `learning-evaluation-v1`。`legacy` 行为 NULL |
| `legacy` | 由旧投影回填、字段无法证明时为 TRUE，不伪造 rubric/model/prompt |
| `submitted_at`、`created_at` | 提交时间与落库时间；旧投影缺失提交时间时为 NULL |

隔离与删除：

- 所有读写按 `(id, user_id)` 或 `(plan_id, user_id)` 授权；跨用户读取返回 404，不泄露资源存在性。
- 删除学习计划会**不可逆地**通过复合外键级联删除其尝试历史。这是 V52 唯一扩大删除行为的点，已在 PR 中单独说明；需要保留历史的部署必须先做备份。旧 `task`、日历与 `deleted_learning_requests` 行为不变。
- 用户删除按既有 `users` 级联清理计划，再级联尝试。

## 2. 写入合同

### 同步 `POST /api/learning-plans/{id}/attempts`

请求体在旧字段 `answers` 之外新增两个可选围栏：

```json
{
  "answers": ["...", "..."],
  "expectedAttemptCount": 0,
  "exercisesVersion": 1
}
```

- 不传围栏时保持旧客户端行为。
- `expectedAttemptCount` 与当前 `attemptCount` 不一致 → 409 `学习轮次已更新，请刷新后重新提交`。
- `exercisesVersion` 与当前 `exercisesVersion` 不一致 → 409 `学习内容已更新，请刷新后重新提交`。
- 两个围栏都在调用模型之前校验：陈旧答案不消耗模型调用，也不会被挂到新题上。
- 与异步路径一致使用 409 / `INVALID_STATE`；成功响应仍为 `LearningPlanResponse`，其中包含更新后的 `attemptCount`、`contentVersion`、`exercisesVersion`。
- 评分成功后同一事务内：写 `learning_attempts`、更新 `learning_plans` 兼容投影与日历状态。任一步失败整体回滚。

### 异步 `POST /api/learning-plans/{id}/evaluation-jobs`

- 请求身份仍为 `requestId`（幂等键）。任务执行时以 AI Job ID 作为尝试的 `request_id`。
- 幂等改为查询 `learning_attempts` 唯一键：若同一提交已落库，重试直接返回结果，不再次调用模型；旧计划仍回退扫描兼容投影中的 `requestId`。
- `expectedAttemptCount` 仍在入队前校验，避免为陈旧轮次创建任务。
- 异步入口**不接受 `exercisesVersion`**；题目版本是否变化由服务端提交时对计划行重新校验，客户端无法用该字段做异步围栏。

### 服务端并发与幂等

- 模型调用不持锁；提交时对计划行加悲观锁，重新校验 `attemptCount`、`contentVersion`、`exercisesVersion` 后写尝试行。并发提交只有一个成功，其余 409。
- `attempt_no` 在行锁内按 `max(已存最大 attempt_no, attemptCount) + 1` 分配（正常历史即 `attemptCount + 1`）：不一致的旧投影回填后可能出现 `attempt_no > attemptCount`，从已存最大值之后继续可避免唯一键冲突；异常计划允许 `attempt_no` 跳号。唯一键把重复写入变成显式冲突而不是覆盖历史。
- `requestId` 先查唯一键：已存在且属于同一计划时直接返回当前计划；属于其他计划时拒绝。旧投影中的 `requestId` 仍作为升级前的兼容回退。

## 3. 读取合同

`GET /api/learning-plans/{id}/attempts`（只读，属主校验，跨用户 404）按 `attemptNo` 升序返回全量历史。异常历史（回填后 `attemptNo` 可能 > `attemptCount`）不会被重排、隐藏或重新编号；新提交继续追加在已存最大序号之后，因此响应中的 `attemptNo` 可能不连续，客户端应以 `attemptNo` 而非数组下标作为稳定标识。返回结构示例：

```json
[
  {
    "id": "...", "planId": "...", "attemptNo": 1, "requestId": null,
    "exercises": [{"exerciseId": "...", "question": "...", "type": "MULTIPLE_CHOICE",
                   "options": ["..."], "rubric": ["..."], "correctOption": 0}],
    "answers": ["..."], "score": 65,
    "evaluation": {"feedback": "...", "strengths": [], "gaps": [], "nextFocus": "...",
                   "questionFeedback": [], "contentVersion": 1, "exercisesVersion": 1,
                   "choices": [{"exerciseId": "...", "selected": "...", "correctOption": "...", "correct": true}]},
    "modelName": "...", "promptVersion": "learning-evaluation-v1",
    "legacy": false, "submittedAt": "..."
  }
]
```

- 旧 `LearningPlanResponse.lastEvaluation.attempts` 保留原形状并继续截断为最近 20 轮，仅用于旧客户端兼容；新历史不会被截断，读取方应以本接口为准。
- 评分后的 rubric、选择题判定和评价模型可见；未评分内容不通过该接口泄露。

## 4. 回填规则（V52）

V52 在迁移中一次性从旧投影回填，原则是「可证明才还原，缺什么不造什么」：

1. 按 `last_evaluation.attempts` 数组顺序写入；因为旧投影只保留最近 20 轮，`attempt_no` 锚定到 `attempt_count`：`GREATEST(1, attempt_count - retained + 1) + position - 1`。25 轮历史只保留 20 轮时，序号是 6..25，而不是 1..20。
   - 异常数据（`retained > attempt_count`）回填为 `attempt_no = 1..retained`，会超过 `attempt_count`；V52 不改写 `attempt_count`，运行时下一次提交按 `max(已存最大 attempt_no, attemptCount) + 1` 继续（异常计划允许跳号），不会与回填行撞唯一键。修复建议：先用下面的异常报告核对并备份，如需恢复连续编号，另开数据修复迁移/脚本，禁止改写已执行迁移。
2. `exercise_snapshot` 只从旧 `items` 的 question/type/options 还原；旧投影从未保存 rubric/correctOption/练习 ID，因此不补造。
3. `answers`、`score`、`feedback`、`questionFeedback`、`submittedAt`、合法 `requestId` 仅在 JSONB 中实际存在且格式有效时还原。`answers` 保留题目顺序：旧 item 缺失/非文本 `answer` 时以空字符串 `''` 占位，而不是丢弃该题位置；`score` 越界或非整数、`requestId`/`submittedAt` 格式非法时置 NULL。
4. 旧投影没有当轮 strengths/gaps/nextFocus，也没有评分 model/prompt_version，因此 `model_name`、`prompt_version` 为 NULL，所有回填行 `legacy = TRUE`。
5. 同一用户重复 `requestId` 的脏历史只保留第一行 ID，其余置 NULL，避免部分唯一索引中止升级；不重写旧 JSONB。
6. `attempt_count`、`lesson`、`exercises`、`last_evaluation`、来源/目标、日期和资源 UUID 均不改写。

### 回填核对 SQL（只读）

迁移完成（或升级探针）后可直接运行：

```sql
-- 回填计数与 legacy 比例
SELECT count(*) AS backfilled_attempts,
       count(*) FILTER (WHERE legacy) AS legacy_attempts,
       count(DISTINCT plan_id) AS plans_with_backfilled_history
FROM learning_attempts;

-- 每个计划的投影数、回填数、总历史数
SELECT p.id AS plan_id, p.attempt_count,
       COALESCE(jsonb_array_length(
           CASE WHEN jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
                THEN p.last_evaluation -> 'attempts' ELSE '[]'::jsonb END), 0) AS projected_rows,
       count(a.id) FILTER (WHERE a.legacy) AS legacy_rows,
       count(a.id) AS persisted_rows
FROM learning_plans p
LEFT JOIN learning_attempts a ON a.plan_id = p.id AND a.user_id = p.user_id
GROUP BY p.id, p.attempt_count, p.last_evaluation
ORDER BY projected_rows DESC;
```

### 异常报告 SQL（只读，用于人工核对）

```sql
-- 1) 旧投影比 attempt_count 还多：历史已不一致，回填为 1..N（N>attempt_count），
--    运行时下一次提交从 max(已存最大 attempt_no, attempt_count)+1 继续，允许跳号。
SELECT id, user_id, attempt_count,
       jsonb_array_length(last_evaluation -> 'attempts') AS projected_rows,
       GREATEST(jsonb_array_length(last_evaluation -> 'attempts'), attempt_count) + 1
           AS next_attempt_no
FROM learning_plans
WHERE jsonb_typeof(last_evaluation -> 'attempts') = 'array'
  AND jsonb_array_length(last_evaluation -> 'attempts') > attempt_count;

-- 2) 截断造成不可恢复的历史轮次（旧投影只保留最近 20 轮）
SELECT id, user_id, attempt_count,
       jsonb_array_length(last_evaluation -> 'attempts') AS retained_rows,
       attempt_count - jsonb_array_length(last_evaluation -> 'attempts') AS unrecoverable_rows
FROM learning_plans
WHERE jsonb_typeof(last_evaluation -> 'attempts') = 'array'
  AND attempt_count > jsonb_array_length(last_evaluation -> 'attempts')
ORDER BY unrecoverable_rows DESC;

-- 3) 有 attempt_count 但旧投影没有任何可回填历史
SELECT id, user_id, attempt_count
FROM learning_plans
WHERE attempt_count > 0
  AND (jsonb_typeof(last_evaluation -> 'attempts') IS DISTINCT FROM 'array'
       OR jsonb_array_length(last_evaluation -> 'attempts') = 0);

-- 4) 回填行缺失字段汇总（预期：legacy 行可能缺 score/request_id/submitted_at，但绝不缺 legacy 标记）
SELECT count(*) FILTER (WHERE score IS NULL) AS missing_score,
       count(*) FILTER (WHERE request_id IS NULL) AS missing_request_id,
       count(*) FILTER (WHERE submitted_at IS NULL) AS missing_submitted_at,
       count(*) FILTER (WHERE NOT legacy) AS non_legacy_rows
FROM learning_attempts;

-- 5) 历史 requestId 冲突：同一用户重复出现，回填只保留第一行
WITH projected AS (
    SELECT p.user_id, entry.value ->> 'requestId' AS request_id
    FROM learning_plans p
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(p.last_evaluation -> 'attempts') = 'array'
             THEN p.last_evaluation -> 'attempts' ELSE '[]'::jsonb END) AS entry(value)
    WHERE jsonb_typeof(entry.value -> 'requestId') = 'string'
)
SELECT user_id, count(*) AS projected_request_ids,
       count(DISTINCT request_id) AS distinct_request_ids
FROM projected
GROUP BY user_id
HAVING count(*) <> count(DISTINCT request_id);

-- 6) 回填后序号不连续或超过 attempt_count（新写入会继续追加，需在升级后立即核对）
SELECT p.id AS plan_id, p.attempt_count, min(a.attempt_no) AS first_no,
       max(a.attempt_no) AS last_no, count(*) AS rows
FROM learning_plans p
JOIN learning_attempts a ON a.plan_id = p.id AND a.user_id = p.user_id
GROUP BY p.id, p.attempt_count
HAVING max(a.attempt_no) > p.attempt_count
    OR (max(a.attempt_no) - min(a.attempt_no) + 1) <> count(*);
```

核对口径：回填行数应等于迁移时旧投影中的 attempt 条数；25 轮计划回填 20 行是预期损失，报告中按“不可恢复轮次”列出，不伪造补齐。异常报告 #1、#2 由 CI 升级探针的 `learning-attempts-upgrade-anomaly-report.sql` 真实执行并断言输出（fixture 含一条 `attempt_count=1` 而投影 3 轮的对抗行、一条 25 轮截断行）。

## 5. 升级探针与 CI

`.github/workflows/golden-path-acceptance.yml` 在一次性 PostgreSQL 上真实执行已填充数据库的升级：

```bash
bash backend/src/test/resources/interview-round-upgrade.sh attempts
```

- 探针先启动到 `spring.flyway.target=51`，执行 `learning-attempts-upgrade-fixture.sql`，再启动到最新版本并执行 `learning-attempts-upgrade-verify.sql`，最后执行只读 `learning-attempts-upgrade-anomaly-report.sql` 断言异常报告输出。
- fixture 覆盖：0 轮历史、2 轮可还原历史（含重复 requestId 的脏重试）、25 轮但投影只剩 20 轮、投影 3 轮但 `attempt_count=1` 的对抗行（`attempt_no` 回填 1..3、下次分配 4），以及 `LEGACY_RECAP` 来源。
- verify 断言：V52 已应用；版本列默认 1；旧列/投影/来源未被改写；回填序号、快照、答案、分数、feedback、submittedAt 和合法 requestId 正确；对抗行回填为 1..3 且 `attempt_count` 未改写、下一次分配为 `max(已存最大, attempt_count)+1 = 4`；缺 rubric/model 的行全部 `legacy=TRUE` 且没有伪造评分元数据；复合外键拒绝跨用户计划；部分唯一索引拒绝重复 requestId；删除计划级联删除尝试。
- anomaly-report 断言：异常报告 #1 恰好命中该对抗行、#2 恰好命中 25 轮截断行并给出 5 个不可恢复轮次；不修改数据。
- `learning-context-acceptance.mjs` 在同一 CI 运行中验证只读历史接口、越权 404、陈旧围栏拒绝且不评分、一次评分一条历史、题目快照/评分模型/rubric/选择题判定可读；exerciseId 按实际断言验证“存在、重复读取不变、尝试快照与被作答题目一致、替换题目集后不复用旧 ID”（不是只验证字段存在）。

本地没有 CI 容器时可以按同一顺序手工执行：`createdb` → 以 `spring.flyway.target=51` 启动后端 → 执行 fixture → 正常启动后端（应用 V52）→ 执行 verify SQL/异常报告 SQL。不要只检查 SQL 文本。

## 6. 兼容与回退

- 旧字段、旧 JSONB 投影、旧 API 响应字段全部保留；新列与新表是增量。
- 未删除学习计划时数据保留；一旦删除学习计划，其尝试历史随复合外键**不可逆级联删除**（历史行为扩大点，部署需先备份）。
- 回退旧应用可以忽略 `content_version`、`exercises_version` 与 `learning_attempts`，继续读写旧投影；不需要降级数据库、删除历史或改写已执行迁移。
- 已执行迁移不得编辑或删除。若 V52 需要修正，只能新增后续迁移。
- 已知限制：同步 attempts 的 409 版本围栏在配额保护之后执行，被拒绝的陈旧提交仍可能消耗一次 `LEARNING_ATTEMPT` 额度（既有 `QuotaAspect` 顺序），本批不改变配额顺序；异步入口不接受 `exercisesVersion`。
- 未完成/后续项：尚无「重新生成讲解」接口，`content_version` 目前只在创建时为 1；历史计划题目不会补造 exerciseId；前端尚未消费尝试历史接口（接口合同先落地，页面在后续批次接入）。
