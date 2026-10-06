# Claude PR 审阅

本仓库的 Claude Code 审阅采用 PR 评论中的独立指令触发，避免每次推送都自动产生模型费用。Codex 的 PR 自动审阅不受影响。

## 使用方式

1. 打开需要审阅的 Pull Request。
2. 有仓库写权限的维护者在 PR 对话里单独发送一条 `@claude review`。不要把它嵌在普通评论中；每发送一次就可能产生一笔模型费用。
3. 等待 `Claude PR Review` 工作流完成，并查看 PR 中的审阅结果。评论作者显示为 `github-actions`，因为发布评论使用仓库的 GitHub Token；审阅内容由 Claude Code 生成。
4. 修复后如需再次审阅，维护者再发送一条 `@claude review`。推送新 commit 不会自动触发付费复审。

工作流使用仓库 Actions Secret `PACKY_ANTHROPIC_TOKEN`，通过 PackyCode Anthropic 兼容端点调用 Claude Code。**本机 Claude Code / CC Switch 的 Key 与 Actions Secret 相互独立**；更换本机分组或密钥后，若旧密钥失效，需单独更新 GitHub Secret。密钥不得写入代码、工作流日志或 PR 内容。

这是仓库自建的受限 Action，不是官方 Claude GitHub App 的交互模式。`@claude review` 是本工作流的精确命令，**不会**让评论署名变为 Claude，也不会授权 Claude 修改代码。若将来要显示官方 Claude bot 身份，需要另行安装 GitHub App 并审核其仓库权限，不能只改触发词。

单次任务在 YAML 中默认使用 `claude-sonnet-5`；Ardor 仓库目前通过变量 `CLAUDE_REVIEW_MODEL=claude-opus-5-5` 覆盖。当前 `cc-sale` 密钥的只读模型列表已列出这一 ID，但实际推理仍需首次手动审阅验证。最多 20 轮，Claude Code 估算预算为 $5，工作流最长运行 15 分钟。切换本机 CC Switch 分组不会自动改变 CI 模型；其他仓库应先确认供应商支持的**准确模型标识**，不要凭展示名猜测。

在 GitHub 仓库的 **Settings → Secrets and variables → Actions → Variables** 中可以覆盖默认值：

- `CLAUDE_REVIEW_MAX_TURNS`：最大轮数，例如 `20`。
- `CLAUDE_REVIEW_MAX_BUDGET_USD`：CLI 估算预算，例如 `5`。
- `CLAUDE_REVIEW_MODEL`：供应商支持的准确模型标识；未设置时为 `claude-sonnet-5`。

未设置变量时使用以上默认值。`--max-budget-usd` 使用 Claude Code 的美元估算口径，不能直接当作 PackyCode 的人民币扣款，也不是服务商端的硬性消费限额；实际费用以服务商账单为准。达到轮数、预算或超时限制时，审阅可能未完成，不能仅凭进度评论判断成功。

## 私有仓库鉴权

`issue_comment` 事件使用默认分支上的工作流。独立的前置任务先核验完整 `@claude review` 指令和请求人的仓库写权限；**只有通过后**才进入同一 PR 的互斥审阅任务，普通或无权限评论不会取消正在进行的审阅。审阅任务再次核对 PR 仍打开。Checkout 使用临时 GitHub Token 检出**可信默认分支**，不运行 PR 代码，也不把凭据留在工作区。随后将 PR head 仅作为 Git 数据抓取，固定 base/head SHA，生成有大小上限的 diff；从可信默认分支读取 `docs/REVIEW_GUIDE.md`。Claude Code 在临时目录中审阅这份内容；它只加载该临时目录的项目设置，未预先批准任何本地工具，也不接收 GitHub Token。Claude 结束后，独立发布步骤使用 GitHub Token 把结果发到 PR。

发布前会再次核对 PR 的 base/head SHA；审阅期间若有人 push 或目标 PR 关闭，工作流会拒绝发布过期结论。成功评论会标明实际审阅的 commit ID。

这一隔离也意味着审阅只覆盖提交的 diff：超出 120 KB 时工作流会停止，Claude 不会暗中截断后给出不完整结论。若判断问题需要 diff 之外的上下文，审阅者应明确说无法确认。Action 若失败，先查看日志，不要把失败当成“没有问题”；也不要为了试通就连续发送多条付费指令。
