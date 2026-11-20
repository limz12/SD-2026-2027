# TPF Líder (`tpf-lider`)

Bot líder em Java 17+ (JDA) responsável pela gestão de comandos, persistência durável (WAL) e armazenamento encriptado em *chunks* das mensagens recebidas no canal `#arquivo`.

## Pré-requisitos
- **Java 17** ou superior
- **Maven** instalado (ou extensão *Extension Pack for Java* no VS Code)
- Bot criado no [Discord Developer Portal](https://discord.com/developers/applications) com a opção **Message Content Intent** ativada
- Servidor de teste no Discord com o canal de texto `#arquivo` e o cargo `admin-arquivo` criados

## Configuração
1. Na pasta `src/main/java/tpf`, cria um ficheiro chamado `.env`.
2. Adiciona o token do bot e o ID do servidor de teste (sem espaços à volta do `=`):

```env
BOT_TOKEN=teu_token_do_discord_aqui
GUILD_ID=id_do_teu_servidor_aqui
```

### Parâmetros Configuráveis (`Wire`)
O comportamento da selagem de *chunks* pode ser ajustado através de propriedades da JVM (`-Dtpf.*`):

| Propriedade | Valor por omissão | Descrição |
| :--- | :--- | :--- |
| `tpf.chunk.max.bytes` | `65536` (64 KB) | Tamanho máximo de dados acumulados antes de selar o *chunk*. |
| `tpf.pad.bytes` | `4096` (4 KB) | Alinhamento (*padding*) do bloco serializado antes da cifra. |
| `tpf.selagem.ms` | `5000` (5 s) | Tempo máximo desde o primeiro registo aberto até à selagem automática. |

---

## Persistência e Formato Binário dos Chunks

### 1. Registo Durável (WAL)
Cada mensagem enviada em `#arquivo` ou via comando `/registar` recebe um **LSN** (*Log Sequence Number*) sequencial e é gravada de forma síncrona em `registos/registos.log` com `FileChannel.force(true)` (`fsync`) antes de o bot confirmar a operação no Discord:

```text
<lsn>;<instante_ISO8601>;<autor>;<canal>;<texto_em_Base64>\n
```

### 2. Formato Binário Antes da Cifra (*Plaintext* Serializado)
Quando um *chunk* é selado (por atingir `CHUNK_MAX_BYTES` ou após `SELAGEM_MS`), os registos acumulados são serializados em **Big-Endian** (`DataOutputStream`) com a seguinte estrutura:

| Offset / Tamanho | Tipo | Campo | Descrição |
| :--- | :--- | :--- | :--- |
| `4 bytes` | `int32` | `quantidade` | Número total de registos ($N$) contidos no *chunk*. |
| *Repetido $N$ vezes:* | | | |
| `4 bytes` | `int32` | `tamanho_i` | Tamanho em bytes (UTF-8) do registo $i$. |
| `tamanho_i bytes` | `byte[]` | `dados_i` | Linha do registo $i$ codificada em UTF-8 (`lsn;instante;autor;canal;textoBase64`). |
| *Fim do bloco:* | | | |
| `0` a `PAD_BYTES - 1` | `byte[]` | `padding` | Bytes nulos (`0x00`) adicionados até que o tamanho total seja múltiplo exato de `PAD_BYTES` (`4096` bytes por omissão). |

**Leitura Inversa (`desserializarChunk`):** O leitor extrai o inteiro `quantidade`, lê exatamente $N$ registos usando cada prefixo `tamanho_i` para delimitar os bytes a ler, e ignora todos os bytes de `padding` remanescentes até ao fim do *array*.

### 3. Estrutura Criptográfica em Disco (`selados/`)
Após a serialização, o bloco é cifrado com **AES-256-GCM** e gravado atomicamente (`.tmp` + `fsync` + `ATOMIC_MOVE`) na pasta `selados/`, usando como nome o hash **SHA-256** em hexadecimal do *ciphertext* (`<chunkId>`):

* **Chave Mestra (`chave-mestra/master.key`)**: 32 bytes aleatórios (`SecureRandom`) gerados no primeiro arranque com permissões ACL restritas exclusivamente ao proprietário do ficheiro.
* **Derivação de Chave (DEK)**: Derivada por *chunk* via **HKDF-SHA256** (`info = "tpf-chunk-v1"`) a partir da `master.key` e de um `salt` aleatório de 32 bytes.
* **Autenticação (AEAD)**: Tag GCM de 128 bits com `nonce` aleatório de 12 bytes e **AAD** (*Additional Authenticated Data*) composto por `"tpf-v1" || keyVersion (4B) || salt (32B) || nonce (12B)`.

Cada *chunk* selado origina dois ficheiros:
1. **`<chunkId>.bin`**: Conteúdo binário bruto do *ciphertext* AES-GCM (inclui os dados cifrados e a tag de autenticação GCM de 16 bytes).
2. **`<chunkId>.hdr`**: Cabeçalho em texto UTF-8 com 3 linhas separadas por `\n`:
   * Linha 1: Versão da chave (`keyVersion`, ex.: `1`)
   * Linha 2: `salt` de 32 bytes codificado em Base64
   * Linha 3: `nonce` de 12 bytes codificado em Base64


### 3. Testar leitura de chunks selados
Executar o LerChunk.java ajustando os caminhos da "pastaSelados" e "caminhoEventos"