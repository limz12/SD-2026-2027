# Arranque local — COMINDIR03

Faz duplo clique em iniciar-comindir03.cmd para compilar e iniciar os quatro projetos.
São usadas três partições e três réplicas: p0/p1, p1/p2 e p2/p0.

A ordem é: réplicas → líderes → processador → produtor. Confirma as subscrições nas
consolas antes de continuar em cada pausa. Abrir uma janela não garante que a
subscrição esteja pronta. Não repitas o arranque com os processos anteriores ativos.
Fecha a janela ou usa Ctrl+C para parar um processo; no produtor podes usar sair.

## Comandos PowerShell nesta pasta

    # Compilar todos os ficheiros Java dos quatro projetos para Java 11
    .\iniciar-comindir03.cmd -Verificar

    # Arranque completo, com pasta separada para o ensaio
    .\iniciar-comindir03.cmd -PastaDados dados-7a

    # Analisar depois de parar a produção e aguardar as escritas
    .\iniciar-comindir03.cmd -Acao CausalCheck -PastaDados dados-7a

    # Conferir sequências e cópias das réplicas
    .\iniciar-comindir03.cmd -Acao PartitionCheck -PastaDados dados-7a

    # Arrancar sem processador
    .\iniciar-comindir03.cmd -SemProcessador -PastaDados dados-7b

    # Reiniciar apenas p2, com o anterior parado, na mesma pasta de dados
    .\iniciar-comindir03.cmd -Acao Lider -Lider 2 -PastaDados dados-7a

    # Abrir só o processador (o anterior deve estar parado)
    .\iniciar-comindir03.cmd -Acao Processador -PastaDados dados-7a

    # Abrir só o produtor
    .\iniciar-comindir03.cmd -Acao Produtor -PastaDados dados-7a

    # Indicar as ferramentas
    .\iniciar-comindir03.cmd -JavaHome 'C:\caminho\jdk' -KuboPath 'C:\caminho\ipfs.exe'

A pasta predefinida é dados-n3. Caminhos relativos de -PastaDados são relativos a este
sprint; caminhos absolutos também são aceites. Para novo ensaio, escolhe uma pasta
nova e para antes os processos do ensaio anterior. Não são apagados ficheiros.
Usa sempre a mesma pasta para verificar a recuperação após reiniciar um líder.

No produtor, rajada 300 10 envia 300 leituras usando 10 sensores; o segundo número
não é o número de partições. O relatório CausalCheck aparece na consola e
vista-lamport.txt fica na pasta analisada. Consulta o campo Resultado geral:
o verificador Java não usa ainda um código de saída distinto para erros de dados.

## Ferramentas

É necessário um JDK 11 ou superior, procurado em -JavaHome, JAVA_HOME e PATH.
As classes ficam numa pasta nova por execução em %LOCALAPPDATA%\SD-COMINDIR03\build,
evitando classes antigas. Os binários não são colocados no repositório.
-Verificar só compila; as ações de análise também não iniciam Kubo.

O script reutiliza Kubo na API 5001 ou procura o executável no PATH, em -KuboPath
e nas ferramentas dos sprints 02/03. Mantém a alternativa de descarregar Kubo
0.32.1 do script anterior. O repositório próprio fica em .tools\ipfs-repo-comindir03.
Um Kubo já ativo deve ter PubSub habilitado; consulta erros nas consolas.
Os portos TCP dos líderes são 7000, 7001 e 7002.

Os scripts preparam a execução; os ensaios e medições da ficha são realizados pelo aluno.

## Procedimento detalhado dos ensaios

### Preparação e identificação das consolas

1. Abre um PowerShell na pasta E1-COMINDIR03, onde está iniciar-comindir03.cmd.
   Podes escrever powershell na barra de endereços do Explorador nessa pasta.
2. Confirma a compilação:

       .\iniciar-comindir03.cmd -Verificar

3. Executa os comandos de arranque neste PowerShell. Introduz leituras apenas na
   consola intitulada COMINDIR03 - Produtor. Não introduzas comandos PowerShell
   na linha de comandos do produtor.
4. No arranque completo, o script abre três réplicas. Confirma que não há erros de
   subscrição antes de premir Enter na consola do script.
5. Abre depois três líderes. Confirma os portos 7000, 7001 e 7002 e a ausência de
   erros de subscrição antes de continuar.
6. Quando o processador estiver incluído, confirma as três threads e a ausência
   de erros antes de continuar para o produtor.
7. Para um ensaio novo, usa uma pasta nova e confirma que os três líderes mostram
   seq inicial = 0, L = 0 e V = 0,0,0. Uma pasta existente retoma o histórico.
8. Para mudar de ensaio, termina os processos Java: produtor com sair; líderes,
   processador e réplicas com Ctrl+C ou fechando as respetivas janelas.
   Podes manter o Kubo. As janelas abertas com -NoExit podem continuar abertas
   depois de o Java terminar; isso não significa que o processo ainda esteja ativo.
9. Para o mesmo ensaio, mantém todos os processos na mesma pasta. Duas execuções
   com pastas diferentes continuam a partilhar tópicos e portos: não as sobreponhas.
10. Não apagues ficheiros enquanto os processos estiverem ativos. Prefere nomes
    novos, como dados-7a-repeticao2, para preservar a evidência anterior.

As mensagens de thread iniciada e janela aberta não constituem confirmação de
entrega. Se a primeira leitura não chegar, verifica os erros nas consolas e se
os processos já estavam subscritos antes da publicação.

### 7(a) — Reprodução manual da Figura 1

Com os processos do ensaio anterior parados e uma pasta ainda inexistente:

    .\iniciar-comindir03.cmd -PastaDados dados-7a

No produtor, envia cada comando uma única vez:

    S02 29.3

Aguarda a criação do alarme em p2 antes de prosseguir. Guarda as linhas EVENTO
de p1 e p2 e a linha ALARME do processador. Depois envia:

    S01 21.0

Guarda o EVENTO de p0. Depois envia:

    S04 22.5

Guarda o segundo EVENTO de p0. Não repitas um comando apenas para voltar a ver
a saída: cada envio produz um novo pedido de registo.

Esta sequência controlada reproduz os carimbos pretendidos. O exemplo contém
eventos independentes cuja ordem física pode variar sem mudar esses carimbos.
Enviar leituras adicionais antes do alarme pode alterar o conhecimento acumulado
pelo processador.

Sem enviar mais leituras, aguarda as escritas e executa no PowerShell:

    .\iniciar-comindir03.cmd -Acao CausalCheck -PastaDados dados-7a

Compara os carimbos com a figura da ficha e guarda o relatório global e
vista-lamport.txt. Justifica as relações encontradas pelos vetores.

### 7(b) — Rajada sem processador

Para todos os processos Java do ensaio anterior. Numa pasta nova:

    .\iniciar-comindir03.cmd -SemProcessador -PastaDados dados-7b

Confirma que nenhum processador antigo ficou ativo. No produtor:

    rajada 300 10

O número 300 indica a quantidade de leituras; 10 é o número de sensores usados.
O sistema continua com três partições.

Deixa terminar a rajada e as escritas. Sem novos envios:

    .\iniciar-comindir03.cmd -Acao CausalCheck -PastaDados dados-7b

Guarda os totais por partição e o relatório global. Explica o número de pares
causais entre partições a partir da arquitetura, sem usar apenas “deu OK”.

### 7(c) — Três rajadas com processador

Realiza três arranques completos independentes, parando os processos Java entre
execuções. Usa uma pasta nova por execução:

    .\iniciar-comindir03.cmd -PastaDados dados-7c-1

No produtor, executa uma única vez:

    rajada 300 10

Aguarda a conclusão dos alarmes e das escritas, sem enviar mais leituras.
Guarda a saída do processador, incluindo todas as linhas ALARME, e executa:

    .\iniciar-comindir03.cmd -Acao CausalCheck -PastaDados dados-7c-1

Repete todo o procedimento com dados-7c-2 e dados-7c-3, incluindo o novo arranque
do processador. Nas ações de análise, usa sempre a pasta da execução respetiva.

Em cada execução, recolhe:
- Registos por partição.
- Número de publicações confirmadas pelas linhas ALARME do processador.
- Totais BEFORE e AFTER do relatório e o seu total de pares causais.
- Pares concorrentes e concorrentes com Lamport distinto, incluindo a proporção.
- Erros ou mensagens de perda que tenham ocorrido.

Conserva os relatórios originais antes de prosseguir ao reinício. Não substituas
as medições de 7(c) por relatórios de uma pasta à qual acrescentaste eventos depois.

### Guardar os relatórios

Podes copiar o relatório da consola para um ficheiro. Em alternativa, redireciona
a saída da ação PowerShell, sem a pausa do ficheiro .cmd:

    powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\iniciar-comindir03.ps1 -Acao CausalCheck -PastaDados dados-7c-1 | Tee-Object -FilePath .\relatorio-7c-1.txt

O relatório inclui a compilação e a análise. Confirma visualmente Resultado geral
e os contadores: o CausalCheck atual não comunica erros de dados através de um
código de saída diferente. O script de arranque não calcula as médias nem mede
automaticamente os bytes das mensagens.

### 7(d) — Reinício de p2

Mantém as réplicas, o processador e o produtor ativos, sem novas leituras durante
o reinício. Usa a mesma pasta da execução cujo estado pretendes recuperar.

1. Guarda o último EVENTO de p2: seq, L e vetor completo.
2. Para apenas o líder p2 com Ctrl+C.
3. No PowerShell, executa, por exemplo:

       .\iniciar-comindir03.cmd -Acao Lider -Lider 2 -PastaDados dados-7c-3

4. Compara a linha de estado inicial com o último registo anterior.
5. Confirma que o líder está pronto e envia uma leitura para p2, por exemplo:

       S03 21.0

6. Guarda o novo EVENTO e explica a evolução de cada relógio.
7. Executa novamente CausalCheck, guardando o relatório como 7(d):

       .\iniciar-comindir03.cmd -Acao CausalCheck -PastaDados dados-7c-3

Neste caso, a pasta contém agora mais um registo. O relatório não substitui
a medição original da terceira execução de 7(c).

### 7(e) — Observação e limitação da evidência disponível

A ficha pede procurar na réplica 1 um alarme escrito antes da leitura que o
originou, comparando instantes de escrita. A réplica 1 guarda p0 e p1 em ficheiros
separados, replica-1-p0.txt e replica-1-p1.txt.

Limitação identificada durante o acompanhamento:
- O campo instante é criado no líder e copiado para a réplica.
- Esse campo não regista o momento em que a réplica executa append.
- A posição de uma linha num ficheiro não estabelece a sua ordem de escrita
  relativamente a uma linha de outro ficheiro.
- Os carimbos lógicos descrevem conhecimento causal, sem medir instantes físicos
  de escrita na réplica.

Consequentemente, os ficheiros atuais não fornecem evidência suficiente para
confirmar ou excluir a inversão pedida através dos instantes reais de escrita.
Este ponto fica registado como limitação da observação, não como demonstração
de que a inversão ocorreu nem de que foi impedida. Não se alterou o protocolo,
o formato dos ficheiros nem o comportamento de aplicação das réplicas.

Por decisão do aluno, avançou-se sem instrumentação adicional. A verificação
experimental específica do 7(e) permanece por demonstrar com os dados disponíveis;
esta nota não substitui o requisito da ficha nem a reflexão do aluno sobre ele.

### 8 — Organização dos ensaios com falhas deliberadas

Esta etapa ainda será acompanhada separadamente. Não aplica falhas automaticamente.

1. Guarda uma versão correta do código e os relatórios anteriores.
2. Introduz apenas uma das falhas indicadas na ficha.
3. Compila com -Verificar; reinicia os processos necessários para carregar o código
   alterado. Compilar não substitui o código de processos Java já em execução.
4. Usa uma pasta nova, identificada pela falha, para não contaminar os ensaios corretos.
5. Recolhe mensagens dos processos e executa CausalCheck sobre essa pasta.
6. Preenche as observações e conclusões da ficha a partir da evidência recolhida.
7. Repõe o código correto, recompila e termina os processos alterados antes da falha seguinte.

O arranque completo impõe N = 3. O caso deliberado de produtor com N = 4 exige
arrancar apenas esse produtor de forma distinta, conservando os outros processos
com N = 3; será preparado na etapa correspondente.

## Lembrete sobre o lock do líder

Em PartitionLeader.java, LOCK é um objeto declarado static final. O bloco
synchronized (LOCK) adquire o monitor desse objeto antes de executar e liberta-o
automaticamente à saída, incluindo return ou uma exceção.

Uma thread que tente entrar num bloco protegido pelo mesmo objeto aguarda enquanto
outra thread detiver esse monitor. Todas as operações que precisam desta proteção
têm de respeitar o mesmo monitor. O lock não protege automaticamente acessos feitos
fora desses blocos.

No líder, o bloco de criação mantém juntos os relógios, a atribuição de seq,
a verificação e o append. A consulta usada na recuperação TCP também usa LOCK,
evitando consultar esse estado a meio de uma atualização protegida. A passagem
pelo mesmo monitor também garante a visibilidade das alterações entre threads.

static partilha a referência entre as threads dessa classe no mesmo processo;
final impede que a referência seja substituída. Cada processo líder tem o seu
próprio LOCK: este mecanismo não sincroniza líderes diferentes nem as réplicas.
Também não desfaz alterações em caso de falha, nem torna a escrita transacional.

## Laboratório visual HTML

Abre [laboratorio-comindir03.html](laboratorio-comindir03.html) num navegador.
O endereço anterior, apresentacao-comindir03.html, também mostra agora este laboratório.
O ficheiro funciona sem internet e não precisa de servidor.

- Escolhe um dos cinco ensaios ou uma das cinco situações de falha nos botões superiores.
- Usa Reproduzir para animar o cenário; o mesmo botão permite pausar.
- Usa as setas dos controlos, ou as teclas esquerda/direita, para avançar um passo de cada vez.
- Usa o botão de reposição, ou R, para voltar ao início do cenário.
- Espaço alterna entre reprodução e pausa quando o foco não estiver num botão.
- Ecrã inteiro facilita a apresentação; F11 é a alternativa do navegador.
- Movimento permite desligar as mensagens animadas. A preferência de movimento reduzido
  do sistema é respeitada no arranque.
- Em ecrãs pequenos, o diagrama tem deslocação horizontal para preservar a legibilidade.
- Expande “Consultar medições e distinguir simulação de evidência” para consultar a tabela.

Cada líder mostra seq, Lamport e vetor; a componente que avança fica destacada.
As mensagens animadas ilustram o transporte entre produtor, líderes e processador.
Os diagramas são reproduções didáticas, não capturas de tráfego IPFS.
Nos ensaios medidos usam-se os valores recolhidos; os estados não fornecidos não são
inventados. Os cenários de falha mostram candidatos e perguntas de verificação,
sem preencher resultados experimentais do CausalCheck.

A versão anterior foi conservada em
[apresentacao-comindir03-diapositivos.html](apresentacao-comindir03-diapositivos.html).
Essa versão mantém as notas locais exportáveis e o formato de impressão em diapositivos.