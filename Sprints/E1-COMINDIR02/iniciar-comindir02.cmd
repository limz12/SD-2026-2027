@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar-comindir02.ps1" %*
set "arranqueExitCode=%ERRORLEVEL%"
pause
exit /b %arranqueExitCode%
