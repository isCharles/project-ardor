# 面试轮次与记录归属

此实现属于整体演进的 C 阶段第一批，不代表学习来源消歧或完整领域重构完成。

## 单一归属链

真实面试流程拥有轮次。原始转录任务保存流程及可空轮次 ID；可编辑纪要与不可变确认版本通过受所有者约束的 draft/job 继承该上下文。生成后的报告保存同一上下文，其问题通过报告继承。不会在纪要、版本、问题中复制一组可独立改写的父对象 ID。

`InterviewContextAccess` 是求职域对外的最小引用校验接口：既验证当前用户拥有流程，又验证可选轮次属于同一用户和同一流程。面经队列不再直接读取求职域 Repository。手动上传与 Agent 工具复用这一入口；模型不能决定归属是否有效，也不能代替用户确认纪要。

新 API 在原请求中追加可选 `interviewRoundId`，旧请求省略时保留流程级归档，不默认指定最新轮次。页面切换所属面试会清除旧轮次。相同原文重发但父流程/轮次不同会拒绝静默改挂；原始材料指纹与资源身份不变。旧报告的“校正原始纪要”保留原有轮次。

## 迁移、历史与回退

- V50 只新增可空关联、复合唯一键、复合外键和用户前缀索引；不改写原文、UUID、确认快照或既有 Flyway 文件。
- 旧记录全部保留 `interview_round_id IS NULL`。即使历史流程只有一轮，也不推断归属。待核对项可按用户及流程统计；核实前保持未指定，而不是伪造轮次。
- 外键同时约束轮次、流程和用户；业务入口再做同一校验。删除轮次只清空轮次关联；删除流程保留来源任务和报告，沿用原有置空语义，不新增级联删除。
- 回退应用可忽略新增可空列，不降级或删除 schema。回退期间新数据可能只有流程关联；恢复新版后也不能据此推测轮次。部署前备份按现有运维流程执行。

历史核对统计（只读，不返回正文；在受控运维环境运行）：

```sql
SELECT user_id, job_opportunity_id, count(*) AS unassigned_records
FROM interview_recaps WHERE interview_round_id IS NULL
GROUP BY user_id, job_opportunity_id;
```

## 验证边界

`InterviewContextAccessTests` 覆盖跨用户、同用户不同父流程、缺失轮次和旧请求。`interview-minutes-acceptance.mjs` 在隔离库验证原文→指定轮次→确认快照→报告的传递，以及删除轮次/流程后保留材料；不在真实用户数据上测试删除。`interview-minutes-ui.mjs` 验证切换父对象清除旧子对象、原文和轮次参数提交、编辑保存与显式确认。

`interview-round-upgrade-fixture.sql` 用于填充隔离 V49 库；升级后由 `interview-round-upgrade-verify.sql` 检查 V50、原文、报告、确认版本和未指定轮次保持不变。空库及已填充 V49 库均须经真实 Flyway 执行；只读 SQL 或单元测试不能替代该验证。
