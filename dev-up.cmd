@echo off
REM Double-click to start ISOLATED development PostgreSQL and Redis.
REM The window stays open on failure; details are written to scripts\dev-up.log.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\dev-up.ps1"
if errorlevel 1 pause
