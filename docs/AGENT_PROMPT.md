# Agent System Prompt Contract

## Layering

The prompt is deliberately split into three layers:

1. **System prompt** (`CareerAgentService.SYSTEM_PROMPT`)
   - identity;
   - domain glossary (课程 = learning plan; 学校课程 = calendar series);
   - read-before-write and confirmation semantics;
   - no-fabrication, time, untrusted-content and memory boundaries;
   - output style.

2. **Tool descriptions** (`CareerAgentTools`)
   - the actual contract of each tool: arguments, supported values, return shape;
   - tool-specific procedures (recurrence, recap source, review rating, etc.);
   - one tool's corner case stays in that tool's description, not in the global prompt.

3. **Server-side validation and authorization**
   - ownership, idempotency, confirmation, duplicate-write prevention and business rules.

The system prompt ends with:

> 具体工具参数、支持范围和返回结构以工具描述及服务端校验为准；本提示只定义行为原则，不重复工具手册。

## Prompt budget

`CareerAgentPromptTests` enforces:

- the static prompt stays under 4,000 characters;
- it contains the core terms and principles;
- it does not reintroduce old numbered sub-rules such as `17a` / `23b`.

## When fixing a behavior

Choose the lowest layer that can enforce the fix:

- If it is a tool argument/value rule → tool description or schema validation.
- If it is a safety/authorization invariant → service/repository validation.
- If it is a genuine cross-tool behavioral principle → system prompt, in one sentence.
- If it is a regression → add a test/eval; do not append a numbered example to the system prompt.

This prevents the prompt from turning into a growing pile of one-off reminders.

## Dynamic tail

`systemPrompt()` appends only two dynamic sections:

1. the current time and the user's timezone;
2. `<user_memory>` retrieved for this turn.

Advice-card requirements, recurring-calendar semantics and untrusted-content handling
live in the relevant tool descriptions or the static principles, not in a growing
tail of per-feature reminders.
