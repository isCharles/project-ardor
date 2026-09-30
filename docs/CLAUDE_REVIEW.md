# Claude PR 审阅

本仓库的 Claude Code 审阅采用手动标签触发，避免每次推送都自动产生模型费用。

## 使用方式

1. 打开需要审阅的 Pull Request。
2. 添加 `claude-review` 标签。
3. 等待 `Claude PR Review` 工作流完成，并查看 PR 中的审阅结果。评论作者显示为 `github-actions`，因为发布评论使用仓库的 GitHub Token；审阅内容由 Claude Code 生成。
4. 如需再次审阅，先移除标签，再重新添加。

工作流使用仓库 Secret `PACKY_ANTHROPIC_TOKEN`，通过 PackyCode Anthropic 兼容端点调用 Claude Code。密钥不得写入代码、工作流日志或 PR 内容。

单次任务使用 `claude-sonnet-5`，默认最多 20 轮，Claude Code 估算预算为 $5，工作流最长运行 15 分钟。

在 GitHub 仓库的 **Settings → Secrets and variables → Actions → Variables** 中可以覆盖默认值：

- `CLAUDE_REVIEW_MAX_TURNS`：最大轮数，例如 `20`。
- `CLAUDE_REVIEW_MAX_BUDGET_USD`：CLI 估算预算，例如 `5`。

未设置变量时使用以上默认值。`--max-budget-usd` 使用 Claude Code 的美元估算口径，不能直接当作 PackyCode 的人民币扣款，也不是服务商端的硬性消费限额；实际费用以服务商账单为准。达到轮数、预算或超时限制时，审阅可能未完成，不能仅凭进度评论判断成功。

## 私有仓库鉴权

Checkout 使用临时 GitHub Token 检出私有仓库，但不把凭据留在工作区。工作流先生成有大小上限的 PR diff，Claude Code 在临时目录中审阅这份内容，不能使用本地工具，也不接收 GitHub Token。Claude 结束后，独立发布步骤使用 GitHub Token 把结果发到 PR。

这一隔离也意味着审阅只覆盖提交的 diff：超出 120 KB 时工作流会停止，Claude 不会暗中截断后给出不完整结论。若判断问题需要 diff 之外的上下文，审阅者应明确说无法确认。
