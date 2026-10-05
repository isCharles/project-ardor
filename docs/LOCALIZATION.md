# Localization rollout

Ardor's UI locale defaults to English. The user can switch to 简体中文; the choice is saved in browser storage and reused across sessions on that device. The initial server render uses English, then restores a saved choice after hydration. Locale selection does not change account data, stored messages, or the server URL.

## Current coverage

- Login and registration
- Shared navigation and the main chat workspace (primary labels and actions)
- Learning workspace (primary labels, answer controls, and attempt history)
- Application rhythm, growth evidence, resume library/report, and flashcard interface text
- Main chat sidebar workspaces and account menu
- Settings, including profile, API configuration, password controls, and the desktop-update panel
- Admin dashboard, including system APIs, quotas, users, and navigation
- Calendar, including dates, holidays, task forms, recurring schedules, and task actions

This is a foundation, **not a complete bilingual application**. Interviews, knowledge, interview notes, replay, desktop-native dialogs and offline/error screens still contain Chinese UI text. API and model-generated errors may also remain Chinese, including messages shown after a request. Stored task and report content is displayed verbatim. Do not advertise those surfaces as translated until they are reviewed in both locales.

## Next steps

1. Translate each feature module and its empty, loading, error, and confirmation states.
2. Add a locale-aware option for model-generated learning content and other generated reports while preserving existing user content unchanged.
3. Localize desktop-native prompts, update status, installer/offline screens, and add a keyboard-accessible language choice where needed.
4. Test the complete route set in both locales at desktop and mobile widths, including locale persistence and switching mid-task without losing drafts or answers.

Keep display text translation in the UI layer. Do not translate user-authored text, stored reports, or identifiers during locale switching.
