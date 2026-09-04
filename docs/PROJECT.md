# Project Ardor 产品说明

## 产品定位

Project Ardor 是一个能够持续理解用户状态并推进求职过程的 AI Career Agent，不是一次性聊天机器人，也不是简历、面试和日历三个互不相干的工具。

V0.1 只服务 Career 场景。系统应保留用户简历、目标、对话、面试结果和计划，使用户再次回来时可以从已有状态继续。

## Golden Path

1. 用户注册并登录。
2. 用户上传 PDF 或 DOCX 简历。
3. 系统解析一次并持久化结构化简历分析。
4. 用户说：“我想准备字节 Java 面试。”
5. Agent 检查已有信息并主动澄清缺项。
6. 信息充分后，Agent 通过 Interview Service 创建文本模拟面试。
7. 用户逐题作答并完成面试。
8. 系统生成结构化评价。
9. Planner 根据评价自动创建 3 个后续待办。
10. 用户以后可继续对话、面试或完成计划。

## V0.1 成功标准

- Golden Path 端到端完整、稳定并可以演示。
- 真实登录与严格的多用户数据隔离。
- Agent 会在信息不足时澄清，而不是盲目执行。
- 简历文本和分析结果可复用，不在每次对话时重新解析。
- 面试评价和后续待办结构化持久化。
- Manual UI 与 Agent Tool 的行为一致。

## 本轮范围

Phase 1 只交付可运行工程骨架、PostgreSQL、Flyway 初始 schema、前后端基础页、架构边界和开发文档。业务用例从 Phase 2 开始实现。

## 明确不做

V0.1 已提供轮次式语音面试 MVP（录音、ASR 转写、题目 TTS 朗读和文字确认）。
逐帧流式 ASR、打断式对话和 WebRTC 原生 Speech-to-Speech 仍不在当前范围；
面经爬取、Google Calendar、Gmail、Multi-Agent、LangGraph 以及 Career 之外的生活领域也暂不实现。

联网搜索（Tavily）与知识库 RAG（pgvector + 用户自备 Embedding 模型）已在后续阶段落地，见 `docs/DATABASE.md` 与 `README.md`。
