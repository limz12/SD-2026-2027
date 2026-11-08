# Arranque local — COMINDIR05

Este guia compila os três projetos Java e inicia os nove nós, o processador e
o produtor especificados no sprint. Os ensaios são manuais: o script prepara
as consolas e as ferramentas, mas não introduz leituras nem calcula as
conclusões experimentais.

## Pré-requisitos e compilação

- Windows PowerShell e um JDK 11 ou superior (`JAVA_HOME` ou `PATH`).
- Kubo/IPFS. Se não existir um Kubo ativo na API `127.0.0.1:5001`, o script
  procura `ipfs.exe` ou descarrega a versão 0.32.1 do repositório oficial do
  Kubo. O nó criado pelo script usa um repositório separado em
  `%LOCALAPPDATA%\SD-COMINDIR05`.

Abre PowerShell nesta pasta e verifica a compilação:

    .\iniciar-comindir05.cmd -Verificar

O comando compila `raft04-no`, `raft04-processador` e `raft04-produtor` para
uma pasta temporária em `%LOCALAPPDATA%`; não coloca ficheiros `.class` nos
projetos. Também podes executar diretamente o ficheiro `.ps1`.

## Arranque

Para iniciar a configuração completa numa pasta nova:

    .\iniciar-comindir05.cmd -PastaDados dados-9a

O script compila os três projetos, confirma os portos TCP 7100–7108, inicia os
nove nós (`p0`–`p2`, `n1`–`n3`), aguarda confirmação para iniciar o processador
e, por fim, abre o produtor. Todos os processos escrevem na pasta escolhida.
O arranque completo usa `N = 3`, `G = 3`, período de heartbeat de 2000 ms,
prazo de eleição aleatório de 6000–12000 ms e fator de timeout 3.

Se já existir um Kubo ativo na API `127.0.0.1:5001`, o script confirma que a
API PubSub está disponível antes de iniciar os programas. Se o Kubo ativo não
tiver PubSub disponível, termina esse daemon e volta a executar o script; ele
iniciará o Kubo isolado do COMINDIR05. O script não termina nem reconfigura
automaticamente um daemon que possa estar a ser usado por outro ensaio.

Cada nó guarda uma cópia completa da partição que serve. As portas de
recuperação são:

| Nó | Porta TCP | Nó | Porta TCP | Nó | Porta TCP |
| --- | ---: | --- | ---: | --- | ---: |
| p0/n1 | 7100 | p1/n1 | 7103 | p2/n1 | 7106 |
| p0/n2 | 7101 | p1/n2 | 7104 | p2/n2 | 7107 |
| p0/n3 | 7102 | p1/n3 | 7105 | p2/n3 | 7108 |

Para iniciar apenas os nove nós, ou um único nó:

    .\iniciar-comindir05.cmd -Acao Nos -PastaDados dados-9a
    .\iniciar-comindir05.cmd -Acao No -Particao 1 -NoId 2 -PastaDados dados-9a

Para iniciar apenas o processador ou o produtor:

    .\iniciar-comindir05.cmd -Acao Processador -PastaDados dados-9a
    .\iniciar-comindir05.cmd -Acao Produtor -PastaDados dados-9a

Os nós podem arrancar por qualquer ordem. Inicia os nove antes do processador e
do produtor. O script não apaga dados existentes: reutiliza a mesma pasta
apenas para ensaios que exigem continuidade ou reinício do nó; usa pastas
distintas para execuções independentes.

Na consola do produtor, os comandos são:

    <sensor> <temperatura>
    rajada <número_de_leituras> <número_de_sensores>
    rajada-quente <número_de_leituras> <número_de_sensores>
    sair

Por exemplo, com `N = 3`, `S01` é encaminhado para p0, `S02` para p1 e `S03`
para p2. Escreve `sair` para terminar o produtor. Usa Ctrl+C nas restantes
consolas quando terminares o ensaio.

## Parâmetros

Os valores de heartbeat, timeout e eleição têm de ser iguais em todos os
processos de um ensaio, incluindo nós reiniciados. O período e o fator
controlam o detetor do produtor e do processador; o mínimo e o máximo
configuram os prazos aleatórios dos nós.

    # Valores predefinidos: período 2000 ms, timeout 3x, eleição entre 6000 e 12000 ms
    .\iniciar-comindir05.cmd -PastaDados dados-9a

    # Eleição com prazo fixo, para comparar votos divididos no ensaio 9(b)
    .\iniciar-comindir05.cmd -PastaDados dados-9b-fixo -EleicaoMin 6000 -EleicaoMax 6000

    # Período alternativo: ajustar em todos os arranques e reinícios do ensaio
    .\iniciar-comindir05.cmd -PastaDados dados-9e-1000 -Periodo 1000

As opções correspondem às propriedades `raft04.periodo`, `raft04.fator`,
`raft04.eleicaoMin` e `raft04.eleicaoMax`. Os valores predefinidos para os
prazos de eleição são, respetivamente, três e seis vezes o período.

## Falha e partição de rede

Para terminar abruptamente um nó durante um ensaio, abre outro PowerShell na
pasta do sprint:

    .\iniciar-comindir05.cmd -Acao TerminarNo -Particao 1 -NoId 2 -PastaDados dados-9c

O comando exige que exista exatamente um processo com essa partição e
identificador. Depois de o terminar, regista `p<k>;n<i>;<instante_ms>` em
`falhas.txt`, que o `RaftCheck` usa para calcular a indisponibilidade observada.
A linha só é escrita depois de `Stop-Process` ter terminado sem erro.

Para simular e reparar uma partição de rede:

    .\iniciar-comindir05.cmd -Acao IsolarNo -Particao 1 -NoId 1 -PastaDados dados-9d
    .\iniciar-comindir05.cmd -Acao RepararNo -Particao 1 -NoId 1 -PastaDados dados-9d

Estas ações criam e removem `isolar-p<k>-n<i>` na pasta de dados e anotam os
instantes de início e fim em `particoes.txt`. A simulação bloqueia a publicação
e o processamento dos tópicos de lotes e eleição e as ligações TCP desse nó;
as mensagens `IN` continuam a chegar.

## Verificadores

Depois de terminares os processos de cada execução, corre ambos os
verificadores. O `RaftCheck` inclui automaticamente `falhas.txt` se o ficheiro
existir:

    .\iniciar-comindir05.cmd -Acao RaftCheck -PastaDados dados-9a
    .\iniciar-comindir05.cmd -Acao CausalCheck -PastaDados dados-9a

O `RaftCheck` reporta eleições, líderes por termo, segurança, indisponibilidade,
leituras perdidas, truncagens e divergências finais. O `CausalCheck` valida
`V[k] == seq`, a continuidade das sequências e a ordem de Lamport.

## Ensaios do enunciado

Para cada execução independente, escolhe uma pasta nova. Termina os processos
Java antes de correr os verificadores. Apaga `estado-*` e `no-*` entre ensaios
independentes; não os apagues quando o ensaio requer reiniciar um nó e
recuperar o seu estado.

- **9(a), falha e reinício:** inicia os nós de p1, espera por um líder,
  introduz leituras para p1, termina rapidamente o líder com `TerminarNo`,
  envia uma leitura durante a eleição e outra após a eleição, e reinicia o nó
  terminado com `-Acao No` e os mesmos parâmetros. Confirma `TRUNCADO` e a
  coincidência dos registos.
- **9(b), ordem de arranque:** repete o arranque dos nove nós em ordens
  diferentes. Para observar votos divididos, usa uma pasta nova e
  `-EleicaoMin 6000 -EleicaoMax 6000`.
- **9(c), falha durante rajada:** executa `rajada 300 10`, termina um nó de p1
  com `TerminarNo` e volta a iniciá-lo 30 segundos depois, preservando a pasta
  e os parâmetros.
- **9(d), isolamento:** isola o líder de p1 durante 30 segundos com
  `IsolarNo`; repara a partição com `RepararNo`. Repete sem rajada, isolando um
  seguidor durante 60 segundos.
- **9(e), perda de maioria:** termina dois nós de p1, envia leituras para p1
  durante 30 segundos e volta a iniciar um dos nós terminados.

Exemplo de reinício de um nó terminado:

    .\iniciar-comindir05.cmd -Acao No -Particao 1 -NoId 2 -PastaDados dados-9c

O comando de reinício deve usar os mesmos valores de `-Periodo`, `-FatorTimeout`,
`-EleicaoMin` e `-EleicaoMax` usados no arranque original.

## Ficheiros produzidos

- `no-p<k>-n<i>.txt`: registos persistidos em cada nó.
- `estado-p<k>-n<i>.txt`: termo e voto persistentes.
- `hb-p<k>-n<i>.txt`: lotes e heartbeats publicados pelo líder.
- `eventos-p<k>-n<i>.txt`: eleições, votos, aplicação e recuperação de lotes.
- `monitor-processador.txt` e `monitor-produtor.txt`: suspeitas, mudanças de
  líder, avisos e lacunas de heartbeat.
- `produtor.txt`: instante, partição, sensor e temperatura de cada `IN`.
- `falhas.txt`: falhas de nós anotadas pelo comando `TerminarNo`.
- `particoes.txt`: instantes de isolamento e reparação de nós.
