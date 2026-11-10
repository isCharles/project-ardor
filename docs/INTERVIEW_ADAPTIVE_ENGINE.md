# 自适应模拟面试引擎

一句话目标：不是问完一套固定题目打分，而是用可控的方式探到候选人真实的知识边界，并说清每个结论的证据来自哪一次回答。

## 一、为什么是工作流编排而不是自由 Agent

模型负责所有非结构化工作：读简历与岗位描述、写题干、按 rubric 评分、写报告文字。Java 负责全部确定性逻辑：面试状态、难度档位、维度调度、画像更新、终止条件。这些判断写在 `AdaptivePolicyEngine`、`CompetencyProfileManager` 等纯 Java 类里，不读任何模型自由文本，也不写数据库、不调用模型，因此可以脱离 Spring 与网络重复运行。

只有一个编排者 `AdaptiveInterviewOrchestrator`，不存在“每个功能一个 Agent”。模型不能决定下一题问什么、什么时候结束，也不能改写画像；它只能在 Java 给出的 `QuestionTarget`（维度、知识点、难度、题型、来源）约束下产出一道题，或在给定 rubric 约束下给出评分字段。

## 二、组件与边界

| 组件 | 职责 | 关键约束 |
| --- | --- | --- |
| `InterviewPlanner` | 简历 + 岗位描述 → 结构化能力规划 | 简历只产生 `resumeMentioned` 假设，不构成能力证据；维度数量、去重、排序、题型合法性由 Java 决定 |
| `AdaptiveQuestionService` | 出题并冻结 rubric | 编程题来自内置题库且必须落在该知识点自己的题目范围内，不调用模型；rubric 在题目离开服务前必须完整 |
| `AnswerEvaluator` | 按已存的 rubric 评分 | 没有 rubric 的题直接拒绝评分；`quote` 必须逐字来自答案原文；3 分及以上必须覆盖全部必答要点 |
| `AdaptivePolicyEngine` | 难度、追问、换维度、终止 | 纯 Java，输入只有历史回合与预算 |
| `CompetencyProfileManager` | 维度画像 | 证据必须来自不同难度的多题，未考察 ≠ 不会 |
| `AdaptiveReportGenerator` | 报告 | 画像与知识边界由 Java 组装，模型只写文字；没有对应证据的建议被丢弃 |

## 三、评分标准先于题目存在

每道题的 rubric（必答要点、深度标志、常见误解、固定 0–4 分档）在题目持久化时一起写入 `interview_questions.rubric`，评分器只读这份数据，不重新生成。0–4 档含义固定：0 错误或无关，1 概念残缺且有明确误解，2 方向正确但不完整，3 正确并讲清关键原理，4 有场景与取舍。

结构化输出为 `{answerKind, score, confidence, covered[], misconceptions[], knowledgeGaps[]}`。Java 由此推导 `missingPoints`，而不是相信模型自述的“还差什么”。三类不算能力的回答被单独区分：`CLARIFICATION`（候选人反问题意）、`OFF_TOPIC`（跑题）、`UNKNOWN`（明确说不会）。低置信度（`confidence < 0.60`）的评分不作为能力证据，策略改为换角度澄清或换题。

## 四、策略表与硬约束

| 上一回合 | 下一步 |
| --- | --- |
| 3–4 分 | 升一档并深挖同一知识点 |
| 2 分 | 保持难度，针对未覆盖要点追问 |
| 0–1 分 | 降一档验证基础 |
| 低置信度 | 换问法或换角度，最多 1 次 |
| 连续答不出 | 记录缺口并切换到下一维度 |

硬约束：同一知识点最多 3 个回合、澄清最多 2 次；同一维度连续追问最多 2 次（用户选择深挖时放宽）；每个维度最多 4 个回合；单场最多 30 题；到达时长上限立即结束。未评分的题不计入能力证据，但计入出题预算与时长判断，因此“评分一直失败”不会让面试无限进行。

## 五、状态与恢复

PostgreSQL 是唯一状态存储，Redis 不参与面试状态。`V97__add_adaptive_interview.sql` 在既有表上扩展 `interview_sessions`（`mode`、`seniority`、`duration_minutes`、`deep_dive`、`job_description`）、`interview_questions`（维度、知识点、难度、`rubric`、`origin`、`parent_question_id`、`difficulty_feedback`）、`interview_answers`（`evaluation_attempts`、`evaluation_error`），并新增 `interview_plans`、`interview_turn_evaluations`、`interview_policy_decisions`、`interview_competency_assessments`。结构化内容用 JSONB，但用户归属、会话、序号、知识点等仍是有索引的独立列。

`GET /api/interviews/adaptive/{sessionId}` 既是读接口也是恢复入口：它会先补算待评分的回答，再决定下一题。所有模型调用都在事务之外，所有写入都在 `findOwnedForUpdate` 的行锁内重新确认状态。因此崩溃可能留下的三种半成品（缺规划、答案待评分、已评分但没出下一题）都会被下一次读取补齐。重复提交同一答案按幂等处理；并发读取不会重复插入评分，也不会用过期历史多问一题。

## 六、接口

固定题目面试继续使用原有接口，自适应面试走 `/api/interviews/adaptive`：`POST` 创建、`GET` 列表、`GET /{sessionId}` 读取状态并驱动恢复、`POST /{sessionId}/answers` 提交答案与难度反馈、`POST /{sessionId}/finish` 生成报告。取消、删除与报告读取两种模式共用。`InterviewService` 对自适应会话拒绝执行固定题目流程，反向亦然。

候选人侧的状态对象刻意不包含 rubric：评分标准在出题前写好，展示出来会把面试变成阅读理解。

## 七、指标与验收

`InterviewMetrics` 输出 `ardor.interview.*`：场次开始/完成计数、出题耗时（按题型与来源区分是否调用模型）、评分耗时（按 scored/clarification/failed 区分）、按难度计的题目分布、候选人难度反馈计数、每个维度画像实际覆盖的维度数、以及按阶段（plan/question/evaluate/report）归属的输入输出 token。维度名来自模型，不能做 tag，所以覆盖度用每场汇总。指标记录失败不影响面试推进。

自动化验收：`node tests/interview-adaptive-ui.mjs` 覆盖创建→按 rubric 出题→评分失败可重试→难度升档→画像与缺口→报告与建议；`mvn test` 覆盖规划、评分、策略、编排恢复、HTTP 边界与指标。两者都通过 mock 或 fake LLM，不代表真实模型质量；真实供应商下的面试质量仍需人工抽样。
