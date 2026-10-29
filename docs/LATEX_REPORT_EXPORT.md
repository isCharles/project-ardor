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
- `ARDOR_LATEX_WORK_DIR`: existing writable temporary parent directory. By default,
  Windows retains the development-directory workaround; Linux uses the OS temp directory.
- `ARDOR_BACKEND_URL`: backend base URL used by the route (default
  `http://127.0.0.1:8080`).

## Fonts

The generated document prefers `SimSun` for body text and `SimHei` for field
labels, with Noto Serif/Sans CJK as fallback. The Docker runtime installs XeLaTeX,
the required TeX packages and Noto CJK fonts, and sets `ARDOR_LATEX_ENGINE=xelatex`.
Compilation therefore needs no first-request TeX download or Windows font paths.
This substantially increases the first image build/download size; subsequent builds
reuse the unchanged compiler/font layer. Daily native development need not rebuild it.

## Why the temporary directory is under the frontend

Tectonic failed with `Access is denied` when its working directory was the
Windows `%TEMP%` tree in this environment. The route creates
`frontend/.latex-report-*` on Windows instead. Linux containers compile in `/tmp`
as the non-root `node` user. Both paths are removed after compilation; temporary
sources are excluded from Git and the Docker build context.

## Limits and verification

- At most two compilations run concurrently per frontend process; excess requests
  receive 429 and `Retry-After`. Each compiler process has a 90-second timeout.
- XeLaTeX runs with shell escape disabled. Failed passes/timeouts do not become
  successful downloads just because a partial PDF exists. The legacy Windows
  Tectonic cleanup exception remains narrowly scoped to its known access-denied error.
- Requests retain backend session-cookie authorization. Malformed IDs fail before
  upstream requests, and no temporary compilation starts for 401/404 responses.
- `scripts/report-export-smoke.mjs` exercises the real frontend container/compiler
  against an explicitly synthetic authenticated backend fixture, not real accounts.
  The `report-export` CI job parses and renders the exported Chinese PDF and retains
  synthetic artifacts. It does not replace real-backend cross-user tests.
## LaTeX resume workspace preview

`GET /latex-api/{projectId}?revision={n}` compiles a stored workspace snapshot and
returns the PDF inline. It reads the revision through the backend with the caller's
session cookie, so ownership is enforced before any compiler starts. Both this route
and the report export share `frontend/src/lib/latex-compile.ts`: one concurrency
limiter, one timeout, one completeness check.

Workspace documents are user-authored, so this route also passes `openin_any=p` and
`openout_any=p` to Web2C engines (XeLaTeX), which rejects absolute and parent-relative
file access from inside the document. Shell escape stays disabled. Tectonic ignores
those environment variables; the CI smoke test asserts that an `\input{/etc/hostname}`
document is refused instead of compiled.

`scripts/report-export-smoke.mjs` now also requests a synthetic workspace revision,
checks the PDF bytes and asserts the escape attempt returns 503; the workflow extracts
its Chinese text with `pdftotext`.

## UI and CI contract

- The report header action is labelled **Export report / 导出报告**; it must
  not be named “Export PDF” because that can be mistaken for exporting the
  original resume.
- The old client-side canvas screenshot exporter and its dependencies
  (`html2canvas-pro`, `jspdf`) were removed.
- `frontend/tests/resume-scoring-ui.mjs` opens the legacy-report entry with
  **Start analysis / 开始分析**. The report itself has no re-analyze action.
- The browser UI regression does not invoke the LaTeX route; the separate
  `report-export` CI job exercises the actual container, TeX engine and PDF output.
- The workspace page lives at `/app/latex` and is linked from the resumes header. It
  previews only saved revisions; saving uses the revision number it started from and
  surfaces a 409 as a conflict instead of overwriting.
- Before changing the button label or report flow, update
  `frontend/tests/resume-scoring-ui.mjs` and run it with
  `ARDOR_UI_BROWSER_CHANNEL=msedge` locally if the bundled Chromium is absent.
