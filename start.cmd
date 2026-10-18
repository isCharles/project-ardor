@echo off
REM Double-click to build and deploy the full Docker stack (like a local release).
REM Docker app containers that own 8080/3000 are stopped automatically.
REM IDE-run backend or dev servers are never killed here: stop those yourself.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-deploy.ps1"
if errorlevel 1 (echo. & echo Deploy failed. See the output above.) else (echo. & echo Deploy complete. Use the configured frontend port.)
pause
