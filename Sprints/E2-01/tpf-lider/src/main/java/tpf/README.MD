# TPF Líder (`tpf-lider`)

Bot líder em Java 17+ (JDA) responsável pela gestão de comandos e registo de mensagens no canal `#arquivo`.

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