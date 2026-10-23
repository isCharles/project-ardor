# LaTeX report export

The resume report uses a server-side LaTeX/XeTeX compilation step.

## Endpoint

`GET /report-api/{resumeId}`

The route reads the latest analysis from the backend using the caller's session
cookie, generates `report.tex`, compiles it, and returns `application/pdf`.

## Engine configuration

The route looks for the engine in this order:

1. `ARDOR_LATEX_ENGINE` (absolute path or command name)
2. `preview/latex-tools/tectonic.exe` for local development
3. `tectonic` on `PATH`

Supported engines:

- Tectonic (default): `tectonic -X compile report.tex`
- XeLaTeX: any executable whose name ends in `xelatex`; it is run twice with
  `-interaction=nonstopmode -halt-on-error`.

Optional environment variables:

- `ARDOR_TECTONIC_CACHE_DIR`: persistent Tectonic bundle/cache directory.
- `ARDOR_BACKEND_URL`: backend base URL used by the route (default
  `http://127.0.0.1:8080`).

## Fonts

The generated document prefers `SimSun` for body text and `SimHei` for field
labels, with Noto Serif/Sans CJK as fallback. Production images should include a
CJK font package; do not depend on Windows-only fonts.

## Why the temporary directory is under the frontend

Tectonic failed with `Access is denied` when its working directory was the
Windows `%TEMP%` tree in this environment. The route creates
`frontend/.latex-report-*` instead and removes it after compilation; that path
is gitignored.

## UI and CI contract

- The report header action is labelled **Export report / 导出报告**; it must
  not be named “Export PDF” because that can be mistaken for exporting the
  original resume.
- The old client-side canvas screenshot exporter and its dependencies
  (`html2canvas-pro`, `jspdf`) were removed.
- `frontend/tests/resume-scoring-ui.mjs` opens the legacy-report entry with
  **Start analysis / 开始分析**. The report itself has no re-analyze action.
- The UI regression does not invoke the LaTeX route because CI does not install
  a TeX engine. The route is still covered by Next.js typecheck, lint, build,
  and a manual smoke test when an engine is configured.
- Before changing the button label or report flow, update
  `frontend/tests/resume-scoring-ui.mjs` and run it with
  `ARDOR_UI_BROWSER_CHANNEL=msedge` locally if the bundled Chromium is absent.
