# Arranque local — COMINDIR04

O script `iniciar-comindir04.ps1` compila os quatro projetos `rep04`, inicia os processos na ordem réplicas, líderes, processador e produtor, e conserva os ficheiros de cada ensaio. O ficheiro `.cmd` permite abri-lo por duplo clique.

Executa os comandos abaixo num PowerShell aberto nesta pasta:

```powershell
.\iniciar-comindir04.ps1 -Verificar
.\iniciar-comindir04.ps1 -PastaDados dados-8a
.\iniciar-comindir04.ps1 -Acao Lider -Lider 0 -PastaDados dados-8a
.\iniciar-comindir04.ps1 -Acao Analises -PastaDados dados-8a -FalhasTxt falhas.txt
```

Para uma falha provocada durante um ensaio, prepara o comando seguinte num segundo PowerShell e executa-o **no instante escolhido**, logo após enviares a leitura pretendida no produtor:

```powershell
.\iniciar-comindir04.ps1 -Acao FalharLider -Lider 0 -PastaDados dados-8a
```

`FalharLider` identifica o processo que escuta na porta TCP da partição, confirma que é um `rep04.PartitionLeader`, termina-o à força e anota `p<k>;instante` em `falhas.txt`. Esta ação não compila, não inicia Kubo e não espera por outras ações. Confirma na consola do líder e nos ficheiros se a publicação seguinte chegou a ocorrer; a ferramenta não garante por si só que a leitura ficou por publicar. Podes escolher outro ficheiro com `-FalhasTxt`.

Usa uma pasta de dados diferente por execução independente. Para reiniciar só um líder durante o mesmo ensaio, mantém a mesma pasta. Termina os processos do ensaio anterior antes de iniciar outro; o script não apaga dados nem agenda falhas automaticamente.

`-Acao Analises` executa `HeartbeatCheck` e `CausalCheck`. Também podes usar `-Acao HeartbeatCheck`, `-Acao CausalCheck` ou `-Acao PartitionCheck` separadamente. `-FalhasTxt` é opcional e só é passado a `HeartbeatCheck`; um caminho relativo é interpretado dentro de `-PastaDados`.

Para os ensaios com outros períodos, passa `-PeriodoMs 1000`, `-PeriodoMs 5000` ou `-PeriodoMs 10000` em **cada arranque** dos processos. O valor por omissão é 5000. `-FatorTimeout` tem valor por omissão 3. O script passa ambos os valores a todos os processos Java que inicia. Não mistures processos com períodos diferentes no mesmo ensaio.

`-Acao Produtor` e `-Acao Processador` abrem apenas o respetivo processo. `-SemProcessador` omite o processador no arranque completo. `-PerdaReplica3` conserva a simulação de perdas do script anterior, com valor por omissão zero.

Confirma as subscrições nas consolas antes de prosseguir nas pausas do script. Os ensaios, a anotação das falhas e a interpretação dos relatórios são feitos pelo estudante.
