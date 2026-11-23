package tpf;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class LeaderBot {
    private static LeaderService leaderService;

    public static void main(String[] args) throws Exception {
        String token = EnvConfig.get("BOT_TOKEN");
        String guildId = EnvConfig.get("GUILD_ID");
        leaderService = new LeaderService();

        JDA jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                .addEventListeners(new SlashCommandListener(leaderService),
                        new ArquivoChannelListener(leaderService))
                .build();

        jda.awaitReady();

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Servidor não encontrado: " + guildId);
        }

        guild.updateCommands().addCommands(SlashCommandListener.comandos()).queue();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> leaderService.shutdown(),
                "tpf-leader-shutdown"));
        System.out.println("LeaderBot online no servidor: " + guild.getName());
    }
}