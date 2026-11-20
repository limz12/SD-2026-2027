
package tpf;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;



public class SlashCommandListener extends ListenerAdapter {

    // Referência ao LeaderBot
    private final LeaderBot leaderBot;

    // Construtor
    public SlashCommandListener(LeaderBot leaderBot) {
        this.leaderBot = leaderBot;
    }


    private static final Set<String> COMANDOS_ADMIN = Set.of(
        "config", "replica-token", "replica-remover", "rajada"
    );

    // Definição dos comandos
    public static List<CommandData> comandos() {

        return List.of(
            Commands.slash("registar", "Regista um texto")
                .addOption(OptionType.STRING, "texto",
                    "Texto a registar", true),

            Commands.slash("estado", "Mostra o estado atual"),

            Commands.slash("config", "Configurações")
                .addSubcommands(
                    new SubcommandData("r", "Define r")
                        .addOption(OptionType.INTEGER, "n",
                            "Valor de r", true)
                ),

            Commands.slash("recuperar", "Recupera intervalo")
                .addOption(OptionType.INTEGER, "de", "Início", true)
                .addOption(OptionType.INTEGER, "ate", "Fim", true),

            Commands.slash("replica-token", "Gera token para réplica"),

            Commands.slash("replica-remover", "Remove uma réplica")
                .addOption(OptionType.STRING, "id", "ID da réplica", true),

            Commands.slash("rajada", "Envia rajada de testes")
                .addOption(OptionType.INTEGER, "n",
                    "Quantidade", true)
                .addOption(OptionType.INTEGER, "intervaloms",
                    "Intervalo em ms", true)
        );
    }

    @Override
    public void onSlashCommandInteraction(
            SlashCommandInteractionEvent event) {

        // Bloquear comandos fora do canal #arquivo
        if (!event.getChannel().getName().equalsIgnoreCase("arquivo")) {
            responderErro(event,
                "Os comandos só podem ser usados no canal **#arquivo**.");
            return;
        }

        String nome = event.getName();

        if (COMANDOS_ADMIN.contains(nome)
                && !isAdmin(event.getMember())) {

            responderErro(event,
                "Acesso negado: requer o papel **admin-arquivo**.");
            return;
        }

        switch (nome) {
            case "registar" ->
                cmdRegistar(event);

            case "estado" ->
                event.reply("`/estado` recebido — ainda não implementado")
                    .queue();

            case "config" ->
                cmdConfig(event);

            case "recuperar" ->
                cmdRecuperar(event);

            case "replica-token" ->
                event.reply("`/replica-token` recebido — ainda não implementado")
                    .queue();

            case "replica-remover" ->
                cmdReplicaRemover(event);

            case "rajada" ->
                cmdRajada(event);
        }
    }

    // Comando /registar
    private void cmdRegistar(SlashCommandInteractionEvent event) {

        String texto = event.getOption("texto").getAsString().trim();

        if (texto.isEmpty()) {
            responderErro(event, "O `<texto>` não pode estar vazio.");
            return;
        }

        try {
            // Pedir ao LeaderBot para guardar o registo (com "#" para manter consistência com ArquivoChannelListener)
            long lsn = leaderBot.registar(
                event.getUser().getName(),
                "#" + event.getChannel().getName(),
                texto
            );

            // Responder apenas depois da escrita e do fsync
            event.reply("Registo guardado com sucesso! LSN: " + lsn)
                .queue();

        } catch (IOException e) {
            responderErro(event, "Erro ao guardar o registo.");
            e.printStackTrace();
        }
    }

    private void cmdConfig(SlashCommandInteractionEvent event) {

        if (!"r".equals(event.getSubcommandName())) return;

        int n = event.getOption("n").getAsInt();

        if (n < 0) {
            responderErro(event, "`<n>` deve ser >= 0.");
            return;
        }

        event.reply("`/config r` recebido: n=" + n
            + " — ainda não implementado").queue();
    }

    private void cmdRecuperar(SlashCommandInteractionEvent event) {

        int de = event.getOption("de").getAsInt();
        int ate = event.getOption("ate").getAsInt();

        if (de < 0 || ate < de) {
            responderErro(event,
                "Intervalo inválido (`de` >= 0 e `ate` >= `de`).");
            return;
        }

        event.reply("`/recuperar` recebido: de=" + de
            + ", ate=" + ate + " — ainda não implementado").queue();
    }

    private void cmdReplicaRemover(SlashCommandInteractionEvent event) {

        String id = event.getOption("id").getAsString().trim();

        if (id.isEmpty()) {
            responderErro(event, "O `<id>` não pode estar vazio.");
            return;
        }

        event.reply("`/replica-remover` recebido: id=" + id
            + " — ainda não implementado").queue();
    }

    private void cmdRajada(SlashCommandInteractionEvent event) {

        int n = event.getOption("n").getAsInt();
        int intervaloMs = event.getOption("intervaloms").getAsInt();

        if (n <= 0 || intervaloMs < 0) {
            responderErro(event,
                "`<n>` deve ser > 0 e `<intervaloMs>` >= 0.");
            return;
        }

        String autor = event.getUser().getName();
        String canal = "#" + event.getChannel().getName();

        // Adiar a resposta para o Discord não dar timeout (3s) em rajadas longas
        event.deferReply().queue(hook -> {
            CompletableFuture.runAsync(() -> {
                long primeiroLsn = -1;
                long ultimoLsn = -1;

                try {
                    for (int i = 1; i <= n; i++) {
                        String textoSintetico = String.format(
                            "[RAJADA %d/%d] Registo sintético de teste", i, n
                        );

                        // Passa exatamente pelo mesmo caminho de /registar e #arquivo
                        long lsn = leaderBot.registar(autor, canal, textoSintetico);

                        if (i == 1) {
                            primeiroLsn = lsn;
                        }
                        ultimoLsn = lsn;

                        if (intervaloMs > 0 && i < n) {
                            Thread.sleep(intervaloMs);
                        }
                    }

                    hook.editOriginal(
                        String.format(
                            "Rajada concluída: **%d** registos injetados (LSN **%d** a **%d**, intervalo: %d ms).",
                            n, primeiroLsn, ultimoLsn, intervaloMs
                        )
                    ).queue();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    hook.editOriginal("Rajada interrompida.").queue();
                } catch (IOException e) {
                    hook.editOriginal("Erro de I/O durante a rajada (último LSN gravado: " + ultimoLsn + ").").queue();
                    e.printStackTrace();
                }
            });
        });
        
    }

    private boolean isAdmin(Member member) {

        return member != null && member.getRoles().stream()
            .anyMatch(r ->
                r.getName().equalsIgnoreCase("admin-arquivo")
            );
    }

    private void responderErro(
            SlashCommandInteractionEvent event,
            String msg) {

        event.reply(msg).setEphemeral(true).queue();
    }
}
