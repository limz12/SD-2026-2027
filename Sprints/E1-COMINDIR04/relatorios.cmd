@echo off
rem Gera os relatorios HeartbeatCheck e CausalCheck sobre uma pasta de dados.
rem Uso: relatorios.cmd [pasta] [falhas.txt]
rem   relatorios.cmd                            -> analisa dados-n3
rem   relatorios.cmd dados-test falhas.txt      -> analisa dados-test com falhas
setlocal
set "PS1=%~dp0iniciar-comindir04.ps1"
if not exist "%PS1%" set "PS1=%~dp0..\iniciar-comindir04.ps1"
if not exist "%PS1%" (
    echo Nao encontrei iniciar-comindir04.ps1 nem nesta pasta nem na pasta pai.
    pause
    exit /b 1
)
set PASTA=%~1
if "%PASTA%"=="" set PASTA=dados-n3
set FALHAS=%~2
set EXTRA=
if not "%FALHAS%"=="" set EXTRA=-Falhas "%FALHAS%"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -Acao HeartbeatCheck -PastaDados "%PASTA%" %EXTRA%
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -Acao CausalCheck -PastaDados "%PASTA%"
pause
