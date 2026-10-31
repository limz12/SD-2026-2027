@echo off
rem Para todos os processos java (replicas, lideres, processador, produtor)
rem e o daemon Kubo lancados pelo arranque.
taskkill /F /IM java.exe /T 2>nul
taskkill /F /IM ipfs.exe /T 2>nul
echo Processos parados. As consolas abertas podem fechar com o botao X.
pause
