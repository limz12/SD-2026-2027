# TPF Líder / Réplicas (`tpf-lider-03`, `tpf-replica`)

## Implementação E2-03: Comunicação Líder–Réplicas, Colocação e Escrita Replicada

Este sprint acrescenta comunicação líder-réplicas sobre WebSocket (`PubSubHub` no processo do líder), registo/autenticação de réplicas com token de uso único + desafio-resposta Ed25519, heartbeats, colocação WRH, escrita replicada com `STORE/ACK`, e catálogo de chunks (`SELADO`/`DURAVEL`/subreplicado).

Fora do âmbito: manifesto cifrado, reconciliação completa, `DELETE`, desafios de posse, `FETCH`, recuperação a partir das réplicas e `ArquivoCheck`. `/config` e `/recuperar` mantêm resposta provisória.

## 1. Configuração (Wire)

Constantes configuráveis por propriedades JVM (`-Dtpf.<nome>=...`):

| Constante | Propriedade | Valor por omissão | Descrição |
|---|---|---:|---|
| `PORTO` | `tpf.porto` | 8765 | Porto do `PubSubHub` (WebSocket) |
| `PERIODO_HB_MS` | `tpf.periodoHbMs` | 2000 | Intervalo de heartbeats |
| `SUSPEITA_MS` | `tpf.suspeitaMs` | 6000 | Sem HB => SUSPEITA |
| `MORTA_MS` | `tpf.mortaMs` | 10000 | Sem HB => MORTA |
| `TOKEN_VALIDADE_MS` | `tpf.tokenValidadeMs` | 600000 | Validade do token (10 min) |
| `R` | `tpf.r` | 3 | Fator de replicação alvo |
| `W_MIN` | `tpf.wMin` | 2 | Mínimo absoluto de ACKs |
| `ACK_TIMEOUT_MS` | `tpf.ackTimeoutMs` | 4000 | Timeout por ronda de ACK |
| `MAX_RONDAS` | `tpf.maxRondas` | 3 | Máximo de rondas por chunk |
| `MARGEM_BYTES` | `tpf.margemBytes` | 65536 | Margem de espaço livre |
| `ALFA` | `tpf.alfa` | 1.0 | Peso do espaço livre no WRH |
| `BACKOFF_MIN_MS` | `tpf.backoffMinMs` | 1000 | Backoff mínimo da réplica |
| `BACKOFF_MAX_MS` | `tpf.backoffMaxMs` | 30000 | Backoff máximo da réplica |

Regras/validações relevantes:
- `PORTO` em `1..65535`.
- `W_MIN <= R`.
- `SUSPEITA_MS < MORTA_MS`.
- `BACKOFF_MIN_MS <= BACKOFF_MAX_MS`.
- `ALFA >= 0`, `MARGEM_BYTES >= 0`.
- Quórum de durabilidade: `max(W_MIN, floor(R/2)+1)`.

## 2. Formato das mensagens

Mensagens de texto, campos separados por `;`. Campos binários codificados em Base64. O `.bin` do chunk é identificado pelo SHA-256 do seu conteúdo.

### 2.1 Registo / autenticação (Réplica <-> Hub)

| Direção | Mensagem | Descrição |
|---|---|---|
| Réplica -> Hub | `OLA;<id>;<chavePublicaB64>;<token>` | 1.º registo. Chave pública Ed25519 em X.509/Base64; token de uso único. |
| Réplica -> Hub | `OLA;<id>` | Registo seguinte (réplica já em `replicas.txt`). |
| Hub -> Réplica | `DESAFIO;<nonceB64>` | Nonce aleatório (32 bytes, `SecureRandom`), novo por ligação. |
| Réplica -> Hub | `PROVA;<id>;<assinaturaB64>` | Assinatura Ed25519 de `"tpf-registo|" + id + "|" + nonceB64` (UTF-8). |

Notas:
- O token só é consumido e a réplica só é gravada em `replicas.txt` após `PROVA` válida.
- `replicas.txt`: `id;chavePublicaB64`, escrita atómica (tmp + `ATOMIC_MOVE`).
- Mensagem fora de ordem, token inválido/expirado/reutilizado, PROVA inválida, ou id/chave incompatível => ligação fechada e `ERRO` registado.

### 2.2 Subscrição / publicação

| Direção | Mensagem | Descrição |
|---|---|---|
| Réplica -> Hub | `SUB;<topico>` | Subscrição. Permitido: `tpf-r-<id>` da própria réplica e `tpf-ctl`. |
| Réplica -> Hub | `PUB;<topico>;<mensagem>` | Publicação. Réplica só pode publicar em `tpf-lider`. |
| Hub/Líder -> Réplica | `<mensagem>` | Conteúdo publicado no tópico subscrito (ex.: `STORE;...`). |

Tópicos:

| Tópico | Quem publica | Quem subscreve | Uso |
|---|---|---|---|
| `tpf-r-<id>` | Líder | Só réplica `<id>` | Pedidos dirigidos (`STORE`) |
| `tpf-ctl` | Líder | Todas as réplicas | Difusão (futuro) |
| `tpf-lider` | Réplicas | Líder (em processo) | `HB`, `INVENTARIO`, `ACK` |

### 2.3 Mensagens no tópico `tpf-lider`

| Mensagem | Emissor | Significado |
|---|---|---|
| `HB;<id>;<usados>;<quota>` | Réplica | Heartbeat periódico; reporta espaço usado e quota. |
| `INVENTARIO;<id>;<chunkId1,chunkId2,...>` | Réplica | Inventário após ligação/religação; lista pode ser vazia. |
| `ACK;<id>;<chunkId>;<sha256>` | Réplica | Confirmação de STORE após persistência atómica. |

Validação de ACK no líder:
- `sha256 == chunkId`.
- `<id>` da mensagem == id autenticado da ligação WebSocket.
- Réplica tinha STORE pendente para esse chunk.

### 2.4 STORE (Líder -> Réplica, tópico `tpf-r-<id>`)

| Mensagem | Descrição |
|---|---|
| `STORE;<chunkId>;<hdrB64>;<binB64>` | Envia chunk cifrado. `chunkId` é o SHA-256 do `.bin`. |

Comportamento da réplica:
1. Rejeita se `usados + tamanhoBin > quota` (`QUOTA`).
2. Calcula SHA-256 do `.bin`; se diferente de `chunkId`, rejeita.
3. Grava `<chunkId>.hdr` e `<chunkId>.bin` em `dados-<id>/` com ficheiro temporário + `ATOMIC_MOVE`; atualiza `usados`.
4. Só depois envia `ACK`.
5. Se chunk já existe, responde com `ACK` sem reescrever.

## 3. Catálogo e estados

Ficheiro: `catalogo.txt` no `tpf.dataDir`, escrita atómica.

Formato por linha:
```text
chunkId;lsnDe;lsnAte;tamanho;replicas;estado
```

- `replicas`: ids separados por vírgulas, com cópia confirmada.
- `estado`: `SELADO` ou `DURAVEL`.
- `DURAVEL` quando ACKs válidos >= `max(W_MIN, floor(R/2)+1)`.
- Subreplicado: condição derivada quando nº de cópias < `R` (pode coexistir com `DURAVEL`).

## 4. Como lançar N réplicas

### 4.1 Pré-requisitos
- Java 17+ e Maven.
- Líder configurado com `.env` Discord (`BOT_TOKEN`, `GUILD_ID`) e `-Dtpf.dataDir=<caminho absoluto>`.
- Porta `tpf.porto` acessível às réplicas.

### 4.2 Arrancar o líder
Na pasta `Sprints/E2-03/tpf-lider-03`:

```powershell
mvn clean compile
java "-Dtpf.dataDir=C:\...\dados-lider" -cp "target/classes;target/dependency/*" tpf.LeaderBot
```

(Ajustar conforme a forma de execução/dependências do projeto.)

### 4.3 Gerar tokens
No Discord, no canal `#arquivo`, com papel `admin-arquivo`:
- Executar `/replica-token` uma vez por réplica.
- A resposta é efémera; o token é de uso único e válido por 10 min.

### 4.4 Arrancar réplicas
Na pasta `Sprints/E2-03/tpf-replica`:

```powershell
mvn clean compile
# 1.º registo (com token)
java -cp "target/classes;target/dependency/*" tpf.ReplicaService <id> ws://<host>:8765 <quotaBytes> <token>

# Arranques seguintes (sem token)
java -cp "target/classes;target/dependency/*" tpf.ReplicaService <id> ws://<host>:8765 <quotaBytes>
```

Exemplo para 3 réplicas locais:

```powershell
java -cp "target/classes;target/dependency/*" tpf.ReplicaService rep1 ws://127.0.0.1:8765 8388608 <token1>
java -cp "target/classes;target/dependency/*" tpf.ReplicaService rep2 ws://127.0.0.1:8765 8388608 <token2>
java -cp "target/classes;target/dependency/*" tpf.ReplicaService rep3 ws://127.0.0.1:8765 8388608 <token3>
```

Cada réplica cria/usa `dados-<id>/` na pasta de execução atual.

## 5. Comandos Discord (E2-03)

| Comando | Permissão | Comportamento |
|---|---|---|
| `/replica-token` | `admin-arquivo` | Gera token efémero, uso único, validade 10 min. |
| `/replica-remover <id>` | `admin-arquivo` | Revoga réplica: remove de `replicas.txt`, fecha ligação ativa, exclui de colocação e remove referências no catálogo. |
| `/estado` | utilizadores do canal | Mostra estado/ocupação/chunks por réplica e totais SELADO/DURAVEL/subreplicados. |
| `/config r` | `admin-arquivo` | Resposta provisória (fora do âmbito). |
| `/recuperar` | utilizadores do canal | Resposta provisória (fora do âmbito). |

## 6. Sincronização entre threads

> **A completar pelo grupo.** Descreve aqui as estruturas partilhadas, as threads que lhes acedem, e o mecanismo de sincronização usado.

Pontos a cobrir (guia):
- Que threads existem? (eventos JDA, callbacks WebSocket Java-WebSocket, thread de escrita/registo, temporizador de selagem do `ArquivoLocal`, `ScheduledExecutorService` do `ReplicaRegistry`/`ReplicationManager`, etc.)
- Que objetos são partilhados? (`ReplicaRegistry`, `Catalog`, `PubSubHub` mapas/subscrições, `ReplicationManager.emCurso`, `ArquivoLocal` estado de chunk aberto, etc.)
- Onde há `synchronized`/locks/estruturas concorrentes (`ConcurrentHashMap`, `newKeySet`, `volatile`, executores single-thread)?
- Porque é que `reserveAndChoose` tem de ser atómico?
- Que invariantes estás a proteger?

## 7. Questões de reflexão

> **A completar pelo grupo.** Responde com justificação técnica; não basta “funciona”.

1. Porque é que o token é necessário, se a réplica já se autentica com Ed25519? O que acontece se um token for intercetado nos 10 minutos de validade?
2. Porque é que a réplica assina o `id` e um prefixo juntamente com o nonce, e não apenas o nonce?
3. Porque é que a escolha e a reserva têm de acontecer na mesma secção sincronizada? Dê um exemplo de execução que falharia sem isso.
4. Porque é que a durabilidade exige um mínimo absoluto (`W_MIN`) e também a maioria de R? O que muda com `R = 5`? Que risco se corre com W = 1, e que custo teria exigir R confirmações?
5. Num sistema com vários escritores, uma maioria serve para garantir consistência (quórum). Porque é que aqui não há esse problema? Pense em chunks imutáveis, identificados pelo hash, e num único líder.
6. Qual é o efeito de α na distribuição? Com α = 0, o espaço livre ainda influencia a escolha de alguma forma?
7. Porque é que a réplica só pode enviar `ACK` depois de gravar os ficheiros com `ATOMIC_MOVE`?
8. Porque é que o líder usa o id autenticado da ligação, e não o `<id>` escrito no `ACK`?

## 8. Experiências (E2-03)

> Registar aqui evidências/resultados das experiências 1–9 do enunciado (comandos, logs, capturas, tabelas/medições).

- [ ] 1. Registo com tokens diferentes; reutilização; token expirado; PROVA com chave errada.
- [ ] 2. Matar réplica e observar `VIVA -> SUSPEITA -> MORTA`; relançar e observar `INVENTARIO`.
- [ ] 3. Desligar líder e observar backoff em `eventos-<id>.txt`.
- [ ] 4. 3 réplicas vivas, `/rajada` 50 registos; chunks `DURAVEL`, 3 cópias, SHA-256 igual ao nome.
- [ ] 5. WRH com quotas 8/2/4/6 MB e α=1; repetir α=0 e α=2.
- [ ] 6. 2 réplicas vivas => `DURAVEL` subreplicado 2/3; 1 réplica viva => `SELADO` subreplicado 1/3.
- [ ] 7. Quota pequena => réplica deixa de ser escolhida; `QUOTA` se STORE não cabe.
- [ ] 8. Hash errado => ACK rejeitado, chunk DURAVEL com outras, nova ronda coloca 3ª cópia.
- [ ] 9. ACK com id spoofed => rejeitado.

## 9. Limitações conhecidas / notas

- `Catalog.removerReplicaDeTodasEntradas` remove a réplica das entradas do catálogo; confirmar com o docente/enunciado se o estado deve ser degradado quando as cópias ficam abaixo do quórum.
- `/config` e `/recuperar` são provisórios neste sprint.
- A validação completa depende de testes ponta-a-ponta com líder + múltiplas réplicas.
