
package tpf;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class ArquivoChannelListener extends ListenerAdapter {

    private final LeaderBot leaderBot;

    public ArquivoChannelListener(LeaderBot leaderBot) {
        this.leaderBot = leaderBot;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {

        // Ignorar mensagens de bots
        if (event.getAuthor().isBot()) return;

        // Aceitar apenas mensagens no canal #arquivo
        if (!event.getChannel().getName().equalsIgnoreCase("arquivo")) {
            return;
        }

        String autor = event.getAuthor().getName();
        String texto = event.getMessage().getContentDisplay();

        if (texto.isBlank()) return;

        try {

            // Pedir ao LeaderBot para guardar o registo
            long lsn = leaderBot.registar(
                autor,
                "#arquivo",
                texto
            );

            // Responder apenas depois do fsync
            event.getChannel()
                .sendMessage("Registo guardado com sucesso! LSN: " + lsn)
                .queue();

        } catch (Exception e) {

            event.getChannel()
                .sendMessage("Erro ao guardar o registo.")
                .queue();

            e.printStackTrace();
        }
    }
}
