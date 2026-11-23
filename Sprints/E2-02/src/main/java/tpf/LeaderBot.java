package tpf;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sprint E2-02: caminho de escrita local do lider.
 *
 * - A master.key e carregada (ou gerada) no arranque; sem ela, ou com uma chave
 *   com tamanho errado, o lider NEM ARRANCA.
 * - /registar e as mensagens em #arquivo passam pelo mesmo metodo sincronizado
 *   Arquivo.registar(...), que grava no diario com fsync ANTES de responder.
 * - O chunk aberto selar-se por tamanho (na hora) ou por tempo (SELAGEM_MS apos
 *   o primeiro registo); ao selar e cifrado com AES-GCM (DEK por HKDF da
 *   master.key) e gravado em selados/<chunkId>.bin + .hdr.
 * - /rajada injeta n registos sinteticos pelo MESMO metodo de /registar.
 * - /estado, /config, /recuperar, /replica-token e /replica-remover mantem a
 *   resposta provisoria do sprint E2-01.
 */
public class LeaderBot extends ListenerAdapter {

    private final long canalArquivoId;
    private final Arquivo arquivo;
    private final ExecutorService injetorRajada = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rajada");
        t.setDaemon(true);
        return t;
    });

    private LeaderBot(long canalArquivoId, Arquivo arquivo) {
        this.canalArquivoId = canalArquivoId;
        this.arquivo = arquivo;
    }

    public static void main(String[] args) throws Exception {
        tpf.Configuracao cfg = Configuracao.carregar();

        // master.key: gera no primeiro arranque; recusa o arranque se tiver outro tamanho.
        MasterKey chaveMestra = MasterKey.carregar(Path.of("master.key"));
        System.out.println("master.key carregada (" + MasterKey.TAMANHO + " bytes).");

        Arquivo arquivo = new Arquivo(Path.of("."), chaveMestra.copiarBytes());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                arquivo.close();
            } catch (IOException ignorada) {
                // best effort no shutdown
            }
        }));

        // MESSAGE_CONTENT e um intent "privilegiado": tem de estar ligado no Developer Portal
        // (Bot > Privileged Gateway Intents > Message Content Intent), senao o login falha.
        JDA jda = JDABuilder.createDefault(cfg.getToken())
                .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                .build()
                .awaitReady();

        Guild guild = jda.getGuildById(cfg.getGuildId());
        if (guild == null) {
            System.err.println("O bot nao esta no servidor " + cfg.getGuildId()
                    + ". Convida-o com o URL OAuth2 (scopes bot + applications.commands).");
            jda.shutdown();
            return;
        }

        Role papelAdmin = guild.getRolesByName(Permissoes.PAPEL_ADMIN, true).stream()
                .findFirst()
                .orElseGet(() -> guild.createRole().setName(Permissoes.PAPEL_ADMIN).complete());
        TextChannel canalArquivo = guild.getTextChannelsByName(CanalArquivo.NOME_CANAL, true).stream()
                .findFirst()
                .orElseGet(() -> guild.createTextChannel(CanalArquivo.NOME_CANAL).complete());
        System.out.println("Recursos Discord prontos: papel " + papelAdmin.getName()
                + " e canal #" + canalArquivo.getName() + ".");

        jda.addEventListener(new LeaderBot(canalArquivo.getIdLong(), arquivo));

        // Guild commands aparecem logo (os globais podem demorar ate 1 hora).
        // updateCommands() substitui a lista inteira: comandos antigos desaparecem.
        guild.updateCommands().addCommands(
                Commands.slash(Comandos.REGISTAR, "Regista um texto no arquivo")
                        .addOption(OptionType.STRING, Comandos.ARG_TEXTO, "Texto a registar", true),
                Commands.slash(Comandos.ESTADO, "Mostra o estado do líder"),
                Commands.slash(Comandos.CONFIG, "Configura o líder (só admin-arquivo)")
                        .addSubcommands(new SubcommandData("r", "Define o fator de replicação R")
                                .addOption(OptionType.INTEGER, Comandos.ARG_N, "Novo valor de R", true))
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash(Comandos.RECUPERAR, "Recupera registos entre dois números de sequência")
                        .addOption(OptionType.INTEGER, Comandos.ARG_DE, "Primeiro número de sequência", true)
                        .addOption(OptionType.INTEGER, Comandos.ARG_ATE, "Último número de sequência", true),
                Commands.slash(Comandos.REPLICA_TOKEN, "Gera um token para uma nova réplica (só admin-arquivo)")
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash(Comandos.REPLICA_REMOVER, "Remove uma réplica (só admin-arquivo)")
                        .addOption(OptionType.STRING, Comandos.ARG_ID, "Id da réplica", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED),
                Commands.slash(Comandos.RAJADA, "Envia uma rajada de registos de teste (só admin-arquivo)")
                        .addOption(OptionType.INTEGER, Comandos.ARG_N, "Número de registos", true)
                        .addOption(OptionType.INTEGER, Comandos.ARG_INTERVALO_MS, "Intervalo entre registos (ms)", true)
                        .setDefaultPermissions(DefaultMemberPermissions.DISABLED)
        ).queue(
                ok -> System.out.println("LeaderBot online em \"" + guild.getName()
                        + "\" com " + Comandos.TODOS.size() + " comandos registados. Último LSN: "
                        + arquivo.getUltimoLsn() + "."),
                erro -> System.err.println("Falha ao registar comandos: " + erro.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (event.getGuild() == null
                || !CanalArquivo.eCanalArquivo(event.getChannel().getIdLong(), canalArquivoId)) {
            event.reply("Os comandos só podem ser usados em #" + CanalArquivo.NOME_CANAL + ".")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        // Papeis do membro que escreveu o comando (vem no proprio pedido, nao precisa de intent extra).
        List<String> papeis = new ArrayList<>();
        if (event.getMember() != null) {
            for (Role r : event.getMember().getRoles()) {
                papeis.add(r.getName());
            }
        }

        // Converte as opcoes do JDA num Map simples: nome -> valor em texto.
        Map<String, String> args = new LinkedHashMap<>();
        if (event.getSubcommandName() != null) {
            args.put(Comandos.ARG_SUBCOMANDO, event.getSubcommandName());
        }
        for (OptionMapping o : event.getOptions()) {
            args.put(o.getName(), o.getAsString());
        }

        Resultado r = Decisor.decidir(event.getName(), papeis, args);
        if (r.isOk() && r.getMensagem().isEmpty()) {
            // Comando validado que faz trabalho a valer: /registar ou /rajada.
            try {
                if (Comandos.REGISTAR.equals(event.getName())) {
                    Registo reg = arquivo.registar(event.getUser().getName(), CanalArquivo.NOME_CANAL,
                            args.get(Comandos.ARG_TEXTO));
                    responder(event, "Registado no arquivo com **LSN " + reg.lsn() + "**.", false);
                } else if (Comandos.RAJADA.equals(event.getName())) {
                    long n = Long.parseLong(args.get(Comandos.ARG_N));
                    long intervalo = Long.parseLong(args.get(Comandos.ARG_INTERVALO_MS));
                    responder(event, "Rajada iniciada: **" + n + "** registos, intervalo **" + intervalo
                            + " ms**. Acompanha em eventos-lider.txt e selados/.", false);
                    injetarRajada(n, intervalo);
                } else {
                    // So /registar e /rajada devolvem OK com mensagem vazia; se um dia
                    // algum outro o fizer, nao pode ficar sem resposta.
                    responder(event, "Comando aceite, mas sem ação associada.", true);
                }
            } catch (IOException | RuntimeException e) {
                responder(event, "Falha ao gravar o registo: " + e.getMessage(), true);
            }
            return;
        }

        // Erros so aparecem a quem escreveu o comando (ephemeral); respostas ok ficam visiveis no canal.
        responder(event, r.getMensagem(), !r.isOk());
        System.out.println("[comando] " + event.getUser().getName() + " -> " + event.getCommandString()
                + " => " + (r.isOk() ? "aceite" : "recusado"));
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        boolean autorEBot = event.getAuthor().isBot() || event.isWebhookMessage();
        if (!CanalArquivo.deveProcessar(autorEBot, event.isFromGuild(), event.getChannel().getName())
                || !CanalArquivo.eCanalArquivo(event.getChannel().getIdLong(), canalArquivoId)) {
            return;
        }
        String texto = event.getMessage().getContentDisplay();
        if (texto == null || texto.isBlank()) {
            return;
        }
        // Mesmo caminho do /registar: metodo sincronizado do Arquivo, com fsync.
        try {
            Registo r = arquivo.registar(event.getAuthor().getName(), CanalArquivo.NOME_CANAL, texto);
            event.getMessage().reply("Registado no arquivo com **LSN " + r.lsn() + "**.").queue();
        } catch (IOException | RuntimeException e) {
            event.getMessage().reply("Falha ao gravar o registo: " + e.getMessage()).queue();
        }
    }

    /** Injeta n registos sinteticos pelo MESMO metodo de /registar. */
    private void injetarRajada(long n, long intervaloMs) {
        injetorRajada.submit(() -> {
            long inicio = System.currentTimeMillis();
            for (long i = 1; i <= n; i++) {
                try {
                    arquivo.registar("rajada", CanalArquivo.NOME_CANAL,
                            "registo sintético " + i + "/" + n);
                } catch (IOException | RuntimeException e) {
                    System.err.println("[rajada] falha no registo " + i + ": " + e.getMessage());
                    return;
                }
                if (intervaloMs > 0 && i < n) {
                    try {
                        Thread.sleep(intervaloMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            System.out.println("[rajada] " + n + " registos injetados em "
                    + (System.currentTimeMillis() - inicio) + " ms. Último LSN: " + arquivo.getUltimoLsn() + ".");
        });
    }

    private void responder(SlashCommandInteractionEvent event, String mensagem, boolean efemera) {
        event.reply(mensagem).setEphemeral(efemera).queue();
        System.out.println("[comando] " + event.getUser().getName() + " -> " + event.getCommandString()
                + " => respondido.");
    }
}
