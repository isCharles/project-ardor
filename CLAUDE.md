@AGENTS.md

# Claude Code 入口

Claude 与其他 Agent 一样，按用户当前任务担任实现、诊断或审阅角色；不要因为工具名称就固定为只读审阅者。通用规则以仓库根目录 `AGENTS.md` 为入口，工程边界见 `docs/AI_GUIDE.md`，PR 审阅标准见 `docs/REVIEW_GUIDE.md`。

注意：在 PR 评论中提到 `@claude` 触发的 GitHub Action 是**独立的只读审阅流程**。它在隔离目录运行，只接收可信默认分支的审阅准则和有大小上限的 PR diff，不依赖本文件自动加载，也不能修改代码或持有可写 GitHub 凭据。本机 Claude Code 的 API Key 不会自动同步到 GitHub Actions Secret；触发、费用和限制见 `docs/CLAUDE_REVIEW.md`。

