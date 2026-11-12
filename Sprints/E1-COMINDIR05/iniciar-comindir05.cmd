@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar-comindir05.ps1" %*
set "result=%errorlevel%"
pause
exit /b %result%
