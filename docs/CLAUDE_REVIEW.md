# Claude PR 审阅

本仓库的 Claude Code 审阅采用手动标签触发，避免每次推送都自动产生模型费用。

## 使用方式

1. 打开需要审阅的 Pull Request。
2. 添加 `claude-review` 标签。
3. 等待 `Claude PR Review` 工作流完成，并查看 PR 中的审阅结果。
4. 如需再次审阅，先移除标签，再重新添加。

工作流使用仓库 Secret `PACKY_ANTHROPIC_TOKEN`，通过 PackyCode Anthropic 兼容端点调用 Claude Code。密钥不得写入代码、工作流日志或 PR 内容。

为控制费用，单次任务使用 `claude-sonnet-5`、最多 4 轮，并设置 0.10 美元的 Claude Code 预算上限。中转站的实际计费和预算换算仍以服务商账单为准。
