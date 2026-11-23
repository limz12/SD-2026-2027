# TPF Líder (`tpf-lider`)

Bot líder em Java 17+ (JDA) responsável pelo registo durável e pela criação de chunks cifrados.

## Pré-requisitos

- Java 17 ou superior
- Maven
- Bot com `Message Content Intent`
- Canal `#arquivo`
- Cargo `admin-arquivo`

## Configuração

Cria `Sprints/E2-02/tpf-lider/src/main/java/tpf/.env`:

```env
BOT_TOKEN=teu_token
GUILD_ID=id_do_servidor
```

No primeiro arranque é criada a chave `master.key` em `Sprints/E2-02/tpf-lider/master.key`.
Só são aceites chaves com exatamente 32 bytes.

## Diretórios e ficheiros

- `registos.log`: uma entrada por linha, no formato `lsn;instante;autor;canal;textoEmBase64`
- `eventos-lider.txt`: eventos `REGISTO` e `SELADO`
- `selados/<chunkId>.bin`: texto cifrado AES-256-GCM
- `selados/<chunkId>.hdr`: `keyVersion;saltBase64;nonceBase64`
- `master.key`: chave mestra de 32 bytes

Os caminhos podem ser alterados com `-Dtpf.dataDir=...`.

## Parâmetros

- `-Dtpf.chunkMaxBytes=65536`
- `-Dtpf.padBytes=4096`
- `-Dtpf.selagemMs=5000`

## Formato binário do chunk

Antes da cifra, o chunk usa:

1. 4 bytes `TPF2`;
2. inteiro de 32 bits com o número de registos;
3. para cada registo:
   - `lsn` como inteiro de 64 bits;
   - `instante` como inteiro de 64 bits;
   - comprimento UTF-8 do autor como inteiro de 32 bits e os respetivos bytes;
   - comprimento UTF-8 do canal como inteiro de 32 bits e os respetivos bytes;
   - comprimento UTF-8 do texto como inteiro de 32 bits e os respetivos bytes.

Todos os inteiros são escritos em big-endian. Os bytes zero são adicionados até o tamanho
do bloco ser múltiplo de `PAD_BYTES`; a leitura inversa para no número indicado de
registos e ignora os restantes bytes de padding.

## Cifra

Por chunk são gerados `salt` de 32 bytes e `nonce` de 12 bytes com `SecureRandom`.
O HKDF-SHA256 usa a `master.key` como IKM, o salt como informação de extração e
`info = "tpf-chunk-v1"` na expansão de um bloco para produzir uma DEK de 32 bytes.

AES-256-GCM usa tag de 128 bits e AAD:

```text
"tpf-v1" + keyVersion (big-endian, 4 bytes) + salt + nonce
```

O `chunkId` é o SHA-256 em hexadecimal do ciphertext completo, incluindo a tag GCM.

## Comandos

- `/registar <texto>`: regista com fsync e devolve o LSN.
- Mensagens humanas em `#arquivo`: usam exatamente o mesmo método de registo.
- `/rajada <n> <intervaloms>`: disponível apenas para `admin-arquivo`.
- Os comandos `/estado`, `/config`, `/recuperar`, `/replica-token` e
  `/replica-remover` mantêm respostas provisórias deste sprint.