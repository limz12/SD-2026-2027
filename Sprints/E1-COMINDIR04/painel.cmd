@echo off
rem Abre o painel de controlo COMINDIR04 (janela com botoes).
rem Funciona quer esteja na raiz do sprint quer na subpasta cmd.
setlocal
set "PS1=%~dp0painel.ps1"
if not exist "%PS1%" set "PS1=%~dp0..\painel.ps1"
if not exist "%PS1%" (
    echo Nao encontrei painel.ps1 nem nesta pasta nem na pasta pai.
    pause
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
