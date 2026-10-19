@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0iniciar-comindir01.ps1" -DoisNos -PerdaReplica3 0.2 %*
pause
