# Arranque local — COMINDIR04

Este guia compila os quatro projetos Java e inicia as três réplicas, os três
líderes, o processador e o produtor. Os ensaios são manuais: o script abre as
consolas e prepara as ferramentas, mas não introduz leituras nem calcula as
conclusões experimentais.

## Pré-requisitos e primeira verificação

- Windows PowerShell e um JDK 11 ou superior (`JAVA_HOME` ou `PATH`).
- Kubo/IPFS. Se não existir um Kubo ativo na API `127.0.0.1:5001`, o script
  procura `ipfs.exe` ou descarrega a versão 0.32.1 do repositório oficial do
  Kubo. O nó criado pelo script usa um repositório separado em
  `%LOCALAPPDATA%\SD-COMINDIR04`.

Abre PowerShell nesta pasta e confirma que os quatro projetos compilam:

    .\iniciar-comindir04.cmd -Verificar

O comando compila para uma pasta temporária em `%LOCALAPPDATA%`; não coloca
ficheiros `.class` nos projetos. Para iniciar um ensaio, escolhe uma pasta de
dados nova:

    .\iniciar-comindir04.cmd -PastaDados dados-8a

Se já existir um Kubo ativo na API `127.0.0.1:5001`, o script confirma que a
API PubSub está disponível antes de abrir as consolas. Se o Kubo ativo tiver
PubSub desativado, termina esse daemon e volta a executar o script; ele inicia
o Kubo COMINDIR04 com PubSub ativado. O script não termina nem reconfigura
automaticamente um daemon que possa estar a ser usado por outro ensaio.

O arranque completo usa N = 3 e atribui as partições às réplicas assim:

| Réplica | Partições |
| --- | --- |
| 1 | p0, p1 |
| 2 | p1, p2 |
| 3 | p2, p0 |

Com N = 3, o produtor encaminha `S01` para p0, `S02` para p1 e `S03` para p2.

O script pausa entre grupos de consolas para dar tempo a confirmar as
subscrições. Só introduz comandos de leitura na consola **COMINDIR04 - Produtor**.
Para terminar o produtor, escreve `sair`; para terminar os outros processos,
usa Ctrl+C nas respetivas consolas. Não inicies um segundo arranque com os mesmos
portos ocupados.

Cada projeto escreve os ficheiros de dados e monitorização na pasta indicada por
`-PastaDados`. As pastas existentes não são apagadas: reutiliza a mesma pasta
apenas quando o ensaio exige continuidade/reinício do estado, e escolhe nomes
distintos para execuções independentes.

## Parâmetros e ações

Os parâmetros usados em cada arranque devem manter-se iguais em todos os
processos desse ensaio, sobretudo `-Periodo` e `-FatorTimeout`.

    # Arranque completo com valores predefinidos: período 5000 ms, fator 3
    .\iniciar-comindir04.cmd -PastaDados dados-8a

    # Período alternativo (usar em todos os arranques e verificações do ensaio)
    .\iniciar-comindir04.cmd -PastaDados dados-8e-1000 -Periodo 1000

    # Reiniciar apenas o líder p0, conservando a pasta e o período do ensaio
    .\iniciar-comindir04.cmd -Acao Lider -Lider 0 -PastaDados dados-8a

    # Compilar e executar apenas uma réplica
    .\iniciar-comindir04.cmd -Acao Replica -ReplicaId 1 -PastaDados dados-8a

    # Executar apenas o processador ou o produtor
    .\iniciar-comindir04.cmd -Acao Processador -PastaDados dados-8a
    .\iniciar-comindir04.cmd -Acao Produtor -PastaDados dados-8a

    # Verificações finais de cada ensaio
    .\iniciar-comindir04.cmd -Acao HeartbeatCheck -PastaDados dados-8a
    .\iniciar-comindir04.cmd -Acao CausalCheck -PastaDados dados-8a

`HeartbeatCheck` recebe automaticamente `falhas.txt` quando esse ficheiro
existe. Para uma análise correta, usa o mesmo `-Periodo` e `-FatorTimeout` com
que os processos foram iniciados. `PartitionCheck` também está disponível para
conferir os ficheiros das réplicas.

### Registo de falha abrupta e controlo de suspensão

Para terminar imediatamente um líder específico e anotar o instante no
`falhas.txt` da pasta do ensaio, abre outro PowerShell nesta pasta e executa:

    .\iniciar-comindir04.cmd -Acao TerminarLider -Lider 0 -PastaDados dados-8a

O script só termina o processo se encontrar exatamente um `PartitionLeader`
com esse índice e N. Este comando não recompila os projetos, para reduzir o
atraso entre a anotação e a terminação.

Para suspender e retomar um líder sem o terminar:

    .\iniciar-comindir04.cmd -Acao SuspenderLider -Lider 2 -PastaDados dados-8d
    .\iniciar-comindir04.cmd -Acao RetomarLider -Lider 2 -PastaDados dados-8d

Estas ações também requerem exatamente um processo do líder indicado. A
suspensão não acrescenta uma linha a `falhas.txt`.

## Ensaios 8(a–e)

No fim de **cada execução independente**, termina os processos Java e executa
`HeartbeatCheck` e `CausalCheck` sobre a pasta respetiva. Guarda a saída dos
dois verificadores juntamente com as observações da ficha. Nos exemplos abaixo,
acrescenta o mesmo `-Periodo` e `-FatorTimeout` usados no arranque, quando não
forem os valores predefinidos.

### 8(a) — Perda do último registo e reinício de p0

1. Inicia uma pasta nova com `.\iniciar-comindir04.cmd -PastaDados dados-8a`.
2. No produtor, introduz `S01 21.0` e `S04 22.5`. Aguarda que p0 publique um
   LOTE vazio.
3. Introduz `S01 21.4`. Termina o líder p0 em menos de um segundo, usando outro
   PowerShell:

       .\iniciar-comindir04.cmd -Acao TerminarLider -Lider 0 -PastaDados dados-8a

4. Aguarda a suspeita nos participantes. Volta a arrancar apenas p0, com a mesma
   pasta de dados:

       .\iniciar-comindir04.cmd -Acao Lider -Lider 0 -PastaDados dados-8a

5. Confirma o evento `REINICIO` e a recuperação do registo em falta nas réplicas
   que guardam p0. Depois de terminares os processos, executa:

       .\iniciar-comindir04.cmd -Acao HeartbeatCheck -PastaDados dados-8a
       .\iniciar-comindir04.cmd -Acao CausalCheck -PastaDados dados-8a

### 8(b) — Rajada e período sem produtor

1. Inicia uma execução nova e, no produtor, executa `rajada 300 10`.
2. Recolhe o número de lotes, a proporção de lotes vazios, a latência de lote e
   o intervalo entre heartbeats. Termina os processos e corre os dois
   verificadores.
3. Repete com outra pasta. Inicia os processos, escreve `sair` no produtor e
   deixa os restantes processos ativos durante um minuto. Recolhe os heartbeats
   desse período e executa novamente `HeartbeatCheck` e `CausalCheck`.

Mantém os dados dos dois períodos em pastas distintas para comparar as
medições sem misturar intervalos.

### 8(c) — Falha de p1 durante uma rajada

Faz três execuções independentes, por exemplo com `dados-8c-1`,
`dados-8c-2` e `dados-8c-3`:

1. Inicia o sistema e prepara um segundo PowerShell na pasta do script.
2. No produtor, inicia `rajada 300 10`. Enquanto decorre, executa no segundo
   PowerShell `-Acao TerminarLider -Lider 1 -PastaDados dados-8c-1`.
3. Trinta segundos depois da falha, arranca p1 com a mesma pasta:

       .\iniciar-comindir04.cmd -Acao Lider -Lider 1 -PastaDados dados-8c-1

4. Regista o tempo de deteção em cada participante que acompanha p1. Repete as
   verificações finais para cada pasta antes de avançar para a execução
   seguinte.

Substitui o nome da pasta em cada comando nas repetições 2 e 3. Usa sempre o
mesmo período de heartbeat durante cada execução.

### 8(d) — Suspensão temporária de p2

1. Inicia o sistema numa pasta nova, por exemplo `dados-8d`.
2. Suspende p2:

       .\iniciar-comindir04.cmd -Acao SuspenderLider -Lider 2 -PastaDados dados-8d

3. Durante a pausa, introduz no produtor leituras cujos sensores sejam
   encaminhados para p2 (por exemplo, `S03 21.0`). Após 20 segundos, retoma p2:

       .\iniciar-comindir04.cmd -Acao RetomarLider -Lider 2 -PastaDados dados-8d

4. Recolhe suspeitas e retomas, os intervalos em `hb-p2.txt` após a retoma e o
   destino das leituras introduzidas durante a pausa. Termina os processos e
   executa `HeartbeatCheck` e `CausalCheck`.

### 8(e) — Comparação de períodos

Repete a experiência 8(b) e uma execução de 8(c) para cada período: 1000, 5000
e 10000 ms. Cada processo — incluindo líderes reiniciados, réplicas, produtor,
processador e verificadores — deve receber o mesmo `-Periodo` nessa execução.
Usa uma pasta diferente para cada combinação de ensaio e período, por exemplo
`dados-8e-b-1000` e `dados-8e-c-1000`. Regista as mesmas métricas para permitir
a comparação.

## Ficheiros produzidos

- `lider-p<k>.txt`: registos persistidos pelo líder da partição.
- `hb-p<k>.txt`: LOTEs publicados, incluindo heartbeats vazios.
- `replica-<id>-p<k>.txt`: registos escritos por cada réplica.
- `monitor-*.txt`: eventos dos detetores e das recuperações.
- `falhas.txt`: falhas abruptas anotadas pelo comando `TerminarLider`.

Os verificadores apresentam análise dos ficheiros existentes; cabe ao aluno
confirmar os dados e justificar as conclusões com as observações recolhidas.
