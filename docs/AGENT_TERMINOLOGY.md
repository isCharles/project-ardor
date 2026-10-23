# Agent terminology: 课程 vs 学校课程

## Product term

The workbench navigation uses **课程** for Ardor's learning plan feature
(`/app/learning`, backend `LearningPlan`, agent tools `*_learning_plan`).
This is the user's chosen product term; do not rename the navigation.

## Agent mapping

- “课程 / 学习计划 / 学习主题” → `LearningPlan` → `list_learning_plans`,
  `get_learning_plan`, `create_learning_plan`, `update_learning_plan`,
  `begin_learning_plan`, `delete_learning_plan`.
- “学校课程表 / 上课时间 / 课表里的课 / 固定课程” → calendar recurring series →
  `list_calendar_tasks` / `list_recurring_tasks`.
- If ambiguous, ask one short question:
  “你说的课程是 Ardor 里的课程（学习计划），还是学校课表？”

The glossary lives in the tool descriptions, not in the global system prompt.
`list_learning_plans` says “课程（学习计划）”; `list_calendar_tasks` says “学校固定课程”.
This keeps the system prompt free of product-specific terminology while still giving the
model the correct mapping. The system prompt only says: if colloquial wording and resource
names differ, consult the tool/resource descriptions and ask one clarifying question if
still ambiguous.

## Why not rename the UI

Renaming 课程 to 学习计划 or 学习 everywhere would make the navigation less
friendly and would not solve the model ambiguity by itself. The durable fix is
an explicit domain glossary plus consistent tool descriptions.

## Naming policy

The persisted object behind the product's 「课程」 is `LearningPlan` in Java and
`learning_plans` in PostgreSQL. That name is historical and is kept for compatibility.

The object is actually a **learning unit / micro-course**:

- one concept;
- one generated lesson;
- one exercise set;
- an assessment, score and review schedule;
- an optional calendar task.

It is not a plan that merely points at content, and it is not a multi-lesson course.
Reserve `Course` for a future grouping of several learning units. If the Java type is
renamed later, prefer `LearningUnit`; do not rename the table, API or agent tool names
without a versioned compatibility migration.

