package tpf;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

public class ArquivoChannelListener extends ListenerAdapter {

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!event.getChannel().getName().equalsIgnoreCase("arquivo")) return;

        String autor = event.getAuthor().getName();
        String texto = event.getMessage().getContentDisplay();

        event.getChannel()
             .sendMessage("`#arquivo` [" + autor + "]: `" + texto + "` — registado provisoriamente.")
             .queue();
    }
}