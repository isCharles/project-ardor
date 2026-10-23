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

The system prompt carries this glossary and the learning-plan tool descriptions
repeat it, so the model does not infer “课程” as a recurring calendar meeting.

## Why not rename the UI

Renaming 课程 to 学习计划 or 学习 everywhere would make the navigation less
friendly and would not solve the model ambiguity by itself. The durable fix is
an explicit domain glossary plus consistent tool descriptions.
