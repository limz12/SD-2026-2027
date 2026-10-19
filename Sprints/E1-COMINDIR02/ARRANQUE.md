# Arranque local da COMINDIR02

## Requisitos

Windows, PowerShell 5.1 ou superior e um JDK Java 11 ou superior com `java` e
`javac` no PATH. Para indicar outro JDK, usa o parametro `-JavaHome`.
No primeiro arranque, o script descarrega o Kubo 0.32.1 da distribuicao oficial
no GitHub, se ainda nao existir na pasta `.tools`.

Foi escolhida esta versao depois de um teste local de publicacoes concorrentes:
o Kubo 0.43.1 apresentou `validation ignored`; o 0.32.1 passou o teste.

## Atalhos

Executa os atalhos a partir de qualquer pasta ou por duplo clique.

| Atalho | Cenario | Dados |
| --- | --- | --- |
| `iniciar-comindir02.cmd` | Parte A: N=1, um no IPFS, tres replicas | `dados/` |
| `iniciar-comindir02-dois-nos.cmd` | Ponto 6(f): N=1, dois nos, replica 3 com perda=0.2 | `dados/` |
| `iniciar-comindir02-parteB.cmd` | Parte B: N=3, R=2, um no IPFS | `dados-parteB/` |

Na Parte B, a replica 1 guarda p0,p1; a replica 2 guarda p1,p2; a replica 3 guarda
p2,p0. Os lideres usam os portos TCP 7000, 7001 e 7002.

Fecha os processos do cenario anterior antes de iniciar outro. Os scripts
compilam os tres projetos, preparam o Kubo e abrem as replicas. Confirma as
consolas e prime Enter para abrir os lideres; confirma novamente para abrir
o produtor. A mensagem de lancamento de uma thread nao confirma, por si so,
que a subscricao tenha sido aceite. Verifica que nao existem erros nas consolas.

O cenario de dois nos usa APIs 5001/5002, gateways 8080/8081 e comunicacao entre
nos nos portos 4001/4002. Apenas o no local esperado pode estar ligado.
Os cenarios de um no desativam a comunicacao com outros nos IPFS.

## Parametros e verificacao

No PowerShell, a partir desta pasta:

```powershell
# Compilar e mostrar o plano sem iniciar programas.
.\iniciar-comindir02.ps1 -ParteB -Verificar

# Escolher o JDK.
.\iniciar-comindir02.ps1 -ParteB -JavaHome 'C:\caminho\do\jdk'

# Ativar perdas apenas na replica 3.
.\iniciar-comindir02.ps1 -ParteB -PerdaReplica3 0.2
```

O script principal tambem aceita `-DoisNos`, `-KuboPath` e `-KuboRepo`.
`-ParteB` e `-DoisNos` nao podem ser combinados. O modo `-Verificar` verifica a
compilacao e os caminhos; nao e um teste completo de entrega de mensagens.

## Reiniciar apenas o lider da p1

Na consola desse lider, depois de Ctrl+C, o diretorio continua a ser
`dados-parteB`. Executa:

```powershell
java -cp "$env:LOCALAPPDATA\SD-COMINDIR02\build\rep02-lider" rep02.PartitionLeader 1 3 127.0.0.1 http://127.0.0.1:5001/api/v0
```

Se usares uma consola nova, entra primeiro na pasta `dados-parteB` deste sprint.
O diretorio de trabalho determina onde e lido e escrito `lider-p1.txt`.

## Estrutura dos scripts e ficheiros gerados

- `iniciar-comindir02.ps1`: parametros e sequencia de arranque dos cenarios.
- `scripts/Arranque.Java.ps1`: localizacao do JDK e abertura de consolas.
- `scripts/Arranque.Kubo.ps1`: API, configuracao e verificacao dos nos IPFS.
- `.tools/`: executavel Kubo, configuracoes IPFS e diagnosticos locais; ignorada pelo Git.
- `%LOCALAPPDATA%/SD-COMINDIR02/build/`: classes compiladas fora do repositorio.
- `dados/` e `dados-parteB/`: registos do trabalho, preservados entre arranques.

Os scripts nao apagam nem reinicializam os registos Java e nao fazem commits.
Para terminar, usa `sair` no produtor e nas replicas; Ctrl+C nos lideres e nos
nos Kubo. O codigo Java avaliado nao e modificado pelos scripts.
