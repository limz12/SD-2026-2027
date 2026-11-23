package tpf;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.*;

import java.util.List;
import java.util.Set;

public class SlashCommandListener extends ListenerAdapter {
    private final LeaderService leaderService;
    private static LeaderService fallbackService;
    private static final Object FALLBACK_LOCK = new Object();

    public SlashCommandListener() {
        this(getFallbackService());
    }

    public SlashCommandListener(LeaderService leaderService) {
        this.leaderService = leaderService;
    }

    private static LeaderService getFallbackService() {
        synchronized (FALLBACK_LOCK) {
            if (fallbackService == null) {
                try {
                    fallbackService = new LeaderService();
                } catch (Exception e) {
                    throw new IllegalStateException("Não foi possível iniciar o líder", e);
                }
            }
            return fallbackService;
        }
    }

    private static final Set<String> COMANDOS_ADMIN = Set.of(
            "config", "replica-token", "replica-remover", "rajada"
    );

    // Definição dos Guild Commands
    public static List<CommandData> comandos() {
        return List.of(
            Commands.slash("registar", "Regista um texto")
                .addOption(OptionType.STRING, "texto", "Texto a registar", true),
            Commands.slash("estado", "Mostra o estado atual"),
            Commands.slash("config", "Configurações")
                .addSubcommands(new SubcommandData("r", "Define r")
                    .addOption(OptionType.INTEGER, "n", "Valor de r", true)),
            Commands.slash("recuperar", "Recupera intervalo")
                .addOption(OptionType.INTEGER, "de", "Início", true)
                .addOption(OptionType.INTEGER, "ate", "Fim", true),
            Commands.slash("replica-token", "Gera token para réplica"),
            Commands.slash("replica-remover", "Remove uma réplica")
                .addOption(OptionType.STRING, "id", "ID da réplica", true),
            Commands.slash("rajada", "Envia rajada de testes")
                .addOption(OptionType.INTEGER, "n", "Quantidade", true)
                .addOption(OptionType.INTEGER, "intervaloms", "Intervalo em ms", true)
        );
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        
        // Bloqueia comandos fora do canal #arquivo
        if (!event.getChannel().getName().equalsIgnoreCase("arquivo")) {
            responderErro(event, "Os comandos só podem ser usados no canal **#arquivo**.");
            return;
        }

        String nome = event.getName();

        if (COMANDOS_ADMIN.contains(nome) && !isAdmin(event.getMember())) {
            responderErro(event, "Acesso negado: requer o papel **admin-arquivo**.");
            return;
        }

        switch (nome) {
            case "registar"        -> cmdRegistar(event);
            case "estado"          -> event.reply("`/estado` recebido — ainda não implementado").queue();
            case "config"          -> cmdConfig(event);
            case "recuperar"       -> cmdRecuperar(event);
            case "replica-token"   -> event.reply("`/replica-token` recebido — ainda não implementado").queue();
            case "replica-remover" -> cmdReplicaRemover(event);
            case "rajada"          -> cmdRajada(event);
        }
    }

    private void cmdRegistar(SlashCommandInteractionEvent event) {
        String texto = event.getOption("texto").getAsString().trim();
        if (texto.isEmpty()) {
            responderErro(event, "O `<texto>` não pode estar vazio.");
            return;
        }
        try {
            Registo registo = leaderService.registar(
                    event.getUser().getName(), "arquivo", texto);
            event.reply("LSN atribuído: " + registo.getLsn()).queue();
        } catch (Exception e) {
            responderErro(event, "Falha ao registar: " + e.getMessage());
        }
    }

    private void cmdConfig(SlashCommandInteractionEvent event) {
        if (!"r".equals(event.getSubcommandName())) return;
        int n = event.getOption("n").getAsInt();
        if (n < 0) {
            responderErro(event, "`<n>` deve ser >= 0.");
            return;
        }
        event.reply("`/config r` recebido: n=" + n + " — ainda não implementado").queue();
    }

    private void cmdRecuperar(SlashCommandInteractionEvent event) {
        int de = event.getOption("de").getAsInt();
        int ate = event.getOption("ate").getAsInt();
        if (de < 0 || ate < de) {
            responderErro(event, "Intervalo inválido (`de` >= 0 e `ate` >= `de`).");
            return;
        }
        event.reply("`/recuperar` recebido: de=" + de + ", ate=" + ate + " — ainda não implementado").queue();
    }

    private void cmdReplicaRemover(SlashCommandInteractionEvent event) {
        String id = event.getOption("id").getAsString().trim();
        if (id.isEmpty()) {
            responderErro(event, "O `<id>` não pode estar vazio.");
            return;
        }
        event.reply("`/replica-remover` recebido: id=" + id + " — ainda não implementado").queue();
    }

    private void cmdRajada(SlashCommandInteractionEvent event) {
        int n = event.getOption("n").getAsInt();
        int intervaloMs = event.getOption("intervaloms").getAsInt();
        if (n <= 0 || intervaloMs < 0) {
            responderErro(event, "`<n>` deve ser > 0 e `<intervaloMs>` >= 0.");
            return;
        }
        event.reply("Rajada iniciada: n=" + n + ", intervaloMs=" + intervaloMs).queue();
        Thread thread = new Thread(() -> {
            for (int i = 1; i <= n; i++) {
                try {
                    leaderService.registar("rajada", "arquivo", "rajada-" + i);
                    if (intervaloMs > 0) Thread.sleep(intervaloMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    break;
                }
            }
        }, "tpf-rajada");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean isAdmin(Member member) {
        return member != null && member.getRoles().stream()
                .anyMatch(r -> r.getName().equalsIgnoreCase("admin-arquivo"));
    }

    private void responderErro(SlashCommandInteractionEvent event, String msg) {
        event.reply(msg).setEphemeral(true).queue();
    }
}