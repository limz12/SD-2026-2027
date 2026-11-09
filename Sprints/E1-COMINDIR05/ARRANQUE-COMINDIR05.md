# Arranque e ensaios COMINDIR05

Executa `iniciar-comindir05.ps1` a partir de PowerShell, ou usa `iniciar-comindir05.cmd`. O script compila os projetos `raft04`, inicia ou reutiliza um Kubo com PubSub e coloca todos os processos na mesma pasta de dados. Se o Kubo não estiver na PATH nem em `.tools`, indica `-KuboPath`.

```powershell
.\iniciar-comindir05.ps1 -Acao Verificar
.\iniciar-comindir05.ps1 -Acao Tudo -PastaDados dados-ensaio-a
```

`Tudo` abre nove nós, depois o processador e o produtor. Para variar a ordem dos nós, usa `-OrdemNos` com os nove identificadores uma vez cada, por exemplo `p1n3,p0n2,p2n1,p1n1,p0n1,p2n2,p0n3,p1n2,p2n3`. Para o ensaio de prazos fixos, usa `-EleicaoMin 6000 -EleicaoMax 6000 -SincronizarArranque`. A sincronização liberta o arranque dos nove processos em conjunto, embora o tempo de inicialização do Java ainda possa variar.

Usa sempre a **mesma** `-PastaDados` nas ações de um ensaio:

```powershell
.\iniciar-comindir05.ps1 -Acao FalharNo -Particao 1 -NoId 2 -PastaDados dados-ensaio-a
.\iniciar-comindir05.ps1 -Acao No -Particao 1 -NoId 2 -PastaDados dados-ensaio-a
.\iniciar-comindir05.ps1 -Acao IsolarNo -Particao 1 -NoId 2 -PastaDados dados-ensaio-a
.\iniciar-comindir05.ps1 -Acao RepararNo -Particao 1 -NoId 2 -PastaDados dados-ensaio-a
.\iniciar-comindir05.ps1 -Acao RaftCheck -PastaDados dados-ensaio-a
.\iniciar-comindir05.ps1 -Acao CausalCheck -PastaDados dados-ensaio-a
```

`FalharNo` termina o processo sem reação e escreve `p<k>;n<i>;instante` em `falhas.txt`. `IsolarNo` e `RepararNo` criam e removem o ficheiro `isolar-p<k>-n<i>` e registam os instantes em `isolamentos.txt`. Identifica o líder atual nos eventos antes de escolher `-NoId`.

Para cada execução independente dos ensaios, fecha os processos anteriores e escolhe uma **nova pasta de dados**. Assim, o estado persistente e os registos de uma execução não entram na seguinte. O script não apaga esses ficheiros automaticamente.
