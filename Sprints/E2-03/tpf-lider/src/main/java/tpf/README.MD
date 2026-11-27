# TPF Líder (`tpf-lider`)

## Implementação E2-02: formato e validação

### Recuperação local após interrupção

No arranque, antes de ligar ao Discord, o líder valida `registos.log`, autentica os pares `.bin`/`.hdr` e compara os registos abertos com a sequência do log. Os chunks existentes devem formar um prefixo sem lacunas nem sobreposição. Eventos `REGISTO` e `SELADO` em falta são acrescentados depois da verificação; eventos contraditórios interrompem o arranque. Os registos finais ainda não selados são reconstruídos a partir do log e selados imediatamente sem voltar a escrevê-los no log.

A publicação escreve e força ficheiros `.bin.tmp` e `.hdr.tmp`, cria um marcador `.pending` e publica cada parte através de `ATOMIC_MOVE`. Após gravar `SELADO`, remove o marcador. Se o processo parar durante a publicação e o marcador existir, o arranque autentica as partes disponíveis e termina a publicação. Um par final incompleto sem marcador, um chunk inválido ou eventos incoerentes interrompem o arranque e preservam os ficheiros para análise. Temporários sem marcador, por exemplo de uma falha antes da publicação, não são tratados como chunks e são preservados. Os movimentos atómicos têm de ser suportados pelo sistema de ficheiros; `force(true)` não garante por si só a persistência dos metadados do diretório em todas as plataformas.

Se `master.key` não existir mas houver dados anteriores, o líder recusa gerar outra chave. Não substituir nem apagar uma chave para reparar o arranque.

O chunk serializado usa ordem de bytes big-endian: inteiro de 4 bytes com o tamanho útil total (incluindo cabeçalho), inteiro de 4 bytes com o número de registos, e para cada registo um inteiro de 4 bytes com o comprimento seguido da linha `Registo.toLine()` em UTF-8. A representação é preenchida com bytes zero até ao múltiplo de `PAD_BYTES`; o tamanho útil permite ignorar o padding. `CHUNK_MAX_BYTES` inclui os campos estruturais e exclui o padding. O ficheiro cifrado inclui ainda a tag GCM de 16 bytes, pelo que o tamanho do `.bin` não é necessariamente múltiplo de `PAD_BYTES`.

O cabeçalho `.hdr` contém uma linha `keyVersion;salt;nonce`: versão decimal, salt em Base64 e nonce em Base64. A versão é codificada em 4 bytes big-endian na AAD. A AAD concatena UTF-8 de `tpf-v1`, versão, salt e nonce. A derivação HKDF usa `tpf-chunk-v1` em UTF-8. O nome do `.bin` é o SHA-256 hexadecimal do seu conteúdo, incluindo a tag.

O ficheiro `eventos-lider.txt` regista `instante;REGISTO;lsn=<n>` e `instante;SELADO;chunkId=<id>;lsn=<primeiro>..<último>;tamanho=<bytes cifrados>`. As entradas de texto usam IDs numéricos Discord para autor e canal. Mensagens sem texto, mensagens de bots e webhooks são ignoradas.

Em PowerShell, a partir da pasta `tpf-lider`, `mvn test` executa os testes automáticos e `./iniciar-bot.ps1` inicia o bot com a pasta `dados-lider`. As variáveis do Discord são procuradas em `Sprints/E2-02/tpf-lider/src/main/java/tpf/.env`, relativamente à raiz do repositório.

Bot líder desenvolvido em **Java 17+**, utilizando a biblioteca **JDA (Java Discord API)**, responsável pela receção e encaminhamento dos comandos e mensagens do canal `#arquivo`.

O projeto prevê também a gestão de registos organizados em chunks, incluindo mecanismos de selagem, padding e cifra AES-GCM.

## 1. Pré-requisitos

- Java 17 ou superior.
- Maven instalado.
- Bot criado no [Discord Developer Portal](https://discord.com/developers/applications), com a opção **Message Content Intent** ativada.
- Servidor de teste Discord com o canal de texto `#arquivo` e o cargo `admin-arquivo`.
- Uma pasta de dados persistente para armazenar a chave mestra do líder.

## 2. Configuração do Discord

Na pasta `src/main/java/tpf`, criar um ficheiro denominado `.env`, de acordo com a localização atualmente utilizada pelo projeto.

Definir as seguintes variáveis:

```env
BOT_TOKEN=teu_token_do_discord_aqui
GUILD_ID=id_do_teu_servidor_aqui
```

Não colocar espaços em torno do sinal `=`.

O token do Discord é uma credencial privada e não deve ser incluído no repositório Git.

O ficheiro `.env` deve estar excluído do controlo de versões.

## 3. Pasta de dados do líder

O líder utiliza uma pasta persistente para guardar a sua chave mestra.

A localização é definida através da propriedade JVM obrigatória:

`tpf.dataDir`

**Esta propriedade deve conter um caminho absoluto.**

Por exemplo, no Windows:

```text
C:\Projetos\TPF\tpf-lider\dados-lider
```

A chave mestra fica armazenada em:

```text
C:\Projetos\TPF\tpf-lider\dados-lider\master.key
```

### 3.1. Porquê utilizar um caminho absoluto?

Um caminho relativo, como `dados-lider/master.key`, depende da pasta de execução do processo Java.

Se o programa for iniciado a partir de outra pasta, poderá criar uma chave diferente, deixando de conseguir derivar as chaves necessárias para decifrar chunks antigos.

Para evitar esse problema, o `LeaderBot` exige que `tpf.dataDir` seja definida explicitamente e contenha um caminho absoluto.

**A mesma pasta de dados deve ser utilizada em todos os arranques do líder.**

Não alterar a localização da chave entre execuções, exceto numa migração controlada que preserve o ficheiro original.

### 3.2. Falta de configuração

Se `tpf.dataDir` não estiver definida, estiver vazia ou contiver um caminho relativo, o arranque é rejeitado.

O programa não estabelece ligação ao Discord e termina com um código de saída diferente de zero.

## 4. Gestão da chave mestra — `MasterKey`

A classe `MasterKey` é responsável por carregar ou criar uma chave mestra persistente de **32 bytes (256 bits)**.

O ficheiro utilizado é:

`<tpf.dataDir>/master.key`

### 4.1. Primeiro arranque

Se o ficheiro `master.key` não existir:

1. O líder gera 32 bytes aleatórios utilizando `SecureRandom`.
2. Cria o ficheiro sem substituir outro ficheiro existente.
3. Aplica e verifica as permissões de acesso.
4. Guarda os 32 bytes e força a escrita no disco.
5. Disponibiliza a chave ao restante sistema.

Se alguma destas operações falhar, o arranque é interrompido.

### 4.2. Arranques seguintes

Se `master.key` já existir:

1. Verifica que corresponde a um ficheiro regular.
2. Verifica as permissões de acesso.
3. Lê a chave existente.
4. Confirma que contém exatamente 32 bytes.
5. Reutiliza a mesma chave.

Uma chave existente não é substituída automaticamente.

Se o ficheiro estiver corrompido, tiver um tamanho incorreto ou não puder ser lido, o líder rejeita o arranque.

**Não apagar nem regenerar `master.key` para resolver um erro de inicialização.** A chave existente poderá ser necessária para decifrar chunks anteriormente armazenados.

### 4.3. Proteção da chave

A chave mestra não deve:

- Ser apresentada na consola.
- Ser incluída em mensagens de erro.
- Ser enviada para o Discord.
- Ser incluída no repositório Git.
- Ser substituída automaticamente após uma falha.

A classe `MasterKey` procura garantir acesso restrito ao dono do ficheiro.

Em sistemas POSIX, utiliza permissões equivalentes a `0600`.

No Windows, utiliza as interfaces ACL disponibilizadas pelo Java e rejeita configurações que não satisfaçam a política de acesso definida.

A validação realizada em Java não constitui uma auditoria completa das permissões efetivas do Windows. A configuração deve ser validada no ambiente onde o líder é executado.

No PowerShell, é possível inspecionar as permissões com:

```powershell
icacls "C:\Projetos\TPF\tpf-lider\dados-lider\master.key"
```

O diretório de dados também deve estar protegido contra alterações por utilizadores não autorizados.

### 4.4. Proteção no Git

O ficheiro `.gitignore` da raiz do repositório deve conter:

```gitignore
**/dados-lider/master.key
```

Esta regra exclui a chave sem impedir que as classes Java sejam acompanhadas pelo Git.

Para verificar a regra, executar na raiz do repositório:

```powershell
git check-ignore -v tpf-lider/dados-lider/master.key
```

A regra deve ser configurada antes de gerar a chave.

Se a chave já estiver a ser acompanhada pelo Git, adicioná-la ao `.gitignore` não a remove automaticamente do índice nem do histórico.

## 5. Configuração dos chunks — `Wire`

A classe `tpf.Wire` centraliza os parâmetros de configuração utilizados pelo líder na gestão dos chunks.

| Constante | Propriedade JVM | Valor predefinido | Descrição |
|---|---|---|---|
| `CHUNK_MAX_BYTES` | `tpf.chunkMaxBytes` | 65536 bytes | Limiar de selagem por tamanho, antes do padding |
| `PAD_BYTES` | `tpf.padBytes` | 4096 bytes | Múltiplo utilizado no padding |
| `SELAGEM_MS` | `tpf.selagemMs` | 5000 ms | Tempo máximo desde o primeiro registo do chunk aberto |

Quando uma propriedade opcional não é fornecida, utiliza-se o respetivo valor predefinido.

Se uma propriedade reconhecida tiver um valor inválido, o arranque é rejeitado.

O `LeaderBot` também rejeita propriedades JVM desconhecidas que comecem por `tpf.`, evitando que erros no nome passem despercebidos.

A classe `Wire` não implementa registo, serialização, selagem, padding, cifra ou comunicação com o Discord.

## 6. Execução do líder

Antes de iniciar o líder, garantir que:

- O ficheiro `.env` está corretamente configurado.
- A propriedade `tpf.dataDir` aponta para a pasta persistente pretendida.
- A chave mestra, caso exista, é válida.
- As permissões do ficheiro e da pasta de dados são adequadas.
- As dependências JDA estão disponíveis no ambiente de execução.

### 6.1. Compilação com Maven

Na pasta `tpf-lider`, executar:

```powershell
mvn clean compile
```

Este comando compila o projeto, mas não cria necessariamente um JAR executável com todas as dependências.

### 6.2. Arranque com Java

O comando seguinte pressupõe que as classes estão em `target/classes` e que as dependências necessárias foram disponibilizadas em `target/dependency`.

```powershell
java "-Dtpf.dataDir=C:\Projetos\TPF\tpf-lider\dados-lider" -cp "target/classes;target/dependency/*" tpf.LeaderBot
```

A pasta `target/dependency` não é necessariamente criada por `mvn clean compile`. A forma de disponibilizar as dependências depende da configuração Maven do projeto.

Se o projeto for executado através do VS Code ou de outra ferramenta, a propriedade `tpf.dataDir` deve ser adicionada aos argumentos JVM da configuração de execução.

### 6.3. Arranque com configuração personalizada

Exemplo com parâmetros dos chunks diferentes dos predefinidos:

```powershell
java "-Dtpf.dataDir=C:\Projetos\TPF\tpf-lider\dados-lider" -Dtpf.chunkMaxBytes=32768 -Dtpf.padBytes=2048 -Dtpf.selagemMs=3000 -cp "target/classes;target/dependency/*" tpf.LeaderBot
```

Neste exemplo:

- A pasta de dados é definida explicitamente.
- O limiar de selagem é de 32768 bytes.
- O padding utiliza múltiplos de 2048 bytes.
- A selagem temporal ocorre 3000 ms depois do primeiro registo do chunk aberto.

### 6.4. Execução através de JAR

A execução com `java -jar` só é válida se o projeto Maven estiver configurado para produzir um JAR executável, incluindo a definição da classe principal e o acesso às dependências necessárias.

Se esse empacotamento for configurado, a execução poderá seguir uma estrutura semelhante a:

```powershell
java "-Dtpf.dataDir=C:\Projetos\TPF\tpf-lider\dados-lider" -jar target/tpf-lider.jar
```

O nome e a localização do JAR dependem da configuração real do `pom.xml`.

## 7. Ordem de arranque do `LeaderBot`

O arranque respeita a seguinte sequência:

1. Validar os nomes das propriedades JVM `tpf.*`.
2. Inicializar e validar os parâmetros de `Wire`.
3. Obter e validar o caminho absoluto de `tpf.dataDir`.
4. Carregar ou criar a chave mestra através de `MasterKey`.
5. Verificar que a chave tem 32 bytes e que as permissões cumprem a política definida.
6. Obter `BOT_TOKEN` e `GUILD_ID`.
7. Inicializar a ligação ao Discord através de JDA.
8. Confirmar a disponibilidade do servidor Discord.
9. Registar os comandos disponibilizados pelo bot.

Se a configuração ou a inicialização da chave mestra falhar, a ligação ao Discord não é iniciada.

O processo termina com código de saída diferente de zero, permitindo que ferramentas externas identifiquem a falha.

## 8. Critério de tamanho — `CHUNK_MAX_BYTES`

`CHUNK_MAX_BYTES` representa o limiar aplicado ao tamanho, em bytes, da representação binária serializada do conteúdo do chunk, antes do padding e da cifra AES-GCM.

A contagem inclui:

- Os bytes que representam os registos.
- Os campos estruturais internos da serialização.
- Os identificadores, comprimentos, contadores ou delimitadores integrados na representação binária.
- Qualquer outro byte produzido pelo serializador como parte do conteúdo do chunk.

A contagem exclui:

- Os bytes acrescentados pelo padding.
- O nonce e a tag de autenticação AES-GCM.
- Outros metadados externos à representação serializada.
- A sobrecarga adicional de armazenamento ou transporte.

A selagem é desencadeada quando a inclusão de um registo faz o tamanho serializado atingir ou ultrapassar `CHUNK_MAX_BYTES`.

Os registos são indivisíveis. O último registo pode, por isso, fazer o chunk ultrapassar o limiar configurado.

`CHUNK_MAX_BYTES` é um **limiar de selagem**, não uma garantia de tamanho máximo absoluto do ficheiro cifrado.

## 9. Critério temporal — `SELAGEM_MS`

`SELAGEM_MS` define o tempo máximo durante o qual um chunk pode permanecer aberto depois de receber o seu primeiro registo.

A contagem começa no momento em que o primeiro registo é acrescentado ao chunk anteriormente vazio.

O temporizador:

- Não começa no arranque da aplicação.
- Não começa na criação de um chunk vazio.
- Não é reiniciado quando chegam novos registos ao mesmo chunk.
- É reiniciado apenas quando um novo chunk recebe o seu primeiro registo.

Quando o tempo decorrido atinge `SELAGEM_MS`, o chunk deve ser selado, mesmo que não tenha atingido `CHUNK_MAX_BYTES`.

A selagem por tempo e por tamanho são condições alternativas: basta uma delas ser satisfeita.

Um chunk vazio não é selado apenas por ter decorrido o intervalo temporal.

## 10. Padding — `PAD_BYTES`

Depois de o conteúdo do chunk ser serializado, aplica-se padding até que o tamanho seja um múltiplo de `PAD_BYTES`.

`PAD_BYTES` representa o múltiplo pretendido, não uma quantidade fixa de bytes acrescentados.

Por exemplo, com `PAD_BYTES = 4096`:

| Tamanho serializado | Tamanho após padding | Bytes acrescentados |
|---|---|---|
| 3000 bytes | 4096 bytes | 1096 bytes |
| 4096 bytes | 4096 bytes | 0 bytes |
| 5000 bytes | 8192 bytes | 3192 bytes |
| 8192 bytes | 8192 bytes | 0 bytes |

O formato de armazenamento deverá permitir recuperar o comprimento original dos dados serializados, para que o padding possa ser removido corretamente durante a leitura.

## 11. Sequência de processamento dos chunks

O processamento previsto segue a seguinte ordem:

1. O líder recebe o primeiro registo e inicia o temporizador.
2. Os registos seguintes são acrescentados ao chunk aberto.
3. O sistema avalia o tamanho serializado e o tempo decorrido.
4. Quando uma condição de selagem é satisfeita, o chunk deixa de aceitar novos registos.
5. O conteúdo é serializado para uma representação binária.
6. É aplicado o padding definido por `PAD_BYTES`.
7. O resultado é cifrado através de AES-GCM.
8. São produzidos os dados cifrados e os metadados necessários à posterior recuperação.

Cada novo chunk possui o seu próprio instante inicial de selagem.

## 12. Formato binário dos chunks

O formato está descrito na secção «Implementação E2-02: formato e validação» no início deste documento.

Independentemente do formato escolhido, `CHUNK_MAX_BYTES` contabiliza os bytes da representação binária produzida pelo serializador, incluindo os campos estruturais internos, antes do padding.

A classe `Chunk` e este README devem manter a mesma interpretação.

## 13. Decisões de implementação e segurança

Foram adotadas as seguintes decisões:

- Utilizar uma pasta de dados persistente e configurada por caminho absoluto.
- Impedir o arranque se `tpf.dataDir` estiver ausente ou for relativa.
- Gerar a chave mestra com 32 bytes aleatórios utilizando `SecureRandom`.
- Reutilizar a mesma chave mestra entre arranques.
- Nunca substituir automaticamente uma chave existente.
- Rejeitar chaves inválidas e configurações de permissões que não satisfaçam a política.
- Excluir `master.key` do Git.
- Rejeitar propriedades JVM inválidas ou desconhecidas com prefixo `tpf.`.
- Aplicar `CHUNK_MAX_BYTES` ao conteúdo binário serializado antes do padding.
- Iniciar `SELAGEM_MS` no primeiro registo do chunk aberto.
- Aplicar padding antes da cifra AES-GCM.

A chave mestra deverá ser posteriormente utilizada pela componente de cifra para derivar as chaves necessárias à proteção dos chunks. A classe `MasterKey` não realiza essa derivação.

### Limitações conhecidas

A validação de ACLs através de Java não substitui uma auditoria completa das permissões efetivas no Windows.

A verificação dos componentes do caminho também não elimina todas as condições de corrida quando outros processos podem alterar a estrutura de diretórios.

O funcionamento pressupõe que a pasta de dados está protegida e que não existem processos independentes a inicializar simultaneamente a mesma chave.

Estas limitações devem ser consideradas nos testes do ambiente de execução.

## 14. Comunicação e escrita replicada — E2-03

O líder disponibiliza um hub WebSocket (`PubSubHub`) na porta `8765` por predefinição. A porta e os temporizadores de colocação/heartbeat podem ser alterados com propriedades JVM: `tpf.porto`, `tpf.periodoHbMs`, `tpf.suspeitaMs`, `tpf.mortaMs`, `tpf.tokenValidadeMs`, `tpf.r`, `tpf.wMin`, `tpf.ackTimeoutMs`, `tpf.maxRondas`, `tpf.margemBytes`, `tpf.alfa`, `tpf.backoffMinMs` e `tpf.backoffMaxMs`. O líder rejeita propriedades `tpf.*` desconhecidas, valores inválidos, `W_MIN > R`, tempos de morte incoerentes e backoff máximo inferior ao mínimo.

### Registo e formatos de mensagens

O administrador usa `/replica-token` no servidor e canal configurados. O token aleatório é de uso único, dura 10 minutos e só é apresentado numa resposta efémera. Deve ser entregue manualmente à pessoa que inicia a réplica; não deve ser copiado para logs ou canais públicos.

As mensagens são texto UTF-8, com campos separados por `;`; os bytes binários são Base64:

```text
Réplica nova -> hub:       OLA;<id>;<chavePublicaX509B64>;<token>
Réplica registada -> hub:  OLA;<id>
Hub -> réplica:            DESAFIO;<nonceB64>
Hub -> réplica:            AUTENTICADO;<id>
Réplica -> hub:            PROVA;<id>;<assinaturaB64>
Réplica -> hub:            SUB;tpf-r-<id>
Réplica -> hub:            SUB;tpf-ctl
Réplica -> hub:            PUB;tpf-lider;HB;<id>;<usados>;<quota>
Réplica -> hub:            PUB;tpf-lider;INVENTARIO;<id>;<chunkId,...>
Líder -> réplica:          PUB;tpf-r-<id>;STORE;<chunkId>;<hdrB64>;<binB64>
Réplica -> hub:            PUB;tpf-lider;ACK;<id>;<chunkId>;<sha256>
```

O hub desafia cada ligação com 32 bytes aleatórios novos. A assinatura Ed25519 cobre UTF-8 de `tpf-registo|<id>|<nonceB64>`. Apenas após validar a prova o hub consome um token novo e publica `replicas.txt` atomicamente. Uma réplica registada volta a ligar sem token, mas a sua chave tem de coincidir com a chave X.509 já registada. Ligações fora de ordem e tópicos não autorizados são desligados e registados como `ERRO`.

Depois de autenticar, o hub envia `AUTENTICADO;<id>` para a réplica persistir localmente o estado do primeiro registo e só então subscrever os tópicos.

### Arranque das réplicas

Compilar ambos os projetos:

```powershell
mvn -f Sprints/E2-03/tpf-lider/pom.xml test
mvn -f Sprints/E2-03/tpf-replica/pom.xml test
```

Arrancar o líder com `./iniciar-bot.ps1`, configurar o `tpf.dataDir` persistente e garantir que a porta TCP configurada está acessível às máquinas das réplicas. Para cada instância, pedir um token diferente em `/replica-token` e executar, a partir de `Sprints/E2-03/tpf-replica`:

```powershell
mvn package
java -cp target/classes tpf.ReplicaService replica-1 ws://<host-do-lider>:8765 8388608 <token>
```

Executar o comando noutra janela para cada réplica, usando um ID, quota e token novos. A réplica guarda as chaves Ed25519 em `dados-<id>/replica.pk8` e `replica.pub`, os chunks apenas como `.bin` cifrados e `.hdr`, e os eventos em `eventos-<id>.txt`. Os ficheiros de chave privada são protegidos com permissões exclusivas do dono. O cliente calcula o espaço usado a partir dos `.bin`, envia inventário ao ligar e usa backoff exponencial até 30 segundos quando perde a ligação.

`catalogo.txt` guarda `chunkId;lsnDe;lsnAte;tamanho;replicas;estado` e é substituído atomicamente. Uma cópia só entra no catálogo após um `ACK` válido da ligação autenticada que recebeu `STORE`; o hash tem de ser igual ao `chunkId`. `DURAVEL` requer `max(W_MIN, floor(R/2)+1)` confirmações. Menos de R cópias é apresentado como subreplicação, mesmo que o chunk já seja durável. As reservas são feitas na mesma secção sincronizada que escolhe as réplicas, e libertadas após timeout sem ACK. As rondas continuam até R cópias ou `MAX_RONDAS`, inclusive após atingir durabilidade.

Réplicas recebem somente bytes cifrados e o cabeçalho criptográfico. Não recebem a `master.key`, o catálogo do líder nem registos em claro. `INVENTARIO` é registado pelo hub, mas reparação, reconciliação, `FETCH`, `DELETE` e recuperação a partir de réplicas permanecem fora deste sprint.

### Concorrência

As callbacks JDA submetem escritas a uma thread sequencial de `RegistoService`. O temporizador do chunk sela na thread de `ArquivoLocal`. O hub recebe callbacks WebSocket próprias e sincroniza estados da ligação, tokens e subscrições sob o seu lock. Mensagens autenticadas são encaminhadas para o `ReplicationManager`, que serializa operações de ACK/rondas; os seus timeouts correm num scheduler dedicado. O `HeartbeatMonitor` percorre o `ReplicaRegistry` periodicamente. O registo protege atomicamente quota, uso, reservas e heartbeat; `Catalog` sincroniza cada mutação e gravação atómica. O cliente de réplica usa a callback WebSocket para mensagens e sincroniza armazenamento e cálculo de espaço para impedir STOREs concorrentes de exceder a quota.

### Questões de reflexão

1. O token autoriza o primeiro registo e liga a identidade da réplica à chave pública que o administrador aceitou. Ed25519 prova a posse da chave, mas não prova que essa chave foi autorizada. Um token intercetado durante a validade permite a quem o obtiver tentar registar primeiro uma chave própria; a prova é necessária, mas não impede essa corrida, por isso o token deve ser entregue por canal privado e usado imediatamente.
2. Assinar o prefixo e o ID vincula a assinatura ao protocolo e à identidade declarada. Isso impede reutilizar a assinatura noutro protocolo ou trocar o ID numa mensagem de desafio.
3. Escolher e reservar numa secção sincronizada impede dois chunks simultâneos de observar o mesmo espaço livre e ambos o reservarem. Sem atomicidade, uma réplica com 100 bytes livres pode ser escolhida por dois chunks de 80 bytes, reservando 160 bytes.
4. `W_MIN` estabelece um limite absoluto e a maioria de R cresce com o fator de replicação. Com R=5, são necessárias pelo menos 3 confirmações quando W_MIN=2. W=1 deixa uma única cópia confirmar durabilidade, aumentando o risco de perda; exigir R confirmações impede durabilidade enquanto qualquer réplica estiver indisponível.
5. Não há escritores concorrentes a atualizar o mesmo objeto. O líder único cria chunks imutáveis e o hash identifica o seu conteúdo, pelo que o problema de quóruns de escrita concorrentes não se coloca neste sprint.
6. α controla a influência do espaço livre: α=0 elimina essa ponderação e deixa o rendezvous hash determinar a escolha; α=1 pondera linearmente; α=2 favorece mais fortemente réplicas com espaço livre.
7. O ACK só após as escritas e movimentos atómicos confirma que a réplica publicou os ficheiros completos, em vez de apenas os ter recebido em memória.
8. O ID autenticado pertence à ligação cuja prova foi validada. Usar o ID do payload permitiria a uma réplica alegar ser outra e obter uma confirmação atribuída incorretamente.
