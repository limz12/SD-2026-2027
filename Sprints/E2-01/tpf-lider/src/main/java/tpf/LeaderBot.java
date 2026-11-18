package tpf;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class LeaderBot {

    public static void main(String[] args) throws InterruptedException {
        String token = EnvConfig.get("BOT_TOKEN");
        String guildId = EnvConfig.get("GUILD_ID");

        JDA jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new SlashCommandListener(), new ArquivoChannelListener())
                .build();

        jda.awaitReady();

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Servidor não encontrado: " + guildId);
        }

        guild.updateCommands().addCommands(SlashCommandListener.comandos()).queue();
        System.out.println("LeaderBot online no servidor: " + guild.getName());
    }
}