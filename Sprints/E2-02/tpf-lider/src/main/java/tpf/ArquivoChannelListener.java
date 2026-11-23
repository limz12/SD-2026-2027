package tpf;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class ArquivoChannelListener extends ListenerAdapter {
    private final LeaderService leaderService;

    public ArquivoChannelListener() {
        this(createService());
    }

    public ArquivoChannelListener(LeaderService leaderService) {
        this.leaderService = leaderService;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.getChannel().getName().equalsIgnoreCase("arquivo")) return;

        String autor = event.getAuthor().getName();
        String texto = event.getMessage().getContentDisplay();

        try {
            Registo registo = leaderService.registar(autor, "arquivo", texto);
            event.getChannel()
                 .sendMessage("`#arquivo` [" + autor + "]: `" + texto + "` — LSN " + registo.getLsn() + ".")
                 .queue();
        } catch (Exception e) {
            event.getChannel().sendMessage("Falha ao registar a mensagem: " + e.getMessage()).queue();
        }
    }

    private static LeaderService createService() {
        try {
            return new LeaderService();
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível iniciar o líder", e);
        }
    }
}