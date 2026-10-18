@echo off
REM Double-click to build and deploy the full Docker stack (like a local release).
REM Stop the IDEA backend and VS Code Next.js dev server first: ports 8080/3000 must be free.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-deploy.ps1"
if errorlevel 1 (echo. & echo 部署失败，详情见上方输出。) else (echo. & echo 部署完成：http://localhost:3000)
pause
