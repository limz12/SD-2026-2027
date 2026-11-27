
package tpf;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public final class SlashCommandListener extends ListenerAdapter {

    private static final Set<String> COMANDOS_ADMIN =
            Set.of(
                "config",
                "replica-token",
                "replica-remover",
                "rajada"
            );

    private final RegistoService registoService;
    private final String guildId;
    private final String canalArquivoId;
    private final PubSubHub pubSubHub;
    private final ReplicationManager replicationManager;

    public SlashCommandListener(
            RegistoService registoService,
            String guildId,
            String canalArquivoId) {
        this(registoService, guildId, canalArquivoId, null, null);
    }

    public SlashCommandListener(
            RegistoService registoService,
            String guildId,
            String canalArquivoId,
            PubSubHub pubSubHub,
            ReplicationManager replicationManager) {

        this.registoService = Objects.requireNonNull(
            registoService,
            "RegistoService é obrigatório."
        );

        this.guildId = Objects.requireNonNull(
            guildId,
            "Guild ID obrigatório."
        );

        this.canalArquivoId = Objects.requireNonNull(
            canalArquivoId,
            "ID do canal de arquivo obrigatório."
        );
        this.pubSubHub = pubSubHub;
        this.replicationManager = replicationManager;
    }

    /**
     * Definição dos comandos Discord.
     *
     * Os comandos ainda não implementados
     * mantêm as respostas provisórias.
     */
    public static List<CommandData> comandos() {

        return List.of(

            Commands.slash(
                "registar",
                "Regista um texto"
            ).addOption(
                OptionType.STRING,
                "texto",
                "Texto a registar",
                true
            ),

            Commands.slash(
                "estado",
                "Mostra o estado atual"
            ),

            Commands.slash(
                "config",
                "Configurações"
            ).addSubcommands(
                new SubcommandData(
                    "r",
                    "Define r"
                ).addOption(
                    OptionType.INTEGER,
                    "n",
                    "Valor de r",
                    true
                )
            ),

            Commands.slash(
                "recuperar",
                "Recupera intervalo"
            )
                .addOption(
                    OptionType.INTEGER,
                    "de",
                    "Início",
                    true
                )
                .addOption(
                    OptionType.INTEGER,
                    "ate",
                    "Fim",
                    true
                ),

            Commands.slash(
                "replica-token",
                "Gera token para réplica"
            ),

            Commands.slash(
                "replica-remover",
                "Remove uma réplica"
            ).addOption(
                OptionType.STRING,
                "id",
                "ID da réplica",
                true
            ),

            Commands.slash(
                "rajada",
                "Envia rajada de testes"
            )
                .addOption(
                    OptionType.INTEGER,
                    "n",
                    "Quantidade",
                    true
                )
                .addOption(
                    OptionType.INTEGER,
                    "intervaloms",
                    "Intervalo em ms",
                    true
                )
        );
    }

    /**
     * Confirma que a interação provém
     * do servidor e canal autorizados.
     */
    private boolean canalAutorizado(
            SlashCommandInteractionEvent event) {

        return event.isFromGuild()
                && event.getGuild() != null
                && guildId.equals(
                    event.getGuild().getId()
                )
                && canalArquivoId.equals(
                    event.getChannel().getId()
                );
    }

    @Override
    public void onSlashCommandInteraction(
            SlashCommandInteractionEvent event) {

        if (!canalAutorizado(event)) {

            responderErro(
                event,
                "Os comandos só podem ser usados "
                + "no canal **#arquivo** "
                + "do servidor configurado."
            );

            return;
        }

        String nome = event.getName();

        if (COMANDOS_ADMIN.contains(nome)
                && !isAdmin(event.getMember())) {

            responderErro(
                event,
                "Acesso negado: requer o papel "
                + "**admin-arquivo**."
            );

            return;
        }

        switch (nome) {

            case "registar" ->
                cmdRegistar(event);

            case "estado" -> cmdEstado(event);

            case "config" ->
                cmdConfig(event);

            case "recuperar" ->
                cmdRecuperar(event);

            case "replica-token" -> cmdReplicaToken(event);

            case "replica-remover" ->
                cmdReplicaRemover(event);

            case "rajada" ->
                cmdRajada(event);

            default ->
                responderErro(
                    event,
                    "Comando desconhecido."
                );
        }
    }

    /**
     * Trata /registar.
     *
     * O reconhecimento da interação
     * não controla a decisão de escrever.
     *
     * Mesmo quando o reconhecimento falha,
     * o pedido válido é submetido ao serviço.
     */
    private void cmdRegistar(
            SlashCommandInteractionEvent event) {

        var opcao = event.getOption("texto");

        if (opcao == null) {

            responderErro(
                event,
                "É obrigatório indicar o texto."
            );

            return;
        }

        String texto = opcao.getAsString();

        if (texto.isBlank()) {

            responderErro(
                event,
                "O `<texto>` não pode estar vazio."
            );

            return;
        }

        String autorId = event.getUser().getId();
        String canalId = event.getChannel().getId();

        /*
         * O reconhecimento é independente
         * da persistência.
         *
         * true: o Discord aceitou deferReply.
         * false: a confirmação não está disponível.
         */
        CompletableFuture<Boolean> reconhecimento =
                new CompletableFuture<>();

        event.deferReply(true).queue(

            hook -> reconhecimento.complete(true),

            erro -> {

                System.err.println(
                    "Falha ao reconhecer /registar. "
                    + "A escrita será tratada "
                    + "independentemente: "
                    + erro.getMessage()
                );

                reconhecimento.complete(false);
            }
        );

        /*
         * Submeter imediatamente a escrita.
         *
         * Não aguardar pelo callback de
         * sucesso de deferReply().
         *
         * A submissão é assíncrona.
         */
        registoService.registar(
            autorId,
            canalId,
            texto
        ).whenComplete((registo, erroEscrita) -> {

            /*
             * A conclusão do registo e o
             * reconhecimento do Discord podem
             * ocorrer em qualquer ordem.
             */
            reconhecimento.thenAccept(reconhecido -> {

                if (erroEscrita != null) {

                    Throwable causa =
                            causaReal(erroEscrita);

                    System.err.println(
                        "ERRO ao persistir /registar: "
                        + causa.getMessage()
                    );

                    if (reconhecido) {

                        event.getHook()
                            .sendMessage(
                                "Não foi possível "
                                + "confirmar o registo. "
                                + "A escrita não foi "
                                + "confirmada."
                            )
                            .queue(
                                sucesso -> {
                                    // Erro comunicado.
                                },
                                erro -> System.err.println(
                                    "Falha ao comunicar erro "
                                    + "de /registar: "
                                    + erro.getMessage()
                                )
                            );
                    }

                    return;
                }

                /*
                 * Aqui force(true) terminou
                 * com sucesso.
                 */
                long lsn = registo.getLsn();

                if (!reconhecido) {

                    /*
                     * O registo existe, mas não
                     * foi possível confirmar
                     * a interação Discord.
                     *
                     * Não repetir a escrita.
                     */
                    System.err.println(
                        "/registar persistido com LSN="
                        + lsn
                        + ", mas sem reconhecimento "
                        + "da interação Discord."
                    );

                    return;
                }

                /*
                 * Apenas confirmar depois
                 * da persistência durável.
                 */
                event.getHook()
                    .sendMessage(
                        "Registo confirmado. "
                        + "**LSN: " + lsn + "**"
                    )
                    .queue(

                        sucesso -> {
                            // Confirmação enviada.
                        },

                        erro -> {

                            /*
                             * A escrita já foi feita.
                             * Não criar outro registo.
                             */
                            System.err.println(
                                "/registar persistido "
                                + "com LSN=" + lsn
                                + ", mas falhou o envio "
                                + "da confirmação: "
                                + erro.getMessage()
                            );
                        }
                    );
            });
        });
    }

    /**
     * Obtém a causa da falha assíncrona.
     */
    private static Throwable causaReal(
            Throwable erro) {

        Throwable atual = erro;

        while (atual instanceof CompletionException
                && atual.getCause() != null) {

            atual = atual.getCause();
        }

        return atual;
    }

    private void cmdConfig(
            SlashCommandInteractionEvent event) {

        if (!"r".equals(event.getSubcommandName())) {
            return;
        }

        int n = event.getOption("n").getAsInt();

        if (n < 0) {

            responderErro(
                event,
                "`<n>` deve ser >= 0."
            );

            return;
        }

        event.reply(
            "`/config r` recebido: n="
            + n
            + " — ainda não implementado"
        ).queue();
    }

    private void cmdRecuperar(
            SlashCommandInteractionEvent event) {

        int de = event.getOption("de").getAsInt();
        int ate = event.getOption("ate").getAsInt();

        if (de < 0 || ate < de) {

            responderErro(
                event,
                "Intervalo inválido "
                + "(`de` >= 0 e `ate` >= `de`)."
            );

            return;
        }

        event.reply(
            "`/recuperar` recebido: de="
            + de
            + ", ate="
            + ate
            + " — ainda não implementado"
        ).queue();
    }

    private void cmdReplicaRemover(
            SlashCommandInteractionEvent event) {

        String id = event.getOption("id")
                .getAsString()
                .trim();

        if (id.isEmpty()) {

            responderErro(
                event,
                "O `<id>` não pode estar vazio."
            );

            return;
        }

        if (pubSubHub == null || replicationManager == null) {
            responderErro(event, "O serviço de réplicas não está disponível.");
            return;
        }
        try {
            pubSubHub.removerReplica(id);
            replicationManager.registry().remover(id);
            event.reply("Réplica **" + id + "** removida.").setEphemeral(true).queue();
        } catch (IOException e) {
            responderErro(event, "Não foi possível remover a réplica: " + e.getMessage());
        }
    }

    private void cmdReplicaToken(SlashCommandInteractionEvent event) {
        if (pubSubHub == null) {
            responderErro(event, "O serviço de réplicas não está disponível.");
            return;
        }
        String token = pubSubHub.gerarToken();
        event.reply("Token de uso único (válido por 10 minutos):\n`" + token + "`")
                .setEphemeral(true).queue();
    }

    private void cmdEstado(SlashCommandInteractionEvent event) {
        if (replicationManager == null) {
            event.reply("Estado do arquivo local: LSN " + registoService.getUltimoLsn()).queue();
            return;
        }
        var catalog = replicationManager.catalog();
        var replicas = replicationManager.registry().todos();
        long sealed = catalog.entradas().stream()
                .filter(entry -> entry.estado() == EntradaCatalogo.Estado.SELADO).count();
        long durable = catalog.entradas().stream()
                .filter(entry -> entry.estado() == EntradaCatalogo.Estado.DURAVEL).count();
        long underReplicated = catalog.entradas().stream()
                .filter(entry -> entry.replicas().size() < Wire.R).count();
        StringBuilder text = new StringBuilder("**Réplicas**\n");
        if (replicas.isEmpty()) text.append("Nenhuma réplica com heartbeat recebido.\n");
        for (var replica : replicas) {
            text.append("- `").append(replica.id()).append("`: ").append(replica.estado())
                    .append(" — ").append(replica.usados()).append("/").append(replica.quota())
                    .append(" bytes\n");
        }
        text.append("**Chunks:** SELADO ").append(sealed).append(", DURAVEL ").append(durable)
                .append(", subreplicados ").append(underReplicated)
                .append(" (").append(catalog.entradas().size()).append(" total)");
        if (text.length() > 1900) text.setLength(1900);
        event.reply(text.toString()).queue();
    }

    private void cmdRajada(
            SlashCommandInteractionEvent event) {

        int n = event.getOption("n").getAsInt();

        int intervaloMs =
                event.getOption("intervaloms").getAsInt();

        if (n <= 0 || intervaloMs < 0) {

            responderErro(
                event,
                "`<n>` deve ser > 0 e "
                + "`<intervaloMs>` >= 0."
            );

            return;
        }

        String autor = event.getUser().getId();
        String canal = event.getChannel().getId();
        event.deferReply(true).queue(hook ->
            CompletableFuture.runAsync(() -> {
                try {
                    for (int i = 1; i <= n; i++) {
                        registoService.registar(autor, canal, "Rajada " + i + " " + "x".repeat(160)).join();
                        if (i < n && intervaloMs > 0) Thread.sleep(intervaloMs);
                    }
                    hook.sendMessage("Rajada concluída: " + n + " registos.").queue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    hook.sendMessage("Rajada interrompida.").queue();
                } catch (RuntimeException e) {
                    hook.sendMessage("Rajada interrompida por falha no registo.").queue();
                    System.err.println("Falha na rajada: " + e.getMessage());
                }
            }), erro -> System.err.println("Falha ao reconhecer rajada: " + erro.getMessage()));
    }

    private boolean isAdmin(Member member) {

        return member != null
                && member.getRoles()
                    .stream()
                    .anyMatch(
                        role -> role.getName()
                            .equalsIgnoreCase(
                                "admin-arquivo"
                            )
                    );
    }

    private void responderErro(
            SlashCommandInteractionEvent event,
            String mensagem) {

        event.reply(mensagem)
                .setEphemeral(true)
                .queue();
    }
}
