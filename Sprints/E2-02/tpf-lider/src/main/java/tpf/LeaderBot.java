
package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.requests.GatewayIntent;

public class LeaderBot {

    // Variáveis responsáveis pelo registo
    private long ultimoLsn = 0;

    private final Path caminhoLog = Path.of(
        "./Sprints/E2-02/tpf-lider/src/main/java/tpf/registos/registos.log"
    );

    private final Path caminhoEventos = Path.of(
    "./Sprints/E2-02/tpf-lider/src/main/java/tpf/registos/eventos-lider.txt"
);

    public static void main(String[] args) throws InterruptedException {

        String token = EnvConfig.get("BOT_TOKEN");
        String guildId = EnvConfig.get("GUILD_ID");

        // Criar a instância do LeaderBot
        LeaderBot leaderBot = new LeaderBot();

        // Criar ou carregar a chave mestra
        Path caminhoChave = Path.of(
            "./Sprints/E2-02/tpf-lider/src/main/java/tpf/chave-mestra/master.key"
        );

        try {
            Files.createDirectories(caminhoChave.getParent());

            MasterKey.loadOrCreate(caminhoChave);

            System.out.println("Chave mestra criada ou carregada com sucesso.");

        } catch (Exception e) {
            System.err.println("Erro ao carregar ou criar a chave mestra: "
                + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Recuperar o último LSN antes de aceitar comandos
        try {
            Files.createDirectories(leaderBot.caminhoLog.getParent());

            leaderBot.ultimoLsn =
                Registo.carregarUltimoLsn(leaderBot.caminhoLog);

            System.out.println("Ultimo LSN recuperado: "
                + leaderBot.ultimoLsn);

        } catch (IOException e) {
            System.err.println("Erro ao recuperar os registos: "
                + e.getMessage());
            e.printStackTrace();
            return;
        }

        // Criar o listener e passar-lhe o LeaderBot
        SlashCommandListener slashCommandListener =
            new SlashCommandListener(leaderBot);

        JDA jda = JDABuilder.createDefault(token)
            .enableIntents(
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT
            )
            .addEventListeners(
                slashCommandListener,
                new ArquivoChannelListener(leaderBot)
            )
            .build();

        jda.awaitReady();

        Guild guild = jda.getGuildById(guildId);

        if (guild == null) {
            throw new IllegalStateException(
                "Servidor não encontrado: " + guildId
            );
        }

        guild.updateCommands()
            .addCommands(SlashCommandListener.comandos())
            .queue();

        System.out.println("LeaderBot online no servidor: "
            + guild.getName());
    }

    //##################### METODOS #################################################

    // Método responsável por registar e guardar no ficheiro
    public synchronized long registar(String autor,String canal, String texto) throws IOException {

        long novoLsn = ultimoLsn + 1;

        String instante = Instant.now().toString();

        // Converter o texto para Base64
        String textoBase64 = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));

        // Construir a linha do registo
        String linha = novoLsn + ";"+ instante + ";"+ autor + ";"+ canal + ";"+ textoBase64 + "\n";

        ByteBuffer buffer = ByteBuffer.wrap(linha.getBytes(StandardCharsets.UTF_8));

        // Escrever em registos.log e forçar fsync
        try (FileChannel fileChannel = FileChannel.open(
                caminhoLog,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {

            while (buffer.hasRemaining()) {
                fileChannel.write(buffer);
            }

            fileChannel.force(true);
        }

        // Atualizar o último LSN
        ultimoLsn = novoLsn;

        // 3. Registar evento REGISTO em eventos-lider.txt
        String detalhe = "LSN=" + novoLsn+ " autor=" + autor + " canal=" + canal;

        Registo.registarEvento(caminhoEventos,"REGISTO",detalhe);

        return novoLsn;
    }
    
    public synchronized long getUltimoLsn() {
        return ultimoLsn;
    }
}
