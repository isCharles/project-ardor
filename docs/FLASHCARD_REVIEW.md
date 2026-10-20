# 学习复习：记忆卡状态所有权

记忆卡和复习历史由 learning.review 管理，不再由面试记录 Service 写入；它们也不是 Agent 长期记忆。

## 单一写入入口

FlashcardService 拥有创建、列表、编辑、自评复习、暂停/恢复、单项删除和快照删除。HTTP 和 Agent 复用它，返回同一不可变 FlashcardView；旧 recap.web.MemoryCardController/Response 只保留传输适配。

面试来源通过 InterviewRecordSourceAccess 校验当前用户拥有问题及父记录，不读取另一个领域的 Repository。自主卡片不需要面试父对象。既有“同一用户的问题已有卡片”检查、来源类型与字段长度约束保持，不把这次拆分宣称为新的并发去重保证。

复习策略和日程联动保持原行为：按用户时区更新受影响日期；编辑文本不重置间隔、次数或下次复习时间；暂停状态拒绝复习；评一次写一条历史。日程仍是安排投影，不能代替卡片状态。

FlashcardProgressAccess 提供无卡片正文的复习摘要；学习进度独立向它和重答摘要接口取数，面试记录领域不再读取复习 Repository。

## 删除与权限

所有业务操作仍按资源 ID 和当前用户共同限定。Agent 删除工具只提出含真实目标的确认提案，不能直接调用删除；点击受鉴权界面后才进入业务接口。

快照删除先解析全部去重后的、当前用户拥有的 ID，再执行删除、flush 并重算日程。混入外部用户 ID 时，前面的合法卡片也不能被删除。删除只针对该快照，不扩大为执行时新查出的全部卡片。

删除面试记录仍只按既有外键规则清空卡片来源问题引用，不删除独立卡片和复习进度；删除卡片的历史级联沿用原 schema，不增加新级联。

## 数据与兼容

MemoryCard、MemoryCardReview、来源/状态/自评枚举及两个 Repository 从 recap 移至 learning.review。保留 JPA 实体简单名、表 memory_cards/memory_card_reviews、列、枚举字符串、资源 UUID、15 个响应字段及 null/空值语义。

不新增或改写 Flyway，不搬运表、不回填假历史、不双写。旧 /api/memory-cards、/app/cards、工具名称、CARDS scope 和删除快照体继续使用原合同。Agent Java 构造依赖改为显式提供复习服务，不用空依赖默默降级。

部署使用干净构建，避免已删除旧包的 Entity class 残留造成重复映射。应用回退保留现有 schema；领域包名不是持久化身份或桌面协议，不需要因此发布原生安装包。

## 验证

- 原有 MemoryCardTests 保留间隔与暂停规则；FlashcardSourceTests 使用真实来源应用接口验证归属。
- FlashcardLifecycleTests 覆盖自主卡片、编辑保留进度、复习历史、暂停拒绝、外部 ID 快照原子拒绝与删除后重算顺序。
- FlashcardApplicationContractTests 对照旧响应逐字段核对 JSON、快照不可变、JPA 表/实体名及禁止反向跨域存储依赖。
- Agent 工具测试证明读操作使用学习视图，删除仍只生成确认提案。
- learning-context-acceptance.mjs 在隔离 PostgreSQL/HTTP 验证来源记录删除后卡片保留、编辑/暂停/恢复、跨用户拒绝、快照混入外部 ID 不部分删除、快照不影响新卡片；既有真实浏览器与 Golden Path 验收继续适用。
