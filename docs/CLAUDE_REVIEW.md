# Claude PR 审阅

本仓库的 Claude Code 审阅采用手动标签触发，避免每次推送都自动产生模型费用。

## 使用方式

1. 打开需要审阅的 Pull Request。
2. 添加 `claude-review` 标签。
3. 等待 `Claude PR Review` 工作流完成，并查看 PR 中的审阅结果。
4. 如需再次审阅，先移除标签，再重新添加。

工作流使用仓库 Secret `PACKY_ANTHROPIC_TOKEN`，通过 PackyCode Anthropic 兼容端点调用 Claude Code。密钥不得写入代码、工作流日志或 PR 内容。

单次任务使用 `claude-sonnet-5`，默认最多 20 轮，Claude Code 估算预算为 $5，工作流最长运行 15 分钟。

在 GitHub 仓库的 **Settings → Secrets and variables → Actions → Variables** 中可以覆盖默认值：

- `CLAUDE_REVIEW_MAX_TURNS`：最大轮数，例如 `20`。
- `CLAUDE_REVIEW_MAX_BUDGET_USD`：CLI 估算预算，例如 `5`。

未设置变量时使用以上默认值。`--max-budget-usd` 使用 Claude Code 的美元估算口径，不能直接当作 PackyCode 的人民币扣款，也不是服务商端的硬性消费限额；实际费用以服务商账单为准。达到轮数、预算或超时限制时，审阅可能未完成，不能仅凭进度评论判断成功。

## 私有仓库鉴权

Action 显式使用工作流自带的 GitHub Token，不依赖旧的 Claude 订阅登录或 GitHub App OIDC 换取凭据。Checkout 保留该临时凭据，以便 Claude Action 再次拉取 PR 分支；任务结束由 Checkout 清理。Token 的代码权限仍为 `contents: read`，仅允许写入 issue/PR 评论，不能推送代码。
