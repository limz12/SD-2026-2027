# Arranque local — COMINDIR05 (eleição do líder de partição)

Este guia compila os três projetos Java (`raft04-no`, `raft04-produtor`,
`raft04-processador`) e inicia os **nove nós** (G = 3 nós por cada uma das N =
3 partições), o processador e o produtor. Os ensaios são manuais: o script abre
as consolas e prepara as ferramentas, mas não introduz leituras nem calcula as
conclusões experimentais — para a bateria automática completa, usa
`testar-comindir05.ps1` (secção final).

## Pré-requisitos e primeira verificação

- Windows PowerShell e um JDK 11 ou superior (`JAVA_HOME` ou `PATH`).
- Kubo/IPFS. Se não existir um Kubo ativo na API `127.0.0.1:5001`, o script
  procura `ipfs.exe` (ou descarrega a versão 0.32.1) e cria um nó com
  repositório próprio em `%LOCALAPPDATA%\SD-COMINDIR05`.

Abre PowerShell nesta pasta e confirma que os três projetos compilam:

    .\iniciar-comindir05.cmd -Verificar

O comando compila para uma pasta temporária em `%LOCALAPPDATA%`; não coloca
ficheiros `.class` nos projetos. Para iniciar um ensaio, escolhe uma pasta de
dados **nova** (a ficha manda apagar `estado-*` e `no-*` entre ensaios; usar
pastas distintas é a forma mais segura):

    .\iniciar-comindir05.cmd -PastaDados dados-9a

O arranque completo abre **11 consolas**: os nove nós (`No p0 n1` … `No p2 n3`,
por qualquer ordem — a primeira eleição decorre no arranque), o processador e
o produtor. O script faz uma pausa para confirmares a eleição: em cada consola
de nó deve aparecer `LÍDER de p<k>` (a eleição decorre entre 6 s e 12 s após o
arranque, prazo sorteado em `[3Δ, 6Δ]`). **Só introduz leituras na consola
`COMINDIR05 - Produtor`**, depois de as três partições terem líder.

Para terminar o produtor, escreve `sair`; para terminar os outros processos,
usa Ctrl+C nas respetivas consolas. Não inicies um segundo arranque com os
mesmos portos ocupados (portos TCP de recuperação: 7101–7123).

## Parâmetros e ações

Os parâmetros usados em cada arranque devem manter-se iguais em todos os
processos desse ensaio, sobretudo `-Periodo`, `-EleicaoMin` e `-EleicaoMax`.

    # Arranque completo com valores predefinidos: Δ = 2000 ms, eleição [6000, 12000] ms
    .\iniciar-comindir05.cmd -PastaDados dados-9a

    # Ensaio 9(b) com prazo de eleição FIXO (usar em TODOS os processos do ensaio)
    .\iniciar-comindir05.cmd -PastaDados dados-9b-fixo -EleicaoMin 6000 -EleicaoMax 6000

    # Arrancar apenas um nó (reinício do antigo líder, retoma da pasta do ensaio)
    .\iniciar-comindir05.cmd -Acao No -Particao 1 -No 2 -PastaDados dados-9a

    # Arrancar apenas o processador ou o produtor
    .\iniciar-comindir05.cmd -Acao Processador -PastaDados dados-9a
    .\iniciar-comindir05.cmd -Acao Produtor -PastaDados dados-9a

    # Verificações finais de cada ensaio
    .\iniciar-comindir05.cmd -Acao RaftCheck -PastaDados dados-9a
    .\iniciar-comindir05.cmd -Acao CausalCheck -PastaDados dados-9a

`RaftCheck` recebe automaticamente `falhas.txt` quando esse ficheiro existe.
Para uma análise correta, usa o mesmo `-Periodo`/`-EleicaoMin`/`-EleicaoMax`
com que os processos foram iniciados.

### Falha abrupta, partição de rede e reparação

Terminar um nó específico e anotar a terminação em `falhas.txt`
(formato `p<k>;n<i>;<instante>`) num segundo PowerShell:

    .\iniciar-comindir05.cmd -Acao TerminarNo -Particao 1 -No 2 -PastaDados dados-9a

Simular uma **partição de rede** (cria `isolar-p<k>-n<i>` na pasta de dados) e
repará-la (apaga o ficheiro); os dois instantes ficam anotados em
`isolamentos.txt`:

    .\iniciar-comindir05.cmd -Acao IsolarNo  -Particao 1 -No 2 -PastaDados dados-9d
    .\iniciar-comindir05.cmd -Acao RepararNo -Particao 1 -No 2 -PastaDados dados-9d

Enquanto o ficheiro existir, o nó isolado não publica nem processa mensagens
dos tópicos de dados e de eleição, e as suas ligações TCP de recuperação
falham; o tópico de entrada **não** é afetado (o líder isolado continua a
aceitar leituras — que serão descartadas: não há *commit* por maioria).

## Ensaios 9(a–e)

No fim de **cada execução independente**, termina os processos Java e executa
`RaftCheck` e `CausalCheck` sobre a pasta respetiva. Guarda a saída dos dois
verificadores juntamente com as observações da ficha.

### 9(a) — Reproduzir a Figura 1 em p1

1. Arranque completo com `-PastaDados dados-9a`; aguarda as eleições.
2. No produtor, introduz `S05 21.0` e `S05 22.5` (S05 pertence a p1); aguarda
   um `LOTE` com os dois registos (linha `n=2` em `hb-p1-n<i>.txt` do líder).
3. Introduz `S05 21.4` e termina o líder de p1 em menos de 1 s, com
   `-Acao TerminarNo` noutro PowerShell.
4. Introduz `S05 21.1` durante a eleição (perde-se); aguarda o novo líder
   (6–12 s) e introduz `S05 21.2`.
5. Volta a arrancar o antigo líder (`-Acao No`) e confirma o evento
   `TRUNCADO` nos seus eventos (`de=3;descartados=1`) e a coincidência final.
6. Termina tudo e executa `RaftCheck` (com `falhas.txt`) e `CausalCheck`.

### 9(b) — Arranques com prazo aleatório e prazo fixo

- Arranca os nove nós **dez vezes**, por ordens diferentes (pasta nova em
  cada), e regista o tempo até ao primeiro líder (`RaftCheck`, secção 3) e o
  número de termos por partição.
- Repete **cinco vezes** com prazo fixo, arrancando os nós de cada partição no
  mesmo instante:

      .\iniciar-comindir05.cmd -PastaDados dados-9b-fixo-1 -EleicaoMin 6000 -EleicaoMax 6000

- Compara os termos até ao primeiro líder e os votos divididos (secção 1 do
  `RaftCheck`).

### 9(c) — Falha do líder durante uma rajada (3 execuções)

1. Arranque completo com `-PastaDados dados-9c-1`.
2. No produtor, `rajada 300 10`; **a meio**, termina o líder de p1 com
   `-Acao TerminarNo`; continua a introduzir leituras `S05 …` durante a
   eleição (perdem-se) e umas quantas depois do novo líder.
3. Trinta segundos depois da falha, volta a arrancar o antigo líder
   (`-Acao No`) e confirma a truncagem.
4. Termina tudo, corre os verificadores; repete com `dados-9c-2`, `dados-9c-3`.
   Regista a indisponibilidade (prevista em `]4 s, 12,2 s]`), as leituras
   perdidas e os registos truncados.

### 9(d) — Isolamento do líder e de um seguidor

Primeira parte (com rajada):

1. Arranque completo com `-PastaDados dados-9d-lider`; no produtor, inicia
   `rajada 300 10` e isola o líder de p1 (`-Acao IsolarNo`).
2. Continua a introduzir leituras `S05 …` durante o isolamento: chegam ao
   líder isolado (tópico de entrada não é afetado) **e** ao novo líder.
3. Regista os dois líderes simultâneos e os respetivos termos (eventos
   `LIDER`/`NOVO_LIDER`); aos 30 s, repara (`-Acao RepararNo`).
4. Confirma a `DEMISSAO` do antigo líder, a truncagem dos registos que
   atribuiu durante o isolamento e o que o processador observou
   (`monitor-processador.txt`: `SUSPEITA`, `RETOMA`, `NOVO_LIDER`, `AVISO`).

Segunda parte (sem rajada):

5. Arranque com `-PastaDados dados-9d-seguidor`; isola um **seguidor** de p1
   durante 60 s. Regista o termo que atinge (eventos `CANDIDATO` crescentes).
6. Repara e regista o efeito no regresso: a `DEMISSAO` do líder saudável e a
   eleição que se segue.

### 9(e) — Falha de dois nós de p1

1. Arranque com `-PastaDados dados-9e`; termina **dois** dos três nós de p1
   (`-Acao TerminarNo` em cada).
2. Introduz leituras `S05 …` durante 30 s: o nó sobrevivente nunca consegue
   maioria — regista os termos e as candidaturas (eventos `CANDIDATO`), e
   confirma que todas as leituras se perdem.
3. Volta a arrancar um dos terminados (`-Acao No`) e regista o tempo até haver
   líder. Termina tudo e corre os verificadores.

## Ensaio 10 — Falhas deliberadas (sem editar o código)

As cinco falhas da tabela do enunciado podem ser introduzidas com
propriedades de sistema (uma de cada vez; sem a flag, o código é o correto).
Usa-as no arranque **de todos os nós** que participam no ensaio:

| Falha introduzida | Flag |
| --- | --- |
| Maioria calculada como `votes.size() >= G/2` | `-Draft04.falhaMaioria=true` |
| Termo e `votedFor` só em memória | `-Draft04.falhaPersistencia=true` |
| Voto concedido sem a verificação `upToDate` | `-Draft04.falhaUpToDate=true` |
| Seguidor aplica lotes de termo inferior ao seu | `-Draft04.falhaTermoInferior=true` |
| Novo líder inicializa L e V a zero | `-Draft04.falhaLVzero=true` |

Podes passar as flags na consola de cada nó (java `-D… -cp …`) ou acrescentá-las
ao `-commonProperties` do `iniciar-comindir05.ps1` temporariamente.

- **f1** (`falhaMaioria`): com prazo fixo e os três nós de uma partição,
  todos se promovem a líder no mesmo termo → `RaftCheck` reporta termos com
  mais de um líder e eventos `ERRO;dois lideres no termo`; os registos
  divergem entre nós (CausalCheck deteta).
- **f2** (`falhaPersistencia`): os ficheiros `estado-*` deixam de ser gravados;
  num cenário de eleição com reinício no momento certo, o nó pode votar duas
  vezes no mesmo termo — dois líderes no mesmo termo.
- **f3** (`falhaUpToDate`): com o ensaio 9(a) repetido de modo a que o nó
  atrasado seja candidato, este consegue ser eleito e os registos que só o nó
  atualizado tinha são truncados (perdem-se).
- **f4** (`falhaTermoInferior`): o seguidor ressuscita lotes de líderes
  obsoletos (sem `IGNORADO` de termo inferior), com resoluções de divergência
  espúrias.
- **f5** (`falhaLVzero`): o novo líder atribui seq com `V[k] ≠ seq` → a
  `CausalCheck` acusa `VIOLAÇÃO V[k] == seq` (e possíveis violações de
  Lamport entre partições).

## Ficheiros produzidos (pasta de dados)

- `no-p<k>-n<i>.txt`: `seq;termo;sensor;temperatura;instante;L;V` — os registos do nó.
- `estado-p<k>-n<i>.txt`: `termo;votouEm` — o estado persistente (reescrito de forma atómica).
- `hb-p<k>-n<i>.txt`: `termo;hb;instante;ultimoSeq;ultimoTermo;n;bytes` — uma linha por LOTE publicado.
- `eventos-p<k>-n<i>.txt`: `instante;evento;p<k>;detalhe` — CANDIDATO, VOTO, LIDER, NOVO_LIDER, TERMO, DEMISSAO, RECEBIDO, RECUPERACAO, TRUNCADO, IGNORADO, ERRO (a 1.ª linha é o ARRANQUE, usada pelo RaftCheck para medir o tempo até ao primeiro líder).
- `monitor-<id>.txt`: eventos dos detetores do processador e do produtor.
- `produtor.txt`: `instante;p<k>;sensor;temperatura` — uma linha por IN enviado (base das leituras perdidas).
- `falhas.txt`: `p<k>;n<i>;<instante>` — falhas abruptas anotadas por `TerminarNo`.
- `isolamentos.txt`: `p<k>;n<i>;(isolado|reparado);<instante>` — anotações de `IsolarNo`/`RepararNo`.

## Bateria automática (opcional)

Para repetir TODA a validação sem intervenção (eleição inicial, ensaios
9(a)–9(e) e as cinco falhas do ensaio 10, cada um terminado com `RaftCheck` e
`CausalCheck`):

    powershell -ExecutionPolicy Bypass -File .\testar-comindir05.ps1                 # tudo (~15 min)
    powershell -ExecutionPolicy Bypass -File .\testar-comindir05.ps1 -Cenarios 9a,9d # só alguns

Os cenários e pastas de dados ficam em `testes\` (`RESUMO-TESTES.md`,
`<cenário>-RaftCheck.txt`, `<cenário>-CausalCheck.txt`, `dados-<cenário>\`).
No fim, o script imprime o resumo PASS/FAIL.
