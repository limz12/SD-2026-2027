@echo off
rem Lanca o sistema COMINDIR04 completo (Kubo + 3 replicas + 3 lideres + processador + produtor).
rem Funciona quer esteja na raiz do sprint quer numa subpasta.
rem Opcoes uteis:
rem   arrancar.cmd -PastaDados dados-teste     (pasta de dados alternativa)
rem   arrancar.cmd -PeriodoHb 1000             (batimentos de 1 s, em TODOS os processos)
rem   arrancar.cmd -ComPausas                  (confirmar cada fase com ENTER)
rem   arrancar.cmd -SemProcessador             (sem processador de alarmes)
setlocal
set "PS1=%~dp0iniciar-comindir04.ps1"
if not exist "%PS1%" set "PS1=%~dp0..\iniciar-comindir04.ps1"
if not exist "%PS1%" (
    echo Nao encontrei iniciar-comindir04.ps1 nem nesta pasta nem na pasta pai.
    pause
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*
pause
